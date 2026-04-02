package com.lxb.server.execution;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.lxb.server.cortex.json.Json;
import com.lxb.server.system.UiAutomationWrapper;

/**
 * 执行引擎 - 负责输入注入和系统操作
 *
 * 处理的命令:
 * - 0x10 TAP: 单击
 * - 0x11 SWIPE: 滑动
 * - 0x12 LONG_PRESS: 长按
 * - 0x1B UNLOCK: 解锁屏幕
 * - 0x20 INPUT_TEXT: 文本输入
 * - 0x21 KEY_EVENT: 按键事件
 * - 0x43 LAUNCH_APP: 启动应用
 * - 0x44 STOP_APP: 停止应用
 * - 0x48 LIST_APPS: 获取已安装应用列表
 */
public class ExecutionEngine {

    private static final String TAG = "[LXB][Execution]";
    private static final int DEFAULT_SYSTEM_CONTROL_TIMEOUT_MS = 8000;
    private static final int SCREEN_RECORD_VERIFY_DELAY_MS = 500;
    private static final int SWIPE_MIN_DURATION_MS = 1500;

    // 系统层依赖
    private UiAutomationWrapper uiAutomation;
    private volatile int lastScreenRecordPid = -1;
    private static final int INPUT_METHOD_ADB = 0;
    private static final int INPUT_METHOD_CLIPBOARD = 1;
    private static final int INPUT_METHOD_ACCESSIBILITY = 2;
    private static final int INPUT_METHOD_ADB_KEYBOARD = 3;

    /**
     * 设置 UiAutomation 依赖
     *
     * @param wrapper UiAutomationWrapper 实例
     */
    public void setUiAutomation(UiAutomationWrapper wrapper) {
        this.uiAutomation = wrapper;
    }

    /**
     * 初始化执行引擎
     */
    public void initialize() {
        System.out.println(TAG + " Engine initialized");
        if (uiAutomation == null) {
            System.err.println(TAG + " WARNING: UiAutomation not set!");
        }
    }

    public Map<String, Object> refreshTextInputSupportStatus() {
        if (uiAutomation == null) {
            return new LinkedHashMap<>();
        }
        return uiAutomation.refreshAdbKeyboardStatus();
    }

    /**
     * 处理 TAP 命令 (0x10)
     *
     * Payload 格式: x[2B] + y[2B]
     *
     * @param payload 请求负载 (4 字节)
     * @return ACK 响应 (1 字节: success)
     */
    public byte[] handleTap(byte[] payload) {
        if (payload.length < 4) {
            System.err.println(TAG + " TAP payload too short: " + payload.length);
            return new byte[]{0x00};
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int x = buffer.getShort() & 0xFFFF;
        int y = buffer.getShort() & 0xFFFF;

        System.out.println(TAG + " TAP at (" + x + ", " + y + ")");

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00};
        }

        boolean success = uiAutomation.click(x, y);
        return new byte[]{(byte) (success ? 0x01 : 0x00)};
    }

    /**
     * 处理 SWIPE 命令 (0x11)
     *
     * Payload 格式: x1[2B] + y1[2B] + x2[2B] + y2[2B] + duration[2B]
     *
     * @param payload 请求负载 (10 字节)
     * @return ACK 响应
     */
    public byte[] handleSwipe(byte[] payload) {
        if (payload.length < 10) {
            System.err.println(TAG + " SWIPE payload too short: " + payload.length);
            return new byte[]{0x00};
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int x1 = buffer.getShort() & 0xFFFF;
        int y1 = buffer.getShort() & 0xFFFF;
        int x2 = buffer.getShort() & 0xFFFF;
        int y2 = buffer.getShort() & 0xFFFF;
        int requestedDuration = buffer.getShort() & 0xFFFF;
        int duration = Math.max(requestedDuration, SWIPE_MIN_DURATION_MS);

        System.out.println(TAG + " SWIPE from (" + x1 + ", " + y1 +
                ") to (" + x2 + ", " + y2 + "), duration=" + duration + "ms"
                + " (requested=" + requestedDuration + "ms)");

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00};
        }

        boolean success = uiAutomation.swipe(x1, y1, x2, y2, duration);
        return new byte[]{(byte) (success ? 0x01 : 0x00)};
    }

    /**
     * 处理 LONG_PRESS 命令 (0x12)
     *
     * Payload 格式: x[2B] + y[2B] + duration[2B]
     *
     * @param payload 请求负载 (6 字节)
     * @return ACK 响应
     */
    public byte[] handleLongPress(byte[] payload) {
        if (payload.length < 6) {
            System.err.println(TAG + " LONG_PRESS payload too short: " + payload.length);
            return new byte[]{0x00};
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int x = buffer.getShort() & 0xFFFF;
        int y = buffer.getShort() & 0xFFFF;
        int duration = buffer.getShort() & 0xFFFF;

        System.out.println(TAG + " LONG_PRESS at (" + x + ", " + y +
                "), duration=" + duration + "ms");

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00};
        }

        boolean success = uiAutomation.longPress(x, y, duration);
        return new byte[]{(byte) (success ? 0x01 : 0x00)};
    }

    /**
     * 处理 INPUT_TEXT 命令 (0x20)
     *
     * Payload 格式:
     *   method[1B] + flags[1B] + target_x[2B] + target_y[2B] +
     *   delay_ms[2B] + text_len[2B] + text[UTF-8]
     *
     * Flags:
     *   bit 0: CLEAR_FIRST - 先清空输入框
     *   bit 1: PRESS_ENTER - 输入后按回车
     *   bit 2: HIDE_KEYBOARD - 输入后隐藏键盘
     *
     * @param payload 请求负载
     * @return ACK 响应 (2 字节: status + actual_method)
     */
    public byte[] handleInputText(byte[] payload) {
        if (payload.length < 10) {
            System.err.println(TAG + " INPUT_TEXT payload too short: " + payload.length);
            return new byte[]{0x00, 0x00};
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int method = buffer.get() & 0xFF;
        int flags = buffer.get() & 0xFF;
        int targetX = buffer.getShort() & 0xFFFF;
        int targetY = buffer.getShort() & 0xFFFF;
        int delayMs = buffer.getShort() & 0xFFFF;
        int textLen = buffer.getShort() & 0xFFFF;

        if (payload.length < 10 + textLen) {
            System.err.println(TAG + " INPUT_TEXT text truncated");
            return new byte[]{0x00, 0x00};
        }

        byte[] textBytes = new byte[textLen];
        buffer.get(textBytes);
        String text = new String(textBytes, StandardCharsets.UTF_8);

        boolean clearFirst = (flags & 0x01) != 0;
        boolean pressEnter = (flags & 0x02) != 0;
        boolean hideKeyboard = (flags & 0x04) != 0;

        System.out.println(TAG + " INPUT_TEXT: \"" + text + "\" method=" + method +
                " clearFirst=" + clearFirst + " pressEnter=" + pressEnter);

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00, 0x00};
        }

        // 如果指定了目标坐标，先点击
        if (targetX > 0 && targetY > 0) {
            uiAutomation.click(targetX, targetY);
            try {
                Thread.sleep(100);  // 等待键盘弹出
            } catch (InterruptedException ignored) {}
        }

        // 清空现有文本
        if (clearFirst) {
            uiAutomation.clearFocusedText();
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {}
        }

        int actualMethod = method;
        boolean success = false;

        if (uiAutomation.hasAdbKeyboardAvailable()) {
            success = uiAutomation.inputTextByAdbKeyboard(text);
            if (success) {
                actualMethod = INPUT_METHOD_ADB_KEYBOARD;
            } else {
                System.err.println(TAG + " INPUT_TEXT adb_keyboard failed, fallback to legacy path");
            }
        }

        // 优先按客户端指定 method 执行；失败后自动回退。
        if (!success && method == INPUT_METHOD_CLIPBOARD) {
            // Prefer ACTION_SET_TEXT first for complex text (Chinese/emoji), then fallback.
            success = uiAutomation.setFocusedText(text);
            actualMethod = INPUT_METHOD_ACCESSIBILITY;
            if (!success) {
                success = uiAutomation.inputTextByClipboard(text);
                actualMethod = INPUT_METHOD_CLIPBOARD;
            }
            if (!success) {
                success = uiAutomation.inputTextDirect(text);
                actualMethod = INPUT_METHOD_ADB;
            }
        } else if (!success && method == INPUT_METHOD_ADB) {
            success = uiAutomation.inputTextDirect(text);
            actualMethod = INPUT_METHOD_ADB;
            if (!success) {
                success = uiAutomation.setFocusedText(text);
                actualMethod = INPUT_METHOD_ACCESSIBILITY;
            }
        } else if (!success && method == INPUT_METHOD_ACCESSIBILITY) {
            success = uiAutomation.setFocusedText(text);
            actualMethod = INPUT_METHOD_ACCESSIBILITY;
            if (!success) {
                success = uiAutomation.inputTextByClipboard(text);
                actualMethod = INPUT_METHOD_CLIPBOARD;
            }
            if (!success) {
                success = uiAutomation.inputTextDirect(text);
                actualMethod = INPUT_METHOD_ADB;
            }
        } else if (!success) {
            success = uiAutomation.setFocusedText(text);
            actualMethod = INPUT_METHOD_ACCESSIBILITY;
            if (!success) {
                success = uiAutomation.inputTextByClipboard(text);
                actualMethod = INPUT_METHOD_CLIPBOARD;
            }
            if (!success) {
                success = uiAutomation.inputTextDirect(text);
                actualMethod = INPUT_METHOD_ADB;
            }
        }

        System.out.println(TAG + " INPUT_TEXT result: " + (success ? "OK" : "FAIL") + " method=" + actualMethod);

        // 按回车
        if (pressEnter) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {}
            uiAutomation.pressKey(uiAutomation.getKeycodeEnter());
        }

        // 隐藏键盘 (按返回键)
        if (hideKeyboard) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {}
            uiAutomation.pressKey(uiAutomation.getKeycodeBack());
        }

        return new byte[]{(byte)(success ? 0x01 : 0x00), (byte) actualMethod};
    }

    /**
     * 处理 KEY_EVENT 命令 (0x21)
     *
     * Payload 格式: keycode[1B] + action[1B] + meta_state[4B]
     *
     * Action:
     *   0 = DOWN
     *   1 = UP
     *   2 = CLICK (DOWN + UP)
     *
     * @param payload 请求负载 (6 字节)
     * @return ACK 响应
     */
    public byte[] handleKeyEvent(byte[] payload) {
        // 支持两种格式:
        // - 简化格式 (2 bytes): keycode[1B] + action[1B]
        // - 完整格式 (6 bytes): keycode[1B] + action[1B] + metaState[4B]
        if (payload.length < 2) {
            System.err.println(TAG + " KEY_EVENT payload too short: " + payload.length);
            return new byte[]{0x00};
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int keycode = buffer.get() & 0xFF;
        int action = buffer.get() & 0xFF;
        int metaState = 0;

        // 如果有额外字节，读取 metaState
        if (payload.length >= 6) {
            metaState = buffer.getInt();
        }

        System.out.println(TAG + " KEY_EVENT keycode=" + keycode +
                " action=" + action + " metaState=" + metaState);

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00};
        }

        boolean success = uiAutomation.injectKeyEvent(keycode, action, metaState);
        return new byte[]{(byte) (success ? 0x01 : 0x00)};
    }

    /**
     * 处理 UNLOCK 命令 (0x1B)
     *
     * Payload 格式: 无
     *
     * @param payload 请求负载 (应为空)
     * @return ACK 响应
     */
    public byte[] handleUnlock(byte[] payload) {
        System.out.println(TAG + " UNLOCK");

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00};
        }

        boolean success = uiAutomation.unlock();
        return new byte[]{(byte) (success ? 0x01 : 0x00)};
    }

    /**
     * Handle touch mode switch (0x1C).
     *
     * Payload format: mode[1B]
     *   0 = UiAutomation first
     *   1 = shell(input) first
     *
     * Response: status[1B]
     */
    public byte[] handleSetTouchMode(byte[] payload) {
        if (payload.length < 1) {
            System.err.println(TAG + " SET_TOUCH_MODE payload too short: " + payload.length);
            return new byte[]{0x00};
        }

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00};
        }

        int mode = payload[0] & 0xFF;
        boolean shellFirst = mode != 0;
        uiAutomation.setPreferShellInputTouch(shellFirst);
        System.out.println(TAG + " SET_TOUCH_MODE: " + (shellFirst ? "shell_first" : "uiautomation_first"));
        return new byte[]{0x01};
    }

    /**
     * Handle screenshot quality switch (0x1D).
     *
     * Payload format: quality[1B], range 1..100.
     *
     * Response: status[1B]
     */
    public byte[] handleSetScreenshotQuality(byte[] payload) {
        if (payload.length < 1) {
            System.err.println(TAG + " SET_SCREENSHOT_QUALITY payload too short: " + payload.length);
            return new byte[]{0x00};
        }

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00};
        }

        int quality = payload[0] & 0xFF;
        if (quality < 1 || quality > 100) {
            System.err.println(TAG + " SET_SCREENSHOT_QUALITY invalid value: " + quality);
            return new byte[]{0x00};
        }

        uiAutomation.setScreenshotQuality(quality);
        System.out.println(TAG + " SET_SCREENSHOT_QUALITY: " + quality);
        return new byte[]{0x01};
    }


    /**
     * 处理 LAUNCH_APP 命令 (0x43)
     *
     * Payload 格式: flags[1B] + package_len[2B] + package_name[UTF-8]
     *
     * Flags:
     *   bit 0: CLEAR_TASK - 清除任务栈
     *   bit 1: WAIT - 等待 Activity 启动
     *
     * @param payload 请求负载
     * @return ACK 响应
     */
    public byte[] handleLaunchApp(byte[] payload) {
        if (payload.length < 3) {
            System.err.println(TAG + " LAUNCH_APP payload too short: " + payload.length);
            return new byte[]{0x00};
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int flags = buffer.get() & 0xFF;
        int packageLen = buffer.getShort() & 0xFFFF;

        if (payload.length < 3 + packageLen) {
            System.err.println(TAG + " LAUNCH_APP package name truncated");
            return new byte[]{0x00};
        }

        byte[] packageBytes = new byte[packageLen];
        buffer.get(packageBytes);
        String packageName = new String(packageBytes, StandardCharsets.UTF_8);

        System.out.println(TAG + " LAUNCH_APP: " + packageName + " flags=0x" +
                Integer.toHexString(flags));

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00};
        }

        boolean success = uiAutomation.launchApp(packageName, flags);
        return new byte[]{(byte) (success ? 0x01 : 0x00)};
    }

    /**
     * 处理 STOP_APP 命令 (0x44)
     *
     * Payload 格式: package_len[2B] + package_name[UTF-8]
     *
     * @param payload 请求负载
     * @return ACK 响应
     */
    public byte[] handleStopApp(byte[] payload) {
        if (payload.length < 2) {
            System.err.println(TAG + " STOP_APP payload too short: " + payload.length);
            return new byte[]{0x00};
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int packageLen = buffer.getShort() & 0xFFFF;

        if (payload.length < 2 + packageLen) {
            System.err.println(TAG + " STOP_APP package name truncated");
            return new byte[]{0x00};
        }

        byte[] packageBytes = new byte[packageLen];
        buffer.get(packageBytes);
        String packageName = new String(packageBytes, StandardCharsets.UTF_8);

        System.out.println(TAG + " STOP_APP: " + packageName);

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00};
        }

        boolean success = uiAutomation.stopApp(packageName);
        return new byte[]{(byte) (success ? 0x01 : 0x00)};
    }

    /**
     * 处理 LIST_APPS 命令 (0x48)
     *
     * Payload 格式: filter[1B]
     *   filter: 0=all, 1=user, 2=system
     *
     * @param payload 请求负载 (1 字节)
     * @return ACK 响应 (1 字节 status + JSON 字符串)
     */
    public byte[] handleListApps(byte[] payload) {
        int filter = 0;  // 默认返回所有应用
        if (payload.length >= 1) {
            filter = payload[0] & 0xFF;
        }

        System.out.println(TAG + " LIST_APPS filter=" + filter);

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00};
        }

        String jsonResult = uiAutomation.listApps(filter);
        byte[] jsonBytes = jsonResult.getBytes(StandardCharsets.UTF_8);

        // 返回: status[1B] + json_len[2B] + json_data
        ByteBuffer response = ByteBuffer.allocate(3 + jsonBytes.length);
        response.order(ByteOrder.BIG_ENDIAN);
        response.put((byte) 0x01);  // success
        response.putShort((short) jsonBytes.length);
        response.put(jsonBytes);

        return response.array();
    }

    /**
     * Handle generic system shell controls (0x49).
     *
     * Payload format: UTF-8 JSON object
     *   {"action":"wifi_set","enabled":true}
     *
     * Response format: status[1B] + json_len[2B] + json_data[UTF-8]
     */
    public byte[] handleSystemControl(byte[] payload) {
        if (uiAutomation == null) {
            return packJsonResponse(false, buildError("system_control", "UiAutomation not available"));
        }

        if (payload == null || payload.length == 0) {
            return packJsonResponse(false, buildError("system_control", "payload is required"));
        }

        final String reqText = new String(payload, StandardCharsets.UTF_8);
        final Map<String, Object> req;
        try {
            req = Json.parseObject(reqText);
        } catch (Exception e) {
            return packJsonResponse(false, buildError("system_control", "invalid json payload: " + e.getMessage()));
        }

        String action = asString(req.get("action"), "").trim().toLowerCase();
        if (action.isEmpty()) {
            return packJsonResponse(false, buildError("system_control", "action is required"));
        }

        int timeoutMs = asInt(req.get("timeout_ms"), DEFAULT_SYSTEM_CONTROL_TIMEOUT_MS);
        if (timeoutMs < 500) timeoutMs = 500;
        if (timeoutMs > 120000) timeoutMs = 120000;

        Map<String, Object> out;
        try {
            out = executeSystemControlAction(action, req, timeoutMs);
        } catch (Exception e) {
            out = buildError(action, e.getMessage());
        }

        boolean ok = asBoolean(out.get("ok"), false);
        return packJsonResponse(ok, out);
    }

    private Map<String, Object> executeSystemControlAction(String action, Map<String, Object> req, int timeoutMs) {
        if ("panel_expand_notifications".equals(action)) {
            return runShellAction(action, "cmd statusbar expand-notifications", timeoutMs);
        }
        if ("panel_expand_settings".equals(action) || "panel_expand_quick_settings".equals(action)) {
            return runShellAction(action, "cmd statusbar expand-settings", timeoutMs);
        }
        if ("panel_collapse".equals(action)) {
            return runShellAction(action, "cmd statusbar collapse", timeoutMs);
        }
        if ("wifi_set".equals(action)) {
            boolean enabled = readEnabled(req, true);
            return runShellAction(action, enabled ? "svc wifi enable" : "svc wifi disable", timeoutMs);
        }
        if ("bluetooth_set".equals(action)) {
            boolean enabled = readEnabled(req, true);
            return runShellAction(action, enabled ? "svc bluetooth enable" : "svc bluetooth disable", timeoutMs);
        }
        if ("airplane_set".equals(action)) {
            boolean enabled = readEnabled(req, true);
            String state = enabled ? "1" : "0";
            String boolState = enabled ? "true" : "false";
            String cmd = "settings put global airplane_mode_on " + state
                    + "; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state " + boolState;
            return runShellAction(action, cmd, timeoutMs);
        }
        if ("dnd_set".equals(action)) {
            String mode = asString(req.get("mode"), "off").trim().toLowerCase();
            String dndMode;
            if ("on".equals(mode) || "priority".equals(mode)) dndMode = "priority";
            else if ("alarms".equals(mode)) dndMode = "alarms";
            else if ("none".equals(mode) || "total_silence".equals(mode)) dndMode = "none";
            else dndMode = "off";
            return runShellAction(action, "cmd notification set_dnd " + dndMode, timeoutMs);
        }
        if ("brightness_set".equals(action)) {
            int value = asInt(req.get("value"), 128);
            if (value < 0) value = 0;
            if (value > 255) value = 255;
            String cmd = "settings put system screen_brightness_mode 0; settings put system screen_brightness " + value;
            Map<String, Object> out = runShellAction(action, cmd, timeoutMs);
            out.put("value", value);
            return out;
        }
        if ("brightness_mode".equals(action)) {
            String mode = asString(req.get("mode"), "manual").trim().toLowerCase();
            int modeValue = "auto".equals(mode) ? 1 : 0;
            Map<String, Object> out = runShellAction(action, "settings put system screen_brightness_mode " + modeValue, timeoutMs);
            out.put("mode", modeValue == 1 ? "auto" : "manual");
            return out;
        }
        if ("volume_set".equals(action)) {
            int stream = resolveAudioStream(req);
            int level = asInt(req.get("level"), 5);
            if (level < 0) level = 0;
            String cmd = "media volume --stream " + stream + " --set " + level;
            Map<String, Object> out = runShellAction(action, cmd, timeoutMs);
            out.put("stream", stream);
            out.put("level", level);
            return out;
        }
        if ("volume_adjust".equals(action)) {
            int stream = resolveAudioStream(req);
            String direction = asString(req.get("direction"), "raise").trim().toLowerCase();
            if (!"lower".equals(direction) && !"mute".equals(direction) && !"unmute".equals(direction)) {
                direction = "raise";
            }
            String cmd = "media volume --stream " + stream + " --adj " + direction;
            Map<String, Object> out = runShellAction(action, cmd, timeoutMs);
            out.put("stream", stream);
            out.put("direction", direction);
            return out;
        }
        if ("rotation_set".equals(action)) {
            String mode = asString(req.get("mode"), "auto").trim().toLowerCase();
            if ("auto".equals(mode)) {
                return runShellAction(action, "settings put system accelerometer_rotation 1", timeoutMs);
            }
            int rotation = 0;
            if ("landscape".equals(mode)) rotation = 1;
            else if ("reverse_portrait".equals(mode)) rotation = 2;
            else if ("reverse_landscape".equals(mode)) rotation = 3;
            String cmd = "settings put system accelerometer_rotation 0; settings put system user_rotation " + rotation;
            Map<String, Object> out = runShellAction(action, cmd, timeoutMs);
            out.put("mode", mode);
            out.put("rotation", rotation);
            return out;
        }
        if ("media_key".equals(action)) {
            String key = asString(req.get("key"), "play_pause").trim().toLowerCase();
            int keyCode = resolveMediaKeyCode(key);
            if (keyCode <= 0) {
                return buildError(action, "unsupported media key: " + key);
            }
            Map<String, Object> out = runShellAction(action, "input keyevent " + keyCode, timeoutMs);
            out.put("key", key);
            out.put("keycode", keyCode);
            return out;
        }
        if ("torch_set".equals(action)) {
            boolean enabled = readEnabled(req, true);
            String cmd = enabled
                    ? "(cmd flashlight set 1 || cmd flashlight on || cmd statusbar torch on)"
                    : "(cmd flashlight set 0 || cmd flashlight off || cmd statusbar torch off)";
            return runShellAction(action, cmd, timeoutMs);
        }
        if ("screen_record_start".equals(action)) {
            String path = asString(req.get("path"), "").trim();
            if (path.isEmpty()) {
                path = "/sdcard/Movies/lxb_record_" + System.currentTimeMillis() + ".mp4";
            }
            int bitRate = asInt(req.get("bit_rate"), 0);
            int timeLimit = asInt(req.get("time_limit_sec"), 0);
            String size = asString(req.get("size"), "").trim();
            StringBuilder recordCmd = new StringBuilder("screenrecord");
            if (bitRate > 0) recordCmd.append(" --bit-rate ").append(bitRate);
            if (timeLimit > 0) recordCmd.append(" --time-limit ").append(timeLimit);
            if (!size.isEmpty()) recordCmd.append(" --size ").append(size);
            recordCmd.append(" ").append(shellQuote(path));

            // Use nohup to prevent process from being terminated when parent shell exits.
            String cmd = "mkdir -p $(dirname " + shellQuote(path) + "); nohup "
                    + recordCmd + " >/dev/null 2>&1 < /dev/null & echo $!";
            UiAutomationWrapper.ShellCommandResult start = uiAutomation.runShellCommand(cmd, timeoutMs);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("action", action);
            out.put("path", path);
            out.put("command", cmd);
            out.put("exit_code", start.exitCode);
            out.put("timeout", start.timeout);
            if (start.stdout != null && !start.stdout.isEmpty()) out.put("stdout", start.stdout);
            if (start.stderr != null && !start.stderr.isEmpty()) out.put("stderr", start.stderr);

            if (!start.ok) {
                out.put("ok", false);
                out.put("error", "failed_to_start_record_process");
                return out;
            }

            int pid = parseFirstInt(start.stdout);
            if (pid <= 0) {
                out.put("ok", false);
                out.put("error", "screenrecord_pid_not_found");
                return out;
            }

            try { Thread.sleep(SCREEN_RECORD_VERIFY_DELAY_MS); } catch (InterruptedException ignored) {}
            UiAutomationWrapper.ShellCommandResult aliveCheck =
                    uiAutomation.runShellCommand("kill -0 " + pid, Math.min(timeoutMs, 2000));
            boolean alive = aliveCheck.ok;

            out.put("pid", pid);
            out.put("alive", alive);
            if (!alive) {
                out.put("ok", false);
                out.put("error", "screenrecord_exited_immediately");
                return out;
            }

            lastScreenRecordPid = pid;
            out.put("ok", true);
            return out;
        }
        if ("screen_record_stop".equals(action)) {
            int pid = lastScreenRecordPid;
            if (pid > 0) {
                UiAutomationWrapper.ShellCommandResult stopByPid =
                        uiAutomation.runShellCommand("kill -INT " + pid, timeoutMs);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("action", action);
                out.put("pid", pid);
                out.put("command", "kill -INT " + pid);
                out.put("exit_code", stopByPid.exitCode);
                out.put("timeout", stopByPid.timeout);
                if (stopByPid.stdout != null && !stopByPid.stdout.isEmpty()) out.put("stdout", stopByPid.stdout);
                if (stopByPid.stderr != null && !stopByPid.stderr.isEmpty()) out.put("stderr", stopByPid.stderr);

                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                UiAutomationWrapper.ShellCommandResult aliveCheck =
                        uiAutomation.runShellCommand("kill -0 " + pid, 1200);
                boolean stillAlive = aliveCheck.ok;
                out.put("stopped", !stillAlive);
                out.put("ok", !stillAlive);
                if (!stillAlive) {
                    lastScreenRecordPid = -1;
                    return out;
                }
            }

            Map<String, Object> fallback = runShellAction(action, "pkill -INT -f screenrecord", timeoutMs);
            if (asBoolean(fallback.get("ok"), false)) {
                lastScreenRecordPid = -1;
            }
            return fallback;
        }
        if ("doze_whitelist_add".equals(action) || "doze_whitelist_remove".equals(action)) {
            String pkg = asString(req.get("package"), "").trim();
            if (pkg.isEmpty()) {
                return buildError(action, "package is required");
            }
            String prefix = "doze_whitelist_add".equals(action) ? "+" : "-";
            Map<String, Object> out = runShellAction(action, "dumpsys deviceidle whitelist " + prefix + pkg, timeoutMs);
            out.put("package", pkg);
            return out;
        }
        if ("screen_off".equals(action)) {
            return runShellAction(action, "input keyevent 26", timeoutMs);
        }
        if ("wake_up".equals(action)) {
            return runShellAction(action, "input keyevent 224", timeoutMs);
        }
        if ("shell_exec".equals(action)) {
            String command = asString(req.get("command"), "").trim();
            if (command.isEmpty()) {
                return buildError(action, "command is required");
            }
            return runShellAction(action, command, timeoutMs);
        }

        return buildError(action, "unsupported action");
    }

    private Map<String, Object> runShellAction(String action, String command, int timeoutMs) {
        UiAutomationWrapper.ShellCommandResult r = uiAutomation.runShellCommand(command, timeoutMs);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", r.ok);
        out.put("action", action);
        out.put("command", command);
        out.put("exit_code", r.exitCode);
        out.put("timeout", r.timeout);
        if (r.stdout != null && !r.stdout.isEmpty()) out.put("stdout", r.stdout);
        if (r.stderr != null && !r.stderr.isEmpty()) out.put("stderr", r.stderr);
        return out;
    }

    private Map<String, Object> buildError(String action, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("action", action);
        out.put("error", message != null ? message : "unknown_error");
        return out;
    }

    private boolean readEnabled(Map<String, Object> req, boolean defaultValue) {
        Object enabledValue = req.get("enabled");
        if (enabledValue != null) {
            return asBoolean(enabledValue, defaultValue);
        }
        Object stateValue = req.get("state");
        String state = asString(stateValue, "").trim().toLowerCase();
        if ("on".equals(state) || "enable".equals(state) || "enabled".equals(state) || "true".equals(state)) {
            return true;
        }
        if ("off".equals(state) || "disable".equals(state) || "disabled".equals(state) || "false".equals(state)) {
            return false;
        }
        return defaultValue;
    }

    private int resolveAudioStream(Map<String, Object> req) {
        Object streamObj = req.get("stream");
        if (streamObj instanceof Number) {
            int s = ((Number) streamObj).intValue();
            if (s >= 0 && s <= 10) return s;
        }
        String stream = asString(streamObj, "music").trim().toLowerCase();
        if ("voice".equals(stream) || "voice_call".equals(stream) || "call".equals(stream)) return 0;
        if ("system".equals(stream)) return 1;
        if ("ring".equals(stream)) return 2;
        if ("music".equals(stream) || "media".equals(stream)) return 3;
        if ("alarm".equals(stream)) return 4;
        if ("notification".equals(stream)) return 5;
        if ("bluetooth".equals(stream)) return 6;
        return 3;
    }

    private int resolveMediaKeyCode(String key) {
        if ("play_pause".equals(key)) return 85;
        if ("next".equals(key)) return 87;
        if ("previous".equals(key) || "prev".equals(key)) return 88;
        if ("play".equals(key)) return 126;
        if ("pause".equals(key)) return 127;
        if ("stop".equals(key)) return 86;
        if ("mute".equals(key)) return 164;
        if ("volume_up".equals(key)) return 24;
        if ("volume_down".equals(key)) return 25;
        return -1;
    }

    private byte[] packJsonResponse(boolean ok, Map<String, Object> obj) {
        String json = Json.stringify(obj != null ? obj : new LinkedHashMap<String, Object>());
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer response = ByteBuffer.allocate(3 + jsonBytes.length);
        response.order(ByteOrder.BIG_ENDIAN);
        response.put((byte) (ok ? 0x01 : 0x00));
        response.putShort((short) jsonBytes.length);
        response.put(jsonBytes);
        return response.array();
    }

    private String asString(Object v, String def) {
        if (v == null) return def;
        return String.valueOf(v);
    }

    private int asInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    private boolean asBoolean(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean) return (Boolean) v;
        String s = String.valueOf(v).trim().toLowerCase();
        if ("1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s) || "enable".equals(s) || "enabled".equals(s)) {
            return true;
        }
        if ("0".equals(s) || "false".equals(s) || "no".equals(s) || "off".equals(s) || "disable".equals(s) || "disabled".equals(s)) {
            return false;
        }
        return def;
    }

    private int parseFirstInt(String text) {
        if (text == null) return -1;
        String[] parts = text.trim().split("\\s+");
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            try {
                int v = Integer.parseInt(p);
                if (v > 0) return v;
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    private String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }
}
