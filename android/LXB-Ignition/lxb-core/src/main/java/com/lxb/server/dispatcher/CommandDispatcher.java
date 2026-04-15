package com.lxb.server.dispatcher;

import com.lxb.server.daemon.CircuitBreaker;
import com.lxb.server.cortex.CortexFacade;
import com.lxb.server.execution.ExecutionEngine;
import com.lxb.server.perception.PerceptionEngine;
import com.lxb.server.protocol.CommandIds;
import com.lxb.server.protocol.FrameCodec;

/**
 * Command dispatcher.
 *
 * Design note:
 * - No session/connection state is kept here.
 * - Transport-level retry/dedup is not handled here (TCP control channel).
 */
public class CommandDispatcher {

    private static final String TAG = "[LXB][Dispatcher]";

    private final PerceptionEngine perceptionEngine;
    private final ExecutionEngine executionEngine;
    private final CircuitBreaker circuitBreaker;
    private final CortexFacade cortexFacade;

    public CommandDispatcher(
            PerceptionEngine perceptionEngine,
            ExecutionEngine executionEngine,
            CircuitBreaker circuitBreaker
    ) {
        this.perceptionEngine = perceptionEngine;
        this.executionEngine = executionEngine;
        this.circuitBreaker = circuitBreaker;
        this.cortexFacade = new CortexFacade(perceptionEngine, executionEngine);
    }

    /**
     * Dispatch command and return ACK frame.
     */
    public byte[] dispatch(FrameCodec.FrameInfo frame, byte[] payload, String peerTag) {
        // 1) Circuit breaker
        if (circuitBreaker.shouldReject()) {
            System.out.println(TAG + " Circuit breaker triggered, rejecting");
            return buildErrorAck(frame.seq, (byte) 0xFF);
        }

        // 2) Route command
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
                case CommandIds.CMD_SYSTEM_CONTROL:
                    response = executionEngine.handleSystemControl(payload);
                    break;

                // Media Layer
                case CommandIds.CMD_SCREENSHOT:
                    response = perceptionEngine.handleScreenshot();
                    break;
                case CommandIds.CMD_IMG_REQ:
                    // Fragmented screenshot protocol is UDP-specific legacy.
                    // TCP clients should use CMD_SCREENSHOT directly.
                    response = new byte[]{0x00};
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
                case CommandIds.CMD_CORTEX_NOTIFY:
                    response = cortexFacade.handleCortexNotify(payload);
                    break;
                case CommandIds.CMD_CORTEX_TASK_MAP:
                    response = cortexFacade.handleCortexTaskMap(payload);
                    break;

                default:
                    System.out.println(TAG + " Unimplemented command: 0x" +
                            String.format("%02X", frame.cmd));
                    response = new byte[]{0x00};
            }

            // 3) Build ACK
            byte[] ack = buildAck(frame.seq, CommandIds.CMD_ACK, response);
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
