"""
VLM-ReAct runner (baseline A).

Each navigation step: screenshot → VLM → parse action or DONE.
Cost per task: N VLM calls, where N = number of taps taken.
"""

from __future__ import annotations

import json
import re
import sys
import os
import time
from typing import Any
from pathlib import Path

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "src"))

from lxb_link import LXBLinkClient
from lxb_link.constants import KEY_BACK

from benchmark.config import (
    VLM_BASE_URL, VLM_API_KEY, VLM_MODEL,
    TEXT_LLM_MODEL, STEP_PAUSE_SEC, APP_LAUNCH_WAIT,
    VLM_STRUCTURED_LOG_FILE,
)
from benchmark.runners.base import BaseRunner, InferenceCounter, LLMClient, RunResult
from benchmark.tasks import BenchmarkTask
from benchmark.verification import ExternalVisualVerifier

_SYSTEM_PROMPT = """\
You are navigating an Android app step by step to reach a target page.
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

Examine the screenshot carefully.

If a popup / ad / dialog is blocking the screen, close it first:
  {{"done": false, "x": <x>, "y": <y>, "reason": "dismiss popup"}}

To press the system BACK button (use when you went to a wrong page or need to retrace):
  {{"done": false, "back": true, "reason": "<why>"}}

Otherwise tap ONE navigation element (bottom tabs, top tabs, menu entries).
Do NOT tap content cards, article titles, or product items.
  {{"done": false, "x": <x>, "y": <y>, "reason": "<one sentence>"}}

Type constraints (MANDATORY):
- Valid: {{"done": false, "x": 512, "y": 830, "reason": "..."}}.
- Invalid: x/y as strings, arrays, objects, or multi-candidate lists.
- x and y must be REAL SCREEN PIXELS:
  x in [0, {max_x}], y in [0, {max_y}].
- Return exactly one action object and no prose.

Respond with ONLY the JSON object."""

def _parse_response(raw: str) -> dict:
    """Extract JSON from VLM response (handles markdown fences)."""
    # Strip markdown fences if present
    raw = re.sub(r"```(?:json)?", "", raw).strip().strip("`")
    # Find first {...}
    match = re.search(r"\{.*\}", raw, re.DOTALL)
    if match:
        return json.loads(match.group())
    raise ValueError(f"No JSON found in response: {raw!r}")


class VLMReActRunner(BaseRunner):
    """
    Pure vision step-by-step navigation (AppAgent style).
    Each step consumes 1 VLM call.
    """

    METHOD_NAME = "VLM-ReAct"
    MAX_STEPS = 10
    STEP_INFER_RETRIES = 2

    @staticmethod
    def _shell_log(event: str, **kwargs: Any) -> None:
        payload = {"事件": event, **kwargs}
        print(f"[VLM-REACT] {json.dumps(payload, ensure_ascii=False)}", flush=True)

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

        meta = {
            "raw_x": action.get("x"),
            "raw_y": action.get("y"),
            "coord_mode": "invalid",
        }
        if raw_x is None or raw_y is None:
            return None, None, meta

        x = cls._clamp(raw_x, 0, max(screen_w - 1, 0))
        y = cls._clamp(raw_y, 0, max(screen_h - 1, 0))
        meta["coord_mode"] = "pixel_direct"

        x = cls._clamp(x, 0, max(screen_w - 1, 0))
        y = cls._clamp(y, 0, max(screen_h - 1, 0))
        meta["tap_x"] = x
        meta["tap_y"] = y
        return x, y, meta

    @staticmethod
    def _get_screen_size(client, screenshot: bytes) -> tuple[int, int]:
        try:
            ok, w, h, _ = client.get_screen_size()
            if ok and w > 0 and h > 0:
                return w, h
        except Exception:
            pass

        # JPEG SOF fallback parser from screenshot bytes.
        i = 0
        data = screenshot
        while i + 9 < len(data):
            if data[i] == 0xFF and data[i + 1] in {0xC0, 0xC1, 0xC2, 0xC3}:
                h = int.from_bytes(data[i + 5:i + 7], "big")
                w = int.from_bytes(data[i + 7:i + 9], "big")
                if w > 0 and h > 0:
                    return w, h
                break
            i += 1
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

        self._launch_and_wait(client, task.package, APP_LAUNCH_WAIT)
        verifier = ExternalVisualVerifier(task)

        success = False
        steps = 0
        error = None
        last_recoverable_error: str | None = None
        history: list[str] = []
        t0 = time.perf_counter()

        verify_init = verifier.verify(client)
        if verify_init.success:
            success = True
            steps = 0
            self._append_structured_log({
                "event": "external_verify",
                "method": self.METHOD_NAME,
                "task_id": task.task_id,
                "trial": trial,
                "step": 0,
                "where": "before_loop",
                "success": True,
                "target_page": verify_init.target_page,
                "confidence": verify_init.confidence,
                "reason": verify_init.reason,
                "observed_page": verify_init.observed_page,
            })

        for step in range(1, self.MAX_STEPS + 1):
            if success:
                break

            # Capture screenshot
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
                    self._append_structured_log({
                        "event": "parse_error",
                        "method": self.METHOD_NAME,
                        "task_id": task.task_id,
                        "trial": trial,
                        "step": step,
                        "attempt": attempt,
                        "error": str(exc),
                    })
                    time.sleep(0.4)
            if action is None:
                # Recoverable: continue next step instead of hard fail.
                continue

            if action.get("done") is True:
                # Backward compatibility for legacy prompts: ignore self-reported done.
                self._append_structured_log({
                    "event": "done_ignored",
                    "method": self.METHOD_NAME,
                    "task_id": task.task_id,
                    "trial": trial,
                    "step": step,
                    "action": action,
                })
                steps = step
                time.sleep(STEP_PAUSE_SEC)
                verify_after_done = verifier.verify(client)
                self._shell_log(
                    "外部验收",
                    任务=task.task_id,
                    轮次=trial,
                    步骤=step,
                    成功=verify_after_done.success,
                    置信度=round(verify_after_done.confidence, 3),
                    观察页=verify_after_done.observed_page,
                    原因=verify_after_done.reason,
                )
                if verify_after_done.success:
                    success = True
                    break
                continue

            if action.get("back"):
                client.key_event(KEY_BACK)
                history.append(f"step{step}:BACK({action.get('reason','')})")
                steps = step
                self._append_structured_log({
                    "event": "step_action",
                    "method": self.METHOD_NAME,
                    "task_id": task.task_id,
                    "trial": trial,
                    "step": step,
                    "decision": "back",
                    "action": action,
                    "history": history[-5:],
                })
                time.sleep(STEP_PAUSE_SEC)
                verify_after_back = verifier.verify(client)
                self._shell_log(
                    "外部验收",
                    任务=task.task_id,
                    轮次=trial,
                    步骤=step,
                    成功=verify_after_back.success,
                    置信度=round(verify_after_back.confidence, 3),
                    观察页=verify_after_back.observed_page,
                    原因=verify_after_back.reason,
                )
                if verify_after_back.success:
                    success = True
                    self._append_structured_log({
                        "event": "external_verify",
                        "method": self.METHOD_NAME,
                        "task_id": task.task_id,
                        "trial": trial,
                        "step": step,
                        "where": "after_step",
                        "success": True,
                        "target_page": verify_after_back.target_page,
                        "confidence": verify_after_back.confidence,
                        "reason": verify_after_back.reason,
                        "observed_page": verify_after_back.observed_page,
                    })
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
                self._append_structured_log({
                    "event": "invalid_coords",
                    "method": self.METHOD_NAME,
                    "task_id": task.task_id,
                    "trial": trial,
                    "step": step,
                    "action": action,
                    "screen_w": screen_w,
                    "screen_h": screen_h,
                })
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
                self._append_structured_log({
                    "event": "zero_coords",
                    "method": self.METHOD_NAME,
                    "task_id": task.task_id,
                    "trial": trial,
                    "step": step,
                    "action": action,
                    "coord_meta": coord_meta,
                    "screen_w": screen_w,
                    "screen_h": screen_h,
                })
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
            history.append(f"step{step}:tap({action.get('reason','')})"[:60])
            steps = step
            self._append_structured_log({
                "event": "step_action",
                "method": self.METHOD_NAME,
                "task_id": task.task_id,
                "trial": trial,
                "step": step,
                "decision": "tap",
                "action": action,
                "coord_meta": coord_meta,
                "screen_w": screen_w,
                "screen_h": screen_h,
                "history": history[-5:],
            })
            time.sleep(STEP_PAUSE_SEC)
            verify_after_tap = verifier.verify(client)
            self._shell_log(
                "外部验收",
                任务=task.task_id,
                轮次=trial,
                步骤=step,
                成功=verify_after_tap.success,
                置信度=round(verify_after_tap.confidence, 3),
                观察页=verify_after_tap.observed_page,
                原因=verify_after_tap.reason,
            )
            self._append_structured_log({
                "event": "external_verify",
                "method": self.METHOD_NAME,
                "task_id": task.task_id,
                "trial": trial,
                "step": step,
                "where": "after_step",
                "success": verify_after_tap.success,
                "target_page": verify_after_tap.target_page,
                "confidence": verify_after_tap.confidence,
                "reason": verify_after_tap.reason,
                "observed_page": verify_after_tap.observed_page,
            })
            if verify_after_tap.success:
                success = True
                break
        else:
            error = "max_steps_exceeded" if not last_recoverable_error else f"max_steps_exceeded:{last_recoverable_error}"

        latency = time.perf_counter() - t0
        final_pkg, final_act = self._get_final_activity(client)
        cost = counter.estimated_cost(TEXT_LLM_MODEL, VLM_MODEL)

        self._append_structured_log({
            "event": "trial_end",
            "method": self.METHOD_NAME,
            "task_id": task.task_id,
            "trial": trial,
            "success": success,
            "steps": steps,
            "error": error,
            "latency_sec": round(latency, 2),
            "llm_calls": counter.llm_calls,
            "vlm_calls": counter.vlm_calls,
            "input_tokens": counter.input_tokens,
            "output_tokens": counter.output_tokens,
            "estimated_cost": round(cost, 6),
            "final_package": final_pkg,
            "final_activity": final_act,
        })

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
