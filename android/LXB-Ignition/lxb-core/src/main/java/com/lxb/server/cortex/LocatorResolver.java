package com.lxb.server.cortex;

import com.lxb.server.cortex.dump.DumpActionsParser;
import com.lxb.server.perception.PerceptionEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Staged locator resolution:
 * 1) strict match by self features without text (resource_id/content_desc/class)
 * 2) if locator has text, use it as an optional tightening stage
 * 3) if still multiple and locator has parent_rid, use it only as a tightening condition
 * 4) if still multiple and locator has bounds_hint, pick nearest by center distance
 * 5) if still multiple and locator has index/count, pick by stable ordering (future)
 *
 * Fail-fast: if no candidates after stage 1, throw.
 */
public class LocatorResolver {

    private static final String TAG = "[LXB][Locator]";

    private final PerceptionEngine perceptionEngine;
    private final TraceLogger trace;

    public LocatorResolver(PerceptionEngine perceptionEngine, TraceLogger trace) {
        this.perceptionEngine = perceptionEngine;
        this.trace = trace;
    }

    public ResolvedNode resolve(Locator locator) throws Exception {
        Map<String, Object> begin = new LinkedHashMap<>();
        begin.put("rid", locator.resourceId);
        begin.put("text", locator.text);
        begin.put("desc", locator.contentDesc);
        begin.put("class", locator.className);
        begin.put("parent_rid", locator.parentRid);
        trace.event("resolve_begin", begin);

        // 1) Dump action nodes (rich text pool)
        byte[] da = perceptionEngine.handleDumpActions(new byte[0]);
        List<DumpActionsParser.ActionNode> actions = DumpActionsParser.parse(da);

        // 2) Enrich action nodes with parent_rid inferred purely from dump_actions via
        //    geometric containment between actionable nodes (aligned with Python side).
        List<Candidate> candidates = enrichFromActions(actions);

        // Stage 1: strict self match WITHOUT text (AND all non-empty fields except text)
        List<Candidate> stage1 = new ArrayList<>();
        for (Candidate c : candidates) {
            if (matchesSelfNoText(locator, c)) stage1.add(c);
        }
        if (stage1.isEmpty()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("stage", "self_no_text");
            f.put("reason", "no_candidates");
            trace.event("resolve_fail", f);
            throw new IllegalStateException("locator match: no candidates (self_no_text features)");
        }
        List<Candidate> stage2 = stage1;
        String text = Util.normalizeText(locator.text);
        if (!text.isEmpty() && stage1.size() > 1) {
            List<Candidate> textFiltered = new ArrayList<>();
            for (Candidate c : stage1) {
                if (text.equals(c.text)) textFiltered.add(c);
            }
            if (!textFiltered.isEmpty()) {
                stage2 = textFiltered;
            }
            Map<String, Object> stageText = new LinkedHashMap<>();
            stageText.put("stage", "self_text");
            stageText.put("before", stage1.size());
            stageText.put("after", stage2.size());
            stageText.put("hit", !textFiltered.isEmpty());
            trace.event("resolve_stage", stageText);
        }
        if (stage2.size() == 1) {
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("stage", "self_text");
            ok.put("candidates", 1);
            trace.event("resolve_ok", ok);
            return new ResolvedNode(stage2.get(0).bounds, stage1.size(), "self_text");
        }

        // Stage 3: parent_rid tightening (optional)
        List<Candidate> stage3 = stage2;
        String parentRid = Util.normalizeResourceId(locator.parentRid);
        if (!parentRid.isEmpty()) {
            List<Candidate> filtered = new ArrayList<>();
            for (Candidate c : stage2) {
                if (parentRid.equals(c.parentRid)) filtered.add(c);
            }
            if (!filtered.isEmpty()) {
                stage3 = filtered;
            }
            if (stage3.size() == 1) {
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("stage", "parent_rid");
                ok.put("candidates", 1);
                trace.event("resolve_ok", ok);
                return new ResolvedNode(stage3.get(0).bounds, stage1.size(), "parent_rid");
            }
        }

        // Stage 4: bounds_hint tie-break (optional)
        if (locator.boundsHint != null) {
            Candidate best = null;
            long bestDist = Long.MAX_VALUE;
            int hx = locator.boundsHint.centerX();
            int hy = locator.boundsHint.centerY();
            for (Candidate c : stage3) {
                long dx = (long) c.bounds.centerX() - hx;
                long dy = (long) c.bounds.centerY() - hy;
                long d2 = dx * dx + dy * dy;
                if (d2 < bestDist) {
                    bestDist = d2;
                    best = c;
                }
            }
            if (best != null) {
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("stage", "bounds_hint");
                ok.put("candidates", stage3.size());
                ok.put("dist2", bestDist);
                trace.event("resolve_ok", ok);
                return new ResolvedNode(best.bounds, stage1.size(), "bounds_hint");
            }
        }

        // Stage 5: index/count (future). For now, fail to avoid random taps.
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("stage", "ambiguous");
        f.put("candidates", stage3.size());
        trace.event("resolve_fail", f);
        throw new IllegalStateException("locator match: ambiguous candidates=" + stage3.size());
    }

    private static boolean matchesSelfNoText(Locator l, Candidate c) {
        String rid = Util.normalizeResourceId(l.resourceId);
        String cls = Util.normalizeClass(l.className);
        String desc = Util.normalizeText(l.contentDesc);

        if (!rid.isEmpty() && !rid.equals(c.resourceId)) return false;
        if (!cls.isEmpty() && !cls.equals(c.className)) return false;
        if (!desc.isEmpty() && !desc.equals(c.contentDesc)) return false;

        return true;
    }

    /**
     * Enrich DumpActions nodes with parent_rid inferred from other DumpActions nodes,
     * using geometric containment to approximate the visual parent container.
     */
    private static List<Candidate> enrichFromActions(List<DumpActionsParser.ActionNode> actions) {
        List<Candidate> out = new ArrayList<>(actions.size());
        for (DumpActionsParser.ActionNode a : actions) {
            String parentRid = inferParentRidFromActions(a, actions);

            out.add(new Candidate(
                    a.bounds,
                    a.className,
                    a.text,
                    a.resourceId,
                    a.contentDesc,
                    Util.normalizeResourceId(parentRid)
            ));
        }
        return out;
    }

    /**
     * Infer parent container resource_id for a given action node using geometric containment
     * among all action nodes. This mirrors Python _infer_parent_resource_id behavior.
     */
    private static String inferParentRidFromActions(
            DumpActionsParser.ActionNode child,
            List<DumpActionsParser.ActionNode> all
    ) {
        Bounds cb = child.bounds;
        if (cb == null) return "";
        int cl = cb.left;
        int ct = cb.top;
        int cr = cb.right;
        int cbottom = cb.bottom;

        String bestRid = "";
        long bestArea = Long.MAX_VALUE;

        for (DumpActionsParser.ActionNode node : all) {
            if (node == child || node.bounds == null) continue;
            Bounds b = node.bounds;
            int nl = b.left;
            int nt = b.top;
            int nr = b.right;
            int nb = b.bottom;

            // strict containment: parent fully contains child and is strictly larger
            if (nl <= cl && nt <= ct && nr >= cr && nb >= cbottom
                    && (nl < cl || nt < ct || nr > cr || nb > cbottom)) {
                String rid = node.resourceId != null ? node.resourceId : "";
                if (rid.isEmpty()) continue;
                long area = (long) (nr - nl) * (nb - nt);
                if (area > 0 && area < bestArea) {
                    bestArea = area;
                    bestRid = rid;
                }
            }
        }
        return bestRid;
    }

    private static class Candidate {
        final Bounds bounds;
        final String className;
        final String text;
        final String resourceId;
        final String contentDesc;
        final String parentRid;

        Candidate(Bounds bounds, String className, String text, String resourceId, String contentDesc, String parentRid) {
            this.bounds = bounds;
            this.className = className;
            this.text = text;
            this.resourceId = resourceId;
            this.contentDesc = contentDesc;
            this.parentRid = parentRid;
        }
    }

    private static class BoundsKey {
        final int l, t, r, b;

        BoundsKey(Bounds bounds) {
            this.l = bounds.left;
            this.t = bounds.top;
            this.r = bounds.right;
            this.b = bounds.bottom;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof BoundsKey)) return false;
            BoundsKey other = (BoundsKey) o;
            return l == other.l && t == other.t && r == other.r && b == other.b;
        }

        @Override
        public int hashCode() {
            int h = 17;
            h = 31 * h + l;
            h = 31 * h + t;
            h = 31 * h + r;
            h = 31 * h + b;
            return h;
        }
    }
}
