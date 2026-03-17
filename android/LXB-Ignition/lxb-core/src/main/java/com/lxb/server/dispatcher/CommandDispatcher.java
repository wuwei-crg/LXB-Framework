package com.lxb.server.dispatcher;

import com.lxb.server.daemon.CircuitBreaker;
import com.lxb.server.daemon.SequenceTracker;
import com.lxb.server.cortex.CortexFacade;
import com.lxb.server.execution.ExecutionEngine;
import com.lxb.server.perception.PerceptionEngine;
import com.lxb.server.protocol.CommandIds;
import com.lxb.server.protocol.FrameCodec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Command dispatcher.
 *
 * Design note:
 * - No session/connection state is kept here.
 * - UDP duplicate handling is done with a short-lived frame fingerprint window.
 */
public class CommandDispatcher {

    private static final String TAG = "[LXB][Dispatcher]";

    private final PerceptionEngine perceptionEngine;
    private final ExecutionEngine executionEngine;
    private final SequenceTracker sequenceTracker;
    private final CircuitBreaker circuitBreaker;
    private final CortexFacade cortexFacade;

    // ACK cache keyed by frame fingerprint (for UDP retry dedup only).
    private final Map<SequenceTracker.FrameKey, byte[]> ackCache =
            new LinkedHashMap<SequenceTracker.FrameKey, byte[]>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<SequenceTracker.FrameKey, byte[]> eldest) {
                    return size() > 128;
                }
            };

    public CommandDispatcher(
            PerceptionEngine perceptionEngine,
            ExecutionEngine executionEngine,
            SequenceTracker sequenceTracker,
            CircuitBreaker circuitBreaker
    ) {
        this.perceptionEngine = perceptionEngine;
        this.executionEngine = executionEngine;
        this.sequenceTracker = sequenceTracker;
        this.circuitBreaker = circuitBreaker;
        this.cortexFacade = new CortexFacade(perceptionEngine, executionEngine);
    }

    /**
     * Dispatch command and return ACK frame.
     */
    public byte[] dispatch(FrameCodec.FrameInfo frame, byte[] payload, String peerTag) {
        SequenceTracker.FrameKey frameKey = new SequenceTracker.FrameKey(frame.seq, frame.cmd, payload, peerTag);

        // 0) Strong duplicate protection: if we have a cached ACK for this exact
        //    frame fingerprint (same seq + cmd + payload hash), reuse it directly.
        //    This handles late UDP retries that arrive AFTER a long-running
        //    command has finished, and avoids re-executing expensive commands
        //    like CORTEX_ROUTE_RUN twice.
        byte[] cachedAck = ackCache.get(frameKey);
        if (cachedAck != null) {
            System.out.println(TAG + " Cached ACK hit, skipping re-dispatch for seq="
                    + frame.seq + ", cmd=0x" + String.format("%02X", frame.cmd & 0xFF));
            return cachedAck;
        }

        // 1) Short-lived duplicate detection (same seq+cmd+payload fingerprint)
        if (sequenceTracker.isDuplicate(frame.seq, frame.cmd, payload, peerTag)) {
            System.out.println(TAG + " Duplicate frame detected, returning cached ACK");
            byte[] cached = ackCache.get(frameKey);
            if (cached != null) {
                return cached;
            }
            // Defensive fallback for duplicate-without-cache:
            return buildAck(frame.seq, CommandIds.CMD_ACK, new byte[0]);
        }

        // 2) Circuit breaker
        if (circuitBreaker.shouldReject()) {
            System.out.println(TAG + " Circuit breaker triggered, rejecting");
            return buildErrorAck(frame.seq, (byte) 0xFF);
        }

        // 3) Route command
        byte[] response;
        try {
            switch (frame.cmd) {
                // Link Layer
                case CommandIds.CMD_HANDSHAKE:
                    response = handleHandshake();
                    break;
                case CommandIds.CMD_HEARTBEAT:
                    response = new byte[]{0x01};
                    break;

                // Input Layer
                case CommandIds.CMD_TAP:
                    response = executionEngine.handleTap(payload);
                    break;
                case CommandIds.CMD_SWIPE:
                    response = executionEngine.handleSwipe(payload);
                    break;
                case CommandIds.CMD_LONG_PRESS:
                    response = executionEngine.handleLongPress(payload);
                    break;
                case CommandIds.CMD_UNLOCK:
                    response = executionEngine.handleUnlock(payload);
                    break;
                case CommandIds.CMD_SET_TOUCH_MODE:
                    response = executionEngine.handleSetTouchMode(payload);
                    break;
                case CommandIds.CMD_SET_SCREENSHOT_QUALITY:
                    response = executionEngine.handleSetScreenshotQuality(payload);
                    break;

                // Input Extension
                case CommandIds.CMD_INPUT_TEXT:
                    response = executionEngine.handleInputText(payload);
                    break;
                case CommandIds.CMD_KEY_EVENT:
                    response = executionEngine.handleKeyEvent(payload);
                    break;

                // Sense Layer
                case CommandIds.CMD_GET_ACTIVITY:
                    response = perceptionEngine.handleGetActivity();
                    break;
                case CommandIds.CMD_DUMP_HIERARCHY:
                    response = perceptionEngine.handleDumpHierarchy(payload);
                    break;
                case CommandIds.CMD_FIND_NODE:
                    response = perceptionEngine.handleFindNode(payload);
                    break;
                case CommandIds.CMD_DUMP_ACTIONS:
                    response = perceptionEngine.handleDumpActions(payload);
                    break;
                case CommandIds.CMD_GET_SCREEN_STATE:
                    response = perceptionEngine.handleGetScreenState();
                    break;
                case CommandIds.CMD_GET_SCREEN_SIZE:
                    response = perceptionEngine.handleGetScreenSize();
                    break;
                case CommandIds.CMD_FIND_NODE_COMPOUND:
                    response = perceptionEngine.handleFindNodeCompound(payload);
                    break;

                // Lifecycle Layer
                case CommandIds.CMD_LAUNCH_APP:
                    response = executionEngine.handleLaunchApp(payload);
                    break;
                case CommandIds.CMD_STOP_APP:
                    response = executionEngine.handleStopApp(payload);
                    break;
                case CommandIds.CMD_LIST_APPS:
                    response = executionEngine.handleListApps(payload);
                    break;

                // Media Layer
                case CommandIds.CMD_SCREENSHOT:
                    response = perceptionEngine.handleScreenshot();
                    break;

                // Cortex/Map debug layer (bootstrap)
                case CommandIds.CMD_MAP_SET_GZ:
                    response = cortexFacade.handleMapSetGz(payload);
                    break;
                case CommandIds.CMD_MAP_GET_INFO:
                    response = cortexFacade.handleMapGetInfo(payload);
                    break;
                case CommandIds.CMD_CORTEX_RESOLVE_LOCATOR:
                    response = cortexFacade.handleResolveLocator(payload);
                    break;
                case CommandIds.CMD_CORTEX_TAP_LOCATOR:
                    response = cortexFacade.handleTapLocator(payload);
                    break;
                case CommandIds.CMD_CORTEX_TRACE_PULL:
                    response = cortexFacade.handleTracePull(payload);
                    break;
                case CommandIds.CMD_CORTEX_ROUTE_RUN:
                    response = cortexFacade.handleRouteRun(payload);
                    break;
                case CommandIds.CMD_CORTEX_FSM_RUN:
                    response = cortexFacade.handleCortexFsmRun(payload);
                    break;
                case CommandIds.CMD_CORTEX_TASK_STATUS:
                    response = cortexFacade.handleCortexTaskStatus(payload);
                    break;
                case CommandIds.CMD_CORTEX_FSM_CANCEL:
                    response = cortexFacade.handleCortexFsmCancel(payload);
                    break;
                case CommandIds.CMD_CORTEX_TASK_LIST:
                    response = cortexFacade.handleCortexTaskList(payload);
                    break;
                case CommandIds.CMD_CORTEX_SCHEDULE_ADD:
                    response = cortexFacade.handleCortexScheduleAdd(payload);
                    break;
                case CommandIds.CMD_CORTEX_SCHEDULE_LIST:
                    response = cortexFacade.handleCortexScheduleList(payload);
                    break;
                case CommandIds.CMD_CORTEX_SCHEDULE_REMOVE:
                    response = cortexFacade.handleCortexScheduleRemove(payload);
                    break;
                case CommandIds.CMD_CORTEX_SCHEDULE_UPDATE:
                    response = cortexFacade.handleCortexScheduleUpdate(payload);
                    break;

                default:
                    System.out.println(TAG + " Unimplemented command: 0x" +
                            String.format("%02X", frame.cmd));
                    response = new byte[]{0x00};
            }

            // 4) Build ACK and cache by frame fingerprint
            byte[] ack = buildAck(frame.seq, CommandIds.CMD_ACK, response);
            ackCache.put(frameKey, ack);
            return ack;

        } catch (Exception e) {
            circuitBreaker.recordException();
            System.err.println(TAG + " Error handling cmd=0x" +
                    String.format("%02X", frame.cmd) + ": " + e.getMessage());
            e.printStackTrace();
            return buildErrorAck(frame.seq, (byte) 0x00);
        }
    }

    private byte[] buildAck(int seq, byte cmd, byte[] payload) {
        return FrameCodec.encode(seq, cmd, payload);
    }

    private byte[] buildErrorAck(int seq, byte status) {
        return FrameCodec.encode(seq, CommandIds.CMD_ACK, new byte[]{status});
    }

    private byte[] handleHandshake() {
        System.out.println(TAG + " Handshake received");
        return new byte[0];
    }
}
