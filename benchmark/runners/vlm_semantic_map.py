"""
VLM + Semantic Map runner.

Each step uses screenshot + semantic map context to decide the next action.
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
from pathlib import Path
from typing import Any

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "src"))

from lxb_link import LXBLinkClient
from lxb_link.constants import KEY_BACK

from benchmark.config import (
    APP_LAUNCH_WAIT,
    STEP_PAUSE_SEC,
    TEXT_LLM_MODEL,
    VLM_API_KEY,
    VLM_BASE_URL,
    VLM_MODEL,
    VLM_STRUCTURED_LOG_FILE,
)
from benchmark.runners.base import BaseRunner, InferenceCounter, LLMClient, RunResult
from benchmark.tasks import BenchmarkTask
from benchmark.verification import ExternalVisualVerifier

_SYSTEM_PROMPT = """\
You are navigating an Android app step by step with screenshot + semantic map.
You may tap UI elements or press the system BACK button.
Respond ONLY with a JSON object and no extra text.
Strict type rules:
- x and y must be JSON numbers (integers), not strings.
- x and y must be scalar values, not arrays or objects.
- back and done must be JSON booleans."""

_STEP_PROMPT = """\
App: {app_name}
Goal: {user_task}
Screen: {screen_w}x{screen_h} pixels
Step: {step}/{max_steps}
History: {history}

Semantic map:
{semantic_map}

Use screenshot + semantic map together to choose the next navigation action.
If blocked by popup, close it first.
If needed, use BACK.

Return only one JSON:
{{"done": false, "x": <x>, "y": <y>, "reason": "<one sentence>"}}
or
{{"done": false, "back": true, "reason": "<one sentence>"}}

Type constraints (MANDATORY):
- Valid: {{"done": false, "x": 512, "y": 830, "reason": "..."}}.
- Invalid: x/y as strings, arrays, objects, or multi-candidate lists.
- x and y must be REAL SCREEN PIXELS:
  x in [0, {max_x}], y in [0, {max_y}].
- Return exactly one action object and no prose."""

def _parse_response(raw: str) -> dict[str, Any]:
    raw = re.sub(r"```(?:json)?", "", raw).strip().strip("`")
    m = re.search(r"\{.*\}", raw, re.DOTALL)
    if not m:
        raise ValueError(f"No JSON found in response: {raw!r}")
    return json.loads(m.group())


def _build_semantic_map_text(map_path: str, max_pages: int = 90, max_edges: int = 160) -> str:
    with open(map_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    pages = data.get("pages", {}) or {}
    transitions = data.get("transitions", []) or []
    lines: list[str] = []
    lines.append(f"package={data.get('package', '')}")
    lines.append(f"pages={len(pages)} transitions={len(transitions)}")
    lines.append("page_list:")

    page_items = sorted(pages.items(), key=lambda x: x[0])[:max_pages]
    for pid, p in page_items:
        name = str(p.get("name", "")).replace("\n", " ").strip()
        desc = str(p.get("description", "")).replace("\n", " ").strip()
        if len(desc) > 40:
            desc = desc[:40] + "..."
        lines.append(f"- {pid} | {name} | {desc}")

    lines.append("transitions:")
    for t in transitions[:max_edges]:
        frm = str(t.get("from", "")).strip()
        to = str(t.get("to", "")).strip()
        trig = str(t.get("description", "")).replace("\n", " ").strip()
        if len(trig) > 30:
            trig = trig[:30] + "..."
        lines.append(f"- {frm} -> {to} | {trig}")

    if len(pages) > max_pages:
        lines.append(f"... {len(pages) - max_pages} more pages omitted")
    if len(transitions) > max_edges:
        lines.append(f"... {len(transitions) - max_edges} more transitions omitted")
    return "\n".join(lines)


class VLMSemanticMapRunner(BaseRunner):
    METHOD_NAME = "VLM+SemanticMap"
    MAX_STEPS = 10
    STEP_INFER_RETRIES = 2

    @staticmethod
    def _shell_log(event: str, **kwargs: Any) -> None:
        payload = {"事件": event, **kwargs}
        print(f"[VLM-SEM-MAP] {json.dumps(payload, ensure_ascii=False)}", flush=True)

    @staticmethod
    def _append_structured_log(record: dict[str, Any]) -> None:
        path = Path(VLM_STRUCTURED_LOG_FILE)
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8") as f:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")

    @staticmethod
    def _safe_int(value: Any) -> int | None:
        try:
            return int(float(value))
        except (TypeError, ValueError):
            return None

    @staticmethod
    def _clamp(value: int, low: int, high: int) -> int:
        return max(low, min(value, high))

    @classmethod
    def _resolve_tap_coords(
        cls,
        action: dict[str, Any],
        screen_w: int,
        screen_h: int,
    ) -> tuple[int | None, int | None, dict[str, Any]]:
        raw_x = cls._safe_int(action.get("x"))
        raw_y = cls._safe_int(action.get("y"))
        meta: dict[str, Any] = {
            "raw_x": action.get("x"),
            "raw_y": action.get("y"),
            "coord_mode": "invalid",
        }
        if raw_x is None or raw_y is None:
            return None, None, meta

        x = cls._clamp(raw_x, 0, max(screen_w - 1, 0))
        y = cls._clamp(raw_y, 0, max(screen_h - 1, 0))
        meta.update({"coord_mode": "pixel_direct", "tap_x": x, "tap_y": y})
        return x, y, meta

    @staticmethod
    def _get_screen_size(client, screenshot: bytes) -> tuple[int, int]:
        try:
            ok, w, h, _ = client.get_screen_size()
            if ok and w > 0 and h > 0:
                return w, h
        except Exception:
            pass
        return 1080, 1920

    def run(self, client: LXBLinkClient, task: BenchmarkTask, trial: int) -> RunResult:
        counter = InferenceCounter()
        vlm = LLMClient(
            base_url=VLM_BASE_URL,
            api_key=VLM_API_KEY,
            model=VLM_MODEL,
            counter=counter,
            vision=True,
        )

        semantic_map = _build_semantic_map_text(task.map_path)

        self._launch_and_wait(client, task.package, APP_LAUNCH_WAIT)
        verifier = ExternalVisualVerifier(task)

        success = False
        steps = 0
        error: str | None = None
        last_recoverable_error: str | None = None
        history: list[str] = []
        t0 = time.perf_counter()

        verify_init = verifier.verify(client)
        if verify_init.success:
            success = True

        for step in range(1, self.MAX_STEPS + 1):
            if success:
                break

            screenshot = client.screenshot()
            if not screenshot:
                error = "screenshot_failed"
                break

            history_str = "; ".join(history[-5:]) if history else "none"
            screen_w, screen_h = self._get_screen_size(client, screenshot)
            prompt = _STEP_PROMPT.format(
                app_name=task.app_name,
                user_task=task.user_task,
                screen_w=screen_w,
                screen_h=screen_h,
                max_x=max(screen_w - 1, 0),
                max_y=max(screen_h - 1, 0),
                step=step,
                max_steps=self.MAX_STEPS,
                history=history_str,
                semantic_map=semantic_map,
            )

            action = None
            for attempt in range(1, self.STEP_INFER_RETRIES + 1):
                try:
                    raw = vlm.complete_with_image(_SYSTEM_PROMPT + "\n\n" + prompt, screenshot)
                    action = _parse_response(raw)
                    self._shell_log(
                        "模型输出",
                        任务=task.task_id,
                        轮次=trial,
                        步骤=step,
                        尝试=attempt,
                        done=bool(action.get("done", False)),
                        back=bool(action.get("back", False)),
                        x=action.get("x"),
                        y=action.get("y"),
                        原因=str(action.get("reason", ""))[:80],
                    )
                    break
                except Exception as exc:
                    last_recoverable_error = f"parse_error_step{step}: {exc}"
                    self._shell_log(
                        "解析失败",
                        任务=task.task_id,
                        轮次=trial,
                        步骤=step,
                        尝试=attempt,
                        错误=str(exc),
                    )
                    time.sleep(0.4)
            if action is None:
                continue

            if action.get("back"):
                client.key_event(KEY_BACK)
                history.append(f"step{step}:BACK({action.get('reason', '')})")
                steps = step
                time.sleep(STEP_PAUSE_SEC)
                verify_back = verifier.verify(client)
                self._shell_log(
                    "外部验收",
                    任务=task.task_id,
                    轮次=trial,
                    步骤=step,
                    成功=verify_back.success,
                    置信度=round(verify_back.confidence, 3),
                    观察页=verify_back.observed_page,
                    原因=verify_back.reason,
                )
                if verify_back.success:
                    success = True
                    break
                continue

            x, y, coord_meta = self._resolve_tap_coords(action, screen_w, screen_h)
            if x is None or y is None:
                last_recoverable_error = f"invalid_coords_step{step}"
                self._shell_log(
                    "坐标无效",
                    任务=task.task_id,
                    轮次=trial,
                    步骤=step,
                    原始x=action.get("x"),
                    原始y=action.get("y"),
                )
                continue
            if x == 0 and y == 0:
                last_recoverable_error = f"zero_coords_step{step}"
                self._shell_log(
                    "坐标为零",
                    任务=task.task_id,
                    轮次=trial,
                    步骤=step,
                    原始x=action.get("x"),
                    原始y=action.get("y"),
                )
                continue

            client.tap(x, y)
            self._shell_log(
                "点击映射",
                任务=task.task_id,
                轮次=trial,
                步骤=step,
                原始x=action.get("x"),
                原始y=action.get("y"),
                实际x=x,
                实际y=y,
                映射模式=coord_meta.get("coord_mode"),
                屏幕宽=screen_w,
                屏幕高=screen_h,
            )
            # Keep history semantic-only; do not leak concrete coordinates into next-step prompt.
            history.append(f"step{step}:tap({action.get('reason', '')})"[:60])
            steps = step
            self._append_structured_log(
                {
                    "event": "step_action",
                    "method": self.METHOD_NAME,
                    "task_id": task.task_id,
                    "trial": trial,
                    "step": step,
                    "decision": "tap",
                    "action": action,
                    "coord_meta": coord_meta,
                    "history": history[-5:],
                }
            )

            time.sleep(STEP_PAUSE_SEC)
            verify = verifier.verify(client)
            self._shell_log(
                "外部验收",
                任务=task.task_id,
                轮次=trial,
                步骤=step,
                成功=verify.success,
                置信度=round(verify.confidence, 3),
                观察页=verify.observed_page,
                原因=verify.reason,
            )
            if verify.success:
                success = True
                break
        else:
            error = "max_steps_exceeded" if not last_recoverable_error else f"max_steps_exceeded:{last_recoverable_error}"

        latency = time.perf_counter() - t0
        final_pkg, final_act = self._get_final_activity(client)
        cost = counter.estimated_cost(TEXT_LLM_MODEL, VLM_MODEL)

        return RunResult(
            method=self.METHOD_NAME,
            task_id=task.task_id,
            trial=trial,
            success=success,
            llm_calls=counter.llm_calls,
            vlm_calls=counter.vlm_calls,
            steps=steps,
            latency_sec=round(latency, 2),
            input_tokens=counter.input_tokens,
            output_tokens=counter.output_tokens,
            estimated_cost=round(cost, 6),
            error=error,
            final_package=final_pkg,
            final_activity=final_act,
        )
