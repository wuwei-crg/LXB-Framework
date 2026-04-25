package com.lxb.server.cortex.taskmap;

import com.lxb.server.cortex.dump.DumpActionsParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TaskMapLocalTapBuilder {

    public interface LocatorUniquenessGate {
        boolean isAccepted(Map<String, Object> locator);
    }

    public static final class LocalTapPayload {
        public final Map<String, Object> locator = new LinkedHashMap<String, Object>();
        public final Map<String, Object> containerProbe = new LinkedHashMap<String, Object>();
        public final List<Object> tapPoint = new ArrayList<Object>();
        public String fallbackPoint = "";
    }

    private TaskMapLocalTapBuilder() {}

    public static LocalTapPayload materializeTap(
            int x,
            int y,
            List<DumpActionsParser.ActionNode> nodes,
            LocatorUniquenessGate uniquenessGate
    ) {
        LocalTapPayload out = new LocalTapPayload();
        out.tapPoint.add(x);
        out.tapPoint.add(y);
        Map<String, Object> locator = RuntimeLocatorBuilder.buildLocator(x, y, nodes);
        boolean accepted = uniquenessGate != null && uniquenessGate.isAccepted(locator);
        if (accepted) {
            out.locator.putAll(locator);
            Object fallback = locator.get("fallback_point");
            out.fallbackPoint = fallback != null ? String.valueOf(fallback) : String.valueOf(out.tapPoint);
            return out;
        }
        Map<String, Object> probe = RuntimeLocatorBuilder.buildContainerProbe(x, y, nodes);
        if (probe != null && !probe.isEmpty()) {
            out.containerProbe.putAll(probe);
        }
        out.fallbackPoint = String.valueOf(out.tapPoint);
        return out;
    }
}
