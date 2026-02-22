import json
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
    resource_id: Optional[str] = None
    text: Optional[str] = None
    content_desc: Optional[str] = None
    class_name: Optional[str] = None
    parent_rid: Optional[str] = None
    bounds_hint: Optional[Tuple[int, int, int, int]] = None


@dataclass
class RouteEdge:
    from_page: str
    to_page: str
    locator: Locator
    description: str = ""
    legacy_from: Optional[str] = None
    legacy_to: Optional[str] = None


@dataclass
class RouteMap:
    package: str
    pages: Dict[str, Dict[str, Any]]
    transitions: List[RouteEdge]
    popups: List[Dict[str, Any]] = field(default_factory=list)
    blocks: List[Dict[str, Any]] = field(default_factory=list)
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class RoutePlan:
    package_name: str
    target_page: str


@dataclass
class RouteConfig:
    node_exists_retries: int = 3
    node_exists_interval_sec: float = 0.6
    max_route_restarts: int = 3
    use_vlm_takeover: bool = True
    vlm_takeover_timeout_sec: float = 15.0
    route_recovery_enabled: bool = True
    locator_score_threshold: float = 45.0
    locator_ambiguity_delta: float = 8.0
    hint_distance_limit_px: float = 520.0


class MapTaskPlanner(Protocol):
    def plan(self, user_task: str, route_map: RouteMap) -> RoutePlan:
        ...


class VLMActionEngine(Protocol):
    def execute(self, user_task: str, context: Dict[str, Any]) -> Dict[str, Any]:
        ...


class HeuristicPlanner:
    """
    Minimal planner fallback.
    If no external LLM planner is injected, this planner tries to map task text to page id.
    """

    def plan(self, user_task: str, route_map: RouteMap) -> RoutePlan:
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
    """Always return a fixed package + target page plan."""

    def __init__(self, package_name: str, target_page: str):
        self.package_name = package_name
        self.target_page = target_page

    def plan(self, user_task: str, route_map: RouteMap) -> RoutePlan:
        return RoutePlan(self.package_name or route_map.package, self.target_page)


class MapPromptPlanner:
    """
    Planner adapter: map + task -> LLM JSON -> RoutePlan.
    If parse fails, automatically falls back to HeuristicPlanner.
    """

    def __init__(self, llm_complete: Callable[[str], str]):
        self._llm_complete = llm_complete
        self._fallback = HeuristicPlanner()

    def plan(self, user_task: str, route_map: RouteMap) -> RoutePlan:
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
    """
    Route-Then-Act engine:
    1) Planner resolves package + target_page
    2) Route engine runs BFS node-click chain
    3) On target page, handoff to VLM action engine
    """

    def __init__(
        self,
        client,
        planner: Optional[MapTaskPlanner] = None,
        action_engine: Optional[VLMActionEngine] = None,
        config: Optional[RouteConfig] = None,
        log_callback: Optional[Callable[[Dict[str, Any]], None]] = None,
    ):
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
        map_path: str,
        start_page: Optional[str] = None,
    ) -> Dict[str, Any]:
        self._task_id = str(uuid.uuid4())
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
        start = start_page or _infer_home_page(route_map.pages, route_map.transitions)
        if start == target_page:
            return []

        graph: Dict[str, List[RouteEdge]] = {}
        for e in route_map.transitions:
            graph.setdefault(e.from_page, []).append(e)

        queue: List[Tuple[str, List[RouteEdge]]] = [(start, [])]
        visited = {start}

        while queue:
            current, path = queue.pop(0)
            for edge in graph.get(current, []):
                if edge.to_page == target_page:
                    return path + [edge]
                if edge.to_page not in visited:
                    visited.add(edge.to_page)
                    queue.append((edge.to_page, path + [edge]))
        return None

    def _execute_route(self, route_map: RouteMap, path: List[RouteEdge]) -> Tuple[bool, List[str]]:
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
        for attempt in range(self.config.node_exists_retries):
            bounds = self._resolve_locator_bounds(locator)
            if bounds:
                self._log("route", "node_exists", result="ok", attempt=attempt + 1)
                return True
            time.sleep(self.config.node_exists_interval_sec)
        self._log("route", "node_exists", result="fail", retries=self.config.node_exists_retries)
        return False

    def _tap_locator(self, locator: Locator) -> bool:
        bounds = self._resolve_locator_bounds(locator)
        if bounds:
            x, y = _bounds_center(bounds)
            self.client.tap(x, y)
            time.sleep(0.3)
            return True

        if locator.bounds_hint:
            x, y = _bounds_center(locator.bounds_hint)
            self.client.tap(x, y)
            time.sleep(0.3)
            return True
        return False

    def _resolve_locator_bounds(self, locator: Locator) -> Optional[Tuple[int, int, int, int]]:
        # XML-first resolve to avoid false positives from non-unique find_node matches.
        bounds = self._resolve_locator_from_xml(locator)
        if bounds:
            return bounds

        # strict compound
        strict = _compound_conditions(locator, include_text=True)
        relaxed = _compound_conditions(locator, include_text=False)

        for conds in (strict, relaxed):
            if len(conds) < 2:
                continue
            try:
                status, candidates = self.client.find_node_compound(conds, return_mode=1, multi_match=True)
                if status == 1:
                    picked = _pick_best_bounds(locator.bounds_hint, candidates)
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
                    picked = _pick_best_bounds(locator.bounds_hint, candidates)
                    if picked:
                        return picked
            except Exception:
                continue

        return None

    def _resolve_locator_from_xml(self, locator: Locator) -> Optional[Tuple[int, int, int, int]]:
        nodes = self._dump_actions()
        if not nodes:
            return None

        expected_rid = _tail(locator.resource_id)
        expected_text = (locator.text or "").strip().lower()
        expected_desc = (locator.content_desc or "").strip().lower()
        expected_class = _class_leaf(locator.class_name)
        hint = locator.bounds_hint

        scored: List[Tuple[float, Tuple[int, int, int, int], Dict[str, Any]]] = []
        for node in nodes:
            bounds = _node_bounds(node)
            if not bounds:
                continue

            score = 0.0
            hard_miss = False

            node_rid = _tail(node.get("resource_id"))
            node_text = str(node.get("text") or "").strip().lower()
            node_desc = str(node.get("content_desc") or "").strip().lower()
            node_class = _class_leaf(node.get("class"))

            if expected_rid:
                if node_rid == expected_rid:
                    score += 65.0
                else:
                    hard_miss = True

            if expected_text:
                if node_text == expected_text:
                    score += 38.0
                elif expected_text in node_text or node_text in expected_text:
                    score += 24.0
                else:
                    score -= 12.0

            if expected_desc:
                if node_desc == expected_desc:
                    score += 30.0
                elif expected_desc in node_desc or node_desc in expected_desc:
                    score += 16.0
                else:
                    score -= 8.0

            if expected_class:
                if node_class == expected_class:
                    score += 16.0
                else:
                    score -= 6.0

            if hint:
                iou = _iou(hint, bounds)
                score += iou * 44.0
                hx, hy = _bounds_center(hint)
                bx, by = _bounds_center(bounds)
                dist = ((hx - bx) ** 2 + (hy - by) ** 2) ** 0.5
                score += max(0.0, 30.0 - dist / 18.0)

                # If locator features are weak, reject far-away candidates.
                if not (expected_rid or expected_text or expected_desc) and dist > self.config.hint_distance_limit_px:
                    hard_miss = True

            if hard_miss:
                continue

            scored.append(
                (
                    score,
                    bounds,
                    {
                        "rid": node_rid,
                        "text": node_text[:50],
                        "class": node_class,
                    },
                )
            )

        if not scored:
            return None

        scored.sort(key=lambda x: x[0], reverse=True)
        best_score, best_bounds, best_info = scored[0]

        threshold = self.config.locator_score_threshold
        if expected_rid:
            threshold = min(threshold, 35.0)
        elif expected_text or expected_desc:
            threshold = min(threshold, 40.0)
        elif hint:
            threshold = max(threshold, 50.0)

        if best_score < threshold:
            self._log(
                "route",
                "locator_low_confidence",
                result="fail",
                score=round(best_score, 2),
                threshold=threshold,
            )
            return None

        if len(scored) > 1:
            second_score = scored[1][0]
            if (best_score - second_score) < self.config.locator_ambiguity_delta:
                self._log(
                    "route",
                    "locator_ambiguous",
                    result="fail",
                    best_score=round(best_score, 2),
                    second_score=round(second_score, 2),
                )
                return None

        self._log(
            "route",
            "locator_resolved_xml",
            result="ok",
            score=round(best_score, 2),
            rid=best_info["rid"],
            node_class=best_info["class"],
        )
        return best_bounds

    # ----------------------------
    # Low-level data
    # ----------------------------
    def _dump_actions(self) -> List[Dict[str, Any]]:
        try:
            return self.client.dump_actions().get("nodes", [])
        except Exception:
            return []

    def _screenshot(self) -> Optional[bytes]:
        try:
            return self.client.request_screenshot()
        except Exception:
            return None

    # ----------------------------
    # Logging
    # ----------------------------
    def _log(self, stage: str, event: str, **kwargs):
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
    for page_id, page in pages.items():
        if page.get("legacy_page_id") == "home":
            return page_id
    for page_id in pages:
        if page_id.startswith("home__"):
            return page_id

    indegree: Dict[str, int] = {k: 0 for k in pages.keys()}
    for e in transitions:
        if e.to_page in indegree:
            indegree[e.to_page] += 1
    roots = [k for k, v in indegree.items() if v == 0]
    if roots:
        return roots[0]
    return next(iter(pages.keys()))


def _compound_conditions(locator: Locator, include_text: bool) -> List[Tuple[int, int, str]]:
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
    return ((bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2)


def _bounds_area(bounds: Tuple[int, int, int, int]) -> int:
    return max(0, bounds[2] - bounds[0]) * max(0, bounds[3] - bounds[1])


def _iou(a: Tuple[int, int, int, int], b: Tuple[int, int, int, int]) -> float:
    x1 = max(a[0], b[0])
    y1 = max(a[1], b[1])
    x2 = min(a[2], b[2])
    y2 = min(a[3], b[3])
    if x2 <= x1 or y2 <= y1:
        return 0.0
    inter = (x2 - x1) * (y2 - y1)
    union = _bounds_area(a) + _bounds_area(b) - inter
    if union <= 0:
        return 0.0
    return inter / union


def _pick_best_bounds(
    hint: Optional[Tuple[int, int, int, int]],
    raw_candidates: Any,
) -> Optional[Tuple[int, int, int, int]]:
    candidates = _normalize_bounds(raw_candidates)
    if not candidates:
        return None
    if not hint:
        candidates.sort(key=_bounds_area)
        return candidates[0]

    hx, hy = _bounds_center(hint)
    hint_area = max(1, _bounds_area(hint))
    scored = []
    for c in candidates:
        cx, cy = _bounds_center(c)
        dist = ((hx - cx) ** 2 + (hy - cy) ** 2) ** 0.5
        area_ratio = _bounds_area(c) / hint_area
        score = (_iou(hint, c) * 100.0) + max(0.0, 60.0 - dist / 10.0) - min(40.0, abs(area_ratio - 1.0) * 20.0)
        scored.append((score, c))
    scored.sort(key=lambda x: x[0], reverse=True)
    return scored[0][1]


def _node_bounds(node: Dict[str, Any]) -> Optional[Tuple[int, int, int, int]]:
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


def _tail(value: Optional[str]) -> str:
    if not value:
        return ""
    return str(value).split("/")[-1].strip().lower()


def _class_leaf(value: Optional[str]) -> str:
    if not value:
        return ""
    return str(value).split(".")[-1].strip().lower()


def _extract_json_object(text: str) -> Dict[str, Any]:
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
