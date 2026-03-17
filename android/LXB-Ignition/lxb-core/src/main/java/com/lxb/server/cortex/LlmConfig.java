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
 *   "model": "gpt-4o-mini"
 * }
 */
public class LlmConfig {

    public static final String DEFAULT_CONFIG_PATH = "/data/local/tmp/lxb-llm-config.json";

    public final String apiBaseUrl;
    public final String apiKey;
    public final String model;

    public LlmConfig(String apiBaseUrl, String apiKey, String model) {
        this.apiBaseUrl = apiBaseUrl;
        this.apiKey = apiKey;
        this.model = model;
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

        if (baseUrl.isEmpty() || model.isEmpty()) {
            throw new IllegalStateException("LLM config missing api_base_url or model");
        }

        return new LlmConfig(baseUrl, key, model);
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
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
