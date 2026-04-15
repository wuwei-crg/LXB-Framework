package com.lxb.server.cortex;

import com.lxb.server.cortex.dump.DumpActionsParser;
import com.lxb.server.perception.PerceptionEngine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MapBuilder-aligned locator resolution:
 * 1) self attributes without text
 * 2) optional text tightening
 * 3) optional parent_rid tightening
 * 4) locator_index/locator_count disambiguation when 2..3 peers
 * 5) bounds_hint only as final tie-break fallback
 */
public class LocatorResolver {

    private final PerceptionEngine perceptionEngine;
    private final TraceLogger trace;

    public LocatorResolver(PerceptionEngine perceptionEngine, TraceLogger trace) {
        this.perceptionEngine = perceptionEngine;
        this.trace = trace;
    }

    public ResolvedNode resolve(Locator locator) throws Exception {
        Map<String, Object> begin = new LinkedHashMap<>();
        begin.put("rid", Util.normalizeResourceId(locator != null ? locator.resourceId : ""));
        begin.put("text", locator != null ? locator.text : "");
        begin.put("desc", locator != null ? locator.contentDesc : "");
        begin.put("class", Util.normalizeClass(locator != null ? locator.className : ""));
        begin.put("parent_rid", Util.normalizeResourceId(locator != null ? locator.parentRid : ""));
        begin.put("locator_index", locator != null ? locator.index : null);
        begin.put("locator_count", locator != null ? locator.count : null);
        trace.event("resolve_begin", begin);

        byte[] da = perceptionEngine.handleDumpActions(new byte[0]);
        List<DumpActionsParser.ActionNode> actions = DumpActionsParser.parse(da);
        List<LocatorSemantics.NodeRecord> candidates = LocatorSemantics.fromActionNodes(actions);

        List<LocatorSemantics.NodeRecord> stage1 = LocatorSemantics.filterSelfCandidates(locator, candidates, false);
        traceStage("self_no_text", stage1.size(), stage1.size(), !stage1.isEmpty());
        if (stage1.isEmpty()) {
            traceFail("self_no_text", "no_candidates");
            throw new IllegalStateException("locator match: no candidates (self_no_text)");
        }
        if (stage1.size() == 1) {
            return ok(stage1.get(0), stage1.size(), "self_no_text");
        }

        List<LocatorSemantics.NodeRecord> stage2 = stage1;
        String text = Util.normalizeText(locator != null ? locator.text : "");
        if (!text.isEmpty()) {
            List<LocatorSemantics.NodeRecord> textHits = LocatorSemantics.filterSelfCandidates(locator, stage1, true);
            boolean hit = !textHits.isEmpty();
            if (hit) {
                stage2 = textHits;
            }
            traceStage("self_text", stage1.size(), stage2.size(), hit);
            if (stage2.size() == 1) {
                return ok(stage2.get(0), stage1.size(), "self_text");
            }
        }

        List<LocatorSemantics.NodeRecord> stage3 = stage2;
        String parentRid = Util.normalizeResourceId(locator != null ? locator.parentRid : "");
        if (!parentRid.isEmpty() && stage2.size() > 1) {
            List<LocatorSemantics.NodeRecord> parentHits = LocatorSemantics.filterByParentRid(stage2, parentRid);
            boolean hit = !parentHits.isEmpty();
            if (hit) {
                stage3 = parentHits;
            }
            traceStage("parent_rid", stage2.size(), stage3.size(), hit);
            if (stage3.size() == 1) {
                return ok(stage3.get(0), stage1.size(), "parent_rid");
            }
        }

        Integer locatorIndex = locator != null ? locator.index : null;
        Integer locatorCount = locator != null ? locator.count : null;
        if (stage3.size() > 1
                && locatorIndex != null
                && locatorCount != null
                && locatorCount >= 2
                && locatorCount <= 3
                && stage3.size() <= 3) {
            List<LocatorSemantics.NodeRecord> ordered = LocatorSemantics.stableOrder(stage3);
            int idx = locatorIndex.intValue();
            if (idx >= 0 && idx < ordered.size()) {
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("stage", "locator_index");
                ok.put("index", idx);
                ok.put("count", locatorCount);
                ok.put("candidates", stage3.size());
                trace.event("resolve_ok", ok);
                return new ResolvedNode(ordered.get(idx).bounds, stage1.size(), "locator_index");
            }
        }

        if (locator != null && locator.boundsHint != null && !stage3.isEmpty()) {
            LocatorSemantics.NodeRecord best = nearestByBoundsHint(stage3, locator.boundsHint);
            if (best != null) {
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("stage", "bounds_hint");
                ok.put("candidates", stage3.size());
                trace.event("resolve_ok", ok);
                return new ResolvedNode(best.bounds, stage1.size(), "bounds_hint");
            }
        }

        traceFail("ambiguous", "candidates=" + stage3.size());
        throw new IllegalStateException("locator match: ambiguous candidates=" + stage3.size());
    }

    private ResolvedNode ok(LocatorSemantics.NodeRecord node, int candidateCount, String stage) {
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("stage", stage);
        ok.put("candidates", 1);
        trace.event("resolve_ok", ok);
        return new ResolvedNode(node.bounds, candidateCount, stage);
    }

    private void traceStage(String stage, int before, int after, boolean hit) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("stage", stage);
        ev.put("before", before);
        ev.put("after", after);
        ev.put("hit", hit);
        trace.event("resolve_stage", ev);
    }

    private void traceFail(String stage, String reason) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("stage", stage);
        ev.put("reason", reason);
        trace.event("resolve_fail", ev);
    }

    private LocatorSemantics.NodeRecord nearestByBoundsHint(
            List<LocatorSemantics.NodeRecord> nodes,
            Bounds hint
    ) {
        LocatorSemantics.NodeRecord best = null;
        long bestDist = Long.MAX_VALUE;
        int hx = hint.centerX();
        int hy = hint.centerY();
        for (LocatorSemantics.NodeRecord node : nodes) {
            if (node == null || node.bounds == null) {
                continue;
            }
            long dx = (long) node.bounds.centerX() - hx;
            long dy = (long) node.bounds.centerY() - hy;
            long d2 = dx * dx + dy * dy;
            if (d2 < bestDist) {
                bestDist = d2;
                best = node;
            }
        }
        return best;
    }
}
