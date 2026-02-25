"""
LXB-Cortex FSM Runtime Engine

This module provides the core Finite State Machine (FSM) engine for the LXB-Cortex
automation framework. It implements the "Route-Then-Act" pattern, executing tasks
through a deterministic state machine with optional LLM-based command planning.

The FSM engine manages the complete automation lifecycle:
1. INIT - Device initialization and coordinate space probing
2. APP_RESOLVE - Select the target app for the task
3. ROUTE_PLAN - Plan the route to the target page
4. ROUTING - Navigate through UI to reach the target page
5. VISION_ACT - Execute visual actions using VLM
6. FINISH - Task completed successfully
7. FAIL - Task failed with error

Example:
    >>> from lxb_link import LXBLinkClient
    >>> from cortex import CortexFSMEngine
    >>>
    >>> client = LXBLinkClient('192.168.1.100', 12345)
    >>> client.connect()
    >>> engine = CortexFSMEngine(client)
    >>> result = engine.run(
    ...     user_task="Open settings and enable dark mode",
    ...     map_path="maps/com.android.settings.json"
    ... )
    >>> print(result["status"])
    success
"""

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
    """Finite state machine states for the Cortex automation engine.

    The FSM progresses through these states to complete an automation task:
    - INIT: Initialize device connection and probe coordinate space
    - APP_RESOLVE: Select the target application for the task
    - ROUTE_PLAN: Plan the navigation route to the target page
    - ROUTING: Navigate through the UI hierarchy to reach target
    - VISION_ACT: Execute visual actions using VLM guidance
    - FINISH: Task completed successfully
    - FAIL: Task failed with an error condition
    """
    INIT = "INIT"
    APP_RESOLVE = "APP_RESOLVE"
    ROUTE_PLAN = "ROUTE_PLAN"
    ROUTING = "ROUTING"
    VISION_ACT = "VISION_ACT"
    FINISH = "FINISH"
    FAIL = "FAIL"


@dataclass
class FSMConfig:
    """Configuration parameters for the FSM engine.

    Attributes:
        max_turns: Maximum number of state transitions before timeout (default: 30)
        max_commands_per_turn: Maximum commands to execute per model turn (default: 1)
        max_vision_turns: Maximum number of vision/action turns (default: 20)
        action_interval_sec: Delay between action executions in seconds (default: 0.8)
        screenshot_settle_sec: Delay before taking screenshot on first vision turn (default: 0.6)
        tap_bind_clickable: Whether to bind taps to nearest clickable element (default: False)
        tap_jitter_sigma_px: Gaussian sigma for tap coordinate jitter in pixels (default: 0.0)
        swipe_jitter_sigma_px: Gaussian sigma for swipe coordinate jitter in pixels (default: 0.0)
        swipe_duration_jitter_ratio: Ratio for swipe duration jitter (default: 0.0)
        xml_stable_interval_sec: Interval between XML stability checks in seconds (default: 0.3)
        xml_stable_samples: Number of stable samples needed to consider XML stable (default: 4)
        xml_stable_timeout_sec: Maximum time to wait for XML stability in seconds (default: 4.0)
        init_coord_probe_enabled: Whether to probe coordinate space on init (default: True)
    """
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
    """Execution context for a Cortex automation task.

    This dataclass holds all state information for a single automation task execution,
    including task parameters, execution history, and accumulated results.

    Attributes:
        task_id: Unique identifier for this task execution
        user_task: The natural language description of the user's task
        map_path: Path to the navigation map JSON file
        start_page: Optional starting page ID (None = auto-detect)
        selected_package: Selected app package name
        target_page: Target page ID to reach
        route_trace: List of page IDs visited during routing
        command_log: History of all commands executed
        route_result: Result of the routing phase
        error: Error message if task failed
        output: Additional output data (coord probe, etc.)
        vision_turns: Number of vision/action turns executed
        app_candidates: List of candidate apps for task
        page_candidates: List of candidate pages for routing
        device_info: Device screen info (width, height, density)
        current_activity: Current Android activity info
        last_command: Last command executed
        same_command_streak: Count of consecutive identical commands
        last_activity_sig: Signature of last activity seen
        same_activity_streak: Count of consecutive identical activities
        coord_probe: Coordinate space probe results
        llm_history: History of LLM responses with structured data
        lessons: List of learned lessons from LLM reflections
    """
    task_id: str
    user_task: str
    map_path: Optional[str] = None
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
    lessons: List[str] = field(default_factory=list)


class CommandPlanner(Protocol):
    """Protocol for command planning strategies.

    A CommandPlanner generates the next command to execute based on the current
    FSM state, prompt, and execution context. This allows for different planning
    strategies (rule-based, LLM-based, hybrid).
    """
    def plan(self, state: CortexState, prompt: str, context: CortexContext) -> str:
        """Generate the next command to execute.

        Args:
            state: Current FSM state
            prompt: Formatted prompt for the planner
            context: Current execution context

        Returns:
            Command string to execute (e.g., "TAP 500 800", "ROUTE com.app home")
        """
        ...


class RuleBasedPlanner:
    """Minimal rule-based planner fallback.

    This planner provides simple rule-based command generation when no LLM
    planner is available. It uses heuristics for app selection and page targeting.

    Attributes:
        _route_loader: Function to load route maps from file paths
        _heuristic: HeuristicPlanner instance for page matching
    """

    def __init__(self, route_loader: Callable[[str], Any]):
        """Initialize the rule-based planner.

        Args:
            route_loader: Function that loads a RouteMap from a file path
        """
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
    """Builds structured prompts for LLM command planning.

    This class creates formatted prompts for different FSM states, specifying
    the expected output format, allowed operations, and providing context
    for the LLM to generate appropriate commands.
    """

    _STATE_FORMATS: Dict[CortexState, Dict[str, Any]] = {
        CortexState.APP_RESOLVE: {
            "root": "app_analysis",
            "fields": ["user_intent", "candidates", "decision", "reflection", "lesson"],
        },
        CortexState.ROUTE_PLAN: {
            "root": "route_plan_analysis",
            "fields": ["selected_app", "target_page_candidates", "decision", "reflection", "lesson"],
        },
        CortexState.VISION_ACT: {
            "root": "vision_analysis",
            "fields": ["page_state", "step_review", "reflection", "next_step_reasoning", "completion_gate", "done_confirm", "lesson"],
        },
    }

    def _common_output_rules(self) -> str:
        """Return common output format rules for all states.

        Returns:
            String containing the output contract rules that apply
            to all FSM states.
        """
        return (
            "Output Contract:\n"
            "1) Output MUST follow the state XML-like format exactly.\n"
            "2) Output MUST contain exactly one <command>...</command>.\n"
            "3) No JSON, no markdown, no extra prose outside tags.\n"
            "4) If you cannot decide safely, command must be FAIL <reason>.\n"
        )

    def _dsl_semantics(self, allowed_ops: Set[str]) -> str:
        """Generate DSL semantics documentation for allowed operations.

        Args:
            allowed_ops: Set of operation names that are allowed in current state

        Returns:
            Formatted string describing each allowed operation with its syntax
            and usage rules.
        """
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
        """Extract recent LLM history as JSON.

        Args:
            context: Current execution context
            limit: Maximum number of recent history entries to include (default: 5)

        Returns:
            JSON string of recent LLM history entries, or "[]" if empty
        """
        if not context.llm_history:
            return "[]"
        rows = context.llm_history[-max(1, int(limit)) :]
        return json.dumps(rows, ensure_ascii=False)

    def _lessons_snippet(self, context: CortexContext) -> str:
        """Extract lessons as JSON.

        Args:
            context: Current execution context

        Returns:
            JSON string of learned lessons, or "[]" if empty
        """
        if not context.lessons:
            return "[]"
        return json.dumps(context.lessons, ensure_ascii=False)

    def build(self, state: CortexState, context: CortexContext, allowed_ops: Set[str]) -> str:
        """Build a complete prompt for the given state and context.

        Args:
            state: Current FSM state
            context: Current execution context
            allowed_ops: Set of allowed operation names for this state

        Returns:
            Formatted prompt string containing output format rules,
            DSL semantics, context data, and state-specific instructions.
        """
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
                    f"Lessons(JSON): {self._lessons_snippet(context)}\n",
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
                    f"Lessons(JSON): {self._lessons_snippet(context)}\n",
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
                    f"Lessons(JSON): {self._lessons_snippet(context)}\n",
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
                    "3.5) <lesson> is OPTIONAL. Only output it when there is a reusable cross-step rule.\n",
                    "     Keep lesson concise (<= 1 sentence). If no stable lesson, omit it.\n",
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
    """LLM-based command planner with optional vision support.

    This planner uses an LLM to generate commands for the FSM. It supports
    both text-only and vision-aware planning.

    Attributes:
        _complete: Function for text-only LLM completion
        _complete_with_image: Optional function for vision-enabled LLM completion
    """

    def __init__(self, complete: Callable[[str], str], complete_with_image: Optional[Callable[[str, bytes], str]] = None):
        """Initialize the LLM planner.

        Args:
            complete: Function that takes a prompt string and returns LLM response
            complete_with_image: Optional function that takes prompt and image bytes,
                returns LLM response with vision understanding
        """
        self._complete = complete
        self._complete_with_image = complete_with_image

    def plan(self, state: CortexState, prompt: str, context: CortexContext) -> str:
        """Generate a command using text-only LLM completion.

        Args:
            state: Current FSM state
            prompt: Formatted prompt for the LLM
            context: Current execution context

        Returns:
            LLM response containing the command to execute
        """
        return self._complete(prompt)

    def plan_vision(self, state: CortexState, prompt: str, context: CortexContext, screenshot: bytes) -> str:
        if self._complete_with_image and screenshot:
            return self._complete_with_image(prompt, screenshot)
        return self._complete(prompt)


class CortexFSMEngine:
    """Core Finite State Machine engine for LXB-Cortex automation.

    This class implements the complete FSM workflow for Android automation tasks.
    It manages state transitions, command planning, action execution, and error handling.

    The FSM follows this flow:
    1. INIT - Probe device info and coordinate space
    2. APP_RESOLVE - Select target app using LLM or heuristics
    3. ROUTE_PLAN - Plan navigation to target page
    4. ROUTING - Execute route using deterministic navigation
    5. VISION_ACT - Execute visual actions with VLM guidance
    6. FINISH or FAIL - Task completion

    Example:
        >>> from lxb_link import LXBLinkClient
        >>> from cortex import CortexFSMEngine, LLMPlanner
        >>>
        >>> client = LXBLinkClient('192.168.1.100', 12345)
        >>> client.connect()
        >>>
        >>> def my_llm_complete(prompt):
        ...     return my_llm_api.generate(prompt)
        >>>
        >>> planner = LLMPlanner(complete=my_llm_complete)
        >>> engine = CortexFSMEngine(client, planner=planner)
        >>> result = engine.run(
        ...     user_task="Open settings and enable WiFi",
        ...     map_path="maps/com.android.settings.json"
        ... )
        >>> print(result["status"])
        'success'
    """
    """Allowed operations for each FSM state."""
    _ALLOWED_OPS: Dict[CortexState, Set[str]] = {
        CortexState.APP_RESOLVE: {"SET_APP", "FAIL"},
        CortexState.ROUTE_PLAN: {"ROUTE", "FAIL"},
        CortexState.VISION_ACT: {"TAP", "SWIPE", "INPUT", "WAIT", "BACK", "DONE", "FAIL"},
    }
    """Structured output tag specifications for each FSM state."""
    _STATE_OUTPUT_TAGS: Dict[CortexState, Dict[str, Any]] = {
        CortexState.APP_RESOLVE: {
            "root": "app_analysis",
            "fields": ["user_intent", "candidates", "decision", "reflection", "lesson"],
        },
        CortexState.ROUTE_PLAN: {
            "root": "route_plan_analysis",
            "fields": ["selected_app", "target_page_candidates", "decision", "reflection", "lesson"],
        },
        CortexState.VISION_ACT: {
            "root": "vision_analysis",
            "fields": ["page_state", "step_review", "reflection", "next_step_reasoning", "completion_gate", "done_confirm", "lesson"],
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
        """Initialize the Cortex FSM engine.

        Args:
            client: LXBLinkClient instance for device communication
            planner: Optional CommandPlanner for LLM-based planning.
                If None, uses RuleBasedPlanner as fallback.
            route_config: Optional RouteConfig for routing phase behavior
            fsm_config: Optional FSMConfig for engine behavior parameters
            log_callback: Optional callback function for logging events.
                Receives a dict with event data.
        """
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
        map_path: Optional[str] = None,
        start_page: Optional[str] = None,
        extra_context: Optional[Dict[str, Any]] = None,
        package_name: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Execute an automation task using the FSM engine.

        This is the main entry point for running automation tasks. The FSM will
        progress through states until reaching FINISH or FAIL, or hitting max_turns.

        Args:
            user_task: Natural language description of the task to perform
            map_path: Path to the navigation map JSON file (None = vision-only mode)
            start_page: Optional starting page ID. If None, auto-detects from
                current device state
            extra_context: Optional additional context fields to add to CortexContext
            package_name: Optional package to pre-select, bypassing APP_RESOLVE LLM call

        Returns:
            Dict containing execution results:
                - status: "success" or "failed"
                - task_id: Unique task identifier
                - state: Final FSM state
                - package_name: Selected app package
                - target_page: Target page ID
                - route_trace: List of pages visited during routing
                - route_result: Detailed routing phase results
                - command_log: History of all commands executed
                - llm_history: LLM response history with structured data
                - lessons: Learned lessons from reflections
                - reason: Error reason if failed (optional)
                - output: Additional output data (optional)

        Example:
            >>> result = engine.run(
            ...     user_task="Enable dark mode in settings",
            ...     map_path="maps/com.android.settings.json"
            ... )
            >>> if result["status"] == "success":
            ...     print("Task completed!")
            ... else:
            ...     print(f"Task failed: {result.get('reason')}")
        """
        context = CortexContext(task_id=str(uuid.uuid4()), user_task=user_task, map_path=map_path, start_page=start_page)
        if package_name:
            context.selected_package = package_name
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
            "lessons": context.lessons,
        }
        if context.error:
            result["reason"] = context.error
        if context.output:
            result["output"] = context.output
        return result

    def _run_model_state(self, context: CortexContext, state: CortexState) -> CortexState:
        """Execute a model-based state (APP_RESOLVE or ROUTE_PLAN).

        This method handles states that require LLM planning:
        - Builds prompt with context and allowed operations
        - Calls planner to generate command
        - Parses and validates the response
        - Updates context and determines next state

        Args:
            context: Current execution context
            state: Current FSM state (APP_RESOLVE or ROUTE_PLAN)

        Returns:
            Next FSM state to transition to
        """
        # No-map shortcut: skip ROUTE_PLAN LLM call when no map is available
        if state == CortexState.ROUTE_PLAN and not context.map_path:
            self._log(context, "fsm", "route_plan_skipped", reason="no_map", package=context.selected_package)
            return CortexState.ROUTING

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
            self._collect_lesson(context, structured)
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
        """Execute the INIT state to initialize device and context.

        This method:
        - Retrieves device screen size and density
        - Gets current activity info
        - Loads app list if not provided
        - Probes coordinate space for VLM calibration

        Args:
            context: Execution context to populate with device info

        Returns:
            CortexState.APP_RESOLVE to proceed to app selection
        """
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
        """Execute the ROUTING state to navigate to target page.

        This method creates a RouteThenActCortex engine with a fixed plan
        to navigate through the UI hierarchy to reach the target page.

        Args:
            context: Execution context with selected_package and target_page set

        Returns:
            CortexState.VISION_ACT if routing succeeds, CortexState.FAIL if routing fails
        """
        self._log(context, "fsm", "routing_start", package_name=context.selected_package, target_page=context.target_page)
        planner = FixedPlanPlanner(context.selected_package, context.target_page)
        route_engine = RouteThenActCortex(
            client=self.client,
            planner=planner,
            config=self.route_config,
            action_engine=None,
            log_callback=lambda payload: self._log(context, "route", "route_event", payload=payload),
        )
        result = route_engine.run(
            user_task=context.user_task,
            map_path=context.map_path,
            start_page=context.start_page,
            package_name=context.selected_package or None,
        )
        context.route_result = result
        if result.get("status") != "success":
            context.error = result.get("reason", "route_failed")
            self._log(context, "fsm", "routing_fail", reason=context.error)
            return CortexState.FAIL
        context.route_trace = list(result.get("route_trace") or [])
        self._log(context, "fsm", "routing_done", steps=len(context.route_trace))
        return CortexState.VISION_ACT

    def _run_vision_state(self, context: CortexContext) -> CortexState:
        """Execute the VISION_ACT state to perform visual actions.

        This method:
        - Takes a screenshot of the current device state
        - Sends screenshot + context to LLM planner
        - Parses and executes the returned action
        - Detects and prevents action loops
        - Transitions to FINISH on DONE command

        Args:
            context: Execution context with vision state tracking

        Returns:
            Next FSM state (VISION_ACT to continue, FINISH on success, FAIL on error)
        """
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
            self._collect_lesson(context, structured)
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
        """Execute a single action command (TAP, SWIPE, INPUT, WAIT, BACK).

        Args:
            context: Current execution context
            cmd: Instruction to execute

        Returns:
            True if command executed successfully, False on error
        """
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
                status, actual_method = self.client.input_text(cmd.args[0])
                self._log(context, "exec", "input_result", status=int(status), method=int(actual_method))
                if int(status) <= 0:
                    context.error = f"action_exec_error:INPUT:status={status}"
                    self._log(context, "fsm", "action_error", op=cmd.op, error=f"input_failed_status_{status}")
                    return False
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
        """Append executed commands to the context log.

        Args:
            context: Execution context to append commands to
            state: Current FSM state
            commands: List of instructions executed
        """
        for cmd in commands:
            context.command_log.append({"state": state.value, "op": cmd.op, "args": cmd.args, "raw": cmd.raw})
            self._log(context, "fsm", "command", state=state.value, op=cmd.op, args=cmd.args)

    def _screenshot(self) -> Optional[bytes]:
        """Capture a screenshot from the device.

        Returns:
            Screenshot image bytes, or None if capture failed
        """
        try:
            return self.client.request_screenshot()
        except Exception:
            return None

    def _in_screen_bounds(self, context: CortexContext, x: int, y: int) -> bool:
        """Check if coordinates are within screen bounds.

        Args:
            context: Execution context with device_info containing screen dimensions
            x: X coordinate to check
            y: Y coordinate to check

        Returns:
            True if coordinates are within valid screen area, False if invalid bounds
        """
        w = int(context.device_info.get("width") or 0)
        h = int(context.device_info.get("height") or 0)
        if w <= 0 or h <= 0:
            return True
        return 0 <= x < w and 0 <= y < h

    def _apply_point_jitter(self, context: CortexContext, x: int, y: int, sigma: float) -> tuple[int, int]:
        """Apply Gaussian jitter to coordinates for more natural touch input.

        Args:
            context: Execution context with device screen dimensions
            x: Original X coordinate
            y: Original Y coordinate
            sigma: Standard deviation for Gaussian distribution in pixels

        Returns:
            Tuple of (jittered_x, jittery_y) clamped to screen bounds
        """
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
        """Apply Gaussian jitter to duration values.

        Args:
            duration_ms: Original duration in milliseconds
            ratio: Jitter ratio (sigma = duration_ms * ratio)

        Returns:
            Jittered duration in milliseconds, minimum 80ms
        """
        if duration_ms <= 0 or ratio <= 0:
            return max(1, duration_ms)
        sigma = max(1.0, duration_ms * ratio)
        jittered = int(round(random.gauss(duration_ms, sigma)))
        return max(80, jittered)

    def _wait_for_xml_stable(self, context: CortexContext, reason: str) -> None:
        """Wait for the UI hierarchy to stabilize after an action.

        This method polls dump_actions() and waits for the signature to remain
        stable across multiple samples, indicating the UI has settled.

        Args:
            context: Execution context for logging
            reason: Description of what action triggered the wait (for logging)
        """
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
        """Generate a compact signature of the current UI hierarchy.

        This method creates a hash-like string from dump_actions() output
        to detect when the UI has stabilized.

        Returns:
            String signature containing bounds, clickable state, text,
            resource_id, and class for each node, limited to 400 nodes
        """
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
        """Refresh the current activity info and track activity streaks.

        Updates context.current_activity and tracks consecutive identical
        activities for loop detection.

        Args:
            context: Execution context to update with activity info
        """
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
        """Map a TAP point to the center of the smallest clickable bounds containing it.

        This helps improve tap accuracy by snapping to clickable element centers
        when tap_bind_clickable is enabled.

        Args:
            context: Execution context with device info
            x: Original tap X coordinate
            y: Original tap Y coordinate

        Returns:
            Tuple of (tap_x, tap_y, bounds) where bounds is the clicked element's
            bounds [left, top, right, bottom] or None if no clickable element found
        """
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
        """Normalize model output to a standard command format.

        Handles both JSON responses and direct command strings, converting
        JSON to appropriate DSL commands.

        Args:
            raw: Raw model output string
            state: Current FSM state
            context: Execution context

        Returns:
            Normalized command string (e.g., "SET_APP com.example", "ROUTE pkg home")
        """
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
        """Extract structured XML-like output from model response.

        Parses the model's XML-formatted response to extract both the command
        and structured analysis fields (user_intent, decision, reflection, etc.).

        Args:
            state: Current FSM state (determines expected format)
            raw: Raw model output string

        Returns:
            Tuple of (command_text, structured_dict) where structured_dict
            contains the parsed XML fields, or empty dict if parsing fails
        """
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
        """Extract content between XML-like tags.

        Args:
            text: Text to search within
            tag: Tag name to extract (e.g., "command", "decision")

        Returns:
            Content between <tag> and </tag>, or empty string if not found
        """
        m = re.search(rf"<{tag}>\s*([\s\S]*?)\s*</{tag}>", text, flags=re.IGNORECASE)
        if not m:
            return ""
        return m.group(1).strip()

    def _collect_lesson(self, context: CortexContext, structured: Dict[str, Any]) -> None:
        """Extract and store a lesson from structured LLM output.

        Lessons are reusable insights learned during execution that are
        fed back to the LLM in subsequent turns.

        Args:
            context: Execution context to append lesson to
            structured: Structured output dict potentially containing "lesson" field
        """
        raw = str((structured or {}).get("lesson") or "").strip()
        if not raw:
            return
        if raw.lower() in {"none", "n/a", "na", "null", "no lesson"}:
            return
        # Keep lesson concise to avoid prompt bloat.
        lesson = raw if len(raw) <= 180 else (raw[:180].rstrip() + "...")
        if lesson in context.lessons:
            return
        context.lessons.append(lesson)

    def _probe_coordinate_space(self, context: CortexContext) -> Dict[str, Any]:
        """Probe the VLM's coordinate space by sending a synthetic calibration image.

        This method creates a synthetic image with colored corner markers and
        asks the VLM to identify the corners, establishing the VLM's native
        coordinate range for accurate point mapping.

        Args:
            context: Execution context for logging and result storage

        Returns:
            Dict containing calibration data (max_x, max_y, x_min, x_max, y_min, y_max,
            span_x, span_y, points, probe_size) or empty dict if probing fails
        """
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
        """Build a synthetic calibration image for coordinate space probing.

        Creates a black image with colored L-shaped corner markers:
        - Top-left: RED
        - Top-right: GREEN
        - Bottom-left: BLUE
        - Bottom-right: YELLOW

        Args:
            width: Image width in pixels
            height: Image height in pixels

        Returns:
            PNG image bytes
        """
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
        """Parse the VLM's corner detection response from coordinate probing.

        Args:
            raw: Raw VLM response string, should contain JSON with corner coordinates

        Returns:
            Dict mapping corner keys ("tl", "tr", "bl", "br") to (x, y) tuples,
            or empty dict if parsing fails
        """
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
        """Map VLM coordinates to screen coordinates using calibration data.

        Uses the coordinate space probe results to accurately map VLM-generated
        coordinates to actual device screen coordinates. Supports both range-based
        affine mapping and max-only scaling fallback.

        Mapping formulas:
        - Range affine: x_screen = (x_llm - x_min) / (x_max - x_min) * (screen_w - 1)
        - Max-only fallback: x_screen = x_llm / max_x * (screen_w - 1)

        Args:
            context: Execution context with device_info and coord_probe data
            raw_x: VLM X coordinate as string
            raw_y: VLM Y coordinate as string

        Returns:
            Tuple of (screen_x, screen_y) clamped to screen bounds
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
        """Log an event with timestamp and task context.

        Args:
            context: Execution context for task_id
            stage: Stage identifier (e.g., "fsm", "exec", "llm", "route")
            event: Event name within the stage
            **kwargs: Additional event-specific data to log
        """
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
    """Normalize app candidate data to a standard format.

    Converts various input formats (dict with package/name, strings, etc.)
    to a consistent list of dicts with "package" and "name" keys.

    Args:
        raw_apps: Raw app data from client.list_apps(), can be list of dicts
            or list of strings

    Returns:
        List of dicts with "package" and "name" keys
    """
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
