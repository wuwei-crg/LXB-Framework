# LXB-Server 架构设计文档 (Android 端)

---

| 项目         | 内容                                    |
|:-------------|:----------------------------------------|
| **文档版本** | 2.0.0                                   |
| **协议版本** | 1.0 (Binary First Architecture)        |
| **作者**     | WuWei                                   |
| **创建日期** | 2025-01-01                              |
| **最后更新** | 2026-01-12                              |
| **状态**     | 开发中 (In Development)                 |
| **适用范围** | Android 设备端服务器实现                |

---

## 文档说明

本文档定义了 LXB-Server（Android 端服务器）的架构设计，包括：

- 分层架构与职责划分
- 协议层"转译"机制
- 业务层引擎实现
- 系统层 UiAutomation 封装

**设计哲学**: UDP 接收二进制指令 → 转译 → 引擎执行

**目标读者**: Android 端开发者、系统集成工程师

---

## 目录

- [技术栈总览](#0-技术栈总览)
- [分层架构概述](#1-分层架构概述)
- [Layer 1: 网络层](#2-layer-1-网络层)
- [Layer 2: 协议层](#3-layer-2-协议层转译)
- [Layer 3: 调度层](#4-layer-3-调度层)
- [Layer 4: 业务层](#5-layer-4-业务层)
- [Layer 5: 系统层](#6-layer-5-系统层)
- [启动流程](#7-启动流程)
- [错误处理](#8-错误处理)
- [附录](#附录-a-协议兼容性检查清单)

---

## 0. 技术栈总览

| 模块 | 组件 | 技术选型 | 核心理由 |
|------|------|----------|----------|
| **Ignition (宿主)** | 宿主 App | Kotlin / Jetpack Compose | 现代 Android 开发标准 |
| | ADB 客户端 | dadb (Kotlin ADB lib) | 轻量级，直接支持 TCP 连接 |
| | 服务发现 | NsdManager (Android Native) | 发现 Android 11+ 无线调试端口 |
| **Ghost (内核)** | 核心引擎 | Pure Java (JDK 8) | 无 Android Context 依赖，通过 app_process 启动 |
| | 系统交互 | UiAutomation (Hidden API) | 比无障碍服务更快，直接访问节点树 |
| | 通信层 | Java NIO (DatagramChannel) | 非阻塞 UDP，零拷贝读写 |
| | 反射绕过 | HiddenApiBypass | 绕过 Android P+ 非公开 API 限制 |

---

## 1. 分层架构概述

```
┌─────────────────────────────────────────────────────────────────┐
│                    Main (入口 + 事件循环)                        │
├─────────────────────────────────────────────────────────────────┤
│ Layer 5: 系统层 (System Layer)                                  │
│   UiAutomationWrapper - 封装 Hidden API 调用                    │
├─────────────────────────────────────────────────────────────────┤
│ Layer 4: 业务层 (Business Layer)                                │
│   ExecutionEngine (输入注入) + PerceptionEngine (UI 感知)       │
├─────────────────────────────────────────────────────────────────┤
│ Layer 3: 调度层 (Dispatch Layer)                                │
│   CommandDispatcher - 路由 + 去重 + 熔断                        │
├─────────────────────────────────────────────────────────────────┤
│ Layer 2: 协议层 (Protocol Layer)  ← "转译"                      │
│   FrameCodec - 帧编解码 + CRC32 验证                            │
│   CommandParser - Payload 解析为结构化对象                      │
│   ResponseBuilder - 结果编码为 Payload                          │
├─────────────────────────────────────────────────────────────────┤
│ Layer 1: 网络层 (Network Layer)                                 │
│   UdpServer - DatagramSocket 收发                               │
└─────────────────────────────────────────────────────────────────┘
```

### 数据流

```
UDP 接收 → 转译(解码+CRC验证) → 调度(路由/去重/熔断) → 引擎执行 → 响应编码 → UDP 发送
```

### 设计原则

1. **职责单一**: 每层只做一件事
2. **依赖倒置**: 上层依赖下层接口，不依赖实现
3. **协议透明**: 业务层不感知二进制格式

---

## 2. Layer 1: 网络层

**职责**: 原始 UDP 数据报收发，不涉及任何协议解析。

**输入**: 原始字节流 `byte[]`
**输出**: 原始字节流 `byte[]` + 客户端地址信息

### 2.1 UdpServer

```java
public class UdpServer {
    private DatagramSocket socket;
    private volatile boolean running;

    public static final int MAX_FRAME_SIZE = 65549;  // 协议最大帧

    /**
     * 监听指定端口
     */
    public void listen(int port) throws IOException {
        socket = new DatagramSocket(port);
        socket.setReceiveBufferSize(65536);
        running = true;
    }

    /**
     * 阻塞式接收数据报
     * @param timeout 超时时间 (毫秒)
     * @return 接收到的帧 (数据 + 地址)
     */
    public ReceivedFrame receive(int timeout) throws IOException {
        byte[] buffer = new byte[MAX_FRAME_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.setSoTimeout(timeout);
        socket.receive(packet);

        return new ReceivedFrame(
            Arrays.copyOf(packet.getData(), packet.getLength()),
            packet.getAddress(),
            packet.getPort()
        );
    }

    /**
     * 发送数据报
     */
    public void send(InetAddress addr, int port, byte[] data) throws IOException {
        DatagramPacket packet = new DatagramPacket(data, data.length, addr, port);
        socket.send(packet);
    }

    public void close() {
        running = false;
        if (socket != null) socket.close();
    }

    /**
     * 接收到的帧封装
     */
    public static class ReceivedFrame {
        public final byte[] data;
        public final InetAddress address;
        public final int port;

        public ReceivedFrame(byte[] data, InetAddress address, int port) {
            this.data = data;
            this.address = address;
            this.port = port;
        }
    }
}
```

---

## 3. Layer 2: 协议层（转译）

**职责**: 实现"转译"功能 — 二进制帧与结构化对象的相互转换，包含 CRC32 完整性校验。

**输入**: 原始字节流 `byte[]`
**输出**: 结构化命令对象 `DecodedFrame` 或编码后的帧 `byte[]`

### 3.1 帧格式规范

严格遵循 LXB-Link.md 定义（**大端序 / 网络字节序**）：

```
┌─────────┬─────────┬─────────┬─────────┬─────────┬─────────┬─────────┐
│  Magic  │ Version │   Seq   │   Cmd   │  Length │ Payload │  CRC32  │
│  2B     │  1B     │   4B    │   1B    │   2B    │  N B    │   4B    │
│ 0xAA55  │  0x01   │ uint32  │  uint8  │ uint16  │  bytes  │ uint32  │
└─────────┴─────────┴─────────┴─────────┴─────────┴─────────┴─────────┘
     ↑                                                            ↑
     |--- Header (10B) --------------------------------|  CRC32 (4B, 帧尾)
```

| 字段 | 偏移 | 大小 | 说明 |
|------|------|------|------|
| Magic | 0 | 2B | 魔数标识 `0xAA55` |
| Version | 2 | 1B | 协议版本号 (当前: `0x01`) |
| Sequence | 3 | 4B | 序列号 (0 - 4294967295) |
| Command | 7 | 1B | 命令 ID (见 LXB-Link.md) |
| Length | 8 | 2B | Payload 长度 (0 - 65535) |
| Payload | 10 | N | 命令负载数据 |
| CRC32 | 10+N | 4B | CRC32 校验和 (**帧尾**) |

**关键约束**:

- **CRC32 计算范围**: Header (10B) + Payload (N B)，不含 CRC32 字段本身
- **字节序**: 所有多字节整数使用大端序 (Big Endian)
- **最小帧**: 14 字节 (Header + CRC，无 Payload)
- **最大帧**: 65549 字节 (10 + 65535 + 4)

### 3.2 FrameCodec 编解码器

```java
public class FrameCodec {

    public static final short MAGIC = (short) 0xAA55;
    public static final byte VERSION = 0x01;
    public static final int HEADER_SIZE = 10;
    public static final int CRC_SIZE = 4;
    public static final int MIN_FRAME_SIZE = HEADER_SIZE + CRC_SIZE;

    /**
     * 解码帧（含 CRC32 验证）
     *
     * @param raw 原始字节流
     * @return 解码后的帧对象
     * @throws CRCException CRC32 校验失败
     * @throws ProtocolException 协议格式错误
     */
    public static DecodedFrame decode(byte[] raw) throws CRCException, ProtocolException {
        // 1. 检查最小长度
        if (raw.length < MIN_FRAME_SIZE) {
            throw new ProtocolException("Frame too short: " + raw.length);
        }

        ByteBuffer buffer = ByteBuffer.wrap(raw);
        buffer.order(ByteOrder.BIG_ENDIAN);  // 强制大端序

        // 2. 解析帧头
        short magic = buffer.getShort();
        if (magic != MAGIC) {
            throw new ProtocolException("Invalid magic: 0x" +
                Integer.toHexString(magic & 0xFFFF));
        }

        byte version = buffer.get();
        int seq = buffer.getInt();
        byte cmd = buffer.get();
        int payloadLength = buffer.getShort() & 0xFFFF;

        // 3. 检查帧完整性
        int expectedSize = HEADER_SIZE + payloadLength + CRC_SIZE;
        if (raw.length < expectedSize) {
            throw new ProtocolException("Frame truncated: expected " +
                expectedSize + ", got " + raw.length);
        }

        // 4. 提取 Payload
        byte[] payload = new byte[payloadLength];
        System.arraycopy(raw, HEADER_SIZE, payload, 0, payloadLength);

        // 5. 验证 CRC32
        int crcOffset = HEADER_SIZE + payloadLength;
        buffer.position(crcOffset);
        int receivedCRC = buffer.getInt();

        CRC32 crc32 = new CRC32();
        crc32.update(raw, 0, crcOffset);  // Header + Payload
        int calculatedCRC = (int) crc32.getValue();

        if (receivedCRC != calculatedCRC) {
            throw new CRCException(String.format(
                "CRC mismatch: expected=0x%08X, got=0x%08X",
                calculatedCRC, receivedCRC));
        }

        return new DecodedFrame(version, seq, cmd, payload);
    }

    /**
     * 编码帧（自动计算 CRC32）
     *
     * @param seq 序列号
     * @param cmd 命令号
     * @param payload 负载数据 (可为 null)
     * @return 完整帧字节数组
     */
    public static byte[] encode(int seq, byte cmd, byte[] payload) {
        if (payload == null) payload = new byte[0];

        int frameSize = HEADER_SIZE + payload.length + CRC_SIZE;
        ByteBuffer buffer = ByteBuffer.allocate(frameSize);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // 写入 Header (10B)
        buffer.putShort(MAGIC);
        buffer.put(VERSION);
        buffer.putInt(seq);
        buffer.put(cmd);
        buffer.putShort((short) payload.length);

        // 写入 Payload
        buffer.put(payload);

        // 计算并写入 CRC32
        byte[] frameWithoutCRC = buffer.array();
        CRC32 crc32 = new CRC32();
        crc32.update(frameWithoutCRC, 0, HEADER_SIZE + payload.length);
        buffer.putInt((int) crc32.getValue());

        return buffer.array();
    }

    /**
     * 解码后的帧结构
     */
    public static class DecodedFrame {
        public final byte version;
        public final int seq;
        public final byte cmd;
        public final byte[] payload;

        public DecodedFrame(byte version, int seq, byte cmd, byte[] payload) {
            this.version = version;
            this.seq = seq;
            this.cmd = cmd;
            this.payload = payload;
        }
    }
}
```

### 3.3 CommandParser 命令解析

将 Payload 字节流解析为具体的命令参数对象。

```java
public class CommandParser {

    /**
     * 解析 TAP 命令
     * Payload: x(2B) + y(2B) = 4B
     */
    public static TapCommand parseTap(byte[] payload) {
        if (payload.length != 4) {
            throw new IllegalArgumentException("TAP payload must be 4 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);
        return new TapCommand(
            buffer.getShort() & 0xFFFF,
            buffer.getShort() & 0xFFFF
        );
    }

    /**
     * 解析 SWIPE 命令
     * Payload: x1(2B) + y1(2B) + x2(2B) + y2(2B) + duration(2B) = 10B
     */
    public static SwipeCommand parseSwipe(byte[] payload) {
        if (payload.length != 10) {
            throw new IllegalArgumentException("SWIPE payload must be 10 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);
        return new SwipeCommand(
            buffer.getShort() & 0xFFFF,
            buffer.getShort() & 0xFFFF,
            buffer.getShort() & 0xFFFF,
            buffer.getShort() & 0xFFFF,
            buffer.getShort() & 0xFFFF
        );
    }

    /**
     * 解析 LONG_PRESS 命令
     * Payload: x(2B) + y(2B) + duration(2B) = 6B
     */
    public static LongPressCommand parseLongPress(byte[] payload) {
        if (payload.length != 6) {
            throw new IllegalArgumentException("LONG_PRESS payload must be 6 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);
        return new LongPressCommand(
            buffer.getShort() & 0xFFFF,
            buffer.getShort() & 0xFFFF,
            buffer.getShort() & 0xFFFF
        );
    }

    /**
     * 解析 INPUT_TEXT 命令
     * Payload: method(1B) + flags(1B) + target_x(2B) + target_y(2B) +
     *          delay_ms(2B) + text_len(2B) + text(N)
     */
    public static InputTextCommand parseInputText(byte[] payload) {
        if (payload.length < 10) {
            throw new IllegalArgumentException("INPUT_TEXT payload too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);

        byte method = buffer.get();
        byte flags = buffer.get();
        int targetX = buffer.getShort() & 0xFFFF;
        int targetY = buffer.getShort() & 0xFFFF;
        int delayMs = buffer.getShort() & 0xFFFF;
        int textLen = buffer.getShort() & 0xFFFF;

        byte[] textBytes = new byte[textLen];
        buffer.get(textBytes);
        String text = new String(textBytes, StandardCharsets.UTF_8);

        return new InputTextCommand(method, flags, targetX, targetY, delayMs, text);
    }

    /**
     * 解析 KEY_EVENT 命令
     * Payload: keycode(1B) + action(1B) + meta_state(4B) = 6B
     */
    public static KeyEventCommand parseKeyEvent(byte[] payload) {
        if (payload.length != 6) {
            throw new IllegalArgumentException("KEY_EVENT payload must be 6 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);
        return new KeyEventCommand(
            buffer.get() & 0xFF,
            buffer.get() & 0xFF,
            buffer.getInt()
        );
    }

    /**
     * 解析 FIND_NODE 命令
     * Payload: matchType(1B) + returnMode(1B) + multiMatch(1B) +
     *          timeoutMs(2B) + queryLen(2B) + query(N)
     */
    public static FindNodeCommand parseFindNode(byte[] payload) {
        if (payload.length < 7) {
            throw new IllegalArgumentException("FIND_NODE payload too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);

        byte matchType = buffer.get();
        byte returnMode = buffer.get();
        boolean multiMatch = buffer.get() != 0;
        int timeoutMs = buffer.getShort() & 0xFFFF;
        int queryLen = buffer.getShort() & 0xFFFF;

        byte[] queryBytes = new byte[queryLen];
        buffer.get(queryBytes);
        String query = new String(queryBytes, StandardCharsets.UTF_8);

        return new FindNodeCommand(matchType, returnMode, multiMatch, timeoutMs, query);
    }

    /**
     * 解析 DUMP_HIERARCHY 命令
     * Payload: format(1B) + compress(1B) + max_depth(2B) = 4B
     */
    public static DumpHierarchyCommand parseDumpHierarchy(byte[] payload) {
        if (payload.length != 4) {
            throw new IllegalArgumentException("DUMP_HIERARCHY payload must be 4 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);
        return new DumpHierarchyCommand(
            buffer.get() & 0xFF,
            buffer.get() & 0xFF,
            buffer.getShort() & 0xFFFF
        );
    }
}
```

### 3.4 ResponseBuilder 响应构建

将业务层返回的结构化结果编码为 Payload 字节流。

```java
public class ResponseBuilder {

    /**
     * 构建 GET_ACTIVITY 响应
     * Payload: success(1B) + package_len(2B) + package(N) + activity_len(2B) + activity(M)
     */
    public static byte[] buildGetActivityResponse(String packageName, String activityName) {
        byte[] pkgBytes = packageName.getBytes(StandardCharsets.UTF_8);
        byte[] actBytes = activityName.getBytes(StandardCharsets.UTF_8);

        ByteBuffer buffer = ByteBuffer.allocate(1 + 2 + pkgBytes.length + 2 + actBytes.length);
        buffer.order(ByteOrder.BIG_ENDIAN);

        buffer.put((byte) 0x01);  // success
        buffer.putShort((short) pkgBytes.length);
        buffer.put(pkgBytes);
        buffer.putShort((short) actBytes.length);
        buffer.put(actBytes);

        return buffer.array();
    }

    /**
     * 构建 FIND_NODE 响应 (Mode 0: 仅坐标)
     * Payload: status(1B) + count(1B) + coords[x(2B) + y(2B)] * N
     */
    public static byte[] buildFindNodeCoordsResponse(int status, List<Point> coords) {
        ByteBuffer buffer = ByteBuffer.allocate(2 + coords.size() * 4);
        buffer.order(ByteOrder.BIG_ENDIAN);

        buffer.put((byte) status);
        buffer.put((byte) coords.size());

        for (Point p : coords) {
            buffer.putShort((short) p.x);
            buffer.putShort((short) p.y);
        }

        return buffer.array();
    }

    /**
     * 构建 FIND_NODE 响应 (Mode 1: 边界框)
     * Payload: status(1B) + count(1B) + boxes[left(2B) + top(2B) + right(2B) + bottom(2B)] * N
     */
    public static byte[] buildFindNodeBoundsResponse(int status, List<Rect> boxes) {
        ByteBuffer buffer = ByteBuffer.allocate(2 + boxes.size() * 8);
        buffer.order(ByteOrder.BIG_ENDIAN);

        buffer.put((byte) status);
        buffer.put((byte) boxes.size());

        for (Rect r : boxes) {
            buffer.putShort((short) r.left);
            buffer.putShort((short) r.top);
            buffer.putShort((short) r.right);
            buffer.putShort((short) r.bottom);
        }

        return buffer.array();
    }

    /**
     * 构建 INPUT_TEXT 响应
     * Payload: status(1B) + actual_method(1B) = 2B
     */
    public static byte[] buildInputTextResponse(int status, int actualMethod) {
        return new byte[]{(byte) status, (byte) actualMethod};
    }

    /**
     * 构建简单成功/失败响应
     * Payload: status(1B)
     */
    public static byte[] buildSimpleResponse(boolean success) {
        return new byte[]{(byte) (success ? 0x01 : 0x00)};
    }
}
```

---

## 4. Layer 3: 调度层

**职责**: 命令路由、序列号去重、熔断保护、ACK 缓存。

**输入**: 解码后的 `DecodedFrame` 对象
**输出**: 编码后的 ACK 帧 `byte[]`

### 4.1 CommandDispatcher

```java
public class CommandDispatcher {

    private final PerceptionEngine perceptionEngine;
    private final ExecutionEngine executionEngine;
    private final SequenceTracker sequenceTracker;
    private final CircuitBreaker circuitBreaker;
    private final Map<Integer, byte[]> ackCache;

    private static final int ACK_CACHE_SIZE = 50;

    public CommandDispatcher(PerceptionEngine perception, ExecutionEngine execution,
                            SequenceTracker tracker, CircuitBreaker breaker) {
        this.perceptionEngine = perception;
        this.executionEngine = execution;
        this.sequenceTracker = tracker;
        this.circuitBreaker = breaker;
        this.ackCache = new LinkedHashMap<Integer, byte[]>(ACK_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) {
                return size() > ACK_CACHE_SIZE;
            }
        };
    }

    /**
     * 分派命令
     *
     * @param frame 解码后的帧
     * @return ACK 响应帧
     */
    public byte[] dispatch(FrameCodec.DecodedFrame frame) {
        // 1. 序列号去重
        if (sequenceTracker.isDuplicate(frame.seq)) {
            Log.d("LXB", "Duplicate seq: " + frame.seq + ", returning cached ACK");
            byte[] cached = ackCache.get(frame.seq);
            if (cached != null) return cached;
            return FrameCodec.encode(frame.seq, CMD_ACK, new byte[]{0x01});
        }

        // 2. 熔断检查
        if (circuitBreaker.shouldReject()) {
            Log.w("LXB", "Circuit breaker triggered, rejecting command");
            return FrameCodec.encode(frame.seq, CMD_ACK, new byte[]{0x00});
        }

        // 3. 命令路由
        byte[] responsePayload;
        try {
            responsePayload = routeCommand(frame.cmd, frame.payload);
        } catch (Exception e) {
            Log.e("LXB", "Command execution failed", e);
            circuitBreaker.recordException();
            responsePayload = new byte[]{0x00};
        }

        // 4. 构建 ACK 帧
        byte[] ack = FrameCodec.encode(frame.seq, CMD_ACK, responsePayload);
        ackCache.put(frame.seq, ack);

        return ack;
    }

    private byte[] routeCommand(byte cmd, byte[] payload) {
        switch (cmd & 0xFF) {
            // Link Layer (0x00-0x0F)
            case 0x01: return handleHandshake(payload);
            case 0x03: return new byte[]{0x01};  // Heartbeat ACK

            // Input Layer (0x10-0x1F)
            case 0x10: return executionEngine.handleTap(payload);
            case 0x11: return executionEngine.handleSwipe(payload);
            case 0x12: return executionEngine.handleLongPress(payload);
            case 0x1B: return executionEngine.handleUnlock();

            // Input Extension (0x20-0x2F)
            case 0x20: return executionEngine.handleInputText(payload);
            case 0x21: return executionEngine.handleKeyEvent(payload);

            // Sense Layer (0x30-0x3F)
            case 0x30: return perceptionEngine.handleGetActivity();
            case 0x31: return perceptionEngine.handleDumpHierarchy(payload);
            case 0x32: return perceptionEngine.handleFindNode(payload);
            case 0x33: return perceptionEngine.handleDumpActions(payload);
            case 0x36: return perceptionEngine.handleGetScreenState();
            case 0x37: return perceptionEngine.handleGetScreenSize();

            // Lifecycle Layer (0x40-0x4F)
            case 0x43: return executionEngine.handleLaunchApp(payload);
            case 0x44: return executionEngine.handleStopApp(payload);

            // Media Layer (0x60-0x6F)
            case 0x60: return perceptionEngine.handleScreenshot();

            default:
                Log.w("LXB", "Unknown command: 0x" + Integer.toHexString(cmd & 0xFF));
                return new byte[]{0x00};
        }
    }

    private byte[] handleHandshake(byte[] payload) {
        // 返回握手确认
        Log.i("LXB", "Handshake received");
        return new byte[]{0x01, VERSION};
    }

    private static final byte CMD_ACK = 0x02;
    private static final byte VERSION = 0x01;
}
```

### 4.2 SequenceTracker 序列号去重

```java
public class SequenceTracker {

    private static final int WINDOW_SIZE = 100;
    private final LinkedHashSet<Integer> receiveWindow = new LinkedHashSet<>();

    /**
     * 检查序列号是否重复
     *
     * @param seq 序列号
     * @return true 如果重复，false 如果是新序列号
     */
    public synchronized boolean isDuplicate(int seq) {
        if (receiveWindow.contains(seq)) {
            return true;
        }

        receiveWindow.add(seq);

        // 淘汰最旧的条目
        if (receiveWindow.size() > WINDOW_SIZE) {
            Iterator<Integer> it = receiveWindow.iterator();
            it.next();
            it.remove();
        }

        return false;
    }

    /**
     * 重置接收窗口
     */
    public synchronized void reset() {
        receiveWindow.clear();
    }
}
```

### 4.3 CircuitBreaker 熔断器

```java
public class CircuitBreaker {

    private static final int STORM_THRESHOLD = 50;       // 50 cmd/秒
    private static final int EXCEPTION_THRESHOLD = 20;   // 20 异常/分钟
    private static final long IDLE_THRESHOLD = 10 * 60 * 1000;  // 10 分钟

    private int commandCount = 0;
    private int exceptionCount = 0;
    private long lastResetTime = System.currentTimeMillis();
    private long lastCommandTime = System.currentTimeMillis();
    private long lastExceptionResetTime = System.currentTimeMillis();

    /**
     * 检查是否应该拒绝命令
     */
    public synchronized boolean shouldReject() {
        long now = System.currentTimeMillis();

        // 每秒重置命令计数器
        if (now - lastResetTime > 1000) {
            commandCount = 0;
            lastResetTime = now;
        }

        commandCount++;
        lastCommandTime = now;

        // 命令风暴检测
        if (commandCount > STORM_THRESHOLD) {
            Log.w("LXB", "Command storm detected: " + commandCount + "/sec");
            return true;
        }

        return false;
    }

    /**
     * 记录异常
     */
    public synchronized void recordException() {
        long now = System.currentTimeMillis();

        // 每分钟重置异常计数器
        if (now - lastExceptionResetTime > 60000) {
            exceptionCount = 0;
            lastExceptionResetTime = now;
        }

        exceptionCount++;

        if (exceptionCount > EXCEPTION_THRESHOLD) {
            Log.e("LXB", "Exception storm detected: " + exceptionCount + "/min");
        }
    }

    /**
     * 检查是否空闲
     */
    public synchronized boolean isIdle() {
        return System.currentTimeMillis() - lastCommandTime > IDLE_THRESHOLD;
    }
}
```

---

## 5. Layer 4: 业务层

**职责**: 执行具体业务逻辑，调用系统层 API 完成操作。

**输入**: 解析后的命令参数
**输出**: 结构化的执行结果

### 5.1 ExecutionEngine 执行引擎

```java
public class ExecutionEngine {

    private final UiAutomationWrapper uiAuto;

    public ExecutionEngine(UiAutomationWrapper uiAuto) {
        this.uiAuto = uiAuto;
    }

    /**
     * 处理 TAP 命令
     */
    public byte[] handleTap(byte[] payload) {
        TapCommand cmd = CommandParser.parseTap(payload);
        Log.i("LXB", String.format("TAP: x=%d, y=%d", cmd.x, cmd.y));

        boolean success = uiAuto.click(cmd.x, cmd.y);
        return ResponseBuilder.buildSimpleResponse(success);
    }

    /**
     * 处理 SWIPE 命令
     */
    public byte[] handleSwipe(byte[] payload) {
        SwipeCommand cmd = CommandParser.parseSwipe(payload);
        Log.i("LXB", String.format("SWIPE: (%d,%d)->(%d,%d), duration=%dms",
            cmd.x1, cmd.y1, cmd.x2, cmd.y2, cmd.duration));

        boolean success = uiAuto.swipe(cmd.x1, cmd.y1, cmd.x2, cmd.y2, cmd.duration);
        return ResponseBuilder.buildSimpleResponse(success);
    }

    /**
     * 处理 LONG_PRESS 命令
     */
    public byte[] handleLongPress(byte[] payload) {
        LongPressCommand cmd = CommandParser.parseLongPress(payload);
        Log.i("LXB", String.format("LONG_PRESS: x=%d, y=%d, duration=%dms",
            cmd.x, cmd.y, cmd.duration));

        boolean success = uiAuto.longPress(cmd.x, cmd.y, cmd.duration);
        return ResponseBuilder.buildSimpleResponse(success);
    }

    /**
     * 处理 INPUT_TEXT 命令 (统一剪贴板方法)
     */
    public byte[] handleInputText(byte[] payload) {
        InputTextCommand cmd = CommandParser.parseInputText(payload);
        Log.i("LXB", String.format("INPUT_TEXT: text='%s', flags=0x%02X",
            cmd.text, cmd.flags));

        try {
            // 1. 可选：点击目标坐标获取焦点
            if (cmd.targetX != 0 || cmd.targetY != 0) {
                uiAuto.click(cmd.targetX, cmd.targetY);
                Thread.sleep(100);
            }

            // 2. 可选：清空现有内容
            if ((cmd.flags & 0x01) != 0) {  // FLAG_CLEAR_FIRST
                uiAuto.clearFocusedText();
            }

            // 3. 写入剪贴板并粘贴
            uiAuto.setClipboard(cmd.text);
            uiAuto.paste();

            // 4. 可选：按回车
            if ((cmd.flags & 0x02) != 0) {  // FLAG_PRESS_ENTER
                Thread.sleep(cmd.delayMs);
                uiAuto.pressKey(KeyEvent.KEYCODE_ENTER);
            }

            // 5. 可选：隐藏键盘
            if ((cmd.flags & 0x04) != 0) {  // FLAG_HIDE_KEYBOARD
                uiAuto.pressKey(KeyEvent.KEYCODE_BACK);
            }

            return ResponseBuilder.buildInputTextResponse(0x01, 0x01);

        } catch (Exception e) {
            Log.e("LXB", "INPUT_TEXT failed", e);
            return ResponseBuilder.buildInputTextResponse(0x00, 0x01);
        }
    }

    /**
     * 处理 KEY_EVENT 命令 (自动降级)
     */
    public byte[] handleKeyEvent(byte[] payload) {
        KeyEventCommand cmd = CommandParser.parseKeyEvent(payload);
        Log.i("LXB", String.format("KEY_EVENT: keycode=%d, action=%d",
            cmd.keycode, cmd.action));

        try {
            // 优先使用 UiAutomation 注入
            boolean success = uiAuto.injectKeyEvent(cmd.keycode, cmd.action, cmd.metaState);
            return ResponseBuilder.buildSimpleResponse(success);

        } catch (SecurityException e) {
            // 降级到 Shell 命令
            Log.w("LXB", "Key injection failed, falling back to shell");
            try {
                Runtime.getRuntime().exec("input keyevent " + cmd.keycode).waitFor();
                return ResponseBuilder.buildSimpleResponse(true);
            } catch (Exception ex) {
                return ResponseBuilder.buildSimpleResponse(false);
            }
        }
    }
}
```

### 5.2 PerceptionEngine 感知引擎

```java
public class PerceptionEngine {

    private final UiAutomationWrapper uiAuto;

    public PerceptionEngine(UiAutomationWrapper uiAuto) {
        this.uiAuto = uiAuto;
    }

    /**
     * 处理 GET_ACTIVITY 命令
     */
    public byte[] handleGetActivity() {
        try {
            String[] info = uiAuto.getCurrentActivity();
            Log.i("LXB", String.format("GET_ACTIVITY: %s/%s", info[0], info[1]));
            return ResponseBuilder.buildGetActivityResponse(info[0], info[1]);
        } catch (Exception e) {
            Log.e("LXB", "GET_ACTIVITY failed", e);
            return new byte[]{0x00};
        }
    }

    /**
     * 处理 FIND_NODE 命令 (计算卸载)
     */
    public byte[] handleFindNode(byte[] payload) {
        FindNodeCommand cmd = CommandParser.parseFindNode(payload);
        Log.i("LXB", String.format("FIND_NODE: query='%s', type=%d, mode=%d",
            cmd.query, cmd.matchType, cmd.returnMode));

        AccessibilityNodeInfo root = uiAuto.getRootNode();
        if (root == null) {
            return ResponseBuilder.buildFindNodeCoordsResponse(0x00, new ArrayList<>());
        }

        // 执行查找
        List<AccessibilityNodeInfo> results = findNodes(root, cmd);

        // 根据返回模式构建响应
        if (cmd.returnMode == 0) {  // RETURN_COORDS
            List<Point> coords = new ArrayList<>();
            for (AccessibilityNodeInfo node : results) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                coords.add(new Point(bounds.centerX(), bounds.centerY()));
            }
            return ResponseBuilder.buildFindNodeCoordsResponse(
                results.isEmpty() ? 0x00 : 0x01, coords);
        } else {  // RETURN_BOUNDS
            List<Rect> boxes = new ArrayList<>();
            for (AccessibilityNodeInfo node : results) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                boxes.add(bounds);
            }
            return ResponseBuilder.buildFindNodeBoundsResponse(
                results.isEmpty() ? 0x00 : 0x01, boxes);
        }
    }

    /**
     * 节点查找核心逻辑 (BFS 遍历)
     */
    private List<AccessibilityNodeInfo> findNodes(
            AccessibilityNodeInfo root, FindNodeCommand cmd) {

        List<AccessibilityNodeInfo> results = new ArrayList<>();
        Queue<AccessibilityNodeInfo> queue = new LinkedList<>();
        queue.offer(root);

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;

            // 检查匹配
            if (matchNode(node, cmd)) {
                results.add(node);
                if (!cmd.multiMatch) break;  // 仅返回首个
            }

            // 遍历子节点
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.offer(child);
            }
        }

        return results;
    }

    /**
     * 节点匹配逻辑
     */
    private boolean matchNode(AccessibilityNodeInfo node, FindNodeCommand cmd) {
        switch (cmd.matchType) {
            case 0:  // MATCH_EXACT_TEXT
                CharSequence text = node.getText();
                return text != null && text.toString().equals(cmd.query);

            case 1:  // MATCH_CONTAINS_TEXT
                CharSequence t = node.getText();
                return t != null && t.toString().contains(cmd.query);

            case 3:  // MATCH_RESOURCE_ID
                String resId = node.getViewIdResourceName();
                return resId != null && resId.equals(cmd.query);

            case 4:  // MATCH_CLASS
                CharSequence cls = node.getClassName();
                return cls != null && cls.toString().equals(cmd.query);

            case 5:  // MATCH_DESCRIPTION
                CharSequence desc = node.getContentDescription();
                return desc != null && desc.toString().contains(cmd.query);

            default:
                return false;
        }
    }

    /**
     * 处理 DUMP_HIERARCHY 命令
     */
    public byte[] handleDumpHierarchy(byte[] payload) {
        DumpHierarchyCommand cmd = CommandParser.parseDumpHierarchy(payload);
        Log.i("LXB", String.format("DUMP_HIERARCHY: format=%d, compress=%d",
            cmd.format, cmd.compress));

        AccessibilityNodeInfo root = uiAuto.getRootNode();
        if (root == null) {
            return new byte[0];
        }

        // 构建节点列表
        List<UINode> nodes = new ArrayList<>();
        StringPool pool = new StringPool();
        traverseAndExtract(root, -1, nodes, pool, cmd.maxDepth, 0);

        // 序列化为二进制格式
        return serializeHierarchy(nodes, pool, cmd.compress);
    }

    /**
     * 遍历并提取节点
     */
    private void traverseAndExtract(AccessibilityNodeInfo node, int parentIndex,
            List<UINode> nodes, StringPool pool, int maxDepth, int currentDepth) {

        if (node == null) return;
        if (maxDepth > 0 && currentDepth >= maxDepth) return;

        // 过滤：仅保留可见且有意义的节点
        if (!shouldIncludeNode(node)) return;

        int nodeIndex = nodes.size();
        UINode uiNode = extractNodeFeatures(node, parentIndex, pool);
        nodes.add(uiNode);

        // 递归子节点
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            traverseAndExtract(child, nodeIndex, nodes, pool, maxDepth, currentDepth + 1);
        }
    }

    /**
     * 判断节点是否应该包含
     */
    private boolean shouldIncludeNode(AccessibilityNodeInfo node) {
        if (!node.isVisibleToUser()) return false;
        if (node.isClickable()) return true;
        if (node.isEditable()) return true;
        if (node.isScrollable()) return true;
        if (node.getText() != null && node.getText().length() > 0) return true;
        return false;
    }
}
```

---

## 6. Layer 5: 系统层

**职责**: 封装 Android 系统 Hidden API（UiAutomation），隔离平台差异。

**输入**: 高级操作请求（点击坐标、滑动路径等）
**输出**: 操作结果（成功/失败）

### 6.1 UiAutomationWrapper

```java
public class UiAutomationWrapper {

    private UiAutomation uiAutomation;
    private static final String TAG = "LXB";

    /**
     * 初始化 UiAutomation
     * 必须在 app_process 环境中调用
     */
    public void initialize() throws Exception {
        Log.i(TAG, "Initializing UiAutomation...");

        // 准备 Looper
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }

        // 通过反射获取 UiAutomation 实例
        // 参考: scrcpy/app_process 实现
        this.uiAutomation = createUiAutomation();

        Log.i(TAG, "UiAutomation initialized successfully");
    }

    private UiAutomation createUiAutomation() throws Exception {
        // 实现方式取决于 Android 版本
        // Android 8+: 使用 UiAutomation#connect()
        // Android 11+: 可能需要额外权限处理

        // 简化实现，实际需要根据系统版本调整
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method currentActivityThread = activityThread.getMethod("currentActivityThread");
        Object thread = currentActivityThread.invoke(null);

        // 获取 Instrumentation
        Field instrumentationField = activityThread.getDeclaredField("mInstrumentation");
        instrumentationField.setAccessible(true);
        Instrumentation instrumentation = (Instrumentation) instrumentationField.get(thread);

        // 获取 UiAutomation
        return instrumentation.getUiAutomation();
    }

    /**
     * 获取当前 Activity 信息
     * @return [packageName, activityName]
     */
    public String[] getCurrentActivity() {
        AccessibilityNodeInfo root = getRootNode();
        if (root == null) return new String[]{"", ""};

        String packageName = root.getPackageName() != null ?
            root.getPackageName().toString() : "";

        // 获取 Activity 名需要解析 WindowManager
        String activityName = getTopActivityFromWindowManager();

        return new String[]{packageName, activityName};
    }

    private String getTopActivityFromWindowManager() {
        try {
            // 通过 dumpsys 获取
            Process process = Runtime.getRuntime().exec("dumpsys window windows");
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("mCurrentFocus") || line.contains("mFocusedApp")) {
                    // 解析 Activity 名
                    int start = line.indexOf('/');
                    int end = line.indexOf('}');
                    if (start > 0 && end > start) {
                        return line.substring(start, end);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get top activity", e);
        }
        return "";
    }

    /**
     * 获取根节点
     */
    public AccessibilityNodeInfo getRootNode() {
        try {
            return uiAutomation.getRootInActiveWindow();
        } catch (Exception e) {
            Log.e(TAG, "Failed to get root node", e);
            return null;
        }
    }

    /**
     * 点击屏幕
     */
    public boolean click(int x, int y) {
        long downTime = SystemClock.uptimeMillis();

        MotionEvent down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(
            downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0);

        try {
            boolean result = uiAutomation.injectInputEvent(down, true) &&
                           uiAutomation.injectInputEvent(up, true);
            Log.d(TAG, String.format("Click (%d, %d): %s", x, y, result));
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Click injection failed", e);
            return false;
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    /**
     * 滑动手势
     */
    public boolean swipe(int x1, int y1, int x2, int y2, int duration) {
        long downTime = SystemClock.uptimeMillis();
        int steps = Math.max(2, duration / 16);  // 60fps

        try {
            // DOWN
            MotionEvent down = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, x1, y1, 0);
            uiAutomation.injectInputEvent(down, true);
            down.recycle();

            // MOVE
            for (int i = 1; i < steps; i++) {
                float t = (float) i / steps;
                int x = (int) (x1 + (x2 - x1) * t);
                int y = (int) (y1 + (y2 - y1) * t);
                long eventTime = downTime + (duration * i / steps);

                MotionEvent move = MotionEvent.obtain(
                    downTime, eventTime, MotionEvent.ACTION_MOVE, x, y, 0);
                uiAutomation.injectInputEvent(move, true);
                move.recycle();
            }

            // UP
            MotionEvent up = MotionEvent.obtain(
                downTime, downTime + duration, MotionEvent.ACTION_UP, x2, y2, 0);
            uiAutomation.injectInputEvent(up, true);
            up.recycle();

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Swipe injection failed", e);
            return false;
        }
    }

    /**
     * 长按
     */
    public boolean longPress(int x, int y, int duration) {
        long downTime = SystemClock.uptimeMillis();

        try {
            MotionEvent down = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
            uiAutomation.injectInputEvent(down, true);
            down.recycle();

            Thread.sleep(duration);

            MotionEvent up = MotionEvent.obtain(
                downTime, downTime + duration, MotionEvent.ACTION_UP, x, y, 0);
            uiAutomation.injectInputEvent(up, true);
            up.recycle();

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Long press failed", e);
            return false;
        }
    }

    /**
     * 注入按键事件
     */
    public boolean injectKeyEvent(int keycode, int action, int metaState) {
        long now = SystemClock.uptimeMillis();

        try {
            if (action == 2) {  // Click = DOWN + UP
                KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                    keycode, 0, metaState);
                KeyEvent up = new KeyEvent(now, now + 50, KeyEvent.ACTION_UP,
                    keycode, 0, metaState);
                return uiAutomation.injectInputEvent(down, true) &&
                       uiAutomation.injectInputEvent(up, true);
            } else {
                KeyEvent event = new KeyEvent(now, now, action, keycode, 0, metaState);
                return uiAutomation.injectInputEvent(event, true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Key event injection failed", e);
            throw new SecurityException("Key injection failed", e);
        }
    }

    /**
     * 按下指定按键
     */
    public boolean pressKey(int keycode) {
        return injectKeyEvent(keycode, 2, 0);
    }

    /**
     * 设置剪贴板内容
     */
    public void setClipboard(String text) {
        try {
            // 通过反射获取 ClipboardManager
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "clipboard");

            Class<?> clipboardInterface = Class.forName(
                "android.content.IClipboard$Stub");
            Method asInterface = clipboardInterface.getMethod("asInterface", IBinder.class);
            Object clipboard = asInterface.invoke(null, binder);

            // 设置剪贴板内容
            ClipData clip = ClipData.newPlainText("lxb", text);
            Method setPrimaryClip = clipboard.getClass().getMethod(
                "setPrimaryClip", ClipData.class, String.class, int.class);
            setPrimaryClip.invoke(clipboard, clip, "com.lxb.server", 0);

        } catch (Exception e) {
            Log.e(TAG, "Failed to set clipboard", e);
        }
    }

    /**
     * 执行粘贴操作
     */
    public void paste() {
        // Ctrl+V
        injectKeyEvent(KeyEvent.KEYCODE_V, 2, KeyEvent.META_CTRL_ON);
    }

    /**
     * 清空当前焦点输入框
     */
    public void clearFocusedText() {
        // Ctrl+A 全选
        injectKeyEvent(KeyEvent.KEYCODE_A, 2, KeyEvent.META_CTRL_ON);
        // Delete 删除
        pressKey(KeyEvent.KEYCODE_DEL);
    }
}
```

### 6.2 Hidden API 绕过

Android 9+ 限制了 Hidden API 的访问，需要使用绕过机制：

```java
public class HiddenApiBypass {

    /**
     * 绕过 Hidden API 限制
     * 应在 Main 入口处调用
     */
    public static void bypass() {
        try {
            // 方法1: 设置 VM 属性
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getMethod("getRuntime");
            Object runtime = getRuntime.invoke(null);
            Method setHiddenApiExemptions = vmRuntime.getMethod(
                "setHiddenApiExemptions", String[].class);
            setHiddenApiExemptions.invoke(runtime, (Object) new String[]{"L"});

            Log.i("LXB", "Hidden API bypass: success");
        } catch (Exception e) {
            Log.w("LXB", "Hidden API bypass failed, trying alternative", e);

            try {
                // 方法2: 使用反射设置策略
                Class<?> policy = Class.forName(
                    "android.content.pm.ApplicationInfo");
                Field hiddenApiField = policy.getDeclaredField(
                    "mHiddenApiPolicy");
                hiddenApiField.setAccessible(true);
                // 设置策略为允许所有
            } catch (Exception e2) {
                Log.e("LXB", "All Hidden API bypass methods failed", e2);
            }
        }
    }
}
```

---

## 7. 启动流程

### 7.1 Main 入口

```java
public class Main {

    public static void main(String[] args) {
        System.out.println("[LXB] Server starting...");

        try {
            // 0. 绕过 Hidden API 限制
            HiddenApiBypass.bypass();

            // 1. 初始化系统层
            UiAutomationWrapper uiAuto = new UiAutomationWrapper();
            uiAuto.initialize();

            // 2. 初始化业务层
            ExecutionEngine executionEngine = new ExecutionEngine(uiAuto);
            PerceptionEngine perceptionEngine = new PerceptionEngine(uiAuto);

            // 3. 初始化调度层
            SequenceTracker sequenceTracker = new SequenceTracker();
            CircuitBreaker circuitBreaker = new CircuitBreaker();
            CommandDispatcher dispatcher = new CommandDispatcher(
                perceptionEngine, executionEngine, sequenceTracker, circuitBreaker);

            // 4. 初始化网络层
            UdpServer server = new UdpServer();
            server.listen(12345);

            System.out.println("[LXB] Server listening on port 12345");

            // 5. 单线程事件循环
            while (true) {
                try {
                    // 网络层: 接收原始数据
                    UdpServer.ReceivedFrame rawFrame = server.receive(1000);

                    // 协议层: 解码 + CRC 验证 ("转译")
                    FrameCodec.DecodedFrame frame;
                    try {
                        frame = FrameCodec.decode(rawFrame.data);
                    } catch (CRCException e) {
                        Log.w("LXB", "CRC check failed, dropping frame");
                        continue;
                    } catch (ProtocolException e) {
                        Log.w("LXB", "Protocol error: " + e.getMessage());
                        continue;
                    }

                    Log.i("LXB", String.format("Frame: seq=%d, cmd=0x%02X, len=%d",
                        frame.seq, frame.cmd & 0xFF, frame.payload.length));

                    // 调度层: 路由 + 去重 + 熔断
                    byte[] response = dispatcher.dispatch(frame);

                    // 网络层: 发送响应
                    server.send(rawFrame.address, rawFrame.port, response);

                } catch (SocketTimeoutException e) {
                    // 正常超时，继续循环
                }
            }

        } catch (Exception e) {
            System.err.println("[LXB] Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
```

### 7.2 启动命令

通过 app_process 启动服务器：

```bash
# 推送 JAR 文件
adb push lxb-server.zip /data/local/tmp/

# 启动服务器
adb shell CLASSPATH=/data/local/tmp/lxb-server.zip \
    app_process /system/bin com.lxb.server.Main
```

后台运行：

```bash
adb shell "nohup CLASSPATH=/data/local/tmp/lxb-server.zip \
    app_process /system/bin com.lxb.server.Main > /dev/null 2>&1 &"
```

---

## 8. 错误处理

### 8.1 错误处理原则

**核心决策**: 各端内部处理错误，不互通详细错误码，统一返回简单状态。

| 状态值 | 含义 |
|--------|------|
| `0x00` | 失败 |
| `0x01` | 成功 |
| `0x02` | 部分成功 (仅 INPUT_TEXT) |

### 8.2 日志规范

```java
// ERROR: 致命错误
Log.e("LXB", "UiAutomation initialization failed", exception);

// WARN: 可恢复错误
Log.w("LXB", "CRC check failed, dropping frame");

// INFO: 关键业务流程
Log.i("LXB", "FIND_NODE: query=登录, results=1");

// DEBUG: 详细调试信息
Log.d("LXB", "Frame received: " + bytesToHex(data));
```

### 8.3 异常处理策略

| 异常类型 | 处理策略 |
|----------|----------|
| CRCException | 丢弃帧，不响应 |
| ProtocolException | 丢弃帧，不响应 |
| SecurityException | 降级到 Shell 命令 |
| 业务异常 | 返回 status=0x00 |
| 未知异常 | 记录日志，返回 status=0x00 |

---

## 附录 A: 协议兼容性检查清单

### A.1 帧格式验证

- [ ] Magic 字段: `0xAA55` (大端序: `[0xAA, 0x55]`)
- [ ] Version 字段: `0x01`
- [ ] Sequence 字段: 大端序 4 字节
- [ ] Command 字段: 1 字节
- [ ] Length 字段: 大端序 2 字节
- [ ] CRC32: 位于**帧尾**，大端序 4 字节
- [ ] CRC32 计算范围: Header (10B) + Payload (N B)

### A.2 字节序验证

```java
// Java 端必须显式设置
ByteBuffer buffer = ByteBuffer.allocate(size);
buffer.order(ByteOrder.BIG_ENDIAN);  // 必须！

// 测试用例
int seq = 0x12345678;
buffer.putInt(seq);
// 结果应为: [0x12, 0x34, 0x56, 0x78]
```

### A.3 命令实现状态

| 命令 | ID | 状态 |
|------|-----|------|
| HANDSHAKE | 0x01 | ✅ 实现 |
| ACK | 0x02 | ✅ 实现 |
| HEARTBEAT | 0x03 | ✅ 实现 |
| TAP | 0x10 | ✅ 实现 |
| SWIPE | 0x11 | ✅ 实现 |
| LONG_PRESS | 0x12 | ✅ 实现 |
| UNLOCK | 0x1B | ✅ 实现 |
| INPUT_TEXT | 0x20 | ✅ 实现 |
| KEY_EVENT | 0x21 | ✅ 实现 |
| GET_ACTIVITY | 0x30 | ✅ 实现 |
| DUMP_HIERARCHY | 0x31 | ✅ 实现 |
| FIND_NODE | 0x32 | ✅ 实现 |
| DUMP_ACTIONS | 0x33 | ✅ 实现 |
| GET_SCREEN_STATE | 0x36 | ✅ 实现 |
| GET_SCREEN_SIZE | 0x37 | ✅ 实现 |
| LAUNCH_APP | 0x43 | ✅ 实现 |
| STOP_APP | 0x44 | ✅ 实现 |
| SCREENSHOT | 0x60 | ✅ 实现 |

---

**文档维护说明**: 本文档与 `LXB-Link.md` 协议规范保持同步。修改帧格式或命令定义时，两个文档必须同时更新。
