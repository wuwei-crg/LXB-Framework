"""
LXB-Link Protocol Constants

This module defines all constants used in the LXB-Link protocol including:
- Magic numbers and protocol version
- Layered ISA command set (Link/Input/Sense/Lifecycle/Debug/Media layers)
- String constant pools (predefined classes and texts)
- Bit field flags for node properties
- Error codes and channel definitions
- Protocol configuration parameters

Version: 1.0-dev (Binary First Architecture)
"""

# =============================================================================
# Protocol Magic Numbers & Version
# =============================================================================

MAGIC = 0xAA55  # Protocol magic number (2 bytes)
VERSION = 0x01  # Protocol version (1 byte) - v1.0-dev

# Protocol version constants for future negotiation
PROTOCOL_VERSION_1_0 = 0x0100  # Current version (LE: 0x00 0x01)
PROTOCOL_VERSION_2_0 = 0x0200  # Future version with full v2.0 features

# =============================================================================
# Frame Structure Constants
# =============================================================================

MAGIC_SIZE = 2      # Magic field size in bytes
VERSION_SIZE = 1    # Version field size in bytes
SEQ_SIZE = 4        # Sequence number field size in bytes
CMD_SIZE = 1        # Command field size in bytes
LEN_SIZE = 2        # Payload length field size in bytes
CRC_SIZE = 4        # CRC32 checksum field size in bytes

# Calculate header size (excluding payload and CRC)
HEADER_SIZE = MAGIC_SIZE + VERSION_SIZE + SEQ_SIZE + CMD_SIZE + LEN_SIZE

# Minimum frame size (header + CRC, no payload)
MIN_FRAME_SIZE = HEADER_SIZE + CRC_SIZE

# Maximum payload size (64KB - header - CRC)
MAX_PAYLOAD_SIZE = 65535 - HEADER_SIZE - CRC_SIZE

# =============================================================================
# Layered ISA Command Set (Binary First Architecture)
# =============================================================================
#
# Command ID allocation follows a layered approach:
# - 0x00-0x0F: Link Layer (handshake, ACK, heartbeat, protocol control)
# - 0x10-0x1F: Input Layer (tap, swipe, gestures, wake)
# - 0x20-0x2F: Input Extension (text input, key events, paste)
# - 0x30-0x3F: Sense Layer (activity, UI tree, node finding) ⭐ Core upgrade
# - 0x40-0x4F: Lifecycle (restart app, clipboard, app management)
# - 0x50-0x5F: Debug Layer (device info, logcat, performance stats)
# - 0x60-0x6F: Media Layer (screenshot, recording, audio capture)
# - 0x70-0xEF: Reserved for future expansion
# - 0xF0-0xFF: System/Vendor custom commands
#
# =============================================================================

# -----------------------------------------------------------------------------
# Link Layer (0x00-0x0F) - Protocol Infrastructure
# -----------------------------------------------------------------------------
CMD_HANDSHAKE = 0x01    # Handshake command for connection establishment
CMD_ACK = 0x02          # Acknowledgment command for reliable delivery
CMD_HEARTBEAT = 0x03    # Keep-alive heartbeat (new)
CMD_NOOP = 0x04         # No-operation (latency test)
CMD_PROTOCOL_VER = 0x05 # Protocol version negotiation (new)
# 0x06-0x0F: Reserved for transport layer extensions

# -----------------------------------------------------------------------------
# Input Layer (0x10-0x1F) - Basic Interaction
# -----------------------------------------------------------------------------
CMD_TAP = 0x10          # Single tap (migrated from 0x03)
CMD_SWIPE = 0x11        # Swipe gesture (migrated from 0x04)
CMD_LONG_PRESS = 0x12   # Long press (new)
CMD_MULTI_TOUCH = 0x13  # Multi-point touch (new)
CMD_GESTURE = 0x14      # Complex gesture (pinch/rotate) (new)
CMD_WAKE = 0x1A         # Wake/unlock device (migrated from 0x0A)
# 0x15-0x19, 0x1B-0x1F: Reserved

# -----------------------------------------------------------------------------
# Input Extension (0x20-0x2F) - Advanced Input
# -----------------------------------------------------------------------------
CMD_INPUT_TEXT = 0x20   # Text input (binary UTF-8, no JSON!) (new)
CMD_KEY_EVENT = 0x21    # Physical key event (HOME/BACK/ENTER) (new)
CMD_PASTE = 0x22        # Paste operation (new)
# 0x23-0x2F: Reserved

# -----------------------------------------------------------------------------
# Sense Layer (0x30-0x3F) - Perception Capabilities ⭐ Core Upgrade
# -----------------------------------------------------------------------------
CMD_GET_ACTIVITY = 0x30    # Get current Activity name (new)
CMD_DUMP_HIERARCHY = 0x31  # Dump UI tree (binary+string pool) (new)
CMD_FIND_NODE = 0x32       # Find node with computation offloading (new)
CMD_GET_FOCUSED = 0x33     # Get focused element (new)
CMD_WAIT_FOR = 0x34        # Wait for element to appear (new)
CMD_OCR_REGION = 0x35      # OCR on device (new)
# 0x36-0x3F: Reserved for AI enhancements

# -----------------------------------------------------------------------------
# Lifecycle Layer (0x40-0x4F) - Application Management
# -----------------------------------------------------------------------------
CMD_RESTART_APP = 0x40     # Restart application (new)
CMD_GET_CLIPBOARD = 0x41   # Get clipboard content (new)
CMD_SET_CLIPBOARD = 0x42   # Set clipboard content (new)
CMD_INSTALL_APK = 0x43     # Install application (new)
CMD_UNINSTALL = 0x44       # Uninstall application (new)
CMD_CLEAR_DATA = 0x45      # Clear app data (new)
# 0x46-0x4F: Reserved

# -----------------------------------------------------------------------------
# Debug Layer (0x50-0x5F) - Debugging Tools
# -----------------------------------------------------------------------------
CMD_GET_DEVICE_INFO = 0x50 # Get device information (new)
CMD_LOGCAT = 0x51          # Get log entries (new)
CMD_PERF_STATS = 0x52      # Performance statistics (new)
CMD_TRACE_DUMP = 0x53      # Stack trace dump (new)
# 0x54-0x5F: Reserved

# -----------------------------------------------------------------------------
# Media Layer (0x60-0x6F) - Media Capture
# -----------------------------------------------------------------------------
CMD_SCREENSHOT = 0x60      # Screenshot (single-frame, migrated from 0x09)
CMD_IMG_REQ = 0x61         # Request screenshot (fragmented, migrated from 0x10)
CMD_IMG_META = 0x62        # Image metadata (migrated from 0x11)
CMD_IMG_CHUNK = 0x63       # Image chunk (migrated from 0x12)
CMD_IMG_MISSING = 0x64     # Missing chunks request (migrated from 0x13)
CMD_IMG_FIN = 0x65         # Transfer complete (migrated from 0x14)
CMD_START_RECORD = 0x66    # Start screen recording (new)
CMD_STOP_RECORD = 0x67     # Stop screen recording (new)
CMD_AUDIO_CAPTURE = 0x68   # Audio capture (new)
# UI Hierarchy Transfer (0x69-0x6C) - Large UI tree fragmented transfer
CMD_HIERARCHY_REQ = 0x69   # Request UI tree with parameters (format, compress, max_depth)
CMD_HIERARCHY_META = 0x6A  # UI tree metadata (tree_id, total_size, num_chunks)
CMD_HIERARCHY_CHUNK = 0x6B # UI tree chunk (chunk_index + data)
CMD_HIERARCHY_FIN = 0x6C   # UI tree transfer complete (reuses CMD_IMG_MISSING for missing chunks)
# 0x6D-0x6F: Reserved for streaming

# =============================================================================
# Legacy Command Aliases (for backward compatibility during migration)
# =============================================================================
# These will be deprecated in v2.0
CMD_TAP_LEGACY = 0x03       # Old TAP command ID
CMD_SWIPE_LEGACY = 0x04     # Old SWIPE command ID
CMD_SCREENSHOT_LEGACY = 0x09  # Old SCREENSHOT command ID
CMD_WAKE_LEGACY = 0x0A      # Old WAKE command ID
CMD_IMG_REQ_LEGACY = 0x10   # Old IMG_REQ command ID
CMD_IMG_META_LEGACY = 0x11  # Old IMG_META command ID
CMD_IMG_CHUNK_LEGACY = 0x12 # Old IMG_CHUNK command ID
CMD_IMG_MISSING_LEGACY = 0x13  # Old IMG_MISSING command ID
CMD_IMG_FIN_LEGACY = 0x14   # Old IMG_FIN command ID

# =============================================================================
# Error Codes
# =============================================================================

ERR_SUCCESS = 0x00              # Success
ERR_INVALID_MAGIC = 0x01        # Invalid magic number
ERR_INVALID_VERSION = 0x02      # Invalid protocol version
ERR_INVALID_CRC = 0x03          # CRC checksum mismatch
ERR_INVALID_PAYLOAD_SIZE = 0x04 # Payload size exceeds maximum
ERR_TIMEOUT = 0x05              # Operation timeout
ERR_MAX_RETRIES = 0x06          # Maximum retry attempts exceeded
ERR_INVALID_ACK = 0x07          # Invalid acknowledgment received
ERR_SEQ_MISMATCH = 0x08         # Sequence number mismatch
ERR_NOT_FOUND = 0x09            # Element/resource not found (new)
ERR_INVALID_PARAM = 0x0A        # Invalid parameter (new)
ERR_UNSUPPORTED = 0x0B          # Unsupported operation (new)
ERR_PARTIAL_SUCCESS = 0x0C      # Partial success (new)

# =============================================================================
# Channel Definitions (for Multi-Channel Architecture)
# =============================================================================

CH_CONTROL = 0  # Control channel: heartbeat, ACK, small commands (highest priority)
CH_DATA = 1     # Data channel: screenshot, UI tree, large data (medium priority)
CH_DEBUG = 2    # Debug channel: logs, performance stats (lowest priority)

# Channel to command mapping (for QoS scheduling)
CHANNEL_MAP = {
    # Control Channel (must respond quickly)
    CMD_HANDSHAKE: CH_CONTROL,
    CMD_ACK: CH_CONTROL,
    CMD_HEARTBEAT: CH_CONTROL,
    CMD_TAP: CH_CONTROL,
    CMD_SWIPE: CH_CONTROL,
    CMD_KEY_EVENT: CH_CONTROL,
    CMD_FIND_NODE: CH_CONTROL,
    CMD_GET_ACTIVITY: CH_CONTROL,
    CMD_INPUT_TEXT: CH_CONTROL,

    # Data Channel (large data transfer)
    CMD_DUMP_HIERARCHY: CH_DATA,
    CMD_IMG_REQ: CH_DATA,
    CMD_IMG_META: CH_DATA,
    CMD_IMG_CHUNK: CH_DATA,
    CMD_SCREENSHOT: CH_DATA,
    CMD_HIERARCHY_REQ: CH_DATA,
    CMD_HIERARCHY_META: CH_DATA,
    CMD_HIERARCHY_CHUNK: CH_DATA,
    CMD_HIERARCHY_FIN: CH_DATA,

    # Debug Channel (can be dropped)
    CMD_LOGCAT: CH_DEBUG,
    CMD_PERF_STATS: CH_DEBUG,
}

# =============================================================================
# UI Node Flags (Bit Field for Node Properties)
# =============================================================================
# Used in DUMP_HIERARCHY and FIND_NODE responses
# Compress 8 boolean properties into 1 byte

FLAG_CLICKABLE  = 0x01  # bit 0: Node is clickable
FLAG_VISIBLE    = 0x02  # bit 1: Node is visible
FLAG_ENABLED    = 0x04  # bit 2: Node is enabled
FLAG_FOCUSED    = 0x08  # bit 3: Node is focused
FLAG_SCROLLABLE = 0x10  # bit 4: Node is scrollable
FLAG_EDITABLE   = 0x20  # bit 5: Node is editable (input field)
FLAG_CHECKABLE  = 0x40  # bit 6: Node is checkable
FLAG_CHECKED    = 0x80  # bit 7: Node is checked

# =============================================================================
# Android KeyEvent Codes (for CMD_KEY_EVENT)
# =============================================================================
# Common Android KeyEvent constants
# Reference: https://developer.android.com/reference/android/view/KeyEvent

KEY_HOME    = 3     # HOME button
KEY_BACK    = 4     # BACK button
KEY_ENTER   = 66    # ENTER key
KEY_DELETE  = 67    # DELETE key
KEY_MENU    = 82    # MENU button
KEY_RECENT  = 187   # Recent apps (multitasking)
KEY_POWER   = 26    # POWER button
KEY_VOLUME_UP = 24  # Volume up
KEY_VOLUME_DOWN = 25  # Volume down

# =============================================================================
# String Constant Pool (Binary First Optimization)
# =============================================================================
# Predefined class names (0x00-0x3F) - 64 entries
# Used in DUMP_HIERARCHY to avoid transmitting full class names

PREDEFINED_CLASSES = [
    "android.view.View",                    # 0x00
    "android.view.ViewGroup",               # 0x01
    "android.widget.TextView",              # 0x02
    "android.widget.EditText",              # 0x03
    "android.widget.Button",                # 0x04
    "android.widget.ImageView",             # 0x05
    "android.widget.ImageButton",           # 0x06
    "android.widget.LinearLayout",          # 0x07
    "android.widget.RelativeLayout",        # 0x08
    "android.widget.FrameLayout",           # 0x09
    "android.widget.ListView",              # 0x0A
    "android.widget.RecyclerView",          # 0x0B
    "android.widget.ScrollView",            # 0x0C
    "android.widget.HorizontalScrollView",  # 0x0D
    "android.webkit.WebView",               # 0x0E
    "android.widget.GridView",              # 0x0F
    "android.widget.Spinner",               # 0x10
    "android.widget.CheckBox",              # 0x11
    "android.widget.RadioButton",           # 0x12
    "android.widget.Switch",                # 0x13
    "android.widget.SeekBar",               # 0x14
    "android.widget.ProgressBar",           # 0x15
    "android.widget.TabWidget",             # 0x16
    "android.widget.ViewFlipper",           # 0x17
    "android.widget.ViewSwitcher",          # 0x18
    "android.widget.TableLayout",           # 0x19
    "android.widget.TableRow",              # 0x1A
    "android.widget.Space",                 # 0x1B
    "androidx.recyclerview.widget.RecyclerView",  # 0x1C
    "androidx.viewpager.widget.ViewPager",  # 0x1D
    "androidx.coordinatorlayout.widget.CoordinatorLayout",  # 0x1E
    "androidx.constraintlayout.widget.ConstraintLayout",    # 0x1F
    "com.google.android.material.appbar.AppBarLayout",      # 0x20
    "com.google.android.material.tabs.TabLayout",           # 0x21
    "com.google.android.material.bottomnavigation.BottomNavigationView",  # 0x22
    "com.google.android.material.floatingactionbutton.FloatingActionButton",  # 0x23
    "android.widget.Toolbar",               # 0x24
    "androidx.appcompat.widget.Toolbar",    # 0x25
    "android.widget.ActionMenuView",        # 0x26
    "android.widget.AutoCompleteTextView",  # 0x27
    "android.widget.MultiAutoCompleteTextView",  # 0x28
    "android.widget.DatePicker",            # 0x29
    "android.widget.TimePicker",            # 0x2A
    "android.widget.NumberPicker",          # 0x2B
    "android.widget.RatingBar",             # 0x2C
    "android.widget.ToggleButton",          # 0x2D
    "android.widget.ZoomButton",            # 0x2E
    "android.widget.ZoomControls",          # 0x2F
    "android.inputmethodservice.KeyboardView",  # 0x30
    "android.gesture.GestureOverlayView",   # 0x31
    "android.widget.VideoView",             # 0x32
    "android.widget.MediaController",       # 0x33
    "android.widget.SlidingDrawer",         # 0x34
    "android.widget.ExpandableListView",    # 0x35
    "android.widget.Gallery",               # 0x36
    "android.widget.CalendarView",          # 0x37
    "android.widget.SearchView",            # 0x38
    "android.widget.QuickContactBadge",     # 0x39
    "android.widget.TextClock",             # 0x3A
    "android.widget.Chronometer",           # 0x3B
    "android.widget.AnalogClock",           # 0x3C
    "android.widget.DigitalClock",          # 0x3D
    "android.view.SurfaceView",             # 0x3E
    "android.view.TextureView",             # 0x3F
]

# Predefined common texts (0x40-0x7F) - 64 entries
# Used to compress frequently occurring UI text

PREDEFINED_TEXTS = [
    "",                 # 0x40: Empty string (very common!)
    "确定",             # 0x41: Confirm (Chinese)
    "取消",             # 0x42: Cancel (Chinese)
    "返回",             # 0x43: Back (Chinese)
    "搜索",             # 0x44: Search (Chinese)
    "设置",             # 0x45: Settings (Chinese)
    "更多",             # 0x46: More (Chinese)
    "完成",             # 0x47: Done (Chinese)
    "提交",             # 0x48: Submit (Chinese)
    "发送",             # 0x49: Send (Chinese)
    "登录",             # 0x4A: Login (Chinese)
    "注册",             # 0x4B: Register (Chinese)
    "退出",             # 0x4C: Exit (Chinese)
    "刷新",             # 0x4D: Refresh (Chinese)
    "删除",             # 0x4E: Delete (Chinese)
    "保存",             # 0x4F: Save (Chinese)
    "OK",               # 0x50: OK (English)
    "Cancel",           # 0x51: Cancel (English)
    "Back",             # 0x52: Back (English)
    "Search",           # 0x53: Search (English)
    "Settings",         # 0x54: Settings (English)
    "More",             # 0x55: More (English)
    "Done",             # 0x56: Done (English)
    "Submit",           # 0x57: Submit (English)
    "Send",             # 0x58: Send (English)
    "Login",            # 0x59: Login (English)
    "Register",         # 0x5A: Register (English)
    "Exit",             # 0x5B: Exit (English)
    "Refresh",          # 0x5C: Refresh (English)
    "Delete",           # 0x5D: Delete (English)
    "Save",             # 0x5E: Save (English)
    "Next",             # 0x5F: Next (English)
    "Previous",         # 0x60: Previous (English)
    "Close",            # 0x61: Close (English)
    "Open",             # 0x62: Open (English)
    "Share",            # 0x63: Share (English)
    "Copy",             # 0x64: Copy (English)
    "Paste",            # 0x65: Paste (English)
    "Cut",              # 0x66: Cut (English)
    "Undo",             # 0x67: Undo (English)
    "Redo",             # 0x68: Redo (English)
    "Yes",              # 0x69: Yes (English)
    "No",               # 0x6A: No (English)
    "Continue",         # 0x6B: Continue (English)
    "Skip",             # 0x6C: Skip (English)
    "Retry",            # 0x6D: Retry (English)
    "Apply",            # 0x6E: Apply (English)
    "Edit",             # 0x6F: Edit (English)
    "Add",              # 0x70: Add (English)
    "Remove",           # 0x71: Remove (English)
    "Upload",           # 0x72: Upload (English)
    "Download",         # 0x73: Download (English)
    "Play",             # 0x74: Play (English)
    "Pause",            # 0x75: Pause (English)
    "Stop",             # 0x76: Stop (English)
    "Record",           # 0x77: Record (English)
    "Mute",             # 0x78: Mute (English)
    "Unmute",           # 0x79: Unmute (English)
    "Help",             # 0x7A: Help (English)
    "About",            # 0x7B: About (English)
    "Info",             # 0x7C: Info (English)
    "Warning",          # 0x7D: Warning (English)
    "Error",            # 0x7E: Error (English)
    "Success",          # 0x7F: Success (English)
]

# Build reverse lookup dictionaries for fast encoding
CLASS_TO_ID = {cls: i for i, cls in enumerate(PREDEFINED_CLASSES)}
TEXT_TO_ID = {txt: i + 0x40 for i, txt in enumerate(PREDEFINED_TEXTS)}

# Dynamic string pool IDs start from 0x80
DYNAMIC_STRING_POOL_START = 0x80
MAX_STRING_POOL_ID = 0xFE  # 0xFF reserved as "no string"
STRING_POOL_EMPTY_ID = 0xFF  # Special ID for empty/missing strings

# =============================================================================
# FIND_NODE Match Types
# =============================================================================

MATCH_EXACT_TEXT = 0        # Exact text match
MATCH_CONTAINS_TEXT = 1     # Text contains substring
MATCH_REGEX = 2             # Regular expression match
MATCH_RESOURCE_ID = 3       # Match by resource-id
MATCH_CLASS = 4             # Match by class name
MATCH_DESCRIPTION = 5       # Match by content-desc

# =============================================================================
# FIND_NODE Return Modes
# =============================================================================

RETURN_COORDS = 0           # Return only center coordinates (x, y)
RETURN_BOUNDS = 1           # Return bounding boxes (left, top, right, bottom)
RETURN_FULL = 2             # Return full node information

# =============================================================================
# INPUT_TEXT Methods
# =============================================================================

INPUT_METHOD_ADB = 0        # ADB input method (most reliable)
INPUT_METHOD_CLIPBOARD = 1  # Clipboard paste (fastest)
INPUT_METHOD_ACCESSIBILITY = 2  # Accessibility service (best compatibility)

# INPUT_TEXT Flags (bit field)
INPUT_FLAG_CLEAR_FIRST = 0x01       # Clear existing text before input
INPUT_FLAG_PRESS_ENTER = 0x02       # Press ENTER after input
INPUT_FLAG_HIDE_KEYBOARD = 0x04     # Hide keyboard after input

# =============================================================================
# DUMP_HIERARCHY Formats
# =============================================================================

HIERARCHY_FORMAT_XML = 0        # XML format (legacy, for debugging)
HIERARCHY_FORMAT_JSON = 1       # JSON format (human-readable)
HIERARCHY_FORMAT_BINARY = 2     # Binary format with string pool (recommended)

# DUMP_HIERARCHY Compression
HIERARCHY_COMPRESS_NONE = 0     # No compression
HIERARCHY_COMPRESS_ZLIB = 1     # zlib compression (balanced)
HIERARCHY_COMPRESS_LZ4 = 2      # lz4 compression (faster)

# =============================================================================
# Feature Flags (for Protocol Negotiation)
# =============================================================================

FEATURE_BINARY_HIERARCHY = 0x0001   # Supports binary UI tree encoding
FEATURE_STRING_POOL = 0x0002        # Supports string constant pool
FEATURE_DELTA_ENCODING = 0x0004     # Supports delta encoding for coordinates
FEATURE_MULTI_CHANNEL = 0x0008      # Supports multi-channel architecture
FEATURE_ENHANCED_ACK = 0x0010       # Supports enhanced ACK with cmd echo
FEATURE_QOS = 0x0020                # Supports QoS scheduling

# =============================================================================
# Transport Configuration
# =============================================================================

DEFAULT_TIMEOUT = 1.0           # Default socket timeout in seconds
MAX_RETRIES = 3                 # Maximum retry attempts for reliable delivery
DEFAULT_PORT = 12345            # Default UDP port for communication
SOCKET_BUFFER_SIZE = 65536      # Socket receive buffer size

# Fragmented Transfer Configuration
CHUNK_SIZE = 1024               # Default chunk size for fragmented transfer (1KB)
CHUNK_RECV_TIMEOUT = 0.3        # Timeout for receiving chunks (300ms)
MAX_MISSING_RETRIES = 3         # Maximum retries for requesting missing chunks

# =============================================================================
# Exception Classes
# =============================================================================


class LXBLinkError(Exception):
    """Base exception class for LXB-Link protocol errors."""

    def __init__(self, message: str, error_code: int = ERR_SUCCESS):
        """
        Initialize LXB-Link error.

        Args:
            message: Human-readable error message
            error_code: Protocol error code
        """
        super().__init__(message)
        self.error_code = error_code


class LXBTimeoutError(LXBLinkError):
    """Exception raised when operation times out after maximum retries."""

    def __init__(self, message: str = "Operation timeout after maximum retries"):
        super().__init__(message, ERR_TIMEOUT)


class LXBProtocolError(LXBLinkError):
    """Exception raised when protocol validation fails."""

    def __init__(self, message: str, error_code: int):
        super().__init__(message, error_code)


class LXBChecksumError(LXBLinkError):
    """Exception raised when CRC32 checksum validation fails."""

    def __init__(self, message: str = "CRC32 checksum mismatch"):
        super().__init__(message, ERR_INVALID_CRC)
