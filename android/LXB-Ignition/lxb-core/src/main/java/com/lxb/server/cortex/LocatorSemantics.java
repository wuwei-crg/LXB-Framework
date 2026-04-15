package com.lxb.server.cortex;

import com.lxb.server.cortex.dump.DumpActionsParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Shared locator semantics aligned with LXB-MapBuilder node_explorer.py.
 */
public final class LocatorSemantics {

    private static final Comparator<NodeRecord> STABLE_ORDER =
            new Comparator<NodeRecord>() {
                @Override
                public int compare(NodeRecord a, NodeRecord b) {
                    if (a == b) return 0;
                    Bounds ab = a != null ? a.bounds : null;
                    Bounds bb = b != null ? b.bounds : null;
                    if (ab == null && bb == null) return 0;
                    if (ab == null) return 1;
                    if (bb == null) return -1;
                    if (ab.top != bb.top) return ab.top - bb.top;
                    if (ab.left != bb.left) return ab.left - bb.left;
                    if (ab.bottom != bb.bottom) return ab.bottom - bb.bottom;
                    return ab.right - bb.right;
                }
            };

    private LocatorSemantics() {}

    public static final class NodeRecord {
        public final DumpActionsParser.ActionNode actionNode;
        public final Bounds bounds;
        public final String className;
        public final String text;
        public final String resourceId;
        public final String contentDesc;
        public final String parentRid;

        NodeRecord(
                DumpActionsParser.ActionNode actionNode,
                Bounds bounds,
                String className,
                String text,
                String resourceId,
                String contentDesc,
                String parentRid
        ) {
            this.actionNode = actionNode;
            this.bounds = bounds;
            this.className = className != null ? className : "";
            this.text = text != null ? text : "";
            this.resourceId = resourceId != null ? resourceId : "";
            this.contentDesc = contentDesc != null ? contentDesc : "";
            this.parentRid = parentRid != null ? parentRid : "";
        }
    }

    public static List<NodeRecord> fromActionNodes(List<DumpActionsParser.ActionNode> actions) {
        if (actions == null || actions.isEmpty()) {
            return Collections.emptyList();
        }
        List<NodeRecord> out = new ArrayList<>(actions.size());
        for (DumpActionsParser.ActionNode action : actions) {
            if (action == null || action.bounds == null) {
                continue;
            }
            out.add(new NodeRecord(
                    action,
                    action.bounds,
                    Util.normalizeClass(action.className),
                    Util.normalizeText(action.text),
                    Util.normalizeResourceId(action.resourceId),
                    Util.normalizeText(action.contentDesc),
                    Util.normalizeResourceId(inferParentRid(action, actions))
            ));
        }
        return out;
    }

    public static NodeRecord findSmallestContaining(
            int x,
            int y,
            List<NodeRecord> nodes,
            int margin
    ) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }
        NodeRecord best = null;
        long bestArea = Long.MAX_VALUE;
        for (NodeRecord node : nodes) {
            if (node == null || node.bounds == null) {
                continue;
            }
            Bounds b = node.bounds;
            int left = b.left - margin;
            int top = b.top - margin;
            int right = b.right + margin;
            int bottom = b.bottom + margin;
            if (x < left || x > right || y < top || y > bottom) {
                continue;
            }
            long area = area(b);
            if (area < bestArea) {
                bestArea = area;
                best = node;
            }
        }
        return best;
    }

    public static List<NodeRecord> filterSelfCandidates(
            Locator locator,
            List<NodeRecord> nodes,
            boolean includeText
    ) {
        if (locator == null || nodes == null || nodes.isEmpty()) {
            return Collections.emptyList();
        }
        String rid = Util.normalizeResourceId(locator.resourceId);
        String txt = Util.normalizeText(locator.text);
        String desc = Util.normalizeText(locator.contentDesc);
        String cls = Util.normalizeClass(locator.className);
        boolean hasIdentity = !rid.isEmpty()
                || !desc.isEmpty()
                || !cls.isEmpty()
                || (includeText && !txt.isEmpty());
        if (!hasIdentity) {
            return Collections.emptyList();
        }
        List<NodeRecord> out = new ArrayList<>();
        for (NodeRecord node : nodes) {
            if (node == null || node.bounds == null) {
                continue;
            }
            if (!rid.isEmpty() && !rid.equals(node.resourceId)) {
                continue;
            }
            if (includeText && !txt.isEmpty() && !txt.equals(node.text)) {
                continue;
            }
            if (!desc.isEmpty() && !desc.equals(node.contentDesc)) {
                continue;
            }
            if (!cls.isEmpty() && !cls.equals(node.className)) {
                continue;
            }
            out.add(node);
        }
        return out;
    }

    public static List<NodeRecord> filterByParentRid(
            List<NodeRecord> candidates,
            String parentRid
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        String target = Util.normalizeResourceId(parentRid);
        if (target.isEmpty()) {
            return new ArrayList<>(candidates);
        }
        List<NodeRecord> out = new ArrayList<>();
        for (NodeRecord node : candidates) {
            if (node != null && target.equals(node.parentRid)) {
                out.add(node);
            }
        }
        return out;
    }

    public static List<NodeRecord> findPeerCandidates(
            NodeRecord target,
            List<NodeRecord> nodes,
            boolean includeParent,
            boolean includeText
    ) {
        if (target == null || nodes == null || nodes.isEmpty()) {
            return Collections.emptyList();
        }
        String signature = identityTuple(target, includeParent, includeText);
        List<NodeRecord> out = new ArrayList<>();
        for (NodeRecord node : nodes) {
            if (node == null || node.bounds == null) {
                continue;
            }
            if (signature.equals(identityTuple(node, includeParent, includeText))) {
                out.add(node);
            }
        }
        return out;
    }

    public static int[] findPeerIndex(NodeRecord target, List<NodeRecord> peers) {
        if (target == null || peers == null) {
            return null;
        }
        int count = peers.size();
        if (count < 2 || count > 3) {
            return null;
        }
        List<NodeRecord> ordered = stableOrder(peers);
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i) == target) {
                return new int[]{i, count};
            }
        }
        for (int i = 0; i < ordered.size(); i++) {
            NodeRecord peer = ordered.get(i);
            if (sameBounds(peer, target)) {
                return new int[]{i, count};
            }
        }
        return null;
    }

    public static List<NodeRecord> stableOrder(List<NodeRecord> peers) {
        if (peers == null || peers.isEmpty()) {
            return Collections.emptyList();
        }
        List<NodeRecord> ordered = new ArrayList<>(peers);
        Collections.sort(ordered, STABLE_ORDER);
        return ordered;
    }

    public static String inferParentRid(
            DumpActionsParser.ActionNode child,
            List<DumpActionsParser.ActionNode> all
    ) {
        if (child == null || child.bounds == null || all == null || all.isEmpty()) {
            return "";
        }
        Bounds cb = child.bounds;
        String bestRid = "";
        long bestArea = Long.MAX_VALUE;
        for (DumpActionsParser.ActionNode node : all) {
            if (node == null || node == child || node.bounds == null) {
                continue;
            }
            Bounds b = node.bounds;
            if (contains(b, cb)
                    && (b.left < cb.left || b.top < cb.top || b.right > cb.right || b.bottom > cb.bottom)) {
                String rid = Util.normalizeResourceId(node.resourceId);
                if (rid.isEmpty()) {
                    continue;
                }
                long area = area(b);
                if (area < bestArea) {
                    bestArea = area;
                    bestRid = rid;
                }
            }
        }
        return bestRid;
    }

    public static String inferClickableParentRid(
            NodeRecord child,
            List<NodeRecord> all
    ) {
        if (child == null || child.bounds == null || all == null || all.isEmpty()) {
            return "";
        }
        String bestRid = "";
        long bestArea = Long.MAX_VALUE;
        for (NodeRecord node : all) {
            if (node == null || node == child || node.bounds == null || node.actionNode == null) {
                continue;
            }
            if ((node.actionNode.type & 0x01) == 0) {
                continue;
            }
            if (!contains(node.bounds, child.bounds)
                    || (node.bounds.left == child.bounds.left
                    && node.bounds.top == child.bounds.top
                    && node.bounds.right == child.bounds.right
                    && node.bounds.bottom == child.bounds.bottom)) {
                continue;
            }
            String rid = Util.normalizeResourceId(node.resourceId);
            if (!Util.isInformativeResourceId(rid)) {
                continue;
            }
            long area = area(node.bounds);
            if (area < bestArea) {
                bestArea = area;
                bestRid = rid;
            }
        }
        return bestRid;
    }

    private static String identityTuple(NodeRecord node, boolean includeParent, boolean includeText) {
        if (node == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(node.resourceId).append('\u0001');
        sb.append(includeText ? node.text : "__text_ignored__").append('\u0001');
        sb.append(node.contentDesc).append('\u0001');
        sb.append(node.className);
        if (includeParent) {
            sb.append('\u0001').append(node.parentRid);
        }
        return sb.toString();
    }

    private static boolean contains(Bounds outer, Bounds inner) {
        return outer != null
                && inner != null
                && outer.left <= inner.left
                && outer.top <= inner.top
                && outer.right >= inner.right
                && outer.bottom >= inner.bottom;
    }

    private static boolean sameBounds(NodeRecord a, NodeRecord b) {
        Bounds ab = a != null ? a.bounds : null;
        Bounds bb = b != null ? b.bounds : null;
        return ab != null
                && bb != null
                && ab.left == bb.left
                && ab.top == bb.top
                && ab.right == bb.right
                && ab.bottom == bb.bottom;
    }

    private static long area(Bounds b) {
        if (b == null) {
            return Long.MAX_VALUE;
        }
        return (long) Math.max(0, b.right - b.left) * Math.max(0, b.bottom - b.top);
    }
}
