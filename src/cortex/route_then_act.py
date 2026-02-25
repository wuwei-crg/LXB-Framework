"""
LXB-Cortex Route-Then-Act Engine

This module implements the "Route-Then-Act" pattern for Android automation.
The routing phase navigates through the UI hierarchy deterministically to reach
a target page, then hands off to an action engine for task execution.

The routing strategy uses BFS pathfinding through a navigation map, with
XML-first locator resolution to minimize coordinate dependencies. When routing
fails, it can recover using VLM-based popup detection and dismissal.

Key Components:
- Locator: UI element identifier for navigation nodes
- RouteEdge: Transition between pages with trigger locator
- RouteMap: Complete navigation graph for an app
- RoutePlan: Target page specification
- RouteThenActCortex: Main routing engine with BFS execution

Example:
    >>> from lxb_link import LXBLinkClient
    >>> from cortex.route_then_act import RouteThenActCortex, FixedPlanPlanner
    >>>
    >>> client = LXBLinkClient('192.168.1.100', 12345)
    >>> client.connect()
    >>>
    >>> planner = FixedPlanPlanner("com.example.app", "settings")
    >>> engine = RouteThenActCortex(client, planner=planner)
    >>> result = engine.run(
    ...     user_task="Open settings",
    ...     map_path="maps/com.example.app.json"
    ... )
    >>> print(result["status"])
    'success'
"""

import json
import os
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Callable, Dict, List, Optional, Protocol, Tuple

try:
    from src.auto_map_builder.vlm_engine import VLMEngine
except ImportError:
    from auto_map_builder.vlm_engine import VLMEngine


@dataclass
class Locator:
    """UI element identifier for finding navigation nodes.

    Locators are used to identify clickable elements that trigger page
    transitions. The routing engine uses multiple attributes to improve
    match reliability.

    Attributes:
        resource_id: Android resource ID (e.g., "com.app:id/button")
        text: Visible text content of the element
        content_desc: Content description for accessibility
        class_name: Fully qualified class name (e.g., "android.widget.Button")
        parent_rid: Resource ID of parent element for disambiguation
        bounds_hint: Optional screen bounds hint [left, top, right, bottom]
    """
    resource_id: Optional[str] = None
    text: Optional[str] = None
    content_desc: Optional[str] = None
    class_name: Optional[str] = None
    parent_rid: Optional[str] = None
    bounds_hint: Optional[Tuple[int, int, int, int]] = None
    locator_index: Optional[int] = None   # 0-based index among peers with same identity
    locator_count: Optional[int] = None   # total peer count (2-3 when set)


@dataclass
class RouteEdge:
    """Represents a navigational transition between two pages.

    A route edge defines how to navigate from one page to another by
    specifying which UI element to tap.

    Attributes:
        from_page: Source page ID
        to_page: Destination page ID
        locator: UI element to tap for this transition
        description: Human-readable description of the transition
        legacy_from: Deprecated source page ID for backward compatibility
        legacy_to: Deprecated destination page ID for backward compatibility
    """
    from_page: str
    to_page: str
    locator: Locator
    description: str = ""
    legacy_from: Optional[str] = None
    legacy_to: Optional[str] = None


@dataclass
class RouteMap:
    """Complete navigation map for an Android application.

    A route map defines the navigation graph of an app, including all pages,
    transitions between them, and special handlers for popups and blocking states.

    Attributes:
        package: Android package name (e.g., "com.example.app")
        pages: Dict mapping page IDs to page metadata (name, features, aliases, etc.)
        transitions: List of RouteEdges defining navigational paths
        popups: List of popup definitions with close locators for automatic handling
        blocks: List of block states (e.g., loading screens) to detect
        metadata: Additional metadata including page_id mappings
    """
    package: str
    pages: Dict[str, Dict[str, Any]]
    transitions: List[RouteEdge]
    popups: List[Dict[str, Any]] = field(default_factory=list)
    blocks: List[Dict[str, Any]] = field(default_factory=list)
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class RoutePlan:
    """Target specification for routing.

    Defines which app and page the routing engine should navigate to.

    Attributes:
        package_name: Target app package name
        target_page: Target page ID to reach
    """
    package_name: str
    target_page: str


@dataclass
class RouteConfig:
    """Configuration parameters for the Route-Then-Act engine.

    Attributes:
        node_exists_retries: Number of retries to confirm a node exists (default: 3)
        node_exists_interval_sec: Delay between retries in seconds (default: 0.6)
        max_route_restarts: Maximum app restarts when routing fails (default: 3)
        use_vlm_takeover: Whether to use VLM for popup recovery (default: True)
        vlm_takeover_timeout_sec: Timeout for VLM popup classification (default: 15.0)
        route_recovery_enabled: Whether to enable route recovery attempts (default: True)
    """
    node_exists_retries: int = 3
    node_exists_interval_sec: float = 0.6
    max_route_restarts: int = 3
    use_vlm_takeover: bool = True
    vlm_takeover_timeout_sec: float = 15.0
    route_recovery_enabled: bool = True


class MapTaskPlanner(Protocol):
    """Protocol for task planning strategies.

    A MapTaskPlanner analyzes a user task and route map to determine
    which target page the routing engine should navigate to.
    """
    def plan(self, user_task: str, route_map: RouteMap) -> RoutePlan:
        """Plan the target page for a given user task.

        Args:
            user_task: Natural language description of the user's goal
            route_map: Navigation map for the target app

        Returns:
            RoutePlan specifying the package and target page
        """
        ...


class VLMActionEngine(Protocol):
    """Protocol for VLM-based action execution.

    A VLMActionEngine takes over after routing completes to perform
    visual task execution using vision-language model guidance.
    """
    def execute(self, user_task: str, context: Dict[str, Any]) -> Dict[str, Any]:
        """Execute the user task using VLM guidance.

        Args:
            user_task: Natural language description of the task
            context: Execution context with task_id, package_name, target_page, route_trace

        Returns:
            Dict containing execution results (status, output, etc.)
        """
        ...


class HeuristicPlanner:
    """Minimal planner fallback using keyword matching.

    If no external LLM planner is injected, this planner tries to map
    task text to page ID using simple keyword matching against page IDs,
    aliases, names, and legacy IDs.

    Attributes:
        No explicit attributes, uses static analysis of route map
    """

    def plan(self, user_task: str, route_map: RouteMap) -> RoutePlan:
        """Plan target page by matching task keywords against page metadata.

        Matching strategy (in order):
        1. Direct page ID match in task text
        2. Legacy page ID match
        3. Alias match
        4. Page name match
        5. Fallback to inferred home page

        Args:
            user_task: Natural language description of the task
            route_map: Navigation map for the target app

        Returns:
            RoutePlan with matched or fallback target page
        """
        task = (user_task or "").lower()
        pages = route_map.pages

        # 1. direct match by page id / alias
        for page_id, page in pages.items():
            aliases = page.get("target_aliases", [])
            legacy = page.get("legacy_page_id")
            name = (page.get("name") or "").lower()
            if page_id.lower() in task:
                return RoutePlan(route_map.package, page_id)
            if legacy and str(legacy).lower() in task:
                return RoutePlan(route_map.package, page_id)
            if any(str(a).lower() in task for a in aliases):
                return RoutePlan(route_map.package, page_id)
            if name and name in task:
                return RoutePlan(route_map.package, page_id)

        # 2. fallback to home-like page
        home = _infer_home_page(pages, route_map.transitions)
        return RoutePlan(route_map.package, home)


class FixedPlanPlanner:
    """Planner that always returns a predetermined package and target page.

    This planner is useful when the target page is already known and
    no LLM analysis is needed.

    Attributes:
        package_name: Fixed package name to return
        target_page: Fixed target page ID to return
    """

    def __init__(self, package_name: str, target_page: str):
        """Initialize the fixed planner.

        Args:
            package_name: Package name to always return
            target_page: Target page ID to always return
        """
        self.package_name = package_name
        self.target_page = target_page

    def plan(self, user_task: str, route_map: RouteMap) -> RoutePlan:
        """Return the fixed package and target page.

        Args:
            user_task: Ignored (kept for protocol compatibility)
            route_map: Fallback for package name if package_name not set

        Returns:
            RoutePlan with the predetermined package and target page
        """
        return RoutePlan(self.package_name or route_map.package, self.target_page)


class MapPromptPlanner:
    """LLM-based planner with heuristic fallback.

    This planner sends the route map and user task to an LLM to determine
    the target page. If the LLM response cannot be parsed, it automatically
    falls back to HeuristicPlanner.

    Attributes:
        _llm_complete: Function that takes a prompt and returns LLM response
        _fallback: HeuristicPlanner instance for fallback behavior
    """

    def __init__(self, llm_complete: Callable[[str], str]):
        """Initialize the LLM-based planner.

        Args:
            llm_complete: Function that takes a prompt string and returns
                LLM response (should be JSON with package_name and target_page)
        """
        self._llm_complete = llm_complete
        self._fallback = HeuristicPlanner()

    def plan(self, user_task: str, route_map: RouteMap) -> RoutePlan:
        """Plan target page using LLM analysis with heuristic fallback.

        Args:
            user_task: Natural language description of the task
            route_map: Navigation map for the target app

        Returns:
            RoutePlan with LLM-chosen or fallback target page
        """
        prompt = self._build_prompt(user_task, route_map)
        try:
            raw = self._llm_complete(prompt)
            payload = _extract_json_object(raw or "")
            package = str(payload.get("package_name") or route_map.package).strip() or route_map.package
            target_page = str(payload.get("target_page") or "").strip()
            if target_page:
                return RoutePlan(package, target_page)
        except Exception:
            pass
        return self._fallback.plan(user_task, route_map)

    def _build_prompt(self, user_task: str, route_map: RouteMap) -> str:
        """Build the LLM prompt for route planning.

        Creates a structured prompt with the user task and route map,
        instructing the LLM to return JSON with package_name and target_page.

        Args:
            user_task: Natural language description of the task
            route_map: Navigation map for the target app

        Returns:
            Formatted prompt string for LLM completion
        """
        edge_rows = []
        for edge in route_map.transitions:
            edge_rows.append(
                {
                    "from": edge.from_page,
                    "to": edge.to_page,
                    "trigger": edge.description or "",
                }
            )

        page_rows = []
        for page_id, page in route_map.pages.items():
            aliases = page.get("target_aliases") or []
            features = page.get("features") or []
            row = {
                "page_id": page_id,
                "legacy_page_id": page.get("legacy_page_id", ""),
                "name": page.get("name", ""),
                "description": page.get("description", ""),
                "features": features[:12],
                "aliases": aliases[:6],
            }
            page_rows.append(row)

        brief_map = {
            "package": route_map.package,
            "pages": page_rows,
            "transitions": edge_rows,
        }
        return (
            "You are a mobile route planner.\n"
            "Given user task and route map, output JSON only:\n"
            '{"package_name":"...","target_page":"..."}\n'
            "Rules:\n"
            "1) target_page must be one page_id from map.pages, or one legacy_page_id.\n"
            "2) You MUST use semantic fields (name/description/features/aliases) to match intent.\n"
            "3) If intent is ambiguous, choose the page whose semantic description is most specific.\n\n"
            f"user_task:\n{user_task}\n\n"
            f"route_map:\n{json.dumps(brief_map, ensure_ascii=False)}"
        )


class RouteThenActCortex:
    """Route-Then-Act engine for deterministic UI navigation.

    This engine implements the routing phase of the Route-Then-Act pattern:
    1. Planner resolves package + target_page from user task
    2. BFS pathfinding finds shortest route through navigation graph
    3. Execute route by tapping locators along the path
    4. On target page, handoff to VLM action engine for task execution

    The engine supports recovery from route failures through:
    - Known popup detection and dismissal
    - VLM-based popup classification
    - App restart and retry

    Example:
        >>> from lxb_link import LXBLinkClient
        >>> from cortex.route_then_act import RouteThenActCortex, FixedPlanPlanner
        >>>
        >>> client = LXBLinkClient('192.168.1.100', 12345)
        >>> client.connect()
        >>>
        >>> planner = FixedPlanPlanner("com.example.app", "settings")
        >>> engine = RouteThenActCortex(client, planner=planner)
        >>> result = engine.run(
        ...     user_task="Open settings",
        ...     map_path="maps/com.example.app.json"
        ... )
        >>> print(result["status"])
        'success'
    """

    def __init__(
        self,
        client,
        planner: Optional[MapTaskPlanner] = None,
        action_engine: Optional[VLMActionEngine] = None,
        config: Optional[RouteConfig] = None,
        log_callback: Optional[Callable[[Dict[str, Any]], None]] = None,
    ):
        """Initialize the Route-Then-Act engine.

        Args:
            client: LXBLinkClient instance for device communication
            planner: Optional MapTaskPlanner for target page selection.
                Defaults to HeuristicPlanner.
            action_engine: Optional VLMActionEngine for task execution after routing.
                If None, routing completes without action execution.
            config: Optional RouteConfig for engine behavior tuning
            log_callback: Optional callback for logging events
        """
        self.client = client
        self.config = config or RouteConfig()
        self.planner = planner or HeuristicPlanner()
        self.action_engine = action_engine
        self._log_callback = log_callback
        self._task_id = ""
        self._vlm = VLMEngine()

    def run(
        self,
        user_task: str,
        map_path: Optional[str] = None,
        start_page: Optional[str] = None,
        package_name: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Execute the Route-Then-Act workflow.

        This method:
        1. Loads the route map from the specified path
        2. Uses the planner to determine the target page
        3. Finds a BFS path from start_page to target_page
        4. Executes the route by tapping locators
        5. Optionally hands off to action_engine for task execution

        Args:
            user_task: Natural language description of the task
            map_path: Path to the route map JSON file (None = skip routing, launch app directly)
            start_page: Optional starting page ID. If None, infers home page.
            package_name: Package name to launch when map_path is None

        Returns:
            Dict containing execution results:
                - status: "success" or "failed"
                - reason: Failure reason if status is "failed"
                - target_page: Target page that was routed to
                - route_trace: List of step descriptions executed
                - route_only: True if no action_engine was provided
                - vlm_result: Action engine result if provided
        """
        self._task_id = str(uuid.uuid4())

        # ── No-map path: skip ROUTE_PLAN/ROUTING, go straight to VISION_ACT ──
        if not map_path or not os.path.exists(map_path):
            if not package_name:
                return {"status": "failed", "reason": "no_map_and_no_package"}
            self._log("route", "no_map", result="ok", package_name=package_name,
                      note="map unavailable, skipping to vision")
            self.client.launch_app(package_name, clear_task=True)
            time.sleep(1.5)
            context = {
                "task_id": self._task_id,
                "package_name": package_name,
                "target_page": None,
                "route_trace": [],
            }
            if not self.action_engine:
                self._log("vlm", "handoff", result="ok", note="no_action_engine")
                return {"status": "success", **context, "route_only": True, "vlm_result": None}
            self._log("vlm", "handoff", result="ok")
            result = self.action_engine.execute(user_task, self.client)
            return {"status": "success", **context, "vlm_result": result}

        # ── Normal path ──────────────────────────────────────────────────────
        route_map = self._load_map(map_path)

        plan = self.planner.plan(user_task, route_map)
        target_page = self._resolve_target_page(route_map, plan.target_page)
        self._log("route", "plan_ready", result="ok", package_name=plan.package_name, target_page=target_page)

        path = self._bfs_path(route_map, target_page, start_page=start_page)
        if path is None:
            self._log("route", "path_not_found", result="fail", target_page=target_page)
            return {"status": "failed", "reason": "path_not_found", "target_page": target_page}

        self._log("route", "path_ready", result="ok", steps=len(path), target_page=target_page)
        route_ok, trace = self._execute_route(route_map, path)
        if not route_ok:
            return {
                "status": "failed",
                "reason": "route_failed",
                "target_page": target_page,
                "route_trace": trace,
            }

        self._log("route", "route_done", result="ok", steps=len(path), target_page=target_page)

        context = {
            "task_id": self._task_id,
            "package_name": route_map.package,
            "target_page": target_page,
            "route_trace": trace,
        }
        if not self.action_engine:
            self._log("vlm", "handoff", result="ok", note="no_action_engine")
            return {"status": "success", "route_only": True, **context}

        self._log("vlm", "handoff", result="ok")
        result = self.action_engine.execute(user_task, context)
        if not isinstance(result, dict):
            result = {"status": "success", "result": result}
        return {"status": "success", **context, "vlm_result": result}

    # ----------------------------
    # Map + Planner
    # ----------------------------
    def _load_map(self, map_path: str) -> RouteMap:
        """Load a route map from a JSON file.

        Args:
            map_path: Path to the route map JSON file

        Returns:
            RouteMap with parsed transitions, pages, popups, and blocks
        """
        with open(map_path, "r", encoding="utf-8") as f:
            raw = json.load(f)

        transitions: List[RouteEdge] = []
        for t in raw.get("transitions", []):
            action = t.get("action", {})
            loc = action.get("locator", {})
            bounds = loc.get("bounds_hint")
            bounds_hint = tuple(bounds) if isinstance(bounds, list) and len(bounds) >= 4 else None
            locator = Locator(
                resource_id=loc.get("resource_id"),
                text=loc.get("text"),
                content_desc=loc.get("content_desc"),
                class_name=loc.get("class"),
                parent_rid=loc.get("parent_rid"),
                bounds_hint=bounds_hint,
                locator_index=loc.get("locator_index"),
                locator_count=loc.get("locator_count"),
            )
            transitions.append(
                RouteEdge(
                    from_page=t.get("from", ""),
                    to_page=t.get("to", ""),
                    locator=locator,
                    description=t.get("description", ""),
                    legacy_from=t.get("legacy_from"),
                    legacy_to=t.get("legacy_to"),
                )
            )

        return RouteMap(
            package=raw.get("package", ""),
            pages=raw.get("pages", {}),
            transitions=transitions,
            popups=raw.get("popups", []),
            blocks=raw.get("blocks", []),
            metadata=raw.get("metadata", {}),
        )

    def _resolve_target_page(self, route_map: RouteMap, target_page: str) -> str:
        """Resolve a target page reference to a valid page ID.

        Handles various input formats including direct page IDs, legacy IDs,
        aliases, and home-like references.

        Args:
            route_map: Navigation map for the app
            target_page: Target page reference to resolve

        Returns:
            Valid page ID from the route map
        """
        requested = (target_page or "").strip()
        requested_norm = requested.lower()

        # Home-like target should always resolve to actual home page id in map.
        if requested_norm in {"", "home", "index", "main", "homepage", "start", "首页"}:
            return _infer_home_page(route_map.pages, route_map.transitions)

        if target_page in route_map.pages:
            return target_page

        # try alias/legacy mapping
        for page_id, page in route_map.pages.items():
            if page.get("legacy_page_id") == target_page:
                return page_id
            aliases = page.get("target_aliases", [])
            if target_page in aliases:
                return page_id

        id_map = route_map.metadata.get("page_id_map", {})
        mapped = id_map.get(target_page)
        if mapped and mapped in route_map.pages:
            return mapped

        # Fallback: if user/planner requested a home-like id that doesn't exist,
        # route to inferred home instead of failing path lookup.
        if requested_norm.startswith("home"):
            return _infer_home_page(route_map.pages, route_map.transitions)

        # final fallback: keep original
        return target_page

    # ----------------------------
    # Routing
    # ----------------------------
    def _bfs_path(
        self,
        route_map: RouteMap,
        target_page: str,
        start_page: Optional[str] = None,
    ) -> Optional[List[RouteEdge]]:
        """Find shortest path from start page to target page using BFS.

        Args:
            route_map: Navigation map for the app
            target_page: Destination page ID
            start_page: Optional starting page ID. If None, infers home page.

        Returns:
            List of RouteEdges forming the shortest path, or None if no path exists
        """
        start = start_page or _infer_home_page(route_map.pages, route_map.transitions)
        if start == target_page:
            return []

        graph: Dict[str, List[RouteEdge]] = {}
        for e in route_map.transitions:
            graph.setdefault(e.from_page, []).append(e)

        queue: List[Tuple[str, List[RouteEdge]]] = [(start, [])]
        visited: set = {start}
        while queue:
            current, path = queue.pop(0)
            for edge in graph.get(current, []):
                if edge.to_page == target_page:
                    return path + [edge]
                if edge.to_page not in visited:
                    visited.add(edge.to_page)
                    queue.append((edge.to_page, path + [edge]))

        # target_page has no incoming edges — it is a root/launch page.
        # Return an empty path so the caller just launches the app.
        indegree: Dict[str, int] = {pid: 0 for pid in route_map.pages}
        for e in route_map.transitions:
            if e.to_page in indegree:
                indegree[e.to_page] += 1
        if indegree.get(target_page, 0) == 0:
            return []

        # Diagnose why no path was found.
        incoming = [e.from_page for e in route_map.transitions if e.to_page == target_page]
        reachable_from_start = set()
        q = [start]
        while q:
            cur = q.pop()
            if cur in reachable_from_start:
                continue
            reachable_from_start.add(cur)
            for e in graph.get(cur, []):
                if e.to_page not in reachable_from_start:
                    q.append(e.to_page)
        self._log(
            "route", "bfs_no_path",
            start=start,
            target=target_page,
            target_in_pages=target_page in route_map.pages,
            incoming_from=incoming,
            incoming_reachable=[p for p in incoming if p in reachable_from_start],
        )
        return None

    def _execute_route(self, route_map: RouteMap, path: List[RouteEdge]) -> Tuple[bool, List[str]]:
        """Execute a route by tapping locators along the path.

        Launches the app and iterates through each edge in the path,
        tapping the locator to navigate to the next page.

        Args:
            route_map: Navigation map for the app
            path: List of RouteEdges to execute

        Returns:
            Tuple of (success, route_trace) where success is True if all
            steps completed, and route_trace is a list of step descriptions
        """
        self.client.launch_app(route_map.package, clear_task=True)
        time.sleep(1.5)

        route_trace: List[str] = []
        step = 0
        restarts = 0

        while step < len(path):
            edge = path[step]
            route_trace.append(edge.description or f"{edge.from_page}->{edge.to_page}")
            self._log(
                "route",
                "route_step",
                result="start",
                step_index=step,
                from_page=edge.from_page,
                to_page=edge.to_page,
                trigger_node=edge.description,
            )

            if self.config.route_recovery_enabled:
                # known interrupt pre-scan
                self._scan_known_interrupts(route_map)

            exists = self._node_exists(edge.locator)
            if not exists:
                self._log("route", "node_missing", result="fail", step_index=step, trigger_node=edge.description)
                if not self.config.route_recovery_enabled:
                    self._log("route", "route_abort", result="fail", reason="node_missing_no_recovery")
                    return False, route_trace

                handled = self._handle_route_deviation(route_map, edge)
                if handled == "resume":
                    continue

                restarts += 1
                if restarts > self.config.max_route_restarts:
                    self._log("route", "route_abort", result="fail", reason="too_many_restarts")
                    return False, route_trace

                self.client.launch_app(route_map.package, clear_task=True)
                time.sleep(1.5)
                self._log("route", "app_restart", result="ok", restart_count=restarts)
                step = 0
                continue

            if not self._tap_locator(edge.locator):
                self._log("route", "tap_failed", result="fail", step_index=step, trigger_node=edge.description)
                if not self.config.route_recovery_enabled:
                    self._log("route", "route_abort", result="fail", reason="tap_failed_no_recovery")
                    return False, route_trace

                restarts += 1
                if restarts > self.config.max_route_restarts:
                    self._log("route", "route_abort", result="fail", reason="tap_failed_too_many_restarts")
                    return False, route_trace
                self.client.launch_app(route_map.package, clear_task=True)
                time.sleep(1.5)
                self._log("route", "app_restart", result="ok", restart_count=restarts)
                step = 0
                continue

            self._log("route", "route_step", result="ok", step_index=step, trigger_node=edge.description)
            step += 1

        return True, route_trace

    # ----------------------------
    # Deviation handling
    # ----------------------------
    def _handle_route_deviation(self, route_map: RouteMap, edge: RouteEdge) -> str:
        """Handle route deviation when a node is missing or tap fails.

        Attempts recovery through:
        1. Scanning for known interrupts (popups, blocks)
        2. VLM-based popup detection and dismissal
        3. Returns "resume" if recovery succeeded, "restart" if app restart needed

        Args:
            route_map: Navigation map for the app
            edge: The route edge that failed

        Returns:
            "resume" if routing can continue, "restart" if app needs restart
        """
        # 1) known interrupt scan
        if self._scan_known_interrupts(route_map):
            self._log("route", "route_resume", result="ok", reason="known_interrupt_cleared")
            return "resume"

        if not self.config.use_vlm_takeover:
            return "restart"

        # 2) VLM temporary takeover
        self._log("vlm", "vlm_takeover", result="start", reason="route_node_missing")
        screenshot = self._screenshot()
        if not screenshot:
            self._log("vlm", "vlm_takeover", result="fail", reason="screenshot_failed")
            return "restart"

        kind, payload = self._vlm_classify_interrupt(screenshot)
        if kind == "popup":
            x, y = payload.get("x"), payload.get("y")
            if isinstance(x, int) and isinstance(y, int):
                self.client.tap(x, y)
                time.sleep(0.6)
                check_shot = self._screenshot()
                if check_shot:
                    kind2, _ = self._vlm_classify_interrupt(check_shot)
                    if kind2 != "popup":
                        self._log("vlm", "popup_closed", result="ok", x=x, y=y)
                        return "resume"
            self._log("vlm", "popup_closed", result="fail")
            return "restart"

        if kind == "normal":
            self._log("vlm", "vlm_takeover", result="done", decision="restart_route")
            return "restart"

        self._log("vlm", "vlm_takeover", result="fail", reason="unknown")
        return "restart"

    def _vlm_classify_interrupt(self, screenshot: bytes) -> Tuple[str, Dict[str, Any]]:
        """Classify the current screen state using VLM.

        Determines if the screen shows a popup/ad that should be dismissed,
        a normal app page, or an unknown state.

        Args:
            screenshot: Screenshot image bytes

        Returns:
            Tuple of (kind, payload) where:
                - kind is "popup", "normal", or "unknown"
                - For "popup": payload contains {"x": int, "y": int, "reason": str}
                - For "normal": payload contains {"reason": str}
                - For "unknown": payload is empty dict
        """
        """
        Returns:
            ("popup", {"x": int, "y": int, "reason": str})
            ("normal", {"reason": str})
            ("unknown", {})
        """
        prompt = (
            "Analyze this Android screenshot. Output exactly one line:\n"
            "1) POPUP|x|y|reason  (if popup/ad overlay dominates and should be closed)\n"
            "2) NORMAL|reason     (if this is a normal app page)\n"
            "Do not output anything else."
        )
        try:
            text = self._vlm._call_api(screenshot, prompt)
        except Exception:
            return "unknown", {}

        line = (text or "").strip().splitlines()[0] if text else ""
        if line.startswith("POPUP|"):
            parts = line.split("|")
            if len(parts) >= 4:
                try:
                    return "popup", {"x": int(parts[1]), "y": int(parts[2]), "reason": parts[3]}
                except Exception:
                    return "unknown", {}
        if line.startswith("NORMAL|"):
            parts = line.split("|", 1)
            reason = parts[1] if len(parts) > 1 else ""
            return "normal", {"reason": reason}
        return "unknown", {}

    # ----------------------------
    # Known interrupt scan
    # ----------------------------
    def _scan_known_interrupts(self, route_map: RouteMap) -> bool:
        """Scan for and handle known interrupts from the route map.

        Checks for:
        1. Known popups - attempts to close them using defined close locators
        2. Known blocks - checks if current screen matches a blocked state

        Args:
            route_map: Navigation map with popup and block definitions

        Returns:
            True if a popup was closed and routing should resume,
            False if no interrupt was handled or a block was detected
        """
        xml_nodes = self._dump_actions()
        if not xml_nodes:
            return False

        # popup handlers
        for popup in route_map.popups:
            locator = popup.get("close_locator", {})
            loc = Locator(
                resource_id=locator.get("resource_id"),
                text=locator.get("text"),
                content_desc=locator.get("content_desc"),
                class_name=locator.get("class"),
                parent_rid=locator.get("parent_rid"),
                bounds_hint=tuple(locator.get("bounds_hint")) if locator.get("bounds_hint") else None,
                locator_index=locator.get("locator_index"),
                locator_count=locator.get("locator_count"),
            )
            if self._node_exists(loc):
                if self._tap_locator(loc):
                    self._log("route", "known_popup_closed", result="ok", popup=popup.get("description", ""))
                    time.sleep(0.3)
                    return True

        # block recognition by identifiers
        for block in route_map.blocks:
            identifiers = block.get("identifiers", [])
            if not identifiers:
                continue
            hit = 0
            ids = set()
            for n in xml_nodes:
                rid = (n.get("resource_id") or "").split("/")[-1]
                if rid:
                    ids.add(rid)
            for i in identifiers:
                if i in ids:
                    hit += 1
            if identifiers and hit >= max(1, len(identifiers) // 2):
                self._log("route", "known_block_detected", result="ok", block=block.get("type", ""))
                return False

        return False

    # ----------------------------
    # Node resolve/tap
    # ----------------------------
    def _node_exists(self, locator: Locator) -> bool:
        """Check if a locator exists on the current screen with retries.

        Args:
            locator: Locator to search for

        Returns:
            True if the locator is found, False otherwise
        """
        for attempt in range(self.config.node_exists_retries):
            bounds = self._resolve_locator_bounds(locator)
            if bounds:
                self._log("route", "node_exists", result="ok", attempt=attempt + 1)
                return True
            time.sleep(self.config.node_exists_interval_sec)
        self._log("route", "node_exists", result="fail", retries=self.config.node_exists_retries)
        return False

    def _tap_locator(self, locator: Locator) -> bool:
        """Tap a locator's center point on the screen.

        Args:
            locator: Locator to tap

        Returns:
            True if tap was executed, False if locator could not be resolved
        """
        bounds = self._resolve_locator_bounds(locator)
        if bounds:
            x, y = _bounds_center(bounds)
            self.client.tap(x, y)
            time.sleep(0.3)
            return True

        # 不允许坐标兜底，确保跨机型可迁移性
        return False

    def _resolve_locator_bounds(self, locator: Locator) -> Optional[Tuple[int, int, int, int]]:
        """Resolve a locator to screen bounds using multiple strategies.

        Resolution strategy (in order):
        1. XML-first matching against dump_actions() output
        2. Compound find_node with strict conditions
        3. Compound find_node with relaxed conditions (no text)
        4. Single-field find_node queries

        Args:
            locator: Locator to resolve

        Returns:
            Screen bounds as (left, top, right, bottom) tuple, or None if not found
        """
        # strict compound
        strict = _compound_conditions(locator, include_text=True)
        relaxed = _compound_conditions(locator, include_text=False)

        for conds in (strict, relaxed):
            if len(conds) < 2:
                continue
            try:
                status, candidates = self.client.find_node_compound(conds, return_mode=1, multi_match=True)
                if status == 1:
                    picked = _pick_best_bounds(locator, candidates)
                    if picked:
                        return picked
            except Exception:
                pass

        # fallback single query
        for match_type, query in _single_queries(locator):
            try:
                status, candidates = self.client.find_node(
                    query,
                    match_type=match_type,
                    return_mode=1,
                    multi_match=True,
                    timeout_ms=3000,
                )
                if status == 1:
                    picked = _pick_best_bounds(locator, candidates)
                    if picked:
                        return picked
            except Exception:
                continue

        return None

    # ----------------------------
    # Low-level data
    # ----------------------------
    def _dump_actions(self) -> List[Dict[str, Any]]:
        """Dump the current UI hierarchy as action nodes.

        Returns:
            List of node dicts from dump_actions(), or empty list on error
        """
        try:
            return self.client.dump_actions().get("nodes", [])
        except Exception:
            return []

    def _screenshot(self) -> Optional[bytes]:
        """Capture a screenshot from the device.

        Returns:
            Screenshot image bytes, or None if capture failed
        """
        try:
            return self.client.request_screenshot()
        except Exception:
            return None

    # ----------------------------
    # Logging
    # ----------------------------
    def _log(self, stage: str, event: str, **kwargs):
        """Log an event with timestamp and task context.

        Args:
            stage: Stage identifier (e.g., "route", "vlm")
            event: Event name within the stage
            **kwargs: Additional event-specific data to log
        """
        payload = {
            "ts": datetime.now(timezone.utc).isoformat(),
            "task_id": self._task_id,
            "stage": stage,
            "event": event,
            **kwargs,
        }
        if self._log_callback:
            self._log_callback(payload)
        else:
            print(json.dumps(payload, ensure_ascii=False))


def _infer_home_page(pages: Dict[str, Dict[str, Any]], transitions: List[RouteEdge]) -> str:
    """Infer the home page ID from the route map.

    Inference strategy:
    1. Look for page with legacy_page_id == "home"
    2. Look for page_id starting with "home__"
    3. Find pages with zero in-degree (no incoming edges)
    4. Fallback to first page in pages dict

    Args:
        pages: Dict mapping page IDs to page metadata
        transitions: List of route edges for graph analysis

    Returns:
        Inferred home page ID
    """
    for page_id, page in pages.items():
        if page.get("legacy_page_id") == "home":
            return page_id

    # Pages without __n_<hash> suffix are original root pages captured at
    # app launch, not discovered by navigation. Prefer ones named "home".
    no_hash = [pid for pid in pages if "__n_" not in pid]
    if no_hash:
        home_like = [p for p in no_hash if "home" in p.lower()]
        return home_like[0] if home_like else no_hash[0]

    indegree: Dict[str, int] = {k: 0 for k in pages.keys()}
    for e in transitions:
        if e.to_page in indegree:
            indegree[e.to_page] += 1
    roots = [k for k, v in indegree.items() if v == 0]
    if roots:
        # Among roots, prefer the one with the most outgoing edges —
        # the home page is typically the most navigation-rich root.
        out_degree: Dict[str, int] = {k: 0 for k in pages.keys()}
        for e in transitions:
            if e.from_page in out_degree:
                out_degree[e.from_page] += 1
        return max(roots, key=lambda k: out_degree.get(k, 0))
    return next(iter(pages.keys()))


def _compound_conditions(locator: Locator, include_text: bool) -> List[Tuple[int, int, str]]:
    """Build compound find_node conditions from a locator.

    Args:
        locator: Locator to convert to conditions
        include_text: Whether to include text/content_desc in conditions

    Returns:
        List of (field, operator, value) tuples for find_node_compound.
        Field codes: 0=TEXT, 1=RESOURCE_ID, 2=CONTENT_DESC, 3=CLASS_NAME, 4=PARENT_RESOURCE_ID
    """
    conds: List[Tuple[int, int, str]] = []
    # field: 0=TEXT, 1=RESOURCE_ID, 2=CONTENT_DESC, 3=CLASS_NAME, 4=PARENT_RESOURCE_ID
    if locator.resource_id:
        conds.append((1, 0, locator.resource_id))
    if include_text and locator.text:
        conds.append((0, 0, locator.text))
    if include_text and locator.content_desc:
        conds.append((2, 0, locator.content_desc))
    if locator.class_name:
        conds.append((3, 3, locator.class_name.split(".")[-1]))  # ENDS_WITH
    if locator.parent_rid:
        conds.append((4, 0, locator.parent_rid))
    return conds


def _single_queries(locator: Locator) -> List[Tuple[int, str]]:
    """Build single-field find_node queries from a locator.

    Args:
        locator: Locator to convert to queries

    Returns:
        List of (match_type, query) tuples. Match types:
        3=MATCH_RESOURCE_ID, 0=MATCH_EXACT_TEXT, 5=MATCH_DESCRIPTION
    """
    # 3=MATCH_RESOURCE_ID, 0=MATCH_EXACT_TEXT, 5=MATCH_DESCRIPTION
    out: List[Tuple[int, str]] = []
    if locator.resource_id:
        out.append((3, locator.resource_id))
    if locator.text:
        out.append((0, locator.text))
    if locator.content_desc:
        out.append((5, locator.content_desc))
    return out


def _normalize_bounds(raw_candidates: Any) -> List[Tuple[int, int, int, int]]:
    """Normalize raw candidate bounds to valid tuples.

    Args:
        raw_candidates: Raw bounds data from find_node results

    Returns:
        List of valid (left, top, right, bottom) tuples with right > left and bottom > top
    """
    out: List[Tuple[int, int, int, int]] = []
    for item in raw_candidates or []:
        if not isinstance(item, (list, tuple)) or len(item) < 4:
            continue
        try:
            b = (int(item[0]), int(item[1]), int(item[2]), int(item[3]))
        except Exception:
            continue
        if b[2] > b[0] and b[3] > b[1]:
            out.append(b)
    return out


def _bounds_center(bounds: Tuple[int, int, int, int]) -> Tuple[int, int]:
    """Calculate the center point of a bounds rectangle.

    Args:
        bounds: (left, top, right, bottom) tuple

    Returns:
        (center_x, center_y) tuple
    """
    return ((bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2)


def _bounds_area(bounds: Tuple[int, int, int, int]) -> int:
    """Calculate the area of a bounds rectangle.

    Args:
        bounds: (left, top, right, bottom) tuple

    Returns:
        Area in square pixels
    """
    return max(0, bounds[2] - bounds[0]) * max(0, bounds[3] - bounds[1])



def _pick_best_bounds(
    locator: "Locator",
    raw_candidates: Any,
) -> Optional[Tuple[int, int, int, int]]:
    """Select the best matching bounds from candidates.

    Uses the same strategy as map_builder._pick_best_bounds:
    1. If locator_index + locator_count set and count <= 3: sort by (top, left)
       and pick the nth result deterministically.
    2. Fallback: pick smallest area.

    Args:
        locator: Locator whose index/hint fields guide selection
        raw_candidates: Raw bounds candidates from find_node

    Returns:
        Best matching bounds tuple, or None if no candidates
    """
    candidates = _normalize_bounds(raw_candidates)
    if not candidates:
        return None

    # Index-based selection (matches map_builder logic exactly)
    if (
        locator.locator_index is not None
        and locator.locator_count is not None
        and int(locator.locator_count) <= 3
        and len(candidates) <= 3
    ):
        ordered = sorted(candidates, key=lambda b: (b[1], b[0], b[3], b[2]))
        idx = int(locator.locator_index)
        if 0 <= idx < len(ordered):
            return ordered[idx]

    # Fallback: smallest area
    return min(candidates, key=_bounds_area)


def _node_bounds(node: Dict[str, Any]) -> Optional[Tuple[int, int, int, int]]:
    """Extract valid bounds from a node dict.

    Args:
        node: Node dict from dump_actions containing "bounds" key

    Returns:
        (left, top, right, bottom) tuple, or None if invalid
    """
    raw = node.get("bounds")
    if not isinstance(raw, (list, tuple)) or len(raw) < 4:
        return None
    try:
        b = (int(raw[0]), int(raw[1]), int(raw[2]), int(raw[3]))
    except Exception:
        return None
    if b[2] <= b[0] or b[3] <= b[1]:
        return None
    return b


def _extract_json_object(text: str) -> Dict[str, Any]:
    """Extract a JSON object from text, handling various formats.

    Args:
        text: Text potentially containing JSON

    Returns:
        Parsed dict, or empty dict if parsing fails
    """
    text = (text or "").strip()
    if not text:
        return {}
    try:
        obj = json.loads(text)
        return obj if isinstance(obj, dict) else {}
    except Exception:
        pass

    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end == -1 or end <= start:
        return {}
    try:
        obj = json.loads(text[start:end + 1])
        return obj if isinstance(obj, dict) else {}
    except Exception:
        return {}
