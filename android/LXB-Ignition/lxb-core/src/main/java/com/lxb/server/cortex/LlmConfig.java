package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * End-side LLM configuration loaded from a simple JSON file.
 *
 * File format (UTF-8 JSON), written by the APK side via Shizuku:
 * {
 *   "api_base_url": "https://api.openai.com/v1/chat/completions",
 *   "api_key": "sk-...",
 *   "model": "gpt-4o-mini",
 *   "auto_unlock_before_route": true,
 *   "auto_lock_after_task": true,
 *   "unlock_pin": "1234",
 *   "use_map": true,
 *   "map_source": "stable"
 * }
 */
public class LlmConfig {

    public static final String DEFAULT_CONFIG_PATH = "/data/local/tmp/lxb-llm-config.json";

    public final String apiBaseUrl;
    public final String apiKey;
    public final String model;
    public final boolean autoUnlockBeforeRoute;
    public final boolean autoLockAfterTask;
    public final String unlockPin;
    public final boolean useMap;
    public final String mapSource;

    public LlmConfig(String apiBaseUrl, String apiKey, String model) {
        this(apiBaseUrl, apiKey, model, true, true, "", true, "stable");
    }

    public LlmConfig(
            String apiBaseUrl,
            String apiKey,
            String model,
            boolean autoUnlockBeforeRoute,
            boolean autoLockAfterTask,
            String unlockPin,
            boolean useMap,
            String mapSource
    ) {
        this.apiBaseUrl = apiBaseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.autoUnlockBeforeRoute = autoUnlockBeforeRoute;
        this.autoLockAfterTask = autoLockAfterTask;
        this.unlockPin = unlockPin != null ? unlockPin : "";
        this.useMap = useMap;
        this.mapSource = normalizeMapSource(mapSource);
    }

    public static LlmConfig loadDefault() throws Exception {
        String override = System.getProperty("lxb.llm.config.path");
        String path = (override != null && !override.trim().isEmpty())
                ? override.trim()
                : DEFAULT_CONFIG_PATH;
        return loadFromFile(path);
    }

    public static LlmConfig loadFromFile(String path) throws Exception {
        File f = new File(path);
        if (!f.exists()) {
            throw new IllegalStateException("LLM config file not found: " + path);
        }

        byte[] data = readAllBytes(f);
        String s = new String(data, StandardCharsets.UTF_8);
        Map<String, Object> obj = Json.parseObject(s);

        String baseUrl = stringOrEmpty(obj.get("api_base_url"));
        String key = stringOrEmpty(obj.get("api_key"));
        String model = stringOrEmpty(obj.get("model"));
        boolean autoUnlockBeforeRoute = parseBool(obj.get("auto_unlock_before_route"), true);
        boolean autoLockAfterTask = parseBool(obj.get("auto_lock_after_task"), true);
        String unlockPin = stringOrEmpty(obj.get("unlock_pin"));
        boolean useMap = parseBool(obj.get("use_map"), true);
        String mapSource = normalizeMapSource(stringOrEmpty(obj.get("map_source")));

        if (baseUrl.isEmpty() || model.isEmpty()) {
            throw new IllegalStateException("LLM config missing api_base_url or model");
        }

        return new LlmConfig(
                baseUrl,
                key,
                model,
                autoUnlockBeforeRoute,
                autoLockAfterTask,
                unlockPin,
                useMap,
                mapSource
        );
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static boolean parseBool(Object o, boolean defVal) {
        if (o == null) {
            return defVal;
        }
        if (o instanceof Boolean) {
            return ((Boolean) o).booleanValue();
        }
        String s = String.valueOf(o).trim().toLowerCase();
        if (s.isEmpty()) {
            return defVal;
        }
        if ("1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s)) {
            return true;
        }
        if ("0".equals(s) || "false".equals(s) || "no".equals(s) || "off".equals(s)) {
            return false;
        }
        return defVal;
    }

    private static String normalizeMapSource(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase();
        if ("candidates".equals(s)) {
            return "candidate";
        }
        if ("stable".equals(s) || "candidate".equals(s) || "burn".equals(s)) {
            return s;
        }
        return "stable";
    }

    private static byte[] readAllBytes(File f) throws Exception {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(f);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
