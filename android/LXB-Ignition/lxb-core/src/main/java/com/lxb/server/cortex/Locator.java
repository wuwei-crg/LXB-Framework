package com.lxb.server.cortex;

import java.util.List;
import java.util.Map;

/**
 * Locator as stored in map JSON.
 *
 * Map fields observed:
 * - resource_id: string (often short id without "pkg:id/")
 * - text: string
 * - content_desc: string
 * - class: string (often simple name)
 * - parent_rid: string (short id)
 * - bounds_hint: [l,t,r,b]
 * - locator_index/locator_count: optional MapBuilder-style disambiguation
 */
public class Locator {
    public final String resourceId;
    public final String text;
    public final String contentDesc;
    public final String className;
    public final String parentRid;
    public final Bounds boundsHint;
    public final Integer index;
    public final Integer count;

    public Locator(
            String resourceId,
            String text,
            String contentDesc,
            String className,
            String parentRid,
            Bounds boundsHint,
            Integer index,
            Integer count
    ) {
        this.resourceId = resourceId != null ? resourceId : "";
        this.text = text != null ? text : "";
        this.contentDesc = contentDesc != null ? contentDesc : "";
        this.className = className != null ? className : "";
        this.parentRid = parentRid != null ? parentRid : "";
        this.boundsHint = boundsHint;
        this.index = index;
        this.count = count;
    }

    public static Locator fromMap(Map<String, Object> o) {
        if (o == null) return new Locator("", "", "", "", "", null, null, null);

        String resourceId = optStr(o, "resource_id");
        if (resourceId.isEmpty()) {
            // Some older maps used "res_id" naming.
            resourceId = optStr(o, "res_id");
        }

        String text = optStr(o, "text");
        String contentDesc = optStr(o, "content_desc");
        if (contentDesc.isEmpty()) {
            contentDesc = optStr(o, "contentDescription");
        }

        String className = optStr(o, "class");
        if (className.isEmpty()) {
            className = optStr(o, "class_name");
        }

        String parentRid = optStr(o, "parent_rid");
        Bounds boundsHint = null;
        Object bh = o.get("bounds_hint");
        if (bh instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) bh;
            boundsHint = Bounds.fromList(list);
        }

        Integer index = o.containsKey("locator_index")
                ? toInt(o.get("locator_index"))
                : (o.containsKey("index") ? toInt(o.get("index")) : null);
        Integer count = o.containsKey("locator_count")
                ? toInt(o.get("locator_count"))
                : (o.containsKey("count") ? toInt(o.get("count")) : null);

        return new Locator(resourceId, text, contentDesc, className, parentRid, boundsHint, index, count);
    }

    private static String optStr(Map<String, Object> o, String k) {
        Object v = o.get(k);
        if (v == null) return "";
        return String.valueOf(v);
    }

    private static Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception ignored) {
            return null;
        }
    }
}
