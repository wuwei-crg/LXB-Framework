package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;

import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LlmClient {

    public String chatOnce(LlmConfig config, String systemPrompt, String userMessage, byte[] imagePng) throws Exception {
        return chatOnce(config, systemPrompt, userMessage, imagePng, 10000, 60000);
    }

    public String chatOnce(
            LlmConfig config,
            String systemPrompt,
            String userMessage,
            byte[] imagePng,
            int connectTimeoutMs,
            int readTimeoutMs
    ) throws Exception {
        int normalizedConnectTimeout = normalizeTimeout(connectTimeoutMs, 10000);
        int normalizedReadTimeout = normalizeTimeout(readTimeoutMs, 60000);
        final int maxAttempts = 3;
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return chatOnceSingleAttempt(
                        config,
                        systemPrompt,
                        userMessage,
                        imagePng,
                        normalizedConnectTimeout,
                        normalizedReadTimeout
                );
            } catch (Exception e) {
                last = e;
                if (attempt >= maxAttempts || !shouldRetry(e)) {
                    throw e;
                }
                sleepBackoff(attempt);
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IllegalStateException("LLM call failed with unknown error");
    }

    private String chatOnceSingleAttempt(
            LlmConfig config,
            String systemPrompt,
            String userMessage,
            byte[] imagePng,
            int connectTimeoutMs,
            int readTimeoutMs
    ) throws Exception {
        HttpURLConnection conn = null;
        try {
            String endpoint = buildEndpointUrl(config.apiBaseUrl);

            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            if (config.apiKey != null && !config.apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            }

            String body = (imagePng != null && imagePng.length > 0)
                    ? buildChatPayloadWithImage(config.model, userMessage, imagePng)
                    : buildChatPayload(config.model, systemPrompt, userMessage);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.getOutputStream().write(bytes);
            conn.getOutputStream().flush();

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();
            String resp = readAll(is);
            if (code >= 200 && code < 300 && (resp == null || resp.trim().isEmpty())) {
                throw new IllegalStateException("LLM HTTP " + code + ": empty body");
            }

            if (code < 200 || code >= 300) {
                String snippet = resp;
                if (snippet != null && snippet.length() > 256) {
                    snippet = snippet.substring(0, 256) + "...";
                }
                throw new IllegalStateException("LLM HTTP " + code + ": " + snippet);
            }

            try {
                Object parsed = Json.parse(resp);
                if (parsed instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> root = (Map<String, Object>) parsed;
                    String extracted = extractAssistantText(root);
                    if (extracted != null && !extracted.isEmpty()) {
                        return extracted;
                    }
                    Object choicesObj = root.get("choices");
                    if (choicesObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Object> choices = (List<Object>) choicesObj;
                        if (!choices.isEmpty()) {
                            Object first = choices.get(0);
                            if (first instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> choice = (Map<String, Object>) first;
                                Object messageObj = choice.get("message");
                                if (messageObj instanceof Map) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> msg = (Map<String, Object>) messageObj;
                                    Object contentObj = msg.get("content");
                                    String contentText = extractContentText(contentObj);
                                    if (contentText != null && !contentText.isEmpty()) {
                                        return contentText;
                                    }
                                    String reasoningText = extractContentText(msg.get("reasoning_content"));
                                    if (reasoningText != null && !reasoningText.isEmpty()) {
                                        return reasoningText;
                                    }
                                }
                                Object textObj = choice.get("text");
                                String choiceText = extractContentText(textObj);
                                if (choiceText != null && !choiceText.isEmpty()) {
                                    return choiceText;
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            return resp;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static boolean shouldRetry(Exception e) {
        if (e instanceof SocketTimeoutException) {
            return true;
        }
        if (e instanceof InterruptedIOException) {
            // Includes read timeout and interrupted I/O variants.
            return true;
        }
        // Retry once/multiple times on transient HTTP failures wrapped in IllegalStateException.
        if (e instanceof IllegalStateException) {
            String msg = String.valueOf(e.getMessage());
            // Retry for timeout and 5xx/429 classes; skip hard auth/missing endpoint failures.
            if (msg.contains("timeout")) return true;
            if (msg.contains("HTTP 5")) return true;
            if (msg.contains("HTTP 429")) return true;
            if (msg.contains("HTTP 408")) return true;
            if (msg.contains("HTTP 401")) return false;
            if (msg.contains("HTTP 403")) return false;
            if (msg.contains("HTTP 404")) return false;
            return true;
        }
        // Fallback: retry unknown transient exceptions as requested.
        return true;
    }

    private static void sleepBackoff(int attempt) throws InterruptedException {
        // 1st retry: 600ms, 2nd retry: 1200ms.
        long ms = 600L * Math.max(1, attempt);
        Thread.sleep(ms);
    }

    @SuppressWarnings("unchecked")
    private static String extractAssistantText(Map<String, Object> root) {
        if (root == null || root.isEmpty()) {
            return "";
        }
        // Common OpenAI-compatible chat field.
        Object choicesObj = root.get("choices");
        if (choicesObj instanceof List) {
            List<?> choices = (List<?>) choicesObj;
            if (!choices.isEmpty() && choices.get(0) instanceof Map) {
                Map<String, Object> c0 = (Map<String, Object>) choices.get(0);
                Object messageObj = c0.get("message");
                if (messageObj instanceof Map) {
                    Map<String, Object> msg = (Map<String, Object>) messageObj;
                    String s = extractContentText(msg.get("content"));
                    if (!s.isEmpty()) return s;
                    s = extractContentText(msg.get("reasoning_content"));
                    if (!s.isEmpty()) return s;
                }
                String s = extractContentText(c0.get("text"));
                if (!s.isEmpty()) return s;
            }
        }
        // Some providers expose direct output_text field.
        String outText = extractContentText(root.get("output_text"));
        if (!outText.isEmpty()) {
            return outText;
        }
        return "";
    }

    private static int normalizeTimeout(int timeoutMs, int defVal) {
        if (timeoutMs <= 0) return defVal;
        if (timeoutMs < 500) return 500;
        if (timeoutMs > 180000) return 180000;
        return timeoutMs;
    }

    @SuppressWarnings("unchecked")
    private static String extractContentText(Object contentObj) {
        if (contentObj == null) {
            return "";
        }
        if (contentObj instanceof String) {
            return ((String) contentObj).trim();
        }
        if (contentObj instanceof Number || contentObj instanceof Boolean) {
            return String.valueOf(contentObj).trim();
        }
        if (contentObj instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object item : (List<Object>) contentObj) {
                String s = extractContentText(item);
                if (!s.isEmpty()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(s);
                }
            }
            return sb.toString().trim();
        }
        if (contentObj instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) contentObj;
            // Common multimodal content item shapes.
            String s = extractContentText(m.get("text"));
            if (!s.isEmpty()) return s;
            s = extractContentText(m.get("content"));
            if (!s.isEmpty()) return s;
            s = extractContentText(m.get("value"));
            if (!s.isEmpty()) return s;
            // As a last resort keep a compact string form.
            return String.valueOf(m).trim();
        }
        return String.valueOf(contentObj).trim();
    }

    public String chatOnce(LlmConfig config, String userMessage) throws Exception {
        return chatOnce(config, null, userMessage, null);
    }

    public String chatOnce(LlmConfig config, String systemPrompt, String userMessage) throws Exception {
        return chatOnce(config, systemPrompt, userMessage, null);
    }

    public static String buildEndpointUrl(String rawBase) {
        String base = rawBase != null ? rawBase.trim() : "";
        if (base.isEmpty()) {
            throw new IllegalStateException("LLM api_base_url is empty");
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            throw new IllegalStateException("LLM api_base_url is empty");
        }
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    private static String buildChatPayload(String model, String systemPrompt, String userMessage) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("model", model);

        List<Map<String, Object>> msgs = new ArrayList<Map<String, Object>>();
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            Map<String, Object> sys = new LinkedHashMap<String, Object>();
            sys.put("role", "system");
            sys.put("content", systemPrompt.trim());
            msgs.add(sys);
        }
        Map<String, Object> user = new LinkedHashMap<String, Object>();
        user.put("role", "user");
        user.put("content", userMessage);
        msgs.add(user);

        root.put("messages", msgs);
        root.put("max_tokens", 60000);
        return Json.stringify(root);
    }

    private static String buildChatPayloadWithImage(String model, String userMessage, byte[] imagePng) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("model", model);

        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        Map<String, Object> user = new LinkedHashMap<String, Object>();
        user.put("role", "user");

        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        Map<String, Object> imgPart = new LinkedHashMap<String, Object>();
        imgPart.put("type", "image_url");
        Map<String, Object> imgUrl = new LinkedHashMap<String, Object>();
        String b64 = Base64.getEncoder().encodeToString(imagePng);
        imgUrl.put("url", "data:image/png;base64," + b64);
        imgPart.put("image_url", imgUrl);
        content.add(imgPart);

        Map<String, Object> textPart = new LinkedHashMap<String, Object>();
        textPart.put("type", "text");
        textPart.put("text", userMessage);
        content.add(textPart);

        user.put("content", content);
        messages.add(user);

        root.put("messages", messages);
        root.put("max_tokens", 60000);
        return Json.stringify(root);
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toString("UTF-8");
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }
}
