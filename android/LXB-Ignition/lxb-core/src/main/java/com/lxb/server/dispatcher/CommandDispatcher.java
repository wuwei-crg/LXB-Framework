package com.lxb.server.dispatcher;

import com.lxb.server.daemon.CircuitBreaker;
import com.lxb.server.daemon.SequenceTracker;
import com.lxb.server.execution.ExecutionEngine;
import com.lxb.server.perception.PerceptionEngine;
import com.lxb.server.protocol.ProtocolFrame;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 指令分派器
 * 职责: 根据 cmd 字段路由到不同引擎，集成序列号去重和熔断保护
 */
public class CommandDispatcher {
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
     * @param frame 帧信息
     * @param payload 负载数据
     * @return ACK 响应帧
     */
    public byte[] dispatch(ProtocolFrame.FrameInfo frame, byte[] payload) {
        // 1. 序列号去重
        if (sequenceTracker.isDuplicate(frame.seq)) {
            System.out.println("[Dispatcher] Duplicate seq=" + frame.seq + ", returning cached ACK");
            byte[] cached = ackCache.get(frame.seq);
            if (cached != null) {
                return cached;
            }
            // 缓存未命中，重新构建空 ACK
            return buildAck(frame.seq, (byte) 0x02, new byte[0]);
        }

        // 2. 熔断检查
        if (circuitBreaker.shouldReject()) {
            System.out.println("[Dispatcher] Circuit breaker triggered, rejecting");
            return buildErrorAck(frame.seq, (byte) 0xFF);
        }

        // 3. 指令路由
        byte[] response;
        try {
            switch (frame.cmd) {
                // Link Layer
                case 0x01:  // CMD_HANDSHAKE
                    response = handleHandshake();
                    break;
                case 0x03:  // CMD_HEARTBEAT
                    response = new byte[]{0x01};
                    break;

                // Input Layer
                case 0x10:  // CMD_TAP
                    response = executionEngine.handleTap(payload);
                    break;
                case 0x11:  // CMD_SWIPE
                    response = executionEngine.handleSwipe(payload);
                    break;
                case 0x12:  // CMD_LONG_PRESS
                    response = executionEngine.handleLongPress(payload);
                    break;

                // Input Extension
                case 0x20:  // CMD_INPUT_TEXT
                    response = executionEngine.handleInputText(payload);
                    break;
                case 0x21:  // CMD_KEY_EVENT
                    response = executionEngine.handleInputKeyEvent(payload);
                    break;

                // Sense Layer
                case 0x30:  // CMD_GET_ACTIVITY
                    response = perceptionEngine.handleGetActivity();
                    break;
                case 0x31:  // CMD_DUMP_HIERARCHY
                    response = perceptionEngine.handleDumpHierarchy(payload);
                    break;
                case 0x32:  // CMD_FIND_NODE
                    response = perceptionEngine.handleFindNode(payload);
                    break;

                default:
                    System.out.println("[Dispatcher] Unimplemented command: 0x" +
                            String.format("%02X", frame.cmd));
                    response = new byte[]{0x00};  // 未实现
            }

            // 4. 封装 ACK
            byte[] ack = buildAck(frame.seq, (byte) 0x02, response);
            ackCache.put(frame.seq, ack);
            return ack;

        } catch (Exception e) {
            circuitBreaker.recordException();
            System.err.println("[Dispatcher] Error handling cmd=0x" +
                    String.format("%02X", frame.cmd) + ": " + e.getMessage());
            e.printStackTrace();
            return buildErrorAck(frame.seq, (byte) 0x00);
        }
    }

    private byte[] buildAck(int seq, byte cmd, byte[] payload) {
        return ProtocolFrame.pack(seq, cmd, payload);
    }

    private byte[] buildErrorAck(int seq, byte status) {
        return ProtocolFrame.pack(seq, (byte) 0x02, new byte[]{status});
    }

    private byte[] handleHandshake() {
        System.out.println("[Link] Handshake received");
        return new byte[0];  // 空响应
    }
}
