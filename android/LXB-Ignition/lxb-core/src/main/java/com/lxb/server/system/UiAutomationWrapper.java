package com.lxb.server.system;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * UiAutomation 系统层封装 (纯反射实现)
 *
 * 通过 app_process 环境访问 Android UiAutomation API，
 * 所有 Android SDK 类都通过反射访问，避免编译时依赖。
 *
 * 必须在调用前先执行 HiddenApiBypass.bypass()
 */
public class UiAutomationWrapper {

    private static final String TAG = "[LXB][UiAuto]";
    private static final int SWIPE_MIN_DURATION_MS = 1500;
    private static final String ADB_IME_STANDARD_ID = "com.android.adbkeyboard/.AdbIME";
    private static final String ADB_IME_STANDARD_PACKAGE = "com.android.adbkeyboard";
    private static final String ADB_IME_OPENATX_ID = "com.github.uiautomator/.AdbKeyboard";
    private static final String ADB_IME_OPENATX_PACKAGE = "com.github.uiautomator";
    private static volatile boolean preferShellInputTouch = true;
    private final Map<String, String> shellLabelCache = new HashMap<>();
    private final Map<String, String> shellGlobalLabelMapCache = new HashMap<>();
    private long shellGlobalLabelMapCacheTs = 0L;
    private static final long SHELL_GLOBAL_LABEL_CACHE_TTL_MS = 10 * 60 * 1000L;
    private final String externalAppLabelsPath = resolveExternalAppLabelsPath();
    private final Map<String, String> externalLabelsCache = new HashMap<>();
    private long externalLabelsLastModified = -1L;
    private final Object adbKeyboardLock = new Object();
    private long adbKeyboardStatusTs = 0L;
    private AdbKeyboardProfile adbKeyboardProfile =
            new AdbKeyboardProfile(false, "", "", "", "", "", false);

    // 反射获取的 UiAutomation 实例
    private Object uiAutomation;

    // 缓存的反射方法
    private Method injectInputEventMethod;
    private Method getRootInActiveWindowMethod;
    private Method getServiceInfoMethod;
    private Method setServiceInfoMethod;

    // MotionEvent 相关
    private Class<?> motionEventClass;
    private Method motionEventObtainMethod;
    private Method motionEventRecycleMethod;
    private Method motionEventSetSourceMethod;
    private int ACTION_DOWN;
    private int ACTION_UP;
    private int ACTION_MOVE;
    private int SOURCE_TOUCHSCREEN;

    // KeyEvent 相关
    private Class<?> keyEventClass;
    private Constructor<?> keyEventConstructor;
    private int KEY_ACTION_DOWN;
    private int KEY_ACTION_UP;
    private int KEYCODE_WAKEUP;
    private int KEYCODE_BACK;
    private int KEYCODE_HOME;
    private int KEYCODE_ENTER;
    private int KEYCODE_DEL;
    private int KEYCODE_A;
    private int KEYCODE_V;
    private int META_CTRL_ON;
    private int SOURCE_KEYBOARD;
    private int VIRTUAL_KEYBOARD;

    // 屏幕尺寸缓存
    private int screenWidth = 0;
    private int screenHeight = 0;
    private int screenDensity = 0;

    /**
     * 初始化 UiAutomation
     *
     * @throws Exception 初始化失败
     */
    public void initialize() throws Exception {
        System.out.println(TAG + " Initializing...");

        // 初始化反射常量
        initReflectionConstants();

        // 准备 Looper
        prepareLooper();

        // 获取 UiAutomation 实例
        this.uiAutomation = createUiAutomation();
        if (this.uiAutomation == null) {
            throw new RuntimeException("Failed to obtain UiAutomation instance");
        }

        // 缓存常用方法
        cacheReflectionMethods();

        // 获取屏幕信息
        fetchScreenInfo();
        refreshAdbKeyboardStatus();

        System.out.println(TAG + " Initialized successfully");
        System.out.println(TAG + " Screen: " + screenWidth + "x" + screenHeight + " @" + screenDensity + "dpi");
    }

    public void setPreferShellInputTouch(boolean preferShell) {
        preferShellInputTouch = preferShell;
        System.out.println(TAG + " touch mode set: " + (preferShell ? "shell_first" : "uiautomation_first"));
    }

    public boolean isPreferShellInputTouch() {
        return preferShellInputTouch;
    }

    private static final class AdbKeyboardProfile {
        final boolean available;
        final String imeId;
        final String packageName;
        final String variant;
        final String broadcastAction;
        final String extraKey;
        final boolean useBase64;

        AdbKeyboardProfile(
                boolean available,
                String imeId,
                String packageName,
                String variant,
                String broadcastAction,
                String extraKey,
                boolean useBase64
        ) {
            this.available = available;
            this.imeId = imeId != null ? imeId : "";
            this.packageName = packageName != null ? packageName : "";
            this.variant = variant != null ? variant : "";
            this.broadcastAction = broadcastAction != null ? broadcastAction : "";
            this.extraKey = extraKey != null ? extraKey : "";
            this.useBase64 = useBase64;
        }
    }

    /**
     * 初始化反射常量
     */
    private void initReflectionConstants() throws Exception {
        // MotionEvent 常量
        motionEventClass = Class.forName("android.view.MotionEvent");
        ACTION_DOWN = motionEventClass.getField("ACTION_DOWN").getInt(null);
        ACTION_UP = motionEventClass.getField("ACTION_UP").getInt(null);
        ACTION_MOVE = motionEventClass.getField("ACTION_MOVE").getInt(null);

        motionEventObtainMethod = motionEventClass.getMethod("obtain",
                long.class, long.class, int.class, float.class, float.class, int.class);
        motionEventRecycleMethod = motionEventClass.getMethod("recycle");
        motionEventSetSourceMethod = motionEventClass.getMethod("setSource", int.class);

        // InputDevice 常量
        Class<?> inputDeviceClass = Class.forName("android.view.InputDevice");
        SOURCE_TOUCHSCREEN = inputDeviceClass.getField("SOURCE_TOUCHSCREEN").getInt(null);
        SOURCE_KEYBOARD = inputDeviceClass.getField("SOURCE_KEYBOARD").getInt(null);

        // KeyEvent 常量
        keyEventClass = Class.forName("android.view.KeyEvent");
        KEY_ACTION_DOWN = keyEventClass.getField("ACTION_DOWN").getInt(null);
        KEY_ACTION_UP = keyEventClass.getField("ACTION_UP").getInt(null);
        KEYCODE_WAKEUP = keyEventClass.getField("KEYCODE_WAKEUP").getInt(null);
        KEYCODE_BACK = keyEventClass.getField("KEYCODE_BACK").getInt(null);
        KEYCODE_HOME = keyEventClass.getField("KEYCODE_HOME").getInt(null);
        KEYCODE_ENTER = keyEventClass.getField("KEYCODE_ENTER").getInt(null);
        KEYCODE_DEL = keyEventClass.getField("KEYCODE_DEL").getInt(null);
        KEYCODE_A = keyEventClass.getField("KEYCODE_A").getInt(null);
        KEYCODE_V = keyEventClass.getField("KEYCODE_V").getInt(null);
        META_CTRL_ON = keyEventClass.getField("META_CTRL_ON").getInt(null);

        // KeyCharacterMap 常量
        Class<?> keyCharMapClass = Class.forName("android.view.KeyCharacterMap");
        VIRTUAL_KEYBOARD = keyCharMapClass.getField("VIRTUAL_KEYBOARD").getInt(null);

        // KeyEvent 构造函数
        keyEventConstructor = keyEventClass.getConstructor(
                long.class, long.class, int.class, int.class, int.class, int.class,
                int.class, int.class, int.class, int.class);

        System.out.println(TAG + " Reflection constants initialized");
    }

    /**
     * 准备 Looper
     */
    private void prepareLooper() throws Exception {
        Class<?> looperClass = Class.forName("android.os.Looper");
        Method myLooper = looperClass.getMethod("myLooper");
        Object looper = myLooper.invoke(null);

        if (looper == null) {
            Method prepareMainLooper = looperClass.getMethod("prepareMainLooper");
            prepareMainLooper.invoke(null);
            System.out.println(TAG + " Looper prepared");
        }
    }

    /**
     * 创建 UiAutomation 实例
     *
     * 关键点 (来自 Android 源码分析):
     * 1. 必须先有 Looper (prepareLooper 已处理)
     * 2. UiAutomation 构造函数是 @hide 的，必须用反射
     * 3. 构造后必须调用 connect() 方法
     * 4. 不同 Android 版本构造函数签名不同:
     *    - Android 7.x: UiAutomation(Looper, IUiAutomationConnection)
     *    - Android 8.0+: UiAutomation(Looper, IUiAutomationConnection, int flags)
     */
    private Object createUiAutomation() throws Exception {
        // 方法 1: 直接通过反射创建 UiAutomation (最可靠的方式)
        Object ua = createUiAutomationDirect();
        if (ua != null) {
            return ua;
        }

        // 方法 2: 通过 Instrumentation 获取 (需要正确的 ActivityThread 上下文)
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method systemMain = activityThread.getMethod("systemMain");
            Object thread = systemMain.invoke(null);

            Field instrumentationField = activityThread.getDeclaredField("mInstrumentation");
            instrumentationField.setAccessible(true);
            Object instrumentation = instrumentationField.get(thread);

            if (instrumentation != null) {
                // 尝试带 flags 的版本 (Android 7.0+)
                try {
                    Method getUiAutomation = instrumentation.getClass().getMethod("getUiAutomation", int.class);
                    // FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES = 1
                    ua = getUiAutomation.invoke(instrumentation, 1);
                } catch (NoSuchMethodException e) {
                    Method getUiAutomation = instrumentation.getClass().getMethod("getUiAutomation");
                    ua = getUiAutomation.invoke(instrumentation);
                }
                if (ua != null) {
                    System.out.println(TAG + " Got UiAutomation via Instrumentation");
                    return ua;
                }
            }
        } catch (Exception e) {
            System.err.println(TAG + " Instrumentation method failed: " + e.getMessage());
        }

        // 方法 3: 通过 ShellCommand 模拟 (使用 dumpsys 作为后备)
        System.err.println(TAG + " All UiAutomation methods failed, will use shell fallback for UI operations");
        return null;
    }

    /**
     * 直接通过反射创建 UiAutomation 实例
     * 这是最可靠的方式，不依赖 Instrumentation
     */
    private Object createUiAutomationDirect() {
        try {
            System.out.println(TAG + " Trying direct UiAutomation creation...");

            // 获取必要的类
            Class<?> uiAutoClass = Class.forName("android.app.UiAutomation");
            Class<?> looperClass = Class.forName("android.os.Looper");
            Class<?> handlerThreadClass = Class.forName("android.os.HandlerThread");

            // 创建 HandlerThread 用于 UiAutomation 回调
            Constructor<?> handlerThreadCtor = handlerThreadClass.getConstructor(String.class);
            Object handlerThread = handlerThreadCtor.newInstance("UiAutomation");
            Method start = handlerThreadClass.getMethod("start");
            start.invoke(handlerThread);

            Method getLooper = handlerThreadClass.getMethod("getLooper");
            Object looper = getLooper.invoke(handlerThread);
            System.out.println(TAG + " HandlerThread looper ready");

            // 获取 UiAutomationConnection
            Object connection = getUiAutomationConnection();
            if (connection == null) {
                System.err.println(TAG + " Failed to get UiAutomationConnection");
                return null;
            }

            // 尝试不同的构造函数签名
            Object ua = null;
            Class<?> connClass = Class.forName("android.app.IUiAutomationConnection");

            // Android 8.0+ (API 26+): UiAutomation(Looper, IUiAutomationConnection, int)
            try {
                Constructor<?> ctor3 = uiAutoClass.getDeclaredConstructor(looperClass, connClass, int.class);
                ctor3.setAccessible(true);
                // flags = 1 (FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                ua = ctor3.newInstance(looper, connection, 1);
                System.out.println(TAG + " UiAutomation created (3-arg constructor)");
            } catch (NoSuchMethodException e) {
                // Android 7.x: UiAutomation(Looper, IUiAutomationConnection)
                try {
                    Constructor<?> ctor2 = uiAutoClass.getDeclaredConstructor(looperClass, connClass);
                    ctor2.setAccessible(true);
                    ua = ctor2.newInstance(looper, connection);
                    System.out.println(TAG + " UiAutomation created (2-arg constructor)");
                } catch (NoSuchMethodException e2) {
                    System.err.println(TAG + " No suitable UiAutomation constructor found");
                    return null;
                }
            }

            if (ua == null) {
                return null;
            }

            // 关键步骤: 调用 connect() 方法！
            try {
                Method connect = uiAutoClass.getDeclaredMethod("connect");
                connect.setAccessible(true);
                connect.invoke(ua);
                System.out.println(TAG + " UiAutomation.connect() called");
            } catch (NoSuchMethodException e) {
                // 某些版本可能没有 connect 方法，尝试其他方式
                System.out.println(TAG + " No connect() method, trying alternative...");
            }

            System.out.println(TAG + " Got UiAutomation via direct creation");
            return ua;

        } catch (Exception e) {
            System.err.println(TAG + " Direct UiAutomation creation failed: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 获取 UiAutomationConnection
     * 通过 ActivityManagerService 获取真正的连接
     */
    private Object getUiAutomationConnection() {
        // 方法 1: 通过 ServiceManager 获取已注册的服务
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getMethod("getService", String.class);
            Object binder = getService.invoke(null, "uiautomation");

            if (binder != null) {
                Class<?> stubClass = Class.forName("android.app.IUiAutomationConnection$Stub");
                Method asInterface = stubClass.getMethod("asInterface", Class.forName("android.os.IBinder"));
                Object connection = asInterface.invoke(null, binder);
                if (connection != null) {
                    System.out.println(TAG + " Got UiAutomationConnection via ServiceManager");
                    return connection;
                }
            }
        } catch (Exception e) {
            System.err.println(TAG + " ServiceManager method failed: " + e.getMessage());
        }

        // 方法 2: 创建 UiAutomationConnection 实例
        try {
            Class<?> connClass = Class.forName("android.app.UiAutomationConnection");
            Constructor<?> ctor = connClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object connection = ctor.newInstance();
            System.out.println(TAG + " Created new UiAutomationConnection instance");
            return connection;
        } catch (Exception e) {
            System.err.println(TAG + " UiAutomationConnection creation failed: " + e.getMessage());
        }

        return null;
    }

    /**
     * 缓存反射方法
     */
    private void cacheReflectionMethods() throws Exception {
        Class<?> uiAutoClass = uiAutomation.getClass();
        Class<?> inputEventClass = Class.forName("android.view.InputEvent");

        injectInputEventMethod = uiAutoClass.getMethod("injectInputEvent", inputEventClass, boolean.class);
        System.out.println(TAG + " Cached: injectInputEvent");

        getRootInActiveWindowMethod = uiAutoClass.getMethod("getRootInActiveWindow");
        System.out.println(TAG + " Cached: getRootInActiveWindow");

        try {
            getServiceInfoMethod = uiAutoClass.getMethod("getServiceInfo");
            setServiceInfoMethod = uiAutoClass.getMethod("setServiceInfo",
                    Class.forName("android.accessibilityservice.AccessibilityServiceInfo"));
            System.out.println(TAG + " Cached: getServiceInfo/setServiceInfo");
        } catch (NoSuchMethodException e) {
            // 某些版本可能没有这些方法
            System.out.println(TAG + " getServiceInfo/setServiceInfo not available");
        }

        System.out.println(TAG + " Reflection methods cached successfully");
    }

    /**
     * 获取屏幕信息
     */
    private void fetchScreenInfo() {
        try {
            Process process = Runtime.getRuntime().exec("wm size");
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(TAG + " wm size output: " + line);
                // 支持多种格式: "Physical size: 1080x1920" 或 "Override size: 1080x1920"
                if (line.contains("x")) {
                    // 找到数字x数字的模式
                    String[] parts = line.split("\\s+");
                    for (String part : parts) {
                        if (part.matches("\\d+x\\d+")) {
                            String[] dims = part.split("x");
                            screenWidth = Integer.parseInt(dims[0]);
                            screenHeight = Integer.parseInt(dims[1]);
                            break;
                        }
                    }
                }
            }
            reader.close();
            process.waitFor();

            process = Runtime.getRuntime().exec("wm density");
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while ((line = reader.readLine()) != null) {
                System.out.println(TAG + " wm density output: " + line);
                // 支持: "Physical density: 480" 或 "Override density: 480"
                String[] parts = line.split("\\s+");
                for (String part : parts) {
                    if (part.matches("\\d+")) {
                        screenDensity = Integer.parseInt(part);
                    }
                }
            }
            reader.close();
            process.waitFor();

        } catch (Exception e) {
            System.err.println(TAG + " Failed to get screen info: " + e.getMessage());
            e.printStackTrace();
        }

        // 如果解析失败，使用默认值
        if (screenWidth == 0 || screenHeight == 0) {
            System.out.println(TAG + " Using default screen size");
            screenWidth = 1080;
            screenHeight = 2400;
            screenDensity = 440;
        }
    }

    /**
     * 获取系统时间 (毫秒)
     */
    private long uptimeMillis() {
        try {
            Class<?> systemClock = Class.forName("android.os.SystemClock");
            Method uptimeMillis = systemClock.getMethod("uptimeMillis");
            return (Long) uptimeMillis.invoke(null);
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    // =========================================================================
    // 输入注入
    // =========================================================================

    /**
     * 点击屏幕
     */
    public boolean click(int x, int y) {
        // Respect configured touch mode: shell-first or UiAutomation-first.
        if (preferShellInputTouch) {
            if (clickViaShell(x, y)) {
                return true;
            }
        }

// 优先使用 UiAutomation
        if (injectInputEventMethod != null && uiAutomation != null) {
            try {
                long downTime = uptimeMillis();

                Object down = motionEventObtainMethod.invoke(null,
                        downTime, downTime, ACTION_DOWN, (float) x, (float) y, 0);
                motionEventSetSourceMethod.invoke(down, SOURCE_TOUCHSCREEN);

                Object up = motionEventObtainMethod.invoke(null,
                        downTime, downTime + 50, ACTION_UP, (float) x, (float) y, 0);
                motionEventSetSourceMethod.invoke(up, SOURCE_TOUCHSCREEN);

                boolean result = (Boolean) injectInputEventMethod.invoke(uiAutomation, down, true) &&
                        (Boolean) injectInputEventMethod.invoke(uiAutomation, up, true);

                motionEventRecycleMethod.invoke(down);
                motionEventRecycleMethod.invoke(up);

                if (result) {
                    System.out.println(TAG + " Click (" + x + ", " + y + "): OK (UiAutomation)");
                    return true;
                }
            } catch (Exception e) {
                System.err.println(TAG + " UiAutomation click failed: " + e.getMessage());
            }
        }

        // 后备方案：使用 input 命令
        return clickViaShell(x, y);
    }

    /**
     * 通过 shell 命令点击（后备方案）
     */
    private boolean clickViaShell(int x, int y) {
        try {
            Process process = Runtime.getRuntime().exec("input tap " + x + " " + y);
            int exitCode = process.waitFor();
            boolean result = exitCode == 0;
            System.out.println(TAG + " Click (" + x + ", " + y + "): " + (result ? "OK" : "FAIL") + " (shell)");
            return result;
        } catch (Exception e) {
            System.err.println(TAG + " Shell click failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 滑动手势
     */
    public boolean swipe(int x1, int y1, int x2, int y2, int duration) {
        int normalizedDuration = Math.max(duration, SWIPE_MIN_DURATION_MS);
        if (normalizedDuration != duration) {
            System.out.println(TAG + " Swipe duration clamped to min " + SWIPE_MIN_DURATION_MS +
                    "ms (requested=" + duration + "ms)");
        }
        // Respect configured touch mode: shell-first or UiAutomation-first.
        if (preferShellInputTouch) {
            if (swipeViaShell(x1, y1, x2, y2, normalizedDuration)) {
                return true;
            }
        }

// 优先使用 UiAutomation
        if (injectInputEventMethod != null && uiAutomation != null) {
            try {
                long downTime = uptimeMillis();
                int steps = Math.max(2, normalizedDuration / 16);

                Object down = motionEventObtainMethod.invoke(null,
                        downTime, downTime, ACTION_DOWN, (float) x1, (float) y1, 0);
                motionEventSetSourceMethod.invoke(down, SOURCE_TOUCHSCREEN);

                if (!(Boolean) injectInputEventMethod.invoke(uiAutomation, down, true)) {
                    motionEventRecycleMethod.invoke(down);
                    // 尝试后备方案
                    return swipeViaShell(x1, y1, x2, y2, normalizedDuration);
                }
                motionEventRecycleMethod.invoke(down);

                for (int i = 1; i < steps; i++) {
                    float t = (float) i / steps;
                    int x = (int) (x1 + (x2 - x1) * t);
                    int y = (int) (y1 + (y2 - y1) * t);
                    long eventTime = downTime + (long) (normalizedDuration * t);

                    Object move = motionEventObtainMethod.invoke(null,
                            downTime, eventTime, ACTION_MOVE, (float) x, (float) y, 0);
                    motionEventSetSourceMethod.invoke(move, SOURCE_TOUCHSCREEN);
                    injectInputEventMethod.invoke(uiAutomation, move, true);
                    motionEventRecycleMethod.invoke(move);

                    Thread.sleep(normalizedDuration / steps);
                }

                Object up = motionEventObtainMethod.invoke(null,
                        downTime, downTime + normalizedDuration, ACTION_UP, (float) x2, (float) y2, 0);
                motionEventSetSourceMethod.invoke(up, SOURCE_TOUCHSCREEN);
                boolean result = (Boolean) injectInputEventMethod.invoke(uiAutomation, up, true);
                motionEventRecycleMethod.invoke(up);

                if (result) {
                    System.out.println(TAG + " Swipe: OK (UiAutomation)");
                    return true;
                }
            } catch (Exception e) {
                System.err.println(TAG + " UiAutomation swipe failed: " + e.getMessage());
            }
        }

        // 后备方案
        return swipeViaShell(x1, y1, x2, y2, normalizedDuration);
    }

    /**
     * 通过 shell 命令滑动（后备方案）
     */
    private boolean swipeViaShell(int x1, int y1, int x2, int y2, int duration) {
        try {
            String cmd = "input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + duration;
            Process process = Runtime.getRuntime().exec(cmd);
            int exitCode = process.waitFor();
            boolean result = exitCode == 0;
            System.out.println(TAG + " Swipe: " + (result ? "OK" : "FAIL") + " (shell)");
            return result;
        } catch (Exception e) {
            System.err.println(TAG + " Shell swipe failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 长按
     */
    public boolean longPress(int x, int y, int duration) {
        // Respect configured touch mode: shell-first or UiAutomation-first.
        if (preferShellInputTouch) {
            if (longPressViaShell(x, y, duration)) {
                return true;
            }
        }

// 优先使用 UiAutomation
        if (injectInputEventMethod != null && uiAutomation != null) {
            try {
                long downTime = uptimeMillis();

                Object down = motionEventObtainMethod.invoke(null,
                        downTime, downTime, ACTION_DOWN, (float) x, (float) y, 0);
                motionEventSetSourceMethod.invoke(down, SOURCE_TOUCHSCREEN);

                if (!(Boolean) injectInputEventMethod.invoke(uiAutomation, down, true)) {
                    motionEventRecycleMethod.invoke(down);
                    return longPressViaShell(x, y, duration);
                }
                motionEventRecycleMethod.invoke(down);

                Thread.sleep(duration);

                Object up = motionEventObtainMethod.invoke(null,
                        downTime, downTime + duration, ACTION_UP, (float) x, (float) y, 0);
                motionEventSetSourceMethod.invoke(up, SOURCE_TOUCHSCREEN);
                boolean result = (Boolean) injectInputEventMethod.invoke(uiAutomation, up, true);
                motionEventRecycleMethod.invoke(up);

                if (result) {
                    System.out.println(TAG + " LongPress (" + x + ", " + y + ", " + duration + "ms): OK (UiAutomation)");
                    return true;
                }
            } catch (Exception e) {
                System.err.println(TAG + " UiAutomation longPress failed: " + e.getMessage());
            }
        }

        return longPressViaShell(x, y, duration);
    }

    /**
     * 通过 shell 命令长按（后备方案）
     */
    private boolean longPressViaShell(int x, int y, int duration) {
        try {
            // input swipe with same start/end point = long press
            String cmd = "input swipe " + x + " " + y + " " + x + " " + y + " " + duration;
            Process process = Runtime.getRuntime().exec(cmd);
            int exitCode = process.waitFor();
            boolean result = exitCode == 0;
            System.out.println(TAG + " LongPress (" + x + ", " + y + ", " + duration + "ms): " + (result ? "OK" : "FAIL") + " (shell)");
            return result;
        } catch (Exception e) {
            System.err.println(TAG + " Shell longPress failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 注入按键事件
     *
     * @param keycode Android KeyEvent 码
     * @param action 动作: 0=DOWN, 1=UP, 2=CLICK
     * @param metaState 修饰键状态
     * @return 是否成功
     */
    public boolean injectKeyEvent(int keycode, int action, int metaState) {
        // 优先使用 UiAutomation
        if (injectInputEventMethod != null && uiAutomation != null && keyEventConstructor != null) {
            try {
                long now = uptimeMillis();

                if (action == 2) {  // CLICK = DOWN + UP
                    Object down = keyEventConstructor.newInstance(
                            now, now, KEY_ACTION_DOWN, keycode, 0, metaState,
                            VIRTUAL_KEYBOARD, 0, 0, SOURCE_KEYBOARD);
                    Object up = keyEventConstructor.newInstance(
                            now, now + 50, KEY_ACTION_UP, keycode, 0, metaState,
                            VIRTUAL_KEYBOARD, 0, 0, SOURCE_KEYBOARD);
                    boolean result = (Boolean) injectInputEventMethod.invoke(uiAutomation, down, true) &&
                            (Boolean) injectInputEventMethod.invoke(uiAutomation, up, true);
                    if (result) {
                        System.out.println(TAG + " KeyEvent " + keycode + ": OK (UiAutomation)");
                        return true;
                    }
                } else {
                    Object event = keyEventConstructor.newInstance(
                            now, now, action, keycode, 0, metaState,
                            VIRTUAL_KEYBOARD, 0, 0, SOURCE_KEYBOARD);
                    boolean result = (Boolean) injectInputEventMethod.invoke(uiAutomation, event, true);
                    if (result) {
                        System.out.println(TAG + " KeyEvent " + keycode + ": OK (UiAutomation)");
                        return true;
                    }
                }
            } catch (Exception e) {
                System.err.println(TAG + " UiAutomation KeyEvent failed: " + e.getMessage());
            }
        }

        // 后备方案：使用 input keyevent 命令
        return injectKeyViaShell(keycode);
    }

    /**
     * 通过 Shell 注入按键 (降级方案)
     */
    private boolean injectKeyViaShell(int keycode) {
        try {
            Process process = Runtime.getRuntime().exec("input keyevent " + keycode);
            int exitCode = process.waitFor();
            boolean result = exitCode == 0;
            System.out.println(TAG + " KeyEvent " + keycode + ": " + (result ? "OK" : "FAIL") + " (shell)");
            return result;
        } catch (Exception e) {
            System.err.println(TAG + " Shell KeyEvent failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 按下指定按键
     */
    public boolean pressKey(int keycode) {
        return injectKeyEvent(keycode, 2, 0);
    }

    // =========================================================================
    // UI 感知
    // =========================================================================

    /**
     * 获取根节点
     *
     * @return AccessibilityNodeInfo 对象，失败返回 null
     */
    public Object getRootNode() {
        if (uiAutomation == null) {
            System.err.println(TAG + " getRootNode failed: uiAutomation is null");
            return null;
        }
        if (getRootInActiveWindowMethod == null) {
            System.err.println(TAG + " getRootNode failed: getRootInActiveWindowMethod is null");
            return null;
        }
        try {
            return getRootInActiveWindowMethod.invoke(uiAutomation);
        } catch (Exception e) {
            System.err.println(TAG + " getRootNode failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 获取当前 Activity
     *
     * @return [packageName, activityName]
     */
    public String[] getCurrentActivity() {
        String packageName = "";
        String activityName = "";

        // 方法1: 从 getRootNode 获取包名
        try {
            Object root = getRootNode();
            if (root != null) {
                Method getPackageName = root.getClass().getMethod("getPackageName");
                Object pkgName = getPackageName.invoke(root);
                if (pkgName != null) {
                    packageName = pkgName.toString();
                }
            }
        } catch (Exception e) {
            System.err.println(TAG + " getPackageName failed: " + e.getMessage());
        }

        // 方法2: 从 dumpsys activity 获取完整信息
        try {
            Process process = Runtime.getRuntime().exec("dumpsys activity activities");
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                // 查找 "mResumedActivity" 或 "topResumedActivity" 或 "mFocusedActivity"
                if (line.contains("mResumedActivity") ||
                    line.contains("topResumedActivity") ||
                    line.contains("mFocusedActivity")) {

                    System.out.println(TAG + " Activity line: " + line);

                    // 格式: "mResumedActivity: ActivityRecord{xxx u0 com.example/.MainActivity t123}"
                    int startIdx = line.indexOf("u0 ");
                    if (startIdx < 0) startIdx = line.indexOf("u10 ");
                    if (startIdx > 0) {
                        startIdx += 3;
                        int endIdx = line.indexOf(" ", startIdx);
                        if (endIdx < 0) endIdx = line.indexOf("}", startIdx);
                        if (endIdx > startIdx) {
                            String fullName = line.substring(startIdx, endIdx).trim();
                            int slashIdx = fullName.indexOf('/');
                            if (slashIdx > 0) {
                                packageName = fullName.substring(0, slashIdx);
                                activityName = fullName.substring(slashIdx);
                            }
                            break;
                        }
                    }
                }
            }
            reader.close();
            process.waitFor();

        } catch (Exception e) {
            System.err.println(TAG + " getActivity failed: " + e.getMessage());
        }

        // 如果上面失败，尝试旧方法
        if (activityName.isEmpty()) {
            try {
                Process process = Runtime.getRuntime().exec("dumpsys window windows");
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("mCurrentFocus") || line.contains("mFocusedApp")) {
                        System.out.println(TAG + " Window line: " + line);
                        int slashIdx = line.indexOf('/');
                        int braceIdx = line.lastIndexOf('}');
                        if (slashIdx > 0 && braceIdx > slashIdx) {
                            // 尝试提取包名
                            int spaceIdx = line.lastIndexOf(' ', slashIdx);
                            if (spaceIdx > 0 && spaceIdx < slashIdx) {
                                packageName = line.substring(spaceIdx + 1, slashIdx);
                            }
                            activityName = line.substring(slashIdx, braceIdx);
                            break;
                        }
                    }
                }
                reader.close();
                process.waitFor();

            } catch (Exception e) {
                System.err.println(TAG + " getActivity (fallback) failed: " + e.getMessage());
            }
        }

        System.out.println(TAG + " getCurrentActivity: " + packageName + " / " + activityName);
        return new String[]{packageName, activityName};
    }

    // =========================================================================
    // 屏幕状态 (新增指令)
    // =========================================================================

    /**
     * 获取屏幕状态
     *
     * @return 0=灭屏, 1=亮屏未锁定, 2=亮屏已锁定
     */
    public int getScreenState() {
        try {
            Process process = Runtime.getRuntime().exec("dumpsys power");
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            boolean screenOn = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("mWakefulness=") || line.contains("Display Power: state=")) {
                    screenOn = line.contains("Awake") || line.contains("ON");
                    break;
                }
            }
            reader.close();

            if (!screenOn) {
                return 0;  // SCREEN_STATE_OFF
            }

            process = Runtime.getRuntime().exec("dumpsys window policy");
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            boolean locked = false;
            while ((line = reader.readLine()) != null) {
                if (line.contains("mShowingLockscreen=true") ||
                        line.contains("mDreamingLockscreen=true") ||
                        line.contains("isKeyguardShowing=true")) {
                    locked = true;
                    break;
                }
            }
            reader.close();

            return locked ? 2 : 1;

        } catch (Exception e) {
            System.err.println(TAG + " getScreenState failed: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 获取屏幕尺寸 (实时获取)
     *
     * @return [width, height, density]
     */
    public int[] getScreenSize() {
        // 如果缓存值为0，重新获取
        if (screenWidth == 0 || screenHeight == 0) {
            fetchScreenInfoRealtime();
        }
        return new int[]{screenWidth, screenHeight, screenDensity};
    }

    /**
     * 实时获取屏幕信息
     */
    private void fetchScreenInfoRealtime() {
        try {
            // 获取尺寸
            Process process = Runtime.getRuntime().exec(new String[]{"wm", "size"});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(TAG + " wm size: " + line);
                // 匹配 "Physical size: 1280x2772" 或 "Override size: ..."
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("(\\d+)x(\\d+)").matcher(line);
                if (m.find()) {
                    screenWidth = Integer.parseInt(m.group(1));
                    screenHeight = Integer.parseInt(m.group(2));
                }
            }
            reader.close();
            process.waitFor();

            // 获取密度
            process = Runtime.getRuntime().exec(new String[]{"wm", "density"});
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while ((line = reader.readLine()) != null) {
                System.out.println(TAG + " wm density: " + line);
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("(\\d+)").matcher(line);
                if (m.find()) {
                    screenDensity = Integer.parseInt(m.group(1));
                }
            }
            reader.close();
            process.waitFor();

            System.out.println(TAG + " Screen: " + screenWidth + "x" + screenHeight + " @" + screenDensity + "dpi");

        } catch (Exception e) {
            System.err.println(TAG + " fetchScreenInfoRealtime failed: " + e.getMessage());
            // 使用默认值
            if (screenWidth == 0) screenWidth = 1080;
            if (screenHeight == 0) screenHeight = 2400;
            if (screenDensity == 0) screenDensity = 440;
        }
    }

    // =========================================================================
    // 应用控制 (新增指令)
    // =========================================================================

    /**
     * 启动应用
     *
     * @param packageName 包名
     * @param flags 启动标志 (bit0=清除任务, bit1=等待)
     * @return 是否成功
     */
    public boolean launchApp(String packageName, int flags) {
        try {
            StringBuilder cmd = new StringBuilder();

            String launchActivity = getLaunchActivity(packageName);
            if (launchActivity == null || launchActivity.isEmpty()) {
                System.err.println(TAG + " launchApp failed: cannot resolve launch activity for " + packageName);
                return false;
            }

            // Always stop before start to avoid stale task-stack state.
            Process stopProcess = Runtime.getRuntime().exec("am force-stop " + packageName);
            int stopExitCode = stopProcess.waitFor();
            if (stopExitCode != 0) {
                System.err.println(TAG + " launchApp failed: force-stop exitCode=" + stopExitCode
                        + ", package=" + packageName);
                return false;
            }
            Thread.sleep(1500);

            cmd.append("am start -n ").append(packageName).append("/").append(launchActivity);
            if ((flags & 0x01) != 0) {
                cmd.append(" --activity-clear-task");
            }

            Process process = Runtime.getRuntime().exec(cmd.toString());
            int exitCode = process.waitFor();

            if ((flags & 0x02) != 0) {
                Thread.sleep(500);
            }
            if (exitCode != 0) {
                System.err.println(TAG + " launchApp failed: exitCode=" + exitCode + ", cmd=" + cmd);
                return false;
            }

            System.out.println(TAG + " launchApp: " + packageName);
            return true;

        } catch (Exception e) {
            System.err.println(TAG + " launchApp failed: " + e.getMessage());
            return false;
        }
    }

    private String getLaunchActivity(String packageName) {
        try {
            Process process = Runtime.getRuntime().exec(
                    "cmd package resolve-activity --brief " + packageName);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("/")) {
                    String[] parts = line.split("/");
                    if (parts.length == 2) {
                        return parts[1];
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    /**
     * 强制停止应用
     */
    public boolean stopApp(String packageName) {
        try {
            Process process = Runtime.getRuntime().exec("am force-stop " + packageName);
            int result = process.waitFor();
            System.out.println(TAG + " stopApp: " + packageName);
            return result == 0;
        } catch (Exception e) {
            System.err.println(TAG + " stopApp failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取已安装应用列表
     *
     * @param filter 过滤器: 0=all, 1=user, 2=system
     * @return 应用列表，格式为 JSON 数组字符串
     */
    public String listApps(int filter) {
        // Snapshot-only mode: always use APK-provided labels snapshot.
        String external = listAppsWithExternalLabels(filter);
        if (external != null && external.length() > 2) {
            return external;
        }
        String fallback = listAppsWithPackageOnly(filter);
        if (fallback != null && fallback.length() > 2) {
            System.err.println(TAG + " listApps snapshot unavailable; using package-only fallback");
            return fallback;
        }
        System.err.println(TAG + " listApps snapshot unavailable; fallback empty; returning []");
        return "[]";
    }

    private static String resolveExternalAppLabelsPath() {
        String p = System.getProperty("lxb.app.labels.path");
        return p != null ? p.trim() : "";
    }

    private String listAppsWithExternalLabels(int filter) {
        try {
            Map<String, String> labels = loadExternalLabelsFromFile();
            if (labels == null || labels.isEmpty()) {
                return null;
            }
            List<String> packages = listPackagesByShell(filter);
            if (packages.isEmpty()) {
                return null;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            int labeled = 0;
            for (String pkg : packages) {
                if (pkg == null || pkg.isEmpty()) continue;
                String label = labels.get(pkg);
                String norm = label != null ? label.trim() : "";
                if (!norm.isEmpty()) labeled++;
                if (!first) sb.append(",");
                first = false;
                appendAppJsonRow(sb, pkg, norm.isEmpty() ? null : norm);
            }
            sb.append("]");
            System.out.println(TAG + " listAppsWithExternalLabels: filter=" + filter
                    + " total=" + packages.size() + " labeled=" + labeled
                    + " path=" + externalAppLabelsPath);
            return sb.toString();
        } catch (Exception e) {
            System.err.println(TAG + " listAppsWithExternalLabels failed: " + e.getMessage());
            return null;
        }
    }

    private Map<String, String> loadExternalLabelsFromFile() {
        if (externalAppLabelsPath == null || externalAppLabelsPath.isEmpty()) {
            return null;
        }
        File f = new File(externalAppLabelsPath);
        if (!f.exists() || !f.isFile() || f.length() <= 0) {
            return null;
        }

        long lm = f.lastModified();
        synchronized (externalLabelsCache) {
            if (externalLabelsLastModified == lm && !externalLabelsCache.isEmpty()) {
                return new HashMap<>(externalLabelsCache);
            }
        }

        Map<String, String> out = new HashMap<>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                String s = line != null ? line.trim() : "";
                if (s.isEmpty()) continue;
                int tab = s.indexOf('\t');
                if (tab <= 0 || tab >= s.length() - 1) continue;
                String pkg = s.substring(0, tab).trim();
                String label = s.substring(tab + 1).trim();
                if (pkg.isEmpty() || label.isEmpty()) continue;
                out.put(pkg, label);
            }
        } catch (Exception e) {
            System.err.println(TAG + " loadExternalLabelsFromFile failed: " + e.getMessage());
            return null;
        } finally {
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        }

        synchronized (externalLabelsCache) {
            externalLabelsCache.clear();
            externalLabelsCache.putAll(out);
            externalLabelsLastModified = lm;
            return new HashMap<>(externalLabelsCache);
        }
    }

    private String listAppsWithPackageOnly(int filter) {
        try {
            List<String> packages = listPackagesByShell(filter);
            if (packages.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            for (String pkg : packages) {
                if (pkg == null || pkg.isEmpty()) continue;
                if (!first) sb.append(",");
                first = false;
                appendAppJsonRow(sb, pkg, null);
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            System.err.println(TAG + " listAppsWithPackageOnly failed: " + e.getMessage());
            return null;
        }
    }

    private void appendAppJsonRow(StringBuilder sb, String pkg, String label) {
        sb.append("{\"package\":\"")
                .append(jsonEscape(pkg))
                .append("\",\"label\":");
        appendNullableJsonString(sb, label);
        sb.append(",\"name\":");
        appendNullableJsonString(sb, label);
        sb.append("}");
    }

    private void appendNullableJsonString(StringBuilder sb, String value) {
        String v = value != null ? value.trim() : "";
        if (v.isEmpty()) {
            sb.append("null");
            return;
        }
        sb.append("\"").append(jsonEscape(v)).append("\"");
    }

    private String listAppsWithLabels(int filter) {
        try {
            System.out.println(TAG + " listAppsWithLabels: begin filter=" + filter);
            Object pm = resolvePackageManagerForLabels();
            if (pm == null) {
                System.err.println(TAG + " listAppsWithLabels: PackageManager unavailable");
                String shellRich = listAppsWithLabelsViaShell(filter);
                if (shellRich != null && shellRich.length() > 2) {
                    System.out.println(TAG + " listAppsWithLabels: using viaShell (pm unavailable)");
                    return shellRich;
                }
                return null;
            }

            String rich = listAppsWithLabelsViaInstalledApplications(pm, filter);
            if (rich != null && rich.length() > 2) {
                System.out.println(TAG + " listAppsWithLabels: using viaApplications");
                return rich;
            }

            // Slow fallback: iterate installed packages and derive labels one by one.
            rich = listAppsWithLabelsViaInstalledPackages(pm, filter);
            if (rich != null && rich.length() > 2) {
                System.out.println(TAG + " listAppsWithLabels: using viaPackages");
                return rich;
            }

            System.err.println(TAG + " listAppsWithLabels: no labeled result from all strategies");
            String shellRich = listAppsWithLabelsViaShell(filter);
            if (shellRich != null && shellRich.length() > 2) {
                System.out.println(TAG + " listAppsWithLabels: using viaShell (strategies exhausted)");
                return shellRich;
            }
            return null;
        } catch (Exception e) {
            System.err.println(TAG + " listAppsWithLabels failed: " + e.getMessage());
            return null;
        }
    }

    private String listAppsWithLabelsViaInstalledApplications(Object pm, int filter) {
        try {
            Method getInstalledApplications = pm.getClass().getMethod("getInstalledApplications", int.class);
            @SuppressWarnings("unchecked")
            List<Object> appInfos = (List<Object>) getInstalledApplications.invoke(pm, 0);
            if (appInfos == null) return null;

            Class<?> appInfoClass = Class.forName("android.content.pm.ApplicationInfo");
            Field packageNameField = appInfoClass.getField("packageName");
            Field flagsField = appInfoClass.getField("flags");
            int flagSystem = appInfoClass.getField("FLAG_SYSTEM").getInt(null);
            Method loadLabel = appInfoClass.getMethod("loadLabel", Class.forName("android.content.pm.PackageManager"));
            Method getApplicationLabel = null;
            try {
                getApplicationLabel = pm.getClass().getMethod("getApplicationLabel", appInfoClass);
            } catch (Exception ignored) {
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            int kept = 0;
            int labeled = 0;

            for (Object appInfo : appInfos) {
                String packageName = (String) packageNameField.get(appInfo);
                int flags = flagsField.getInt(appInfo);
                boolean isSystem = (flags & flagSystem) != 0;
                if (filter == 1 && isSystem) continue;
                if (filter == 2 && !isSystem) continue;

                String appName = "";
                try {
                    if (getApplicationLabel != null) {
                        Object labelObj = getApplicationLabel.invoke(pm, appInfo);
                        if (labelObj != null) appName = labelObj.toString();
                    }
                } catch (Exception ignored) {
                }
                if (appName == null || appName.trim().isEmpty()) {
                    try {
                        Object labelObj = loadLabel.invoke(appInfo, pm);
                        if (labelObj != null) appName = labelObj.toString();
                    } catch (Exception ignored) {
                    }
                }
                if (appName == null || appName.trim().isEmpty()) {
                    appName = resolveLauncherActivityLabel(pm, packageName);
                }
                if (appName == null) appName = "";
                if (!appName.trim().isEmpty()) labeled++;

                if (!first) sb.append(",");
                first = false;
                kept++;
                sb.append("{\"package\":\"")
                        .append(jsonEscape(packageName))
                        .append("\",\"name\":\"")
                        .append(jsonEscape(appName))
                        .append("\"}");
            }

            sb.append("]");
            System.out.println(TAG + " listAppsWithLabels(viaApplications): filter=" + filter
                    + " total=" + appInfos.size() + " kept=" + kept + " labeled=" + labeled);
            return sb.toString();
        } catch (Exception e) {
            System.err.println(TAG + " listAppsWithLabels(viaApplications) failed: " + e.getMessage());
            return null;
        }
    }

    private String listAppsWithLabelsViaInstalledPackages(Object pm, int filter) {
        try {
            Method getInstalledPackages = pm.getClass().getMethod("getInstalledPackages", int.class);
            @SuppressWarnings("unchecked")
            List<Object> packageInfos = (List<Object>) getInstalledPackages.invoke(pm, 0);
            if (packageInfos == null) return null;

            Class<?> pkgInfoClass = Class.forName("android.content.pm.PackageInfo");
            Field packageNameField = pkgInfoClass.getField("packageName");
            Field applicationInfoField = pkgInfoClass.getField("applicationInfo");

            Class<?> appInfoClass = Class.forName("android.content.pm.ApplicationInfo");
            Field flagsField = appInfoClass.getField("flags");
            int flagSystem = appInfoClass.getField("FLAG_SYSTEM").getInt(null);
            Method loadLabel = appInfoClass.getMethod("loadLabel", Class.forName("android.content.pm.PackageManager"));

            Method getApplicationLabel = null;
            try {
                getApplicationLabel = pm.getClass().getMethod("getApplicationLabel", appInfoClass);
            } catch (Exception ignored) {
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            int kept = 0;
            int labeled = 0;

            for (Object pkgInfo : packageInfos) {
                if (pkgInfo == null) continue;
                String packageName = (String) packageNameField.get(pkgInfo);
                Object appInfo = applicationInfoField.get(pkgInfo);
                if (appInfo == null) continue;

                int flags = flagsField.getInt(appInfo);
                boolean isSystem = (flags & flagSystem) != 0;
                if (filter == 1 && isSystem) continue;
                if (filter == 2 && !isSystem) continue;

                String appName = "";
                try {
                    if (getApplicationLabel != null) {
                        Object labelObj = getApplicationLabel.invoke(pm, appInfo);
                        if (labelObj != null) appName = labelObj.toString();
                    }
                } catch (Exception ignored) {
                }
                if (appName == null || appName.trim().isEmpty()) {
                    try {
                        Object labelObj = loadLabel.invoke(appInfo, pm);
                        if (labelObj != null) appName = labelObj.toString();
                    } catch (Exception ignored) {
                    }
                }
                if (appName == null || appName.trim().isEmpty()) {
                    appName = resolveLauncherActivityLabel(pm, packageName);
                }
                if (appName == null) appName = "";
                if (!appName.trim().isEmpty()) labeled++;

                if (!first) sb.append(",");
                first = false;
                kept++;
                sb.append("{\"package\":\"")
                        .append(jsonEscape(packageName))
                        .append("\",\"name\":\"")
                        .append(jsonEscape(appName))
                        .append("\"}");
            }

            sb.append("]");
            System.out.println(TAG + " listAppsWithLabels(viaPackages): filter=" + filter
                    + " total=" + packageInfos.size() + " kept=" + kept + " labeled=" + labeled);
            return sb.toString();
        } catch (Exception e) {
            System.err.println(TAG + " listAppsWithLabels(viaPackages) failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Try to resolve app label from launcher activity (more stable on some ROMs than app label).
     */
    private String resolveLauncherActivityLabel(Object pm, String packageName) {
        if (pm == null || packageName == null || packageName.isEmpty()) {
            return "";
        }
        try {
            Class<?> intentClass = Class.forName("android.content.Intent");
            Class<?> pkgMgrClass = Class.forName("android.content.pm.PackageManager");
            Class<?> resolveInfoClass = Class.forName("android.content.pm.ResolveInfo");
            Class<?> activityInfoClass = Class.forName("android.content.pm.ActivityInfo");

            Method getLaunchIntentForPackage = pm.getClass().getMethod("getLaunchIntentForPackage", String.class);
            Object launchIntent = getLaunchIntentForPackage.invoke(pm, packageName);
            if (launchIntent == null) return "";

            Method resolveActivity = pm.getClass().getMethod("resolveActivity", intentClass, int.class);
            Object resolveInfo = resolveActivity.invoke(pm, launchIntent, 0);
            if (resolveInfo == null) return "";

            Field activityInfoField = resolveInfoClass.getField("activityInfo");
            Object activityInfo = activityInfoField.get(resolveInfo);
            if (activityInfo == null) return "";

            Method loadLabel = activityInfoClass.getMethod("loadLabel", pkgMgrClass);
            Object labelObj = loadLabel.invoke(activityInfo, pm);
            return labelObj != null ? labelObj.toString() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * Final fallback when PackageManager reflection is unavailable:
     * use shell commands to enumerate packages and parse application-label lines.
     */
    private String listAppsWithLabelsViaShell(int filter) {
        try {
            System.out.println(TAG + " listAppsWithLabels(viaShell): begin filter=" + filter);
            List<String> packages = listPackagesByShell(filter);
            if (packages.isEmpty()) {
                return null;
            }
            Map<String, String> globalLabels = loadGlobalLabelsByShellCached();

            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            int labeled = 0;
            int fromGlobal = 0;
            int fromPerPkg = 0;
            int skippedByBudget = 0;
            int skippedNoGlobal = 0;
            int perPkgLookups = 0;
            final long deadline = System.currentTimeMillis() + 2200L;
            final int perPkgLookupMax = 6;
            final boolean allowPerPkgFallback = globalLabels != null && !globalLabels.isEmpty();

            for (String pkg : packages) {
                if (pkg == null || pkg.isEmpty()) continue;
                String name = "";
                if (globalLabels != null) {
                    String hit = globalLabels.get(pkg);
                    if (hit != null && !hit.trim().isEmpty()) {
                        name = hit;
                        fromGlobal++;
                    }
                }
                if (name.isEmpty() && allowPerPkgFallback) {
                    if (System.currentTimeMillis() >= deadline || perPkgLookups >= perPkgLookupMax) {
                        skippedByBudget++;
                    } else {
                        perPkgLookups++;
                        name = resolveLabelByShellCached(pkg);
                        if (name != null && !name.trim().isEmpty()) {
                            fromPerPkg++;
                        }
                    }
                } else if (name.isEmpty()) {
                    skippedNoGlobal++;
                }
                if (name != null && !name.trim().isEmpty()) labeled++;
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"package\":\"")
                        .append(jsonEscape(pkg))
                        .append("\",\"name\":\"")
                        .append(jsonEscape(name != null ? name : ""))
                        .append("\"}");
            }
            sb.append("]");
            System.out.println(TAG + " listAppsWithLabels(viaShell): filter=" + filter
                    + " total=" + packages.size()
                    + " labeled=" + labeled
                    + " from_global=" + fromGlobal
                    + " from_per_pkg=" + fromPerPkg
                    + " per_pkg_lookups=" + perPkgLookups
                    + " skipped_budget=" + skippedByBudget
                    + " skipped_no_global=" + skippedNoGlobal
                    + " global_cache_size=" + (globalLabels != null ? globalLabels.size() : 0));
            return sb.toString();
        } catch (Exception e) {
            System.err.println(TAG + " listAppsWithLabels(viaShell) failed: " + e.getMessage());
            return null;
        }
    }

    private Map<String, String> loadGlobalLabelsByShellCached() {
        long now = System.currentTimeMillis();
        synchronized (shellGlobalLabelMapCache) {
            if (!shellGlobalLabelMapCache.isEmpty()
                    && (now - shellGlobalLabelMapCacheTs) < SHELL_GLOBAL_LABEL_CACHE_TTL_MS) {
                return new HashMap<>(shellGlobalLabelMapCache);
            }
        }

        Map<String, String> parsed = parseGlobalLabelsByShell();
        synchronized (shellGlobalLabelMapCache) {
            shellGlobalLabelMapCache.clear();
            if (parsed != null && !parsed.isEmpty()) {
                shellGlobalLabelMapCache.putAll(parsed);
            }
            shellGlobalLabelMapCacheTs = now;
            return new HashMap<>(shellGlobalLabelMapCache);
        }
    }

    private Map<String, String> parseGlobalLabelsByShell() {
        Map<String, String> out = new HashMap<>();
        BufferedReader reader = null;
        try {
            // One-pass parse: keep only package headers and application-label lines.
            String cmd = "dumpsys package packages 2>/dev/null | grep -E \"Package \\[|application-label\"";
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String currentPkg = null;
            String line;
            while ((line = reader.readLine()) != null) {
                String s = line != null ? line.trim() : "";
                if (s.isEmpty()) continue;

                int pi = s.indexOf("Package [");
                if (pi >= 0) {
                    int l = s.indexOf('[', pi);
                    int r = s.indexOf(']', l + 1);
                    if (l >= 0 && r > l + 1) {
                        currentPkg = s.substring(l + 1, r).trim();
                    }
                    continue;
                }

                if (currentPkg != null && s.contains("application-label")) {
                    String label = extractLabelFromDumpLine(s);
                    if (label != null && !label.trim().isEmpty() && !out.containsKey(currentPkg)) {
                        out.put(currentPkg, label);
                    }
                }
            }
            process.waitFor();
            System.out.println(TAG + " parseGlobalLabelsByShell: labels=" + out.size());
        } catch (Exception e) {
            System.err.println(TAG + " parseGlobalLabelsByShell failed: " + e.getMessage());
        } finally {
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        }
        return out;
    }

    private List<String> listPackagesByShell(int filter) {
        List<String> out = new ArrayList<>();
        String cmd;
        switch (filter) {
            case 1:
                cmd = "pm list packages -3";
                break;
            case 2:
                cmd = "pm list packages -s";
                break;
            default:
                cmd = "pm list packages";
                break;
        }

        BufferedReader reader = null;
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("package:")) continue;
                String pkg = line.substring(8).trim();
                if (!pkg.isEmpty()) out.add(pkg);
            }
            process.waitFor();
        } catch (Exception e) {
            System.err.println(TAG + " listPackagesByShell failed: " + e.getMessage());
        } finally {
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        }
        return out;
    }

    private String resolveLabelByShellCached(String packageName) {
        if (packageName == null || packageName.isEmpty()) return "";
        synchronized (shellLabelCache) {
            if (shellLabelCache.containsKey(packageName)) {
                return shellLabelCache.get(packageName);
            }
        }
        String label = resolveLabelByShell(packageName);
        synchronized (shellLabelCache) {
            shellLabelCache.put(packageName, label != null ? label : "");
        }
        return label != null ? label : "";
    }

    private String resolveLabelByShell(String packageName) {
        // Try cmd package dump first (usually smaller than dumpsys package).
        String line = runShellFirstLine("cmd package dump " + packageName
                + " 2>/dev/null | grep -m 1 \"application-label\"");
        String label = extractLabelFromDumpLine(line);
        if (!label.isEmpty()) return label;
        // Avoid heavy per-package dumpsys fallback in request path (too slow on many apps).
        return label;
    }

    private String runShellFirstLine(String command) {
        BufferedReader reader = null;
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            process.waitFor();
            return line != null ? line.trim() : "";
        } catch (Exception ignored) {
            return "";
        } finally {
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        }
    }

    public static final class ShellCommandResult {
        public final boolean ok;
        public final boolean timeout;
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        ShellCommandResult(boolean ok, boolean timeout, int exitCode, String stdout, String stderr) {
            this.ok = ok;
            this.timeout = timeout;
            this.exitCode = exitCode;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
        }
    }

    private static final class StreamCollector extends Thread {
        private final InputStream in;
        private final int limitBytes;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        StreamCollector(InputStream in, int limitBytes) {
            this.in = in;
            this.limitBytes = Math.max(256, limitBytes);
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] buf = new byte[512];
            try {
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (n <= 0) continue;
                    int canWrite = Math.min(n, Math.max(0, limitBytes - out.size()));
                    if (canWrite > 0) {
                        out.write(buf, 0, canWrite);
                    }
                    if (out.size() >= limitBytes) {
                        // Continue draining input to avoid child-process pipe block.
                    }
                }
            } catch (Exception ignored) {
            } finally {
                try { in.close(); } catch (Exception ignored) {}
            }
        }

        String text() {
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        }
    }

    /**
     * Execute shell command and capture stdout/stderr with timeout.
     *
     * Notes:
     * - Intended for short control commands (svc/settings/cmd/input).
     * - Output is truncated to protect protocol payload size.
     */
    public ShellCommandResult runShellCommand(String command, int timeoutMs) {
        String cmd = command != null ? command.trim() : "";
        if (cmd.isEmpty()) {
            return new ShellCommandResult(false, false, -1, "", "empty command");
        }

        int timeout = timeoutMs > 0 ? timeoutMs : 8000;
        StreamCollector outCollector = null;
        StreamCollector errCollector = null;
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            outCollector = new StreamCollector(process.getInputStream(), 4096);
            errCollector = new StreamCollector(process.getErrorStream(), 4096);
            outCollector.start();
            errCollector.start();

            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                try { process.destroy(); } catch (Exception ignored) {}
                try { process.destroyForcibly(); } catch (Exception ignored) {}
                if (outCollector != null) outCollector.join(200);
                if (errCollector != null) errCollector.join(200);
                return new ShellCommandResult(false, true, -1,
                        outCollector != null ? outCollector.text() : "",
                        errCollector != null ? errCollector.text() : "");
            }

            int exitCode = process.exitValue();
            if (outCollector != null) outCollector.join(200);
            if (errCollector != null) errCollector.join(200);
            return new ShellCommandResult(
                    exitCode == 0,
                    false,
                    exitCode,
                    outCollector != null ? outCollector.text() : "",
                    errCollector != null ? errCollector.text() : ""
            );
        } catch (Exception e) {
            return new ShellCommandResult(false, false, -1, "", e.getMessage());
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Exception ignored) {}
            }
        }
    }

    public Map<String, Object> refreshAdbKeyboardStatus() {
        synchronized (adbKeyboardLock) {
            ShellCommandResult imeList = runShellCommand("ime list -s -a", 3000);
            String currentIme = getCurrentInputMethodId();
            adbKeyboardProfile = resolveAdbKeyboardProfile(imeList.stdout);
            adbKeyboardStatusTs = System.currentTimeMillis();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("available", adbKeyboardProfile.available);
            out.put("ime_id", adbKeyboardProfile.imeId);
            out.put("package", adbKeyboardProfile.packageName);
            out.put("variant", adbKeyboardProfile.variant);
            out.put("current_ime", currentIme);
            out.put("using_now", adbKeyboardProfile.available && adbKeyboardProfile.imeId.equals(currentIme));
            return out;
        }
    }

    public Map<String, Object> getAdbKeyboardStatus() {
        synchronized (adbKeyboardLock) {
            Map<String, Object> out = new LinkedHashMap<>();
            String currentIme = getCurrentInputMethodId();
            out.put("available", adbKeyboardProfile.available);
            out.put("ime_id", adbKeyboardProfile.imeId);
            out.put("package", adbKeyboardProfile.packageName);
            out.put("variant", adbKeyboardProfile.variant);
            out.put("current_ime", currentIme);
            out.put("using_now", adbKeyboardProfile.available && adbKeyboardProfile.imeId.equals(currentIme));
            return out;
        }
    }

    public boolean hasAdbKeyboardAvailable() {
        synchronized (adbKeyboardLock) {
            if (adbKeyboardProfile.available) {
                return true;
            }
            if (adbKeyboardStatusTs > 0L) {
                return false;
            }
        }
        Map<String, Object> status = refreshAdbKeyboardStatus();
        Object available = status.get("available");
        return available instanceof Boolean && ((Boolean) available).booleanValue();
    }

    private AdbKeyboardProfile resolveAdbKeyboardProfile(String imeList) {
        String raw = imeList != null ? imeList : "";
        if (raw.contains(ADB_IME_STANDARD_ID)) {
            return new AdbKeyboardProfile(
                    true,
                    ADB_IME_STANDARD_ID,
                    ADB_IME_STANDARD_PACKAGE,
                    "adb_keyboard",
                    "ADB_INPUT_B64",
                    "msg",
                    true
            );
        }
        if (raw.contains(ADB_IME_OPENATX_ID)) {
            return new AdbKeyboardProfile(
                    true,
                    ADB_IME_OPENATX_ID,
                    ADB_IME_OPENATX_PACKAGE,
                    "openatx_adb_keyboard",
                    "ADB_KEYBOARD_INPUT_TEXT",
                    "text",
                    false
            );
        }
        return new AdbKeyboardProfile(false, "", "", "", "", "", false);
    }

    private String getCurrentInputMethodId() {
        ShellCommandResult res = runShellCommand("settings get secure default_input_method", 1500);
        if (!res.ok) {
            return "";
        }
        String out = res.stdout != null ? res.stdout.trim() : "";
        if ("null".equalsIgnoreCase(out)) {
            return "";
        }
        return out;
    }

    private boolean switchInputMethod(String imeId) {
        if (imeId == null || imeId.trim().isEmpty()) {
            return false;
        }
        String target = imeId.trim();
        runShellCommand("ime enable " + shellQuote(target), 2500);
        ShellCommandResult setRes = runShellCommand("ime set " + shellQuote(target), 2500);
        if (!setRes.ok) {
            runShellCommand("settings put secure default_input_method " + shellQuote(target), 2500);
        }
        return target.equals(getCurrentInputMethodId());
    }

    private void restoreInputMethod(String previousImeId, String adbImeId) {
        String prev = previousImeId != null ? previousImeId.trim() : "";
        if (prev.isEmpty()) {
            runShellCommand("ime reset", 2500);
            return;
        }
        if (prev.equals(adbImeId)) {
            return;
        }
        if (!switchInputMethod(prev)) {
            runShellCommand("settings put secure default_input_method " + shellQuote(prev), 2500);
        }
    }

    private String shellQuote(String text) {
        String s = text != null ? text : "";
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    public boolean inputTextByAdbKeyboard(String text) {
        if (text == null) {
            text = "";
        }
        AdbKeyboardProfile profile;
        synchronized (adbKeyboardLock) {
            if (!adbKeyboardProfile.available) {
                refreshAdbKeyboardStatus();
            }
            profile = adbKeyboardProfile;
        }
        if (!profile.available) {
            return false;
        }

        String previousIme = getCurrentInputMethodId();
        boolean switched = profile.imeId.equals(previousIme);
        try {
            if (!switched) {
                switched = switchInputMethod(profile.imeId);
                if (!switched) {
                    System.err.println(TAG + " inputTextByAdbKeyboard: switch failed for " + profile.imeId);
                    return false;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }

            String payload = profile.useBase64
                    ? Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))
                    : text;
            String cmd = "am broadcast -a " + profile.broadcastAction
                    + " --es " + profile.extraKey + " " + shellQuote(payload);
            ShellCommandResult res = runShellCommand(cmd, 4000);
            boolean ok = res.ok && (res.stdout.contains("result=-1") || res.stdout.contains("Broadcast completed"));
            if (!ok) {
                System.err.println(TAG + " inputTextByAdbKeyboard failed: stdout=" + res.stdout + " stderr=" + res.stderr);
                return false;
            }
            System.out.println(TAG + " inputTextByAdbKeyboard: OK variant=" + profile.variant
                    + " chars=" + text.length());
            return true;
        } finally {
            restoreInputMethod(previousIme, profile.imeId);
        }
    }

    private String extractLabelFromDumpLine(String line) {
        if (line == null) return "";
        String s = line.trim();
        if (s.isEmpty()) return "";
        int idx = s.indexOf(':');
        if (idx < 0 || idx + 1 >= s.length()) return "";
        String v = s.substring(idx + 1).trim();
        // remove simple wrapping quotes
        while (!v.isEmpty() && (v.startsWith("'") || v.startsWith("\""))) {
            v = v.substring(1).trim();
        }
        while (!v.isEmpty() && (v.endsWith("'") || v.endsWith("\""))) {
            v = v.substring(0, v.length() - 1).trim();
        }
        if ("null".equalsIgnoreCase(v)) return "";
        return v;
    }

    /**
     * Resolve a usable PackageManager object in app_process context.
     *
     * Try order:
     * 1) ActivityThread.currentApplication().getPackageManager()
     * 2) ActivityThread.currentActivityThread().getSystemContext().getPackageManager()
     * 3) ActivityThread.systemMain().getSystemContext().getPackageManager()
     */
    private Object resolvePackageManagerForLabels() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");

            // Path-1: currentApplication
            try {
                Method currentApplication = activityThreadClass.getMethod("currentApplication");
                Object application = currentApplication.invoke(null);
                if (application != null) {
                    Method getPackageManager = application.getClass().getMethod("getPackageManager");
                    Object pm = getPackageManager.invoke(application);
                    if (pm != null) {
                        return pm;
                    }
                }
            } catch (Exception ignored) {
            }

            // Path-2: currentActivityThread -> getSystemContext
            try {
                Method currentActivityThread = activityThreadClass.getMethod("currentActivityThread");
                Object at = currentActivityThread.invoke(null);
                if (at != null) {
                    Method getSystemContext = activityThreadClass.getMethod("getSystemContext");
                    Object systemContext = getSystemContext.invoke(at);
                    if (systemContext != null) {
                        Method getPackageManager = systemContext.getClass().getMethod("getPackageManager");
                        Object pm = getPackageManager.invoke(systemContext);
                        if (pm != null) {
                            return pm;
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            // Path-3: systemMain -> getSystemContext
            try {
                Method systemMain = activityThreadClass.getMethod("systemMain");
                Object at = systemMain.invoke(null);
                if (at != null) {
                    Method getSystemContext = activityThreadClass.getMethod("getSystemContext");
                    Object systemContext = getSystemContext.invoke(at);
                    if (systemContext != null) {
                        Method getPackageManager = systemContext.getClass().getMethod("getPackageManager");
                        Object pm = getPackageManager.invoke(systemContext);
                        if (pm != null) {
                            return pm;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            System.err.println(TAG + " resolvePackageManagerForLabels failed: " + e.getMessage());
        }
        return null;
    }

    private String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * ????
     */
    public boolean unlock() {
        try {
            pressKey(KEYCODE_WAKEUP);
            Thread.sleep(200);

            int centerX = screenWidth / 2;
            int startY = (int) (screenHeight * 0.8);
            int endY = (int) (screenHeight * 0.3);

            boolean result = swipe(centerX, startY, centerX, endY, 300);
            System.out.println(TAG + " unlock: " + (result ? "OK" : "FAIL"));
            return result;

        } catch (Exception e) {
            System.err.println(TAG + " unlock failed: " + e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // 剪贴板操作
    // =========================================================================

    /**
     * 设置剪贴板内容
     */
    public boolean setClipboard(String text) {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getMethod("getService", String.class);
            Object binder = getService.invoke(null, "clipboard");

            Class<?> clipboardStub = Class.forName("android.content.IClipboard$Stub");
            Class<?> ibinderClass = Class.forName("android.os.IBinder");
            Method asInterface = clipboardStub.getMethod("asInterface", ibinderClass);
            Object clipboard = asInterface.invoke(null, binder);

            // 创建 ClipData
            Class<?> clipDataClass = Class.forName("android.content.ClipData");
            Method newPlainText = clipDataClass.getMethod("newPlainText",
                    CharSequence.class, CharSequence.class);
            Object clipData = newPlainText.invoke(null, "lxb", text);

            // 尝试多种方法签名 (不同 Android 版本)
            boolean success = false;
            Method[] methods = clipboard.getClass().getMethods();
            for (Method m : methods) {
                if (!"setPrimaryClip".equals(m.getName())) continue;

                Class<?>[] params = m.getParameterTypes();
                try {
                    if (params.length == 4) {
                        // Android 10+: setPrimaryClip(ClipData, String, String, int)
                        m.invoke(clipboard, clipData, "com.lxb.server", null, 0);
                        success = true;
                        break;
                    } else if (params.length == 3) {
                        // setPrimaryClip(ClipData, String, int)
                        m.invoke(clipboard, clipData, "com.lxb.server", 0);
                        success = true;
                        break;
                    } else if (params.length == 2) {
                        // setPrimaryClip(ClipData, String)
                        m.invoke(clipboard, clipData, "com.lxb.server");
                        success = true;
                        break;
                    } else if (params.length == 1) {
                        // setPrimaryClip(ClipData)
                        m.invoke(clipboard, clipData);
                        success = true;
                        break;
                    }
                } catch (Exception ex) {
                    // 尝试下一个签名
                    continue;
                }
            }

            if (success) {
                System.out.println(TAG + " setClipboard: " + text.length() + " chars");
                return true;
            } else {
                // 后备方案：使用 am broadcast
                return setClipboardViaShell(text);
            }

        } catch (Exception e) {
            System.err.println(TAG + " setClipboard failed: " + e.getMessage());
            // 后备方案
            return setClipboardViaShell(text);
        }
    }

    /**
     * 通过 shell 设置剪贴板 (后备方案)
     * 返回 true 如果成功，false 需要使用 input text 直接输入
     */
    private boolean setClipboardViaShell(String text) {
        // 这个方法实际上很难通过 shell 实现，因为需要 root 或特殊权限
        // 返回 false 让调用者知道需要使用其他方式
        System.err.println(TAG + " setClipboard via shell not supported, will use input text");
        return false;
    }

    /**
     * 直接使用 input text 输入文本 (最可靠的后备方案)
     * @param text 要输入的文本
     * @return 是否成功
     */
    public boolean inputTextDirect(String text) {
        try {
            // 对特殊字符进行转义
            String escaped = text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("'", "\\'")
                .replace(" ", "%s")
                .replace("&", "\\&")
                .replace("<", "\\<")
                .replace(">", "\\>")
                .replace("|", "\\|")
                .replace(";", "\\;")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("$", "\\$")
                .replace("`", "\\`");

            Process process = Runtime.getRuntime().exec(new String[]{"input", "text", escaped});
            int exitCode = process.waitFor();
            boolean success = exitCode == 0;
            System.out.println(TAG + " inputTextDirect: " + (success ? "OK" : "FAIL") + " (" + text.length() + " chars)");
            return success;
        } catch (Exception e) {
            System.err.println(TAG + " inputTextDirect failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 执行粘贴 (Ctrl+V)
     */
    public boolean paste() {
        // 优先尝试 UiAutomation 方式 (Ctrl+V)
        if (injectKeyEvent(KEYCODE_V, 2, META_CTRL_ON)) {
            return true;
        }

        // 后备方案：使用 KEYCODE_PASTE (279)
        try {
            Process process = Runtime.getRuntime().exec("input keyevent 279");
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                System.out.println(TAG + " Paste: OK (KEYCODE_PASTE)");
                return true;
            }
        } catch (Exception e) {
            System.err.println(TAG + " KEYCODE_PASTE failed: " + e.getMessage());
        }

        // 最后备方案：使用 input text 直接输入（需要从剪贴板获取）
        System.err.println(TAG + " Paste failed - all methods exhausted");
        return false;
    }

    /**
     * 使用系统剪贴板 + 粘贴进行输入，适用于中文/emoji 等复杂字符。
     */
    public boolean inputTextByClipboard(String text) {
        if (text == null) {
            text = "";
        }
        boolean clipOk = setClipboard(text);
        if (!clipOk) {
            System.err.println(TAG + " inputTextByClipboard: setClipboard failed");
            return false;
        }
        return paste();
    }

    /**
     * 使用 Accessibility ACTION_SET_TEXT 直接设置焦点输入框文本。
     * 该方式对中文/emoji 等复杂字符通常更稳定。
     */
    public boolean setFocusedText(String text) {
        if (text == null) {
            text = "";
        }
        try {
            Object root = getRootNode();
            if (root == null) {
                System.err.println(TAG + " setFocusedText: root is null");
                return false;
            }

            Class<?> nodeClass = Class.forName("android.view.accessibility.AccessibilityNodeInfo");
            Class<?> bundleClass = Class.forName("android.os.Bundle");

            Method findFocus = null;
            Method performAction = nodeClass.getMethod("performAction", int.class, bundleClass);
            Method getChildCount = nodeClass.getMethod("getChildCount");
            Method getChild = nodeClass.getMethod("getChild", int.class);
            Method isFocused = nodeClass.getMethod("isFocused");
            Method isEditable = null;

            try {
                findFocus = nodeClass.getMethod("findFocus", int.class);
            } catch (Exception ignored) {}
            try {
                isEditable = nodeClass.getMethod("isEditable");
            } catch (Exception ignored) {}

            int actionSetText = nodeClass.getField("ACTION_SET_TEXT").getInt(null);
            String argSetText = (String) nodeClass
                    .getField("ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE")
                    .get(null);

            // First try explicit input focus.
            if (findFocus != null) {
                try {
                    int focusInput = nodeClass.getField("FOCUS_INPUT").getInt(null);
                    Object focused = findFocus.invoke(root, focusInput);
                    if (_performSetText(focused, text, actionSetText, argSetText, bundleClass, performAction)) {
                        return true;
                    }
                } catch (Exception ignored) {}
            }

            // Fallback BFS: focused/editable node.
            ArrayDeque<Object> queue = new ArrayDeque<>();
            queue.add(root);
            while (!queue.isEmpty()) {
                Object node = queue.poll();
                if (node == null) continue;

                boolean focused = false;
                boolean editable = false;
                try {
                    focused = (Boolean) isFocused.invoke(node);
                } catch (Exception ignored) {}
                if (isEditable != null) {
                    try {
                        editable = (Boolean) isEditable.invoke(node);
                    } catch (Exception ignored) {}
                }
                if (focused || editable) {
                    if (_performSetText(node, text, actionSetText, argSetText, bundleClass, performAction)) {
                        return true;
                    }
                }

                int childCount = 0;
                try {
                    childCount = (Integer) getChildCount.invoke(node);
                } catch (Exception ignored) {}
                for (int i = 0; i < childCount; i++) {
                    try {
                        Object child = getChild.invoke(node, i);
                        if (child != null) queue.add(child);
                    } catch (Exception ignored) {}
                }
            }

            System.err.println(TAG + " setFocusedText: no editable/focused node accepted set-text");
            return false;
        } catch (Exception e) {
            System.err.println(TAG + " setFocusedText failed: " + e.getMessage());
            return false;
        }
    }

    private boolean _performSetText(
            Object node,
            String text,
            int actionSetText,
            String argSetText,
            Class<?> bundleClass,
            Method performAction
    ) {
        if (node == null) return false;
        try {
            Object bundle = bundleClass.getConstructor().newInstance();
            Method putCharSequence = bundleClass.getMethod("putCharSequence", String.class, CharSequence.class);
            putCharSequence.invoke(bundle, argSetText, text);
            boolean ok = (Boolean) performAction.invoke(node, actionSetText, bundle);
            if (ok) {
                System.out.println(TAG + " setFocusedText: OK (" + text.length() + " chars)");
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 清空焦点输入框 (Ctrl+A + DEL)
     */
    public void clearFocusedText() {
        // 尝试 Ctrl+A
        if (!injectKeyEvent(KEYCODE_A, 2, META_CTRL_ON)) {
            // 后备: 使用 KEYCODE_MOVE_HOME + KEYCODE_MOVE_END with shift
            try {
                Runtime.getRuntime().exec("input keyevent 123").waitFor(); // KEYCODE_MOVE_END
                Thread.sleep(30);
                Runtime.getRuntime().exec("input keyevent --longpress 123").waitFor(); // 全选
            } catch (Exception ignored) {}
        }
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {}
        pressKey(KEYCODE_DEL);
    }

    // =========================================================================
    // 公开的 KeyCode 常量 (供外部使用)
    // =========================================================================

    public int getKeycodeBack() { return KEYCODE_BACK; }
    public int getKeycodeHome() { return KEYCODE_HOME; }
    public int getKeycodeEnter() { return KEYCODE_ENTER; }

    // =========================================================================
    // 截图
    // =========================================================================

    // JPEG 压缩质量 (1-100)，50 通常能把 400KB PNG 压缩到 30-60KB
    private int screenshotQuality = 35;

    /**
     * 设置截图质量
     * @param quality JPEG 质量 (1-100)
     */
    public void setScreenshotQuality(int quality) {
        this.screenshotQuality = Math.max(1, Math.min(100, quality));
    }

    /**
     * 截取屏幕截图
     *
     * @return JPEG 格式的截图数据，失败返回 null
     */
    public byte[] takeScreenshot() {
        // 优先使用 UiAutomation
        if (uiAutomation != null) {
            try {
                // 尝试通过 UiAutomation 获取截图
                Method takeScreenshot = uiAutomation.getClass().getMethod("takeScreenshot");
                Object bitmap = takeScreenshot.invoke(uiAutomation);

                if (bitmap != null) {
                    // 将 Bitmap 压缩为 JPEG
                    byte[] jpegData = compressBitmapToJpeg(bitmap, screenshotQuality);
                    if (jpegData != null) {
                        System.out.println(TAG + " Screenshot: " + jpegData.length + " bytes (UiAutomation, JPEG q=" + screenshotQuality + ")");
                        return jpegData;
                    }
                }
            } catch (Exception e) {
                System.err.println(TAG + " UiAutomation screenshot failed: " + e.getMessage());
            }
        }

        // 后备方案：使用 screencap 命令 + 转换为 JPEG
        return takeScreenshotViaShell();
    }

    /**
     * 将 Bitmap 压缩为 JPEG 格式
     */
    private byte[] compressBitmapToJpeg(Object bitmap, int quality) {
        try {
            Class<?> bitmapClass = Class.forName("android.graphics.Bitmap");
            Class<?> formatClass = Class.forName("android.graphics.Bitmap$CompressFormat");
            Object jpegFormat = formatClass.getField("JPEG").get(null);

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

            Method compress = bitmapClass.getMethod("compress",
                    formatClass, int.class, java.io.OutputStream.class);
            Boolean success = (Boolean) compress.invoke(bitmap, jpegFormat, quality, baos);

            if (success) {
                return baos.toByteArray();
            }
        } catch (Exception e) {
            System.err.println(TAG + " Bitmap compress (JPEG) failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * 通过 shell 命令截图（后备方案）
     * 截图到临时文件，然后通过 BitmapFactory 加载并转换为 JPEG
     */
    private byte[] takeScreenshotViaShell() {
        String tmpPng = "/data/local/tmp/lxb_screenshot.png";

        try {
            // 截图到 PNG 文件
            Process process = Runtime.getRuntime().exec("screencap -p " + tmpPng);
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                System.err.println(TAG + " screencap failed with exit code: " + exitCode);
                return null;
            }

            // 使用 BitmapFactory.decodeFile 加载 PNG
            Class<?> bitmapFactoryClass = Class.forName("android.graphics.BitmapFactory");
            Method decodeFile = bitmapFactoryClass.getMethod("decodeFile", String.class);
            Object bitmap = decodeFile.invoke(null, tmpPng);

            if (bitmap != null) {
                // 转换为 JPEG
                byte[] jpegData = compressBitmapToJpeg(bitmap, screenshotQuality);

                // 回收 Bitmap
                try {
                    Method recycle = bitmap.getClass().getMethod("recycle");
                    recycle.invoke(bitmap);
                } catch (Exception ignored) {}

                // 删除临时文件
                Runtime.getRuntime().exec("rm -f " + tmpPng);

                if (jpegData != null) {
                    System.out.println(TAG + " Screenshot: " + jpegData.length + " bytes (shell->JPEG q=" + screenshotQuality + ")");
                    return jpegData;
                }
            }

            // 如果转换失败，读取原始 PNG
            System.err.println(TAG + " JPEG conversion failed, using raw PNG");
            java.io.FileInputStream fis = new java.io.FileInputStream(tmpPng);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            fis.close();
            Runtime.getRuntime().exec("rm -f " + tmpPng);

            byte[] data = baos.toByteArray();
            System.out.println(TAG + " Screenshot: " + data.length + " bytes (shell, PNG)");
            return data;

        } catch (Exception e) {
            System.err.println(TAG + " Shell screenshot failed: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    // =========================================================================
    // 关闭资源
    // =========================================================================

    public void close() {
        if (uiAutomation != null) {
            try {
                Method disconnect = uiAutomation.getClass().getMethod("disconnect");
                disconnect.invoke(uiAutomation);
            } catch (Exception e) {
                // Ignore
            }
            uiAutomation = null;
        }
        System.out.println(TAG + " Closed");
    }
}
