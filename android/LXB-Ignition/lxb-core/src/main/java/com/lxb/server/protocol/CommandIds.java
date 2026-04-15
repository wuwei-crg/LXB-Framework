package com.lxb.server.protocol;

/**
 * Protocol command IDs shared by server modules.
 * Keep aligned with client protocol constants (legacy Python path was removed).
 */
public final class CommandIds {

    private CommandIds() {}

    // Link layer
    public static final byte CMD_HANDSHAKE = 0x01;
    public static final byte CMD_ACK = 0x02;
    public static final byte CMD_HEARTBEAT = 0x03;

    // Input layer
    public static final byte CMD_TAP = 0x10;
    public static final byte CMD_SWIPE = 0x11;
    public static final byte CMD_LONG_PRESS = 0x12;
    public static final byte CMD_UNLOCK = 0x1B;
    public static final byte CMD_SET_TOUCH_MODE = 0x1C;
    public static final byte CMD_SET_SCREENSHOT_QUALITY = 0x1D;

    // Input extension
    public static final byte CMD_INPUT_TEXT = 0x20;
    public static final byte CMD_KEY_EVENT = 0x21;

    // Sense layer
    public static final byte CMD_GET_ACTIVITY = 0x30;
    public static final byte CMD_DUMP_HIERARCHY = 0x31;
    public static final byte CMD_FIND_NODE = 0x32;
    public static final byte CMD_DUMP_ACTIONS = 0x33;
    public static final byte CMD_GET_SCREEN_STATE = 0x36;
    public static final byte CMD_GET_SCREEN_SIZE = 0x37;
    public static final byte CMD_FIND_NODE_COMPOUND = 0x39;

    // Lifecycle layer
    public static final byte CMD_LAUNCH_APP = 0x43;
    public static final byte CMD_STOP_APP = 0x44;
    public static final byte CMD_LIST_APPS = 0x48;
    public static final byte CMD_SYSTEM_CONTROL = 0x49;

    // Media layer
    public static final byte CMD_SCREENSHOT = 0x60;
    public static final byte CMD_IMG_REQ = 0x61;

    // Cortex/Map debug layer (0x70-0x7F) - end-side cortex migration bootstrap
    // NOTE: keep aligned with Python constants when PC-side support is added.
    public static final byte CMD_MAP_SET_GZ = 0x70;          // burn map (gzip json)
    public static final byte CMD_MAP_GET_INFO = 0x71;        // get current map info
    public static final byte CMD_CORTEX_RESOLVE_LOCATOR = 0x72; // resolve locator -> bounds
    public static final byte CMD_CORTEX_TAP_LOCATOR = 0x73;     // resolve + tap
    public static final byte CMD_CORTEX_TRACE_PULL = 0x74;      // pull trace lines (jsonl)
    public static final byte CMD_CORTEX_ROUTE_RUN = 0x75;       // run route-only FSM (sync)
    public static final byte CMD_CORTEX_FSM_RUN = 0x76;         // run full Cortex FSM (sync, WIP)
    public static final byte CMD_CORTEX_TASK_STATUS = 0x77;     // query FSM task status by task_id
    public static final byte CMD_CORTEX_FSM_CANCEL = 0x78;      // request cancellation of current FSM task
    public static final byte CMD_CORTEX_TASK_LIST = 0x79;       // list recent Cortex FSM tasks
    public static final byte CMD_CORTEX_SCHEDULE_ADD = 0x7A;    // add periodic scheduled FSM task
    public static final byte CMD_CORTEX_SCHEDULE_LIST = 0x7B;   // list schedules
    public static final byte CMD_CORTEX_SCHEDULE_REMOVE = 0x7C; // remove schedule by id
    public static final byte CMD_CORTEX_SCHEDULE_UPDATE = 0x7D; // update schedule by id
    public static final byte CMD_CORTEX_NOTIFY = 0x7E;          // notification trigger module control
    public static final byte CMD_CORTEX_TASK_MAP = 0x7F;        // task-local learned map control
}
