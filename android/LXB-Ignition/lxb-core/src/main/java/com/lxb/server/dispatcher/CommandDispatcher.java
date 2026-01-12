package com.lxb.server.dispatcher;

import com.lxb.server.daemon.CircuitBreaker;
import com.lxb.server.daemon.SequenceTracker;
import com.lxb.server.execution.ExecutionEngine;
import com.lxb.server.perception.PerceptionEngine;
import com.lxb.server.protocol.FrameCodec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 指令分派器
 *
 * 职责: 根据 cmd 字段路由到不同引擎，集成序列号去重和熔断保护
 *
 * 命令 ID 分配 (Layered ISA):
 * - 0x00-0x0F: Link Layer (handshake, ACK, heartbeat)
 * - 0x10-0x1F: Input Layer (tap, swipe, long_press, unlock)
 * - 0x20-0x2F: Input Extension (text input, key events)
 * - 0x30-0x3F: Sense Layer (activity, UI tree, screen state)
 * - 0x40-0x4F: Lifecycle Layer (launch/stop app)
 */
public class CommandDispatcher {

    private static final String TAG = "[LXB][Dispatcher]";

    private final PerceptionEngine perceptionEngine;
    private final ExecutionEngine executionEngine;
    private final SequenceTracker sequenceTracker;
    private final CircuitBreaker circuitBreaker;

    // ACK 缓存（LRU，最多 50 条）
    private final Map<Integer, byte[]> ackCache = new LinkedHashMap<Integer, byte[]>(50, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) {
            return size() > 50;
        }
    };

    public CommandDispatcher(
            PerceptionEngine perceptionEngine,
            ExecutionEngine executionEngine,
            SequenceTracker sequenceTracker,
            CircuitBreaker circuitBreaker) {
        this.perceptionEngine = perceptionEngine;
        this.executionEngine = executionEngine;
        this.sequenceTracker = sequenceTracker;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * 分派处理命令
     *
     * @param frame 帧信息
     * @param payload 负载数据
     * @return ACK 响应帧
     */
    public byte[] dispatch(FrameCodec.FrameInfo frame, byte[] payload) {
        // 1. 序列号去重
        if (sequenceTracker.isDuplicate(frame.seq)) {
            System.out.println(TAG + " Duplicate seq=" + frame.seq + ", returning cached ACK");
            byte[] cached = ackCache.get(frame.seq);
            if (cached != null) {
                return cached;
            }
            // 缓存未命中，重新构建空 ACK
            return buildAck(frame.seq, (byte) 0x02, new byte[0]);
        }

        // 2. 熔断检查
        if (circuitBreaker.shouldReject()) {
            System.out.println(TAG + " Circuit breaker triggered, rejecting");
            return buildErrorAck(frame.seq, (byte) 0xFF);
        }

        // 3. 指令路由
        byte[] response;
        try {
            switch (frame.cmd) {
                // =================================================================
                // Link Layer (0x00-0x0F)
                // =================================================================
                case 0x01:  // CMD_HANDSHAKE
                    response = handleHandshake();
                    break;
                case 0x03:  // CMD_HEARTBEAT
                    response = new byte[]{0x01};
                    break;

                // =================================================================
                // Input Layer (0x10-0x1F)
                // =================================================================
                case 0x10:  // CMD_TAP
                    response = executionEngine.handleTap(payload);
                    break;
                case 0x11:  // CMD_SWIPE
                    response = executionEngine.handleSwipe(payload);
                    break;
                case 0x12:  // CMD_LONG_PRESS
                    response = executionEngine.handleLongPress(payload);
                    break;
                case 0x1B:  // CMD_UNLOCK ⭐ NEW
                    response = executionEngine.handleUnlock(payload);
                    break;

                // =================================================================
                // Input Extension (0x20-0x2F)
                // =================================================================
                case 0x20:  // CMD_INPUT_TEXT
                    response = executionEngine.handleInputText(payload);
                    break;
                case 0x21:  // CMD_KEY_EVENT
                    response = executionEngine.handleKeyEvent(payload);
                    break;

                // =================================================================
                // Sense Layer (0x30-0x3F)
                // =================================================================
                case 0x30:  // CMD_GET_ACTIVITY
                    response = perceptionEngine.handleGetActivity();
                    break;
                case 0x31:  // CMD_DUMP_HIERARCHY
                    response = perceptionEngine.handleDumpHierarchy(payload);
                    break;
                case 0x32:  // CMD_FIND_NODE
                    response = perceptionEngine.handleFindNode(payload);
                    break;
                case 0x33:  // CMD_DUMP_ACTIONS ⭐ NEW
                    response = perceptionEngine.handleDumpActions(payload);
                    break;
                case 0x36:  // CMD_GET_SCREEN_STATE ⭐ NEW
                    response = perceptionEngine.handleGetScreenState();
                    break;
                case 0x37:  // CMD_GET_SCREEN_SIZE ⭐ NEW
                    response = perceptionEngine.handleGetScreenSize();
                    break;

                // =================================================================
                // Lifecycle Layer (0x40-0x4F)
                // =================================================================
                case 0x43:  // CMD_LAUNCH_APP ⭐ NEW
                    response = executionEngine.handleLaunchApp(payload);
                    break;
                case 0x44:  // CMD_STOP_APP ⭐ NEW
                    response = executionEngine.handleStopApp(payload);
                    break;

                // =================================================================
                // Media Layer (0x60-0x6F)
                // =================================================================
                case 0x60:  // CMD_SCREENSHOT ⭐
                    response = perceptionEngine.handleScreenshot();
                    break;

                // =================================================================
                // Default: Unimplemented
                // =================================================================
                default:
                    System.out.println(TAG + " Unimplemented command: 0x" +
                            String.format("%02X", frame.cmd));
                    response = new byte[]{0x00};  // 未实现
            }

            // 4. 封装 ACK
            byte[] ack = buildAck(frame.seq, (byte) 0x02, response);
            ackCache.put(frame.seq, ack);
            return ack;

        } catch (Exception e) {
            circuitBreaker.recordException();
            System.err.println(TAG + " Error handling cmd=0x" +
                    String.format("%02X", frame.cmd) + ": " + e.getMessage());
            e.printStackTrace();
            return buildErrorAck(frame.seq, (byte) 0x00);
        }
    }

    /**
     * 构建 ACK 帧
     */
    private byte[] buildAck(int seq, byte cmd, byte[] payload) {
        return FrameCodec.encode(seq, cmd, payload);
    }

    /**
     * 构建错误 ACK 帧
     */
    private byte[] buildErrorAck(int seq, byte status) {
        return FrameCodec.encode(seq, (byte) 0x02, new byte[]{status});
    }

    /**
     * 处理握手
     */
    private byte[] handleHandshake() {
        System.out.println(TAG + " Handshake received");
        return new byte[0];  // 空响应
    }
}
