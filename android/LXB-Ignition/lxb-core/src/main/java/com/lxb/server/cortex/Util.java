package com.lxb.server.cortex;

public final class Util {
    private Util() {}

    public static boolean isInformativeResourceId(String rid) {
        String s = normalizeResourceId(rid).toLowerCase();
        if (s.isEmpty()) return false;
        if ("container".equals(s)
                || "content".equals(s)
                || "layout".equals(s)
                || "root".equals(s)
                || "item".equals(s)
                || "view".equals(s)
                || "unknown".equals(s)) {
            return false;
        }
        if (s.length() <= 3) return false;
        boolean allDigits = true;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                allDigits = false;
                break;
            }
        }
        if (allDigits) return false;

        boolean hexOnly = s.length() >= 8;
        for (int i = 0; i < s.length() && hexOnly; i++) {
            char ch = s.charAt(i);
            boolean isHex = (ch >= '0' && ch <= '9')
                    || (ch >= 'a' && ch <= 'f')
                    || (ch >= 'A' && ch <= 'F');
            if (!isHex) {
                hexOnly = false;
            }
        }
        return !hexOnly;
    }

    public static String normalizeResourceId(String rid) {
        if (rid == null) return "";
        String s = rid.trim();
        if (s.isEmpty()) return "";
        // Examples:
        // - "tv.danmaku.bili:id/expand_search" -> "expand_search"
        // - "id/expand_search" -> "expand_search"
        int slash = s.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < s.length()) {
            s = s.substring(slash + 1);
        }
        // Some dumps include "@id/xxx"
        if (s.startsWith("@id/")) {
            s = s.substring(4);
        }
        return s.trim();
    }

    public static String normalizeClass(String cls) {
        if (cls == null) return "";
        String s = cls.trim();
        if (s.isEmpty()) return "";
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < s.length()) {
            s = s.substring(dot + 1);
        }
        return s.trim();
    }

    public static String normalizeText(String s) {
        if (s == null) return "";
        // Trim + normalize line endings; avoid aggressive collapsing.
        String t = s.replace("\r\n", "\n").replace('\r', '\n').trim();
        return t;
    }
}
