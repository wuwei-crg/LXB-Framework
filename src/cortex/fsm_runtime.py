import json
import io
import random
import re
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Protocol, Set

from .fsm_instruction import Instruction, InstructionError, parse_instructions, validate_allowed
from .route_then_act import FixedPlanPlanner, HeuristicPlanner, RouteConfig, RouteThenActCortex


class CortexState(str, Enum):
    INIT = "INIT"
    APP_RESOLVE = "APP_RESOLVE"
    ROUTE_PLAN = "ROUTE_PLAN"
    ROUTING = "ROUTING"
    VISION_ACT = "VISION_ACT"
    FINISH = "FINISH"
    FAIL = "FAIL"


@dataclass
class FSMConfig:
    max_turns: int = 30
    max_commands_per_turn: int = 1
    max_vision_turns: int = 20
    action_interval_sec: float = 0.8
    screenshot_settle_sec: float = 0.6
    tap_bind_clickable: bool = False
    tap_jitter_sigma_px: float = 0.0
    swipe_jitter_sigma_px: float = 0.0
    swipe_duration_jitter_ratio: float = 0.0
    xml_stable_interval_sec: float = 0.3
    xml_stable_samples: int = 4
    xml_stable_timeout_sec: float = 4.0
    init_coord_probe_enabled: bool = True


@dataclass
class CortexContext:
    task_id: str
    user_task: str
    map_path: str
    start_page: Optional[str] = None
    selected_package: str = ""
    target_page: str = ""
    route_trace: List[str] = field(default_factory=list)
    command_log: List[Dict[str, Any]] = field(default_factory=list)
    route_result: Dict[str, Any] = field(default_factory=dict)
    error: str = ""
    output: Dict[str, Any] = field(default_factory=dict)
    vision_turns: int = 0
    app_candidates: List[Dict[str, Any]] = field(default_factory=list)
    page_candidates: List[Dict[str, Any]] = field(default_factory=list)
    device_info: Dict[str, Any] = field(default_factory=dict)
    current_activity: Dict[str, Any] = field(default_factory=dict)
    last_command: str = ""
    same_command_streak: int = 0
    last_activity_sig: str = ""
    same_activity_streak: int = 0
    coord_probe: Dict[str, Any] = field(default_factory=dict)
    llm_history: List[Dict[str, Any]] = field(default_factory=list)


class CommandPlanner(Protocol):
    def plan(self, state: CortexState, prompt: str, context: CortexContext) -> str:
        ...


class RuleBasedPlanner:
    def __init__(self, route_loader: Callable[[str], Any]):
        self._route_loader = route_loader
        self._heuristic = HeuristicPlanner()

    def plan(self, state: CortexState, prompt: str, context: CortexContext) -> str:
        route_map = self._route_loader(context.map_path)
        if state == CortexState.APP_RESOLVE:
            return f"SET_APP {route_map.package}"
        if state == CortexState.ROUTE_PLAN:
            plan = self._heuristic.plan(context.user_task, route_map)
            return f"ROUTE {plan.package_name} {plan.target_page}"
        if state == CortexState.VISION_ACT:
            return "DONE"
        return "FAIL unsupported_state"


class PromptBuilder:
    _STATE_FORMATS: Dict[CortexState, Dict[str, Any]] = {
        CortexState.APP_RESOLVE: {
            "root": "app_analysis",
            "fields": ["user_intent", "candidates", "decision", "reflection"],
        },
        CortexState.ROUTE_PLAN: {
            "root": "route_plan_analysis",
            "fields": ["selected_app", "target_page_candidates", "decision", "reflection"],
        },
        CortexState.VISION_ACT: {
            "root": "vision_analysis",
            "fields": ["page_state", "step_review", "reflection", "next_step_reasoning", "completion_gate", "done_confirm"],
        },
    }

    def _common_output_rules(self) -> str:
        return (
            "Output Contract:\n"
            "1) Output MUST follow the state XML-like format exactly.\n"
            "2) Output MUST contain exactly one <command>...</command>.\n"
            "3) No JSON, no markdown, no extra prose outside tags.\n"
            "4) If you cannot decide safely, command must be FAIL <reason>.\n"
        )

    def _dsl_semantics(self, allowed_ops: Set[str]) -> str:
        lines = ["DSL Semantics (only allowed ops):\n"]
        ordered = sorted([op.upper() for op in allowed_ops])
        for op in ordered:
            if op == "SET_APP":
                lines.append("- SET_APP <package_name>: choose exactly one package from AppCandidates.\n")
            elif op == "ROUTE":
                lines.append("- ROUTE <package_name> <target_page>: package must match selected app; target_page must come from PageCandidates.\n")
            elif op == "TAP":
                lines.append("- TAP <x> <y>: tap at the target position.\n")
            elif op == "SWIPE":
                lines.append("- SWIPE <x1> <y1> <x2> <y2> <duration_ms>: swipe from start to end with duration in ms.\n")
                lines.append("  SWIPE Rule: x1,y1 MUST be inside the main scrollable content container (not nav bar / title bar / edge controls).\n")
                lines.append("  Prefer small exploratory swipes near screen/content center.\n")
                lines.append("  Keep swipe distance small (about 8%~18% of screen height) unless a larger move is explicitly needed.\n")
                lines.append("  Prefer smoother/longer gesture duration (about 450~900ms).\n")
            elif op == "INPUT":
                lines.append("- INPUT \"<text>\": input text into focused field.\n")
            elif op == "WAIT":
                lines.append("- WAIT <ms>: wait milliseconds.\n")
            elif op == "BACK":
                lines.append("- BACK: press Android back key once.\n")
            elif op == "DONE":
                lines.append("- DONE: task complete.\n")
            elif op == "FAIL":
                lines.append("- FAIL <reason>: stop with explicit reason.\n")
        return "".join(lines)

    def _history_snippet(self, context: CortexContext, limit: int = 5) -> str:
        if not context.llm_history:
            return "[]"
        rows = context.llm_history[-max(1, int(limit)) :]
        return json.dumps(rows, ensure_ascii=False)

    def _lesson_snippet(self, context: CortexContext, limit: int = 5) -> str:
        if not context.llm_history:
            return "[]"
        out = []
        for item in reversed(context.llm_history):
            st = item.get("structured") or {}
            reflection = str(st.get("reflection") or "").strip()
            if not reflection:
                continue
            out.append(
                {
                    "state": item.get("state"),
                    "reflection": reflection,
                    "command": item.get("command"),
                }
            )
            if len(out) >= max(1, int(limit)):
                break
        out.reverse()
        return json.dumps(out, ensure_ascii=False)

    def build(self, state: CortexState, context: CortexContext, allowed_ops: Set[str]) -> str:
        fmt = self._STATE_FORMATS.get(state)
        format_block = ""
        if fmt:
            root = fmt["root"]
            fields = fmt["fields"]
            field_block = "".join([f"<{f}>...</{f}>\n" for f in fields])
            format_block = (
                "Output Format (strict):\n"
                f"<{root}>\n"
                f"{field_block}"
                f"</{root}>\n"
                "<command>DSL_COMMAND_HERE</command>\n"
            )

        if state == CortexState.APP_RESOLVE:
            app_rows = context.app_candidates[:80]
            return "".join(
                [
                    "State=APP_RESOLVE\n",
                    self._common_output_rules(),
                    format_block,
                    self._dsl_semantics(allowed_ops),
                    f"Allowed: {', '.join(sorted(allowed_ops))}\n",
                    f"UserTask: {context.user_task}\n",
                    f"DeviceInfo(JSON): {json.dumps(context.device_info, ensure_ascii=False)}\n",
                    f"CurrentActivity(JSON): {json.dumps(context.current_activity, ensure_ascii=False)}\n",
                    f"AppCandidates(JSON): {json.dumps(app_rows, ensure_ascii=False)}\n",
                    f"RecentLLMHistory(JSON): {self._history_snippet(context, limit=5)}\n",
                    f"RecentLessons(JSON): {self._lesson_snippet(context, limit=5)}\n",
                    "State Goal:\n",
                    "Select the best target app for this task from AppCandidates.\n",
                    "Write concise analysis in tags, then output one command in <command>.\n",
                    "Examples:\n",
                    "<app_analysis><user_intent>签到贴吧</user_intent><candidates>com.baidu.tieba</candidates><decision>贴吧最匹配</decision></app_analysis>\n",
                    "<command>SET_APP com.baidu.tieba</command>\n",
                ]
            )

        if state == CortexState.ROUTE_PLAN:
            page_rows = context.page_candidates[:120]
            return "".join(
                [
                    "State=ROUTE_PLAN\n",
                    self._common_output_rules(),
                    format_block,
                    self._dsl_semantics(allowed_ops),
                    f"Allowed: {', '.join(sorted(allowed_ops))}\n",
                    f"UserTask: {context.user_task}\n",
                    f"SelectedPackage: {context.selected_package}\n",
                    f"CurrentActivity(JSON): {json.dumps(context.current_activity, ensure_ascii=False)}\n",
                    f"DeviceInfo(JSON): {json.dumps(context.device_info, ensure_ascii=False)}\n",
                    f"PageCandidates(JSON): {json.dumps(page_rows, ensure_ascii=False)}\n",
                    f"RecentLLMHistory(JSON): {self._history_snippet(context, limit=5)}\n",
                    f"RecentLessons(JSON): {self._lesson_snippet(context, limit=5)}\n",
                    "State Goal:\n",
                    "Pick the best target_page from PageCandidates for the task.\n",
                    "Write concise analysis in tags, then output one command in <command>.\n",
                    "Examples:\n",
                    "<route_plan_analysis><selected_app>com.baidu.tieba</selected_app><target_page_candidates>home, sign</target_page_candidates><decision>先到home</decision></route_plan_analysis>\n",
                    "<command>ROUTE com.baidu.tieba home</command>\n",
                ]
            )

        if state == CortexState.VISION_ACT:
            activity_sig = f"{context.current_activity.get('package','')}/{context.current_activity.get('activity','')}"
            return "".join(
                [
                    "State=VISION_ACT\n",
                    self._common_output_rules(),
                    format_block,
                    self._dsl_semantics(allowed_ops),
                    f"Allowed: {', '.join(sorted(allowed_ops))}\n",
                    f"UserTask: {context.user_task}\n",
                    f"CurrentActivity(JSON): {json.dumps(context.current_activity, ensure_ascii=False)}\n",
                    f"ActivitySignature: {activity_sig}\n",
                    f"SameActivityStreak: {context.same_activity_streak}\n",
                    f"LastCommand: {context.last_command or '<none>'}\n",
                    f"SameCommandStreak: {context.same_command_streak}\n",
                    f"RecentRouteTrace(JSON): {json.dumps(context.route_trace[-8:], ensure_ascii=False)}\n",
                    f"RecentLLMHistory(JSON): {self._history_snippet(context, limit=5)}\n",
                    f"RecentLessons(JSON): {self._lesson_snippet(context, limit=5)}\n",
                    "Screenshot: attached in this request.\n",
                    "State Goal:\n",
                    "Choose ONLY the next best single action.\n",
                    "Important: one turn = one command. Do NOT output TAP then DONE in the same response.\n",
                    "SWIPE Constraint: if using SWIPE, start point must be inside the scrollable content container, avoid starting from top/bottom bars.\n",
                    "SWIPE Strategy: prefer small-distance swipe around center to probe nearby unseen content first.\n",
                    "SWIPE Default Profile: distance about 8%~18% screen height, duration about 450~900ms.\n",
                    "Reflection Contract (strict):\n",
                    "1) You MUST review recent 3~6 steps, not only the last step.\n",
                    "2) Use <step_review> to list per-step outcomes in order:\n",
                    "   Step-1: command=..., page_change=..., result=...\n",
                    "   Step-2: command=..., page_change=..., result=...\n",
                    "3) In <reflection>, summarize what the agent is actually doing across steps,\n",
                    "   identify repeated ineffective intents, and state what intent to avoid next.\n",
                    "4) <command> must be consistent with <reflection> (cannot repeat the avoided intent).\n",
                    "5) If recent steps show repeated no-progress, prioritize changing action type.\n",
                    "   Example: TAP -> small SWIPE near center / BACK / WAIT.\n",
                    "If recent lessons indicate repeated failure, change action type (prefer SWIPE/BACK/WAIT over repeating same TAP intent).\n",
                    "Anti-loop Rule: if activity/screen seems unchanged and last command already repeated, do NOT repeat same TAP.\n",
                    "In that case, choose another action (SWIPE/WAIT/INPUT) or output FAIL with reason.\n",
                    "DONE Gate (strict):\n",
                    "You are NOT allowed to output DONE unless completion evidence and coverage verification both pass.\n",
                    "Visible complete is NOT equal to global complete.\n",
                    "Before DONE, <completion_gate> must include:\n",
                    "- <completion_claim>: what goal is completed and visible evidence.\n",
                    "- <coverage_check>: passed|failed + reason.\n",
                    "- <verification_actions>: what checks were already executed.\n",
                    "Coverage verification rule (universal):\n",
                    "- If task may involve scrollable/unseen content, verify coverage first.\n",
                    "- Coverage is considered passed only if one condition holds:\n",
                    "  A) at least two exploratory swipes with no new actionable targets,\n",
                    "  B) explicit end-of-list/end marker visible,\n",
                    "  C) repeated explored states with no new targets after verification actions.\n",
                    "If coverage_check is failed, command MUST NOT be DONE.\n",
                    "DONE Confirm Contract (strict):\n",
                    "You MUST provide <done_confirm> with all fields below:\n",
                    "- <goal_match>: pass|fail + reason\n",
                    "- <coverage_check>: pass|fail + reason\n",
                    "- <new_info_check>: pass|fail + reason\n",
                    "- <final_decision>: DONE|NOT_DONE\n",
                    "Hard Rule: command=DONE is allowed ONLY when all three checks are pass and final_decision=DONE.\n",
                    "If any check is fail, final_decision MUST be NOT_DONE and command MUST NOT be DONE.\n",
                    "If finished now and DONE gate passed, output DONE only.\n",
                    "Examples:\n",
                    "<vision_analysis><page_state>当前在签到列表页，存在多个可操作入口</page_state><step_review>Step-1: command=TAP 890 67, page_change=进入签到页, result=有效; Step-2: command=TAP 720 420, page_change=无明显变化, result=疑似无效; Step-3: command=TAP 720 420, page_change=无明显变化, result=重复无效</step_review><reflection>最近步骤显示同一动作连续无效，当前策略在原地重复。应避免继续重复该动作意图，改为滚动扫描更多可签到项。</reflection><next_step_reasoning>先下滑一屏扩展可见范围，再选择新的可执行入口</next_step_reasoning><completion_gate><completion_claim>当前仅确认可见区域状态</completion_claim><coverage_check>failed: 仍可能有未显示内容</coverage_check><verification_actions>尚未完成覆盖验证</verification_actions></completion_gate></vision_analysis>\n",
                    "<command>SWIPE 640 1600 640 1400 650</command>\n",
                ]
            )

        return f"State={state.value}"


class LLMPlanner:
    def __init__(self, complete: Callable[[str], str], complete_with_image: Optional[Callable[[str, bytes], str]] = None):
        self._complete = complete
        self._complete_with_image = complete_with_image

    def plan(self, state: CortexState, prompt: str, context: CortexContext) -> str:
        return self._complete(prompt)

    def plan_vision(self, state: CortexState, prompt: str, context: CortexContext, screenshot: bytes) -> str:
        if self._complete_with_image and screenshot:
            return self._complete_with_image(prompt, screenshot)
        return self._complete(prompt)


class CortexFSMEngine:
    _ALLOWED_OPS: Dict[CortexState, Set[str]] = {
        CortexState.APP_RESOLVE: {"SET_APP", "FAIL"},
        CortexState.ROUTE_PLAN: {"ROUTE", "FAIL"},
        CortexState.VISION_ACT: {"TAP", "SWIPE", "INPUT", "WAIT", "BACK", "DONE", "FAIL"},
    }
    _STATE_OUTPUT_TAGS: Dict[CortexState, Dict[str, Any]] = {
        CortexState.APP_RESOLVE: {
            "root": "app_analysis",
            "fields": ["user_intent", "candidates", "decision", "reflection"],
        },
        CortexState.ROUTE_PLAN: {
            "root": "route_plan_analysis",
            "fields": ["selected_app", "target_page_candidates", "decision", "reflection"],
        },
        CortexState.VISION_ACT: {
            "root": "vision_analysis",
            "fields": ["page_state", "step_review", "reflection", "next_step_reasoning", "completion_gate", "done_confirm"],
        },
    }

    def __init__(
        self,
        client,
        planner: Optional[CommandPlanner] = None,
        route_config: Optional[RouteConfig] = None,
        fsm_config: Optional[FSMConfig] = None,
        log_callback: Optional[Callable[[Dict[str, Any]], None]] = None,
    ):
        self.client = client
        self.route_config = route_config or RouteConfig()
        self.fsm_config = fsm_config or FSMConfig()
        self._log_callback = log_callback
        self._prompt_builder = PromptBuilder()
        self._route_helper = RouteThenActCortex(client=self.client, config=self.route_config)
        self._route_loader = self._route_helper._load_map
        self.planner = planner or RuleBasedPlanner(self._route_loader)
        self._heuristic = HeuristicPlanner()

    def run(
        self,
        user_task: str,
        map_path: str,
        start_page: Optional[str] = None,
        extra_context: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        context = CortexContext(task_id=str(uuid.uuid4()), user_task=user_task, map_path=map_path, start_page=start_page)
        for k, v in (extra_context or {}).items():
            if hasattr(context, k):
                setattr(context, k, v)
        state = CortexState.INIT

        for _ in range(self.fsm_config.max_turns):
            if state == CortexState.INIT:
                state = self._run_init_state(context)
                continue
            if state == CortexState.APP_RESOLVE:
                state = self._run_model_state(context, state)
                continue
            if state == CortexState.ROUTE_PLAN:
                state = self._run_model_state(context, state)
                continue
            if state == CortexState.ROUTING:
                state = self._run_routing_state(context)
                continue
            if state == CortexState.VISION_ACT:
                state = self._run_vision_state(context)
                continue
            if state in {CortexState.FINISH, CortexState.FAIL}:
                break
            context.error = f"unknown_state:{state.value}"
            state = CortexState.FAIL

        result = {
            "status": "success" if state == CortexState.FINISH else "failed",
            "task_id": context.task_id,
            "state": state.value,
            "package_name": context.selected_package,
            "target_page": context.target_page,
            "route_trace": context.route_trace,
            "route_result": context.route_result,
            "command_log": context.command_log,
            "llm_history": context.llm_history,
        }
        if context.error:
            result["reason"] = context.error
        if context.output:
            result["output"] = context.output
        return result

    def _run_model_state(self, context: CortexContext, state: CortexState) -> CortexState:
        allowed = self._ALLOWED_OPS.get(state, set())
        prompt = self._prompt_builder.build(state, context, allowed)
        self._log(context, "llm", "prompt", state=state.value, prompt=prompt)
        try:
            raw = self.planner.plan(state, prompt, context)
        except Exception as e:
            context.error = f"planner_call_failed:{state.value}:{e}"
            self._log(context, "fsm", "planner_call_failed", state=state.value, error=str(e))
            return CortexState.FAIL
        self._log(context, "llm", "response", state=state.value, response=(raw or "")[:4000])
        command_text, structured = self._extract_structured_command(state, raw)
        if structured:
            context.llm_history.append(
                {"state": state.value, "structured": structured, "command": command_text}
            )
            self._log(context, "llm", "structured", state=state.value, data=structured, command=command_text)
        raw = self._normalize_model_output(command_text or raw, state, context)
        try:
            commands = parse_instructions(raw, max_commands=self.fsm_config.max_commands_per_turn)
            validate_allowed(commands, allowed)
        except InstructionError as e:
            context.error = f"instruction_invalid:{e}"
            self._log(context, "fsm", "instruction_invalid", state=state.value, error=str(e), raw=raw)
            return CortexState.FAIL

        self._append_commands(context, state, commands)
        first = commands[0]
        if first.op == "FAIL":
            context.error = "planner_fail:" + " ".join(first.args)
            return CortexState.FAIL
        if state == CortexState.APP_RESOLVE and first.op == "SET_APP":
            context.selected_package = first.args[0]
            return CortexState.ROUTE_PLAN
        if state == CortexState.ROUTE_PLAN and first.op == "ROUTE":
            route_map = self._route_loader(context.map_path)
            requested_target = first.args[1]
            resolved_target = self._route_helper._resolve_target_page(route_map, requested_target)
            if resolved_target not in route_map.pages:
                fallback = self._heuristic.plan(context.user_task, route_map)
                self._log(
                    context,
                    "fsm",
                    "route_target_fallback",
                    requested_target=requested_target,
                    resolved_target=resolved_target,
                    fallback_target=fallback.target_page,
                )
                resolved_target = fallback.target_page
            context.selected_package = first.args[0]
            context.target_page = resolved_target
            return CortexState.ROUTING

        context.error = f"unexpected_op:{first.op}@{state.value}"
        return CortexState.FAIL

    def _run_init_state(self, context: CortexContext) -> CortexState:
        self._log(context, "fsm", "state_enter", state=CortexState.INIT.value)

        # Screen size
        try:
            ok, width, height, density = self.client.get_screen_size()
            if ok:
                context.device_info = {"width": int(width), "height": int(height), "density": int(density)}
            else:
                context.device_info = {"width": 0, "height": 0, "density": 0}
        except Exception:
            context.device_info = {"width": 0, "height": 0, "density": 0}

        # Current activity
        try:
            ok, pkg, activity = self.client.get_activity()
            context.current_activity = {
                "ok": bool(ok),
                "package": str(pkg or ""),
                "activity": str(activity or ""),
            }
        except Exception:
            context.current_activity = {"ok": False, "package": "", "activity": ""}

        # App list from Android (fallback when caller does not provide)
        if not context.app_candidates:
            try:
                raw_apps = self.client.list_apps("user")
                context.app_candidates = _normalize_app_candidates(raw_apps)[:200]
            except Exception:
                context.app_candidates = []

        # INIT coordinate probe: identify model coordinate space with a synthetic image.
        probe_result = self._probe_coordinate_space(context)
        if probe_result:
            context.coord_probe = probe_result
            context.output["coord_probe"] = probe_result

        self._log(
            context,
            "fsm",
            "init_ready",
            device_info=context.device_info,
            current_activity=context.current_activity,
            app_candidates=len(context.app_candidates),
            page_candidates=len(context.page_candidates),
            coord_probe=context.coord_probe or None,
        )
        return CortexState.APP_RESOLVE

    def _run_routing_state(self, context: CortexContext) -> CortexState:
        self._log(context, "fsm", "routing_start", package_name=context.selected_package, target_page=context.target_page)
        planner = FixedPlanPlanner(context.selected_package, context.target_page)
        route_engine = RouteThenActCortex(
            client=self.client,
            planner=planner,
            config=self.route_config,
            action_engine=None,
            log_callback=lambda payload: self._log(context, "route", "route_event", payload=payload),
        )
        result = route_engine.run(user_task=context.user_task, map_path=context.map_path, start_page=context.start_page)
        context.route_result = result
        if result.get("status") != "success":
            context.error = result.get("reason", "route_failed")
            self._log(context, "fsm", "routing_fail", reason=context.error)
            return CortexState.FAIL
        context.route_trace = list(result.get("route_trace") or [])
        self._log(context, "fsm", "routing_done", steps=len(context.route_trace))
        return CortexState.VISION_ACT

    def _run_vision_state(self, context: CortexContext) -> CortexState:
        if context.vision_turns >= self.fsm_config.max_vision_turns:
            context.error = "vision_turn_limit"
            return CortexState.FAIL
        context.vision_turns += 1

        # Optional one-time settle on first vision turn only.
        if context.vision_turns == 1 and self.fsm_config.screenshot_settle_sec > 0:
            time.sleep(self.fsm_config.screenshot_settle_sec)

        screenshot = self._screenshot()
        if not screenshot:
            context.error = "vision_screenshot_failed"
            self._log(context, "fsm", "vision_screenshot_failed")
            return CortexState.FAIL
        self._log(context, "fsm", "vision_screenshot_ready", size=len(screenshot))
        self._refresh_activity(context)

        allowed = self._ALLOWED_OPS[CortexState.VISION_ACT]
        prompt = self._prompt_builder.build(CortexState.VISION_ACT, context, allowed)
        self._log(context, "llm", "prompt", state=CortexState.VISION_ACT.value, prompt=prompt)
        try:
            if hasattr(self.planner, "plan_vision"):
                raw = self.planner.plan_vision(CortexState.VISION_ACT, prompt, context, screenshot)
            else:
                raw = self.planner.plan(CortexState.VISION_ACT, prompt, context)
        except Exception as e:
            context.error = f"planner_call_failed:VISION_ACT:{e}"
            self._log(context, "fsm", "planner_call_failed", state=CortexState.VISION_ACT.value, error=str(e))
            return CortexState.FAIL
        self._log(context, "llm", "response", state=CortexState.VISION_ACT.value, response=(raw or "")[:4000])
        command_text, structured = self._extract_structured_command(CortexState.VISION_ACT, raw)
        if structured:
            context.llm_history.append(
                {"state": CortexState.VISION_ACT.value, "structured": structured, "command": command_text}
            )
            self._log(context, "llm", "structured", state=CortexState.VISION_ACT.value, data=structured, command=command_text)
        raw = self._normalize_model_output(command_text or raw, CortexState.VISION_ACT, context)

        try:
            # Vision stage is strictly one-command-per-turn.
            commands = parse_instructions(raw, max_commands=1)
            validate_allowed(commands, allowed)
        except InstructionError as e:
            context.error = f"vision_instruction_invalid:{e}"
            self._log(context, "fsm", "vision_instruction_invalid", error=str(e), raw=raw)
            return CortexState.FAIL

        cmd0 = commands[0]
        current_cmd_sig = cmd0.raw.strip()
        if current_cmd_sig and current_cmd_sig == context.last_command:
            context.same_command_streak += 1
        else:
            context.same_command_streak = 1
        context.last_command = current_cmd_sig

        if context.same_command_streak >= 3 and context.same_activity_streak >= 3:
            context.error = "vision_action_loop_detected:repeated_same_command"
            self._log(
                context,
                "fsm",
                "vision_action_loop_detected",
                command=current_cmd_sig,
                same_command_streak=context.same_command_streak,
                same_activity_streak=context.same_activity_streak,
            )
            return CortexState.FAIL

        self._append_commands(context, CortexState.VISION_ACT, commands)
        for cmd in commands:
            if cmd.op == "DONE":
                return CortexState.FINISH
            if cmd.op == "FAIL":
                context.error = "vision_fail:" + " ".join(cmd.args)
                return CortexState.FAIL
            if not self._exec_action_command(context, cmd):
                return CortexState.FAIL
        return CortexState.VISION_ACT

    def _exec_action_command(self, context: CortexContext, cmd: Instruction) -> bool:
        try:
            if cmd.op == "TAP":
                x, y = self._map_point_by_probe(context, cmd.args[0], cmd.args[1])
                if self.fsm_config.tap_bind_clickable:
                    tx, ty, bound = self._resolve_tap_clickable(context, x, y)
                    if bound:
                        self._log(context, "exec", "tap_bind_clickable", src_x=x, src_y=y, tap_x=tx, tap_y=ty, bound=bound)
                    else:
                        self._log(context, "exec", "tap_bind_clickable_miss", src_x=x, src_y=y, tap_x=tx, tap_y=ty)
                else:
                    tx, ty = x, y
                    self._log(context, "exec", "tap_bind_clickable_disabled", src_x=x, src_y=y, tap_x=tx, tap_y=ty)
                jx, jy = self._apply_point_jitter(context, tx, ty, self.fsm_config.tap_jitter_sigma_px)
                if (jx, jy) != (tx, ty):
                    self._log(context, "exec", "tap_jitter_applied", base_x=tx, base_y=ty, tap_x=jx, tap_y=jy, sigma=self.fsm_config.tap_jitter_sigma_px)
                tx, ty = jx, jy
                self._log(context, "exec", "tap_start", x=tx, y=ty)
                self.client.tap(tx, ty)
                self._log(context, "exec", "tap_done", x=tx, y=ty)
                self._wait_for_xml_stable(context, reason="tap")
                return True
            if cmd.op == "SWIPE":
                x1, y1 = self._map_point_by_probe(context, cmd.args[0], cmd.args[1])
                x2, y2 = self._map_point_by_probe(context, cmd.args[2], cmd.args[3])
                dur = int(cmd.args[4])
                jx1, jy1 = self._apply_point_jitter(context, x1, y1, self.fsm_config.swipe_jitter_sigma_px)
                jx2, jy2 = self._apply_point_jitter(context, x2, y2, self.fsm_config.swipe_jitter_sigma_px)
                jdur = self._apply_duration_jitter(dur, self.fsm_config.swipe_duration_jitter_ratio)
                if (jx1, jy1, jx2, jy2, jdur) != (x1, y1, x2, y2, dur):
                    self._log(
                        context,
                        "exec",
                        "swipe_jitter_applied",
                        base_x1=x1,
                        base_y1=y1,
                        base_x2=x2,
                        base_y2=y2,
                        base_duration=dur,
                        x1=jx1,
                        y1=jy1,
                        x2=jx2,
                        y2=jy2,
                        duration=jdur,
                        sigma=self.fsm_config.swipe_jitter_sigma_px,
                        duration_ratio=self.fsm_config.swipe_duration_jitter_ratio,
                    )
                x1, y1, x2, y2, dur = jx1, jy1, jx2, jy2, jdur
                self._log(context, "exec", "swipe_start", x1=x1, y1=y1, x2=x2, y2=y2, duration=dur)
                self.client.swipe(x1, y1, x2, y2, duration=dur)
                self._log(context, "exec", "swipe_done", x1=x1, y1=y1, x2=x2, y2=y2, duration=dur)
                self._wait_for_xml_stable(context, reason="swipe")
                return True
            if cmd.op == "INPUT":
                self._log(context, "exec", "input_start", text=cmd.args[0])
                self.client.input_text(cmd.args[0])
                if self.fsm_config.action_interval_sec > 0:
                    time.sleep(self.fsm_config.action_interval_sec)
                self._log(context, "exec", "input_done")
                return True
            if cmd.op == "WAIT":
                self._log(context, "exec", "wait_start", ms=int(cmd.args[0]))
                time.sleep(max(0, int(cmd.args[0])) / 1000.0)
                self._log(context, "exec", "wait_done", ms=int(cmd.args[0]))
                return True
            if cmd.op == "BACK":
                self._log(context, "exec", "back_start")
                self.client.key_event(4)
                self._log(context, "exec", "back_done")
                self._wait_for_xml_stable(context, reason="back")
                return True
            context.error = f"unsupported_action_op:{cmd.op}"
            return False
        except Exception as e:
            context.error = f"action_exec_error:{cmd.op}:{e}"
            self._log(context, "fsm", "action_error", op=cmd.op, error=str(e))
            return False

    def _append_commands(self, context: CortexContext, state: CortexState, commands: List[Instruction]) -> None:
        for cmd in commands:
            context.command_log.append({"state": state.value, "op": cmd.op, "args": cmd.args, "raw": cmd.raw})
            self._log(context, "fsm", "command", state=state.value, op=cmd.op, args=cmd.args)

    def _screenshot(self) -> Optional[bytes]:
        try:
            return self.client.request_screenshot()
        except Exception:
            return None

    def _in_screen_bounds(self, context: CortexContext, x: int, y: int) -> bool:
        w = int(context.device_info.get("width") or 0)
        h = int(context.device_info.get("height") or 0)
        if w <= 0 or h <= 0:
            return True
        return 0 <= x < w and 0 <= y < h

    def _apply_point_jitter(self, context: CortexContext, x: int, y: int, sigma: float) -> tuple[int, int]:
        if sigma <= 0:
            return x, y
        jx = int(round(random.gauss(x, sigma)))
        jy = int(round(random.gauss(y, sigma)))
        w = int(context.device_info.get("width") or 0)
        h = int(context.device_info.get("height") or 0)
        if w > 0:
            jx = max(0, min(w - 1, jx))
        if h > 0:
            jy = max(0, min(h - 1, jy))
        return jx, jy

    def _apply_duration_jitter(self, duration_ms: int, ratio: float) -> int:
        if duration_ms <= 0 or ratio <= 0:
            return max(1, duration_ms)
        sigma = max(1.0, duration_ms * ratio)
        jittered = int(round(random.gauss(duration_ms, sigma)))
        return max(80, jittered)

    def _wait_for_xml_stable(self, context: CortexContext, reason: str) -> None:
        interval = max(0.05, float(self.fsm_config.xml_stable_interval_sec))
        stable_needed = max(2, int(self.fsm_config.xml_stable_samples))
        timeout = max(interval, float(self.fsm_config.xml_stable_timeout_sec))
        deadline = time.time() + timeout

        stable_count = 0
        last_sig = ""
        samples = 0
        self._log(
            context,
            "exec",
            "xml_wait_start",
            reason=reason,
            interval_sec=interval,
            stable_samples=stable_needed,
            timeout_sec=timeout,
        )

        while time.time() < deadline:
            sig = self._dump_actions_signature()
            samples += 1
            if not sig:
                self._log(context, "exec", "xml_wait_skip", reason=reason, samples=samples, why="dump_actions_unavailable")
                return
            if sig and sig == last_sig:
                stable_count += 1
            else:
                stable_count = 1
                last_sig = sig

            if stable_count >= stable_needed:
                self._log(
                    context,
                    "exec",
                    "xml_wait_stable",
                    reason=reason,
                    samples=samples,
                    stable_count=stable_count,
                )
                return
            time.sleep(interval)

        self._log(
            context,
            "exec",
            "xml_wait_timeout",
            reason=reason,
            samples=samples,
            stable_count=stable_count,
        )

    def _dump_actions_signature(self) -> str:
        try:
            raw = self.client.dump_actions() or {}
            nodes = raw.get("nodes") or []
        except Exception:
            return ""

        tokens: List[str] = []
        # Keep order to preserve structural changes, but limit size for speed.
        for n in nodes[:400]:
            b = n.get("bounds") or [0, 0, 0, 0]
            if not isinstance(b, (list, tuple)) or len(b) < 4:
                b = [0, 0, 0, 0]
            text = str(n.get("text") or "")[:24]
            res = str(n.get("resource_id") or "")[:32]
            cls = str(n.get("class") or "")[:24]
            clickable = "1" if n.get("clickable") else "0"
            tokens.append(
                f"{int(b[0])},{int(b[1])},{int(b[2])},{int(b[3])}|{clickable}|{text}|{res}|{cls}"
            )
        return "|".join(tokens)

    def _refresh_activity(self, context: CortexContext) -> None:
        try:
            ok, pkg, activity = self.client.get_activity()
            context.current_activity = {
                "ok": bool(ok),
                "package": str(pkg or ""),
                "activity": str(activity or ""),
            }
            sig = f"{context.current_activity.get('package','')}/{context.current_activity.get('activity','')}"
            if sig == context.last_activity_sig:
                context.same_activity_streak += 1
            else:
                context.same_activity_streak = 1
            context.last_activity_sig = sig
            self._log(context, "fsm", "activity_refreshed", current_activity=context.current_activity)
        except Exception as e:
            self._log(context, "fsm", "activity_refresh_failed", error=str(e))

    def _resolve_tap_clickable(self, context: CortexContext, x: int, y: int) -> tuple[int, int, Optional[List[int]]]:
        """
        Map a TAP point to the center of the smallest clickable bounds that contains it.
        If no clickable container contains the point, keep original coordinates.
        """
        try:
            raw = self.client.dump_actions() or {}
            nodes = raw.get("nodes") or []
        except Exception:
            return x, y, None

        candidates: List[List[int]] = []
        for n in nodes:
            if not bool(n.get("clickable")):
                continue
            b = n.get("bounds")
            if not isinstance(b, (list, tuple)) or len(b) < 4:
                continue
            try:
                l, t, r, btm = int(b[0]), int(b[1]), int(b[2]), int(b[3])
            except Exception:
                continue
            if r <= l or btm <= t:
                continue
            if l <= x <= r and t <= y <= btm:
                candidates.append([l, t, r, btm])

        if not candidates:
            return x, y, None

        width = int(context.device_info.get("width") or 0)
        height = int(context.device_info.get("height") or 0)
        screen_area = max(1, width * height)

        def _area(bb: List[int]) -> int:
            return max(0, bb[2] - bb[0]) * max(0, bb[3] - bb[1])

        def _center(bb: List[int]) -> tuple[int, int]:
            return ((bb[0] + bb[2]) // 2, (bb[1] + bb[3]) // 2)

        # Primary strategy: smallest area + nearest center.
        candidates.sort(
            key=lambda bb: (
                _area(bb),
                abs(_center(bb)[0] - x) + abs(_center(bb)[1] - y),
            )
        )
        pick = candidates[0]
        tx = (pick[0] + pick[2]) // 2
        ty = (pick[1] + pick[3]) // 2

        # Safety gate:
        # If only a very large clickable container matches and rebinding would move
        # the tap too far away, keep the original model coordinate.
        picked_area = _area(pick)
        picked_ratio = picked_area / float(screen_area)
        move_dist = abs(tx - x) + abs(ty - y)
        if picked_ratio >= 0.10 and move_dist >= 120:
            return x, y, None

        return tx, ty, pick

    def _normalize_model_output(self, raw: str, state: CortexState, context: CortexContext) -> str:
        text = (raw or "").strip()
        if not text or not text.startswith("{"):
            return text
        try:
            obj = json.loads(text)
        except Exception:
            return text
        if not isinstance(obj, dict):
            return text
        if state == CortexState.APP_RESOLVE:
            pkg = str(obj.get("package_name") or obj.get("package") or "").strip()
            if pkg:
                return f"SET_APP {pkg}"
        if state == CortexState.ROUTE_PLAN:
            pkg = str(obj.get("package_name") or context.selected_package).strip()
            target = str(obj.get("target_page") or "").strip()
            if pkg and target:
                return f"ROUTE {pkg} {target}"
        if state == CortexState.VISION_ACT:
            action = str(obj.get("action") or "").strip().upper()
            if action == "DONE":
                return "DONE"
            if action == "BACK":
                return "BACK"
        return text

    def _extract_structured_command(self, state: CortexState, raw: str) -> tuple[str, Dict[str, Any]]:
        text = (raw or "").strip()
        if not text:
            return "", {}

        spec = self._STATE_OUTPUT_TAGS.get(state)
        if not spec:
            return text, {}

        cmd = self._extract_tag_text(text, "command")
        if not cmd:
            return text, {}

        root = spec["root"]
        root_content = self._extract_tag_text(text, root)
        if not root_content:
            # soft fallback: keep command only if top-level root missing
            return cmd.strip(), {}

        fields: Dict[str, str] = {}
        for f in spec["fields"]:
            fv = self._extract_tag_text(root_content, f)
            if fv:
                fields[f] = fv.strip()

        return cmd.strip(), {"root": root, **fields}

    def _extract_tag_text(self, text: str, tag: str) -> str:
        m = re.search(rf"<{tag}>\s*([\s\S]*?)\s*</{tag}>", text, flags=re.IGNORECASE)
        if not m:
            return ""
        return m.group(1).strip()

    def _probe_coordinate_space(self, context: CortexContext) -> Dict[str, Any]:
        if not bool(self.fsm_config.init_coord_probe_enabled):
            return {}
        if not hasattr(self.planner, "plan_vision"):
            return {}
        try:
            # Use square probe canvas to stabilize VLM native coordinate range
            # and avoid aspect-ratio-induced y-axis inflation.
            probe_w, probe_h = 1000, 1000
            image_bytes = self._build_coord_probe_image(probe_w, probe_h)
            prompt = (
                "Coordinate Calibration Task.\n"
                "You are given a synthetic image with black background and four colored corner markers:\n"
                "- top-left: RED\n"
                "- top-right: GREEN\n"
                "- bottom-left: BLUE\n"
                "- bottom-right: YELLOW\n"
                "Return ONLY JSON with this exact schema:\n"
                '{"tl":[x,y],"tr":[x,y],"bl":[x,y],"br":[x,y]}\n'
                "Rules:\n"
                "1) Output numbers only.\n"
                "2) Do NOT add markdown.\n"
                "3) Use your native coordinate space (do NOT convert on purpose).\n"
                "4) For each corner marker, return the point that is closest to the actual screen corner (not marker center).\n"
                "5) Be precise; this is for coordinate range calibration.\n"
            )
            raw = self.planner.plan_vision(CortexState.VISION_ACT, prompt, context, image_bytes)
            points = self._parse_coord_probe_response(raw)
            if not points:
                self._log(context, "fsm", "coord_probe_failed", reason="parse_failed", raw=(raw or "")[:400])
                return {}

            x_min = (points["tl"][0] + points["bl"][0]) / 2.0
            x_max = (points["tr"][0] + points["br"][0]) / 2.0
            y_min = (points["tl"][1] + points["tr"][1]) / 2.0
            y_max = (points["bl"][1] + points["br"][1]) / 2.0
            span_x = max(1e-6, x_max - x_min)
            span_y = max(1e-6, y_max - y_min)

            # Keep backward-compatible max_x/max_y while introducing range-based mapping.
            max_x = max(v[0] for v in points.values())
            max_y = max(v[1] for v in points.values())
            result = {
                "max_x": round(max_x, 4),
                "max_y": round(max_y, 4),
                "x_min": round(x_min, 4),
                "x_max": round(x_max, 4),
                "y_min": round(y_min, 4),
                "y_max": round(y_max, 4),
                "span_x": round(span_x, 4),
                "span_y": round(span_y, 4),
                "points": points,
                "probe_size": {"width": probe_w, "height": probe_h},
            }
            self._log(context, "fsm", "coord_probe_done", **result)
            return result
        except Exception as e:
            self._log(context, "fsm", "coord_probe_failed", reason=str(e))
            return {}

    def _build_coord_probe_image(self, width: int, height: int) -> bytes:
        from PIL import Image, ImageDraw

        img = Image.new("RGB", (int(width), int(height)), (0, 0, 0))
        draw = ImageDraw.Draw(img)
        arm = max(48, min(width, height) // 7)
        thick = max(6, arm // 8)

        # Draw "L" corner markers so the model can localize real corners better than block centers.
        # TL red
        draw.rectangle([0, 0, arm, thick], fill=(255, 0, 0))
        draw.rectangle([0, 0, thick, arm], fill=(255, 0, 0))
        # TR green
        draw.rectangle([width - arm - 1, 0, width - 1, thick], fill=(0, 255, 0))
        draw.rectangle([width - thick - 1, 0, width - 1, arm], fill=(0, 255, 0))
        # BL blue
        draw.rectangle([0, height - thick - 1, arm, height - 1], fill=(0, 100, 255))
        draw.rectangle([0, height - arm - 1, thick, height - 1], fill=(0, 100, 255))
        # BR yellow
        draw.rectangle([width - arm - 1, height - thick - 1, width - 1, height - 1], fill=(255, 220, 0))
        draw.rectangle([width - thick - 1, height - arm - 1, width - 1, height - 1], fill=(255, 220, 0))

        out = io.BytesIO()
        img.save(out, format="PNG")
        return out.getvalue()

    def _parse_coord_probe_response(self, raw: str) -> Dict[str, tuple[float, float]]:
        text = (raw or "").strip()
        obj = None
        try:
            tmp = json.loads(text)
            if isinstance(tmp, dict):
                obj = tmp
        except Exception:
            m = re.search(r"\{[\s\S]*\}", text)
            if m:
                try:
                    tmp = json.loads(m.group(0))
                    if isinstance(tmp, dict):
                        obj = tmp
                except Exception:
                    obj = None
        if not isinstance(obj, dict):
            return {}

        out: Dict[str, tuple[float, float]] = {}
        for k in ("tl", "tr", "bl", "br"):
            v = obj.get(k)
            if not isinstance(v, (list, tuple)) or len(v) < 2:
                return {}
            try:
                x = float(v[0])
                y = float(v[1])
            except Exception:
                return {}
            out[k] = (x, y)
        return out

    def _map_point_by_probe(self, context: CortexContext, raw_x: str, raw_y: str) -> tuple[int, int]:
        """
        Map LLM point by INIT probe max range:
        x_real = x_llm / max_x * (screen_w - 1)
        y_real = y_llm / max_y * (screen_h - 1)
        If probe data is missing/invalid, fallback to direct int parsing.
        """
        xf = float(raw_x)
        yf = float(raw_y)

        w = int(context.device_info.get("width") or 0)
        h = int(context.device_info.get("height") or 0)
        probe = context.coord_probe or {}
        max_x = float(probe.get("max_x") or 0.0)
        max_y = float(probe.get("max_y") or 0.0)
        if w <= 1 or h <= 1 or max_x <= 0.0 or max_y <= 0.0:
            return int(round(xf)), int(round(yf))

        # If model output already looks like screen pixel coordinates that exceed
        # the calibrated model range, bypass scaling to avoid edge clamping.
        if 0.0 <= xf <= float(w - 1) and 0.0 <= yf <= float(h - 1):
            if xf > max_x * 1.2 or yf > max_y * 1.2:
                rx = int(round(xf))
                ry = int(round(yf))
                self._log(
                    context,
                    "exec",
                    "coord_probe_bypass_absolute",
                    raw_x=xf,
                    raw_y=yf,
                    max_x=max_x,
                    max_y=max_y,
                    x=rx,
                    y=ry,
                )
                return rx, ry

        x_min = float(probe.get("x_min") or 0.0)
        x_max = float(probe.get("x_max") or 0.0)
        y_min = float(probe.get("y_min") or 0.0)
        y_max = float(probe.get("y_max") or 0.0)

        # Preferred mapping: range-based affine transform from probe corner band to screen.
        if x_max > x_min and y_max > y_min:
            nx = (xf - x_min) / (x_max - x_min)
            ny = (yf - y_min) / (y_max - y_min)
            rx = int(round(nx * float(w - 1)))
            ry = int(round(ny * float(h - 1)))
            mode = "range_affine"
        else:
            # Backward-compatible fallback: max-only scaling.
            rx = int(round((xf / max_x) * float(w - 1)))
            ry = int(round((yf / max_y) * float(h - 1)))
            mode = "max_scale"

        rx = max(0, min(w - 1, rx))
        ry = max(0, min(h - 1, ry))
        self._log(
            context,
            "exec",
            "coord_scaled_by_probe",
            mode=mode,
            raw_x=xf,
            raw_y=yf,
            max_x=max_x,
            max_y=max_y,
            x_min=x_min,
            x_max=x_max,
            y_min=y_min,
            y_max=y_max,
            x=rx,
            y=ry,
        )
        return rx, ry

    def _log(self, context: CortexContext, stage: str, event: str, **kwargs: Any) -> None:
        payload = {
            "ts": datetime.now(timezone.utc).isoformat(),
            "task_id": context.task_id,
            "stage": stage,
            "event": event,
            **kwargs,
        }
        if self._log_callback:
            self._log_callback(payload)
        else:
            print(json.dumps(payload, ensure_ascii=False))


def _normalize_app_candidates(raw_apps: Any) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    for item in raw_apps or []:
        if isinstance(item, dict):
            pkg = str(item.get("package") or "").strip()
            if not pkg:
                continue
            name = str(item.get("name") or item.get("label") or "").strip()
            out.append({"package": pkg, "name": name})
            continue
        pkg = str(item or "").strip()
        if not pkg:
            continue
        out.append({"package": pkg, "name": pkg.split(".")[-1]})
    return out
