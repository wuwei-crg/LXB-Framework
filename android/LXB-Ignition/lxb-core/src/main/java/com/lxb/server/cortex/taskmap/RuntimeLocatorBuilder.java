package com.lxb.server.cortex.taskmap;

import com.lxb.server.cortex.Bounds;
import com.lxb.server.cortex.LocatorSemantics;
import com.lxb.server.cortex.Util;
import com.lxb.server.cortex.dump.DumpActionsParser;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RuntimeLocatorBuilder {
    private RuntimeLocatorBuilder() {}

    public static Map<String, Object> buildLocator(
            int x,
            int y,
            List<DumpActionsParser.ActionNode> nodes
    ) {
        Map<String, Object> out = buildNodeLocator(x, y, nodes);
        out.put("fallback_point", Arrays.asList(x, y));
        return out;
    }

    private static Map<String, Object> buildNodeLocator(
            int x,
            int y,
            List<DumpActionsParser.ActionNode> actionNodes
    ) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<LocatorSemantics.NodeRecord> nodes = LocatorSemantics.fromActionNodes(actionNodes);
        LocatorSemantics.NodeRecord best = LocatorSemantics.findSmallestContaining(x, y, nodes, 0);
        if (best == null) {
            best = LocatorSemantics.findSmallestContaining(x, y, nodes, 20);
        }
        if (best == null) {
            out.put("bounds_hint", Arrays.asList(x, y, x, y));
            return out;
        }

        Bounds b = best.bounds;
        putIfNotEmpty(out, "resource_id", informativeRidOrEmpty(best.resourceId));
        putIfNotEmpty(out, "text", best.text);
        putIfNotEmpty(out, "content_desc", best.contentDesc);
        putIfNotEmpty(out, "class", best.className);

        int peerCount = 1;
        Integer locatorIndex = null;
        Integer locatorCount = null;
        boolean useTextIdentity = false;
        boolean useParentIdentity = false;

        List<LocatorSemantics.NodeRecord> selfPeers =
                LocatorSemantics.findPeerCandidates(best, nodes, false, false);
        if (!selfPeers.isEmpty()) {
            peerCount = selfPeers.size();
        }

        List<LocatorSemantics.NodeRecord> basePeers = selfPeers;
        if (!best.text.isEmpty() && peerCount > 1) {
            List<LocatorSemantics.NodeRecord> textPeers =
                    LocatorSemantics.findPeerCandidates(best, nodes, false, true);
            if (!textPeers.isEmpty()) {
                basePeers = textPeers;
                peerCount = textPeers.size();
                useTextIdentity = true;
            }
        }

        List<LocatorSemantics.NodeRecord> parentPeers = basePeers;
        if (!best.parentRid.isEmpty() && peerCount > 1) {
            List<LocatorSemantics.NodeRecord> filtered =
                    LocatorSemantics.filterByParentRid(basePeers, best.parentRid);
            if (!filtered.isEmpty()) {
                parentPeers = filtered;
                peerCount = filtered.size();
                useParentIdentity = true;
            }
        }

        if (peerCount >= 2 && peerCount <= 3) {
            int[] indexCount = LocatorSemantics.findPeerIndex(best, parentPeers);
            if (indexCount != null) {
                locatorIndex = indexCount[0];
                locatorCount = indexCount[1];
            }
        }

        if (useParentIdentity) {
            putIfNotEmpty(out, "parent_rid", informativeRidOrEmpty(best.parentRid));
        }
        if (locatorIndex != null && locatorCount != null) {
            out.put("locator_index", locatorIndex);
            out.put("locator_count", locatorCount);
        }
        out.put("bounds_hint", Arrays.asList(b.left, b.top, b.right, b.bottom));
        out.put("locator_peer_count", peerCount);
        out.put("locator_identity_mode", identityMode(useTextIdentity, useParentIdentity, locatorIndex != null));
        return out;
    }

    public static Map<String, Object> buildContainerProbe(
            int x,
            int y,
            List<DumpActionsParser.ActionNode> actionNodes
    ) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<LocatorSemantics.NodeRecord> nodes = LocatorSemantics.fromActionNodes(actionNodes);
        LocatorSemantics.NodeRecord best = findClickableRecordHitAtPoint(x, y, nodes, 0);
        if (best == null) {
            best = findClickableRecordHitAtPoint(x, y, nodes, 20);
        }
        if (best == null) {
            return out;
        }
        putIfNotEmpty(out, "resource_id", informativeRidOrEmpty(best.resourceId));
        putIfNotEmpty(out, "text", best.text);
        putIfNotEmpty(out, "content_desc", best.contentDesc);
        putIfNotEmpty(out, "class", best.className);
        putIfNotEmpty(out, "parent_rid", informativeRidOrEmpty(LocatorSemantics.inferClickableParentRid(best, nodes)));
        if (best.bounds != null) {
            out.put("bounds_hint", Arrays.asList(best.bounds.left, best.bounds.top, best.bounds.right, best.bounds.bottom));
            out.put("center_hint", Arrays.asList(best.bounds.centerX(), best.bounds.centerY()));
        }
        return out;
    }

    private static String informativeRidOrEmpty(String rid) {
        String normalized = Util.normalizeResourceId(rid);
        return Util.isInformativeResourceId(normalized) ? normalized : "";
    }

    private static void putIfNotEmpty(Map<String, Object> out, String key, String value) {
        if (value != null && !value.isEmpty()) {
            out.put(key, value);
        }
    }

    private static LocatorSemantics.NodeRecord findClickableRecordHitAtPoint(
            int x,
            int y,
            List<LocatorSemantics.NodeRecord> records,
            int margin
    ) {
        if (records == null || records.isEmpty()) {
            return null;
        }
        LocatorSemantics.NodeRecord best = null;
        long bestArea = Long.MAX_VALUE;
        for (LocatorSemantics.NodeRecord record : records) {
            if (record == null || record.bounds == null || record.actionNode == null) {
                continue;
            }
            if ((record.actionNode.type & 0x01) == 0) {
                continue;
            }
            BoundsExt bounds = new BoundsExt(record.bounds.left - margin, record.bounds.top - margin,
                    record.bounds.right + margin, record.bounds.bottom + margin);
            if (x < bounds.left || x > bounds.right || y < bounds.top || y > bounds.bottom) {
                continue;
            }
            long area = (long) Math.max(1, record.bounds.right - record.bounds.left)
                    * (long) Math.max(1, record.bounds.bottom - record.bounds.top);
            if (area < bestArea) {
                bestArea = area;
                best = record;
            }
        }
        return best;
    }

    private static String identityMode(boolean useText, boolean useParent, boolean useIndex) {
        StringBuilder sb = new StringBuilder("self_no_text");
        if (useText) sb.append("+text");
        if (useParent) sb.append("+parent");
        if (useIndex) sb.append("+index");
        return sb.toString();
    }

    private static final class BoundsExt {
        final int left;
        final int top;
        final int right;
        final int bottom;

        BoundsExt(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }
}
