"""
LXB-Link Client API

This module provides the user-facing API for controlling Android devices using
the LXB-Link reliable UDP protocol. It abstracts low-level socket operations
and binary frame manipulation behind a simple, intuitive interface.

Example:
    >>> from ww_link import LXBLinkClient
    >>>
    >>> # Using context manager (recommended)
    >>> with LXBLinkClient('192.168.1.100', 12345) as client:
    ...     client.handshake()
    ...     client.tap(500, 800)
    ...     screenshot_data = client.screenshot()
    ...
    >>> # Manual connection management
    >>> client = LXBLinkClient('192.168.1.100', 12345)
    >>> client.connect()
    >>> client.tap(100, 200)
    >>> client.disconnect()
"""

import logging
from typing import Optional

from .constants import (
    DEFAULT_PORT,
    DEFAULT_TIMEOUT,
    MAX_RETRIES,
    CMD_HANDSHAKE,
    CMD_HEARTBEAT,
    CMD_TAP,
    CMD_SWIPE,
    CMD_LONG_PRESS,
    CMD_SCREENSHOT,
    CMD_WAKE,
    CMD_UNLOCK,
    CMD_SET_TOUCH_MODE,
    CMD_SET_SCREENSHOT_QUALITY,
    # Sense Layer commands
    CMD_GET_ACTIVITY,
    CMD_FIND_NODE,
    CMD_FIND_NODE_COMPOUND,
    CMD_DUMP_HIERARCHY,
    CMD_DUMP_ACTIONS,
    CMD_GET_SCREEN_STATE,
    CMD_GET_SCREEN_SIZE,
    # Input Extension commands
    CMD_INPUT_TEXT,
    CMD_KEY_EVENT,
    # Lifecycle commands
    CMD_LAUNCH_APP,
    CMD_STOP_APP,
    CMD_LIST_APPS,
    # Match types for FIND_NODE
    MATCH_EXACT_TEXT,
    MATCH_CONTAINS_TEXT,
    MATCH_REGEX,
    MATCH_RESOURCE_ID,
    MATCH_CLASS,
    MATCH_DESCRIPTION,
    # Return modes for FIND_NODE
    RETURN_COORDS,
    RETURN_BOUNDS,
    RETURN_FULL,
    # Input methods
    INPUT_METHOD_ADB,
    INPUT_METHOD_CLIPBOARD,
    INPUT_METHOD_ACCESSIBILITY,
    INPUT_METHOD_AUTO,
    # Hierarchy formats
    HIERARCHY_FORMAT_XML,
    HIERARCHY_FORMAT_JSON,
    HIERARCHY_FORMAT_BINARY,
    HIERARCHY_COMPRESS_NONE,
    HIERARCHY_COMPRESS_ZLIB,
    HIERARCHY_COMPRESS_LZ4,
    # Android KeyEvent codes
    KEY_HOME,
    KEY_BACK,
    KEY_ENTER,
    KEY_DELETE,
    KEY_MENU,
    KEY_RECENT,
)
from .transport import Transport
from .protocol import ProtocolFrame


# Configure logging
logger = logging.getLogger(__name__)


class LXBLinkClient:
    """
    High-level client for LXB-Link protocol communication.

    This class provides a simple API for controlling Android devices through
    the LXB-Link protocol, hiding the complexity of UDP socket management,
    frame packing/unpacking, and retry logic.
    """

    def __init__(
        self,
        host: str,
        port: int = DEFAULT_PORT,
        timeout: float = DEFAULT_TIMEOUT,
        max_retries: int = MAX_RETRIES
    ):
        """
        Initialize LXB-Link client.

        Args:
            host: Target device IP address or hostname
            port: Target device UDP port (default: 12345)
            timeout: Command timeout in seconds (default: 1.0)
            max_retries: Maximum retry attempts (default: 3)
        """
        self.host = host
        self.port = port
        self.timeout = timeout
        self.max_retries = max_retries

        # Transport layer instance
        self._transport: Optional[Transport] = None

        # Connection state
        self._connected = False

        logger.info(f"LXBLinkClient initialized for {host}:{port}")

    def connect(self) -> None:
        """
        Establish connection to remote device.

        This method initializes the transport layer and prepares the client
        for sending commands.

        Raises:
            OSError: If socket creation or configuration fails
        """
        if self._connected:
            logger.warning("Client already connected")
            return

        # Create transport layer
        self._transport = Transport(
            remote_host=self.host,
            remote_port=self.port,
            timeout=self.timeout,
            max_retries=self.max_retries
        )

        # Establish transport connection
        self._transport.connect()
        self._connected = True

        logger.info(f"Client connected to {self.host}:{self.port}")

    def reconnect(self, handshake: bool = True) -> None:
        """
        Hard reconnect transport to recover from interrupted sessions.
        """
        try:
            self.disconnect()
        except Exception:
            pass
        self.connect()
        if handshake:
            self.handshake()

    def disconnect(self) -> None:
        """
        Close connection to remote device and release resources.
        """
        if self._transport:
            self._transport.disconnect()
            self._transport = None
            self._connected = False

        logger.info("Client disconnected")

    def _ensure_connected(self) -> None:
        """
        Verify transport layer is connected.

        Raises:
            RuntimeError: If client is not connected
        """
        if not self._connected or not self._transport:
            raise RuntimeError(
                "Client not connected. Call connect() first or use context manager."
            )

    def handshake(self) -> bytes:
        """
        Perform handshake with remote device.

        This command is typically used to verify connectivity and protocol
        compatibility during connection establishment.

        Returns:
            Response payload from device (typically empty or version info)

        Raises:
            LXBTimeoutError: If handshake times out
            RuntimeError: If client is not connected
        """
        self._ensure_connected()

        logger.info("Sending handshake command")
        response = self._transport.send_reliable(CMD_HANDSHAKE, b'') # pyright: ignore[reportOptionalMemberAccess]

        logger.info("Handshake successful")
        return response

    def heartbeat(self) -> bytes:
        """
        Send heartbeat command to verify liveness.

        Returns:
            Response payload from device heartbeat ACK.
        """
        self._ensure_connected()

        logger.info("Sending heartbeat command")
        response = self._transport.send_reliable(CMD_HEARTBEAT, b'')  # pyright: ignore[reportOptionalMemberAccess]

        logger.info("Heartbeat successful")
        return response

    def tap(self, x: int, y: int) -> bytes:
        """
        Perform a tap gesture at specified screen coordinates.

        Args:
            x: X coordinate (0 to 65535)
            y: Y coordinate (0 to 65535)

        Returns:
            Response payload from device

        Raises:
            LXBTimeoutError: If tap command times out
            RuntimeError: If client is not connected
            ValueError: If coordinates are out of range
        """
        # Validate coordinates
        if not (0 <= x <= 65535):
            raise ValueError(f"X coordinate {x} out of range [0, 65535]")
        if not (0 <= y <= 65535):
            raise ValueError(f"Y coordinate {y} out of range [0, 65535]")

        self._ensure_connected()

        logger.info(f"Sending TAP command: ({x}, {y})")

        # Pack TAP payload: x[uint16], y[uint16] - Big Endian (Network Byte Order)
        import struct
        payload = struct.pack('>HH', x, y)

        response = self._transport.send_reliable(CMD_TAP, payload) # pyright: ignore[reportOptionalMemberAccess]

        logger.info(f"TAP successful: ({x}, {y})")
        return response

    def swipe(
        self,
        x1: int,
        y1: int,
        x2: int,
        y2: int,
        duration: int = 300
    ) -> bytes:
        """
        Perform a swipe gesture from (x1, y1) to (x2, y2).

        Args:
            x1: Start X coordinate (0 to 65535)
            y1: Start Y coordinate (0 to 65535)
            x2: End X coordinate (0 to 65535)
            y2: End Y coordinate (0 to 65535)
            duration: Swipe duration in milliseconds (default: 300)

        Returns:
            Response payload from device

        Raises:
            LXBTimeoutError: If swipe command times out
            RuntimeError: If client is not connected
            ValueError: If coordinates are out of range
        """
        # Validate coordinates
        for coord, name in [(x1, 'x1'), (y1, 'y1'), (x2, 'x2'), (y2, 'y2')]:
            if not (0 <= coord <= 65535):
                raise ValueError(f"Coordinate {name}={coord} out of range [0, 65535]")

        if not (0 <= duration <= 65535):
            raise ValueError(f"Duration {duration} out of range [0, 65535]")

        self._ensure_connected()

        logger.info(f"Sending SWIPE command: ({x1}, {y1}) -> ({x2}, {y2}), "
                    f"duration={duration}ms")

        # Pack SWIPE payload: x1, y1, x2, y2, duration (all uint16) - Big Endian (Network Byte Order)
        import struct
        payload = struct.pack('>HHHHH', x1, y1, x2, y2, duration)

        response = self._transport.send_reliable(CMD_SWIPE, payload)

        logger.info(f"SWIPE successful")
        return response

    def long_press(self, x: int, y: int, duration: int = 1000) -> bytes:
        """
        Perform a long press gesture at specified coordinates.

        Args:
            x: X coordinate (0 to 65535)
            y: Y coordinate (0 to 65535)
            duration: Press duration in milliseconds (default: 1000)

        Returns:
            Response payload from device

        Raises:
            LXBTimeoutError: If command times out
            RuntimeError: If client is not connected
            ValueError: If coordinates are out of range
        """
        if not (0 <= x <= 65535):
            raise ValueError(f"X coordinate {x} out of range [0, 65535]")
        if not (0 <= y <= 65535):
            raise ValueError(f"Y coordinate {y} out of range [0, 65535]")
        if not (0 <= duration <= 65535):
            raise ValueError(f"Duration {duration} out of range [0, 65535]")

        self._ensure_connected()

        logger.info(f"Sending LONG_PRESS command: ({x}, {y}), duration={duration}ms")

        import struct
        payload = struct.pack('>HHH', x, y, duration)

        response = self._transport.send_reliable(CMD_LONG_PRESS, payload)

        logger.info(f"LONG_PRESS successful: ({x}, {y})")
        return response

    def screenshot(self) -> bytes:
        """
        Capture a screenshot from the device.

        Note:
            This API now always uses fragmented transfer internally to avoid
            UDP EMSGSIZE on large frames.

        Returns:
            Screenshot image data (format depends on device implementation,
            typically JPEG or PNG encoded)

        Raises:
            LXBTimeoutError: If screenshot command times out
            RuntimeError: If client is not connected
        """
        self._ensure_connected()

        logger.info("screenshot() delegates to fragmented request_screenshot()")
        return self.request_screenshot()

    def request_screenshot(self) -> bytes:
        """
        Request screenshot using fragmented transfer with selective repeat.

        This method implements an efficient fragmented transfer protocol for
        large screenshots (50KB-200KB). It uses application-layer fragmentation
        with selective repeat instead of relying on IP-layer fragmentation.

        Features:
        - Chunked transfer (1KB chunks by default)
        - Burst transmission (server sends all chunks without waiting)
        - Selective repeat (only missing chunks are retransmitted)
        - Handles UDP packet loss and reordering efficiently

        Returns:
            Screenshot image data (format depends on device implementation,
            typically JPEG or PNG encoded)

        Raises:
            LXBTimeoutError: If transfer fails after maximum retries
            RuntimeError: If client is not connected
        """
        self._ensure_connected()

        logger.info("Requesting screenshot (fragmented mode only)")
        last_err: Optional[Exception] = None
        for attempt in range(3):
            try:
                response = self._transport.request_screenshot_fragmented() # type: ignore
                logger.info(
                    f"Screenshot transfer successful: {len(response)} bytes "
                    f"({len(response) / 1024:.1f} KB)"
                )
                return response
            except Exception as e:
                last_err = e
                logger.warning(
                    f"Fragmented screenshot failed (attempt={attempt + 1}/3): {e}"
                )
                try:
                    # Drain stale frames but keep sequence continuity.
                    self.reset_runtime_state(reset_seq=False)
                except Exception:
                    pass

        if last_err:
            raise last_err
        raise RuntimeError("fragmented screenshot failed")

    def reset_runtime_state(self, reset_seq: bool = True) -> int:
        """
        Reset client transport runtime state and drain stale UDP frames.
        """
        self._ensure_connected()
        return self._transport.reset_runtime_state(reset_seq=reset_seq) # type: ignore

    def wake(self) -> bytes:
        """
        Wake up the device (turn on screen/unlock).

        Returns:
            Response payload from device

        Raises:
            LXBTimeoutError: If wake command times out
            RuntimeError: If client is not connected
        """
        self._ensure_connected()

        logger.info("Sending WAKE command")
        response = self._transport.send_reliable(CMD_WAKE, b'') # type: ignore

        logger.info("WAKE successful")
        return response

    # =========================================================================
    # Sense Layer - AI Agent Perception APIs ⭐
    # =========================================================================

    def get_activity(self) -> tuple[bool, str, str]:
        """
        Get current foreground Activity information.

        This method is essential for L1 (lookup table) strategies, allowing
        the agent to route based on current application state.

        Returns:
            Tuple of (success, package_name, activity_name)
            Example: (True, "com.tencent.mm", ".ui.LauncherUI")

        Raises:
            LXBTimeoutError: If command times out
            RuntimeError: If client is not connected

        Example:
            >>> success, pkg, activity = client.get_activity()
            >>> if success:
            ...     print(f"Current: {pkg}/{activity}")
        """
        self._ensure_connected()

        logger.info("Sending GET_ACTIVITY command")

        # 使用 send_reliable 发送
        response = self._transport.send_reliable(CMD_GET_ACTIVITY, b'')

        # Unpack response
        success, package_name, activity_name = ProtocolFrame.unpack_get_activity_response(response)

        logger.info(f"GET_ACTIVITY successful: {package_name}/{activity_name}")
        return success, package_name, activity_name

    def find_node(
        self,
        query: str,
        match_type: int = MATCH_CONTAINS_TEXT,
        return_mode: int = RETURN_COORDS,
        multi_match: bool = False,
        timeout_ms: int = 3000
    ) -> tuple[int, list]:
        """
        Find UI node with computation offloading (executes on device).

        This is a CORE innovation: instead of transmitting 50KB UI tree,
        the query is sent to device and only results are returned.

        Args:
            query: Query string (e.g., "登录", "com.tencent.mm:id/btn_login")
            match_type: Match strategy (default: MATCH_CONTAINS_TEXT)
                - MATCH_EXACT_TEXT: Exact text match
                - MATCH_CONTAINS_TEXT: Text contains substring
                - MATCH_REGEX: Regular expression
                - MATCH_RESOURCE_ID: Match by resource-id
                - MATCH_CLASS: Match by class name
                - MATCH_DESCRIPTION: Match by content-desc
            return_mode: What to return (default: RETURN_COORDS)
                - RETURN_COORDS: Only center (x, y) coordinates
                - RETURN_BOUNDS: Bounding boxes (left, top, right, bottom)
                - RETURN_FULL: Complete node information
            multi_match: Return all matches (True) or first only (False)
            timeout_ms: Device-side search timeout in milliseconds

        Returns:
            Tuple of (status, results)
            - status: 0=not found, 1=success, 2=timeout
            - results: List depends on return_mode:
                - RETURN_COORDS: [(x, y), ...]
                - RETURN_BOUNDS: [(left, top, right, bottom), ...]
                - RETURN_FULL: [node_dict, ...] (not yet implemented)

        Raises:
            LXBTimeoutError: If command times out
            RuntimeError: If client is not connected

        Example:
            >>> # Find login button and tap it
            >>> status, coords = client.find_node("登录", return_mode=RETURN_COORDS)
            >>> if status == 1 and coords:
            ...     x, y = coords[0]
            ...     client.tap(x, y)

            >>> # Find all EditText fields
            >>> status, bounds = client.find_node(
            ...     "EditText",
            ...     match_type=MATCH_CLASS,
            ...     return_mode=RETURN_BOUNDS,
            ...     multi_match=True
            ... )
        """
        self._ensure_connected()

        logger.info(f"Sending FIND_NODE command: query='{query}', "
                    f"match_type={match_type}, return_mode={return_mode}")

        # 构建 payload: match_type[1B] + return_mode[1B] + flags[1B] + timeout[2B] + query_len[2B] + query
        import struct
        flags = 0x01 if multi_match else 0x00

        query_bytes = query.encode('utf-8')
        payload = struct.pack('>BBBHH',
            match_type,
            return_mode,
            flags,
            timeout_ms,
            len(query_bytes)
        ) + query_bytes

        # 使用 send_reliable 发送
        response = self._transport.send_reliable(CMD_FIND_NODE, payload)

        # 解析响应
        if return_mode == RETURN_COORDS:
            status, results = ProtocolFrame.unpack_find_node_coords(response)
            logger.info(f"FIND_NODE successful: status={status}, found {len(results)} nodes")
            return status, results
        elif return_mode == RETURN_BOUNDS:
            status, results = ProtocolFrame.unpack_find_node_bounds(response)
            logger.info(f"FIND_NODE successful: status={status}, found {len(results)} nodes")
            return status, results
        else:
            # RETURN_FULL not yet implemented in this version
            raise NotImplementedError("RETURN_FULL mode not yet implemented")

    def find_node_compound(
        self,
        conditions: list,
        return_mode: int = RETURN_COORDS,
        multi_match: bool = False
    ) -> tuple[int, list]:
        """
        Find UI node using compound multi-condition matching.

        Unlike find_node() which supports only a single field match,
        this method sends multiple conditions (field + op + value) to the
        device. The device BFS-traverses the UI tree and returns only nodes
        that satisfy ALL conditions.

        Args:
            conditions: List of (field, op, value) tuples
                - field: COMPOUND_FIELD_* constant
                    0=TEXT, 1=RESOURCE_ID, 2=CONTENT_DESC,
                    3=CLASS_NAME, 4=PARENT_RESOURCE_ID, 5=ACTIVITY
                - op: COMPOUND_OP_* constant
                    0=EQUALS, 1=CONTAINS, 2=STARTS_WITH, 3=ENDS_WITH
                - value: UTF-8 string to match
            return_mode: What to return (default: RETURN_COORDS)
                - RETURN_COORDS: Center (x, y) coordinates
                - RETURN_BOUNDS: Bounding boxes (left, top, right, bottom)
            multi_match: Return all matches (True) or first only (False)

        Returns:
            Tuple of (status, results)
            - status: 0=not found, 1=success
            - results: List depends on return_mode

        Raises:
            LXBTimeoutError: If command times out
            RuntimeError: If client is not connected

        Example:
            >>> # Find "确定" button inside a dialog
            >>> status, coords = client.find_node_compound([
            ...     (0, 0, "确定"),           # TEXT EQUALS "确定"
            ...     (4, 1, "dialog"),          # PARENT_RESOURCE_ID CONTAINS "dialog"
            ... ])
        """
        self._ensure_connected()

        logger.info(f"Sending FIND_NODE_COMPOUND: {len(conditions)} conditions, "
                    f"return_mode={return_mode}, multi_match={multi_match}")

        # Build payload: return_mode[1B] + flags[1B] + cond_count[1B] + conditions
        import struct
        flags = 0x01 if multi_match else 0x00
        payload = struct.pack('>BBB', return_mode, flags, len(conditions))

        for field, op, value in conditions:
            val_bytes = value.encode('utf-8')
            payload += struct.pack('>BBH', field, op, len(val_bytes)) + val_bytes

        response = self._transport.send_reliable(CMD_FIND_NODE_COMPOUND, payload)

        # Response format is identical to FIND_NODE
        if return_mode == RETURN_COORDS:
            status, results = ProtocolFrame.unpack_find_node_coords(response)
            logger.info(f"FIND_NODE_COMPOUND: status={status}, found {len(results)} nodes")
            return status, results
        elif return_mode == RETURN_BOUNDS:
            status, results = ProtocolFrame.unpack_find_node_bounds(response)
            logger.info(f"FIND_NODE_COMPOUND: status={status}, found {len(results)} nodes")
            return status, results
        else:
            raise NotImplementedError("RETURN_FULL mode not yet implemented")

    def dump_hierarchy(
        self,
        format: int = HIERARCHY_FORMAT_BINARY,
        compress: int = HIERARCHY_COMPRESS_ZLIB,
        max_depth: int = 0
    ) -> dict:
        """
        Dump complete UI hierarchy tree from device.

        This method retrieves the full UI tree structure using binary
        encoding with string pool compression (saves 90% bandwidth vs JSON).

        Args:
            format: Data format (default: HIERARCHY_FORMAT_BINARY)
                - HIERARCHY_FORMAT_XML: XML format (legacy, for debugging)
                - HIERARCHY_FORMAT_JSON: JSON format (human-readable)
                - HIERARCHY_FORMAT_BINARY: Binary + string pool (recommended)
            compress: Compression algorithm (default: HIERARCHY_COMPRESS_ZLIB)
                - HIERARCHY_COMPRESS_NONE: No compression
                - HIERARCHY_COMPRESS_ZLIB: zlib (balanced)
                - HIERARCHY_COMPRESS_LZ4: lz4 (faster, requires lz4 package)
            max_depth: Maximum tree depth (0=unlimited, recommended: 8)

        Returns:
            Dictionary with structure:
            {
                'version': int,
                'node_count': int,
                'nodes': [
                    {
                        'index': int,
                        'parent_index': int or None,
                        'child_count': int,
                        'class': str,
                        'bounds': [left, top, right, bottom],
                        'text': str,
                        'resource_id': str,
                        'content_desc': str,
                        'clickable': bool,
                        'visible': bool,
                        'enabled': bool,
                        'focused': bool,
                        'scrollable': bool,
                        'editable': bool,
                        'checkable': bool,
                        'checked': bool,
                    },
                    ...
                ]
            }

        Raises:
            LXBTimeoutError: If command times out
            RuntimeError: If client is not connected

        Example:
            >>> hierarchy = client.dump_hierarchy()
            >>> print(f"UI tree has {hierarchy['node_count']} nodes")
            >>> for node in hierarchy['nodes']:
            ...     if node['clickable'] and node['text']:
            ...         print(f"Clickable: {node['text']} at {node['bounds']}")
        """
        self._ensure_connected()

        logger.info(f"Sending DUMP_HIERARCHY command: format={format}, compress={compress}")

        # 构建 payload: format[1B] + compress[1B] + max_depth[2B]
        import struct
        payload = struct.pack('>BBH', format, compress, max_depth)

        # 使用 send_reliable 发送 (使用更长的超时)
        original_timeout = self._transport.timeout
        self._transport.timeout = self.timeout * 3
        try:
            response = self._transport.send_reliable(CMD_DUMP_HIERARCHY, payload)
        finally:
            self._transport.timeout = original_timeout

        if format == HIERARCHY_FORMAT_BINARY:
            hierarchy, pool = ProtocolFrame.unpack_dump_hierarchy_binary(response)
            logger.info(f"DUMP_HIERARCHY successful: {hierarchy['node_count']} nodes, "
                        f"string pool has {len(pool.pool)} dynamic entries")
            return hierarchy
        else:
            # XML/JSON formats not yet implemented in this version
            raise NotImplementedError("Only HIERARCHY_FORMAT_BINARY is implemented")

    # =========================================================================
    # Input Extension - Advanced Input APIs ⭐
    # =========================================================================

    def input_text(
        self,
        text: str,
        method: int = INPUT_METHOD_AUTO,
        clear_first: bool = False,
        press_enter: bool = False,
        hide_keyboard: bool = False,
        target_x: int = 0,
        target_y: int = 0,
        delay_ms: int = 0
    ) -> tuple[int, int]:
        """
        Input text into focused field or at specified coordinates.

        This method supports multiple input strategies with automatic fallback
        and uses pure binary encoding (NO JSON bloat).

        Args:
            text: Text to input (UTF-8 string)
            method: Input method (default: INPUT_METHOD_AUTO)
                - INPUT_METHOD_AUTO: Select strategy automatically based on text
                - INPUT_METHOD_ADB: ADB keyboard (most reliable)
                - INPUT_METHOD_CLIPBOARD: Clipboard paste (fastest, ~50ms)
                - INPUT_METHOD_ACCESSIBILITY: Accessibility service (best compatibility)
            clear_first: Clear existing text before input
            press_enter: Press ENTER key after input
            hide_keyboard: Hide keyboard after input
            target_x: Target input box X coordinate (0=current focus)
            target_y: Target input box Y coordinate (0=current focus)
            delay_ms: Delay between characters for human simulation

        Returns:
            Tuple of (status, actual_method)
            - status: 0=failed, 1=success, 2=partial success
            - actual_method: Method actually used (may fallback)

        Raises:
            LXBTimeoutError: If command times out
            RuntimeError: If client is not connected

        Example:
            >>> # Input username
            >>> status, method = client.input_text("alice@example.com", clear_first=True)
            >>> if status == 1:
            ...     print(f"Input successful using method {method}")

            >>> # Input with delay to simulate human typing
            >>> client.input_text("password123", delay_ms=50, press_enter=True)
        """
        self._ensure_connected()

        def _send_once(use_method: int) -> tuple[int, int]:
            # 构建 payload: method[1B] + flags[1B] + target_x[2B] + target_y[2B] + delay[2B] + text_len[2B] + text
            import struct
            flags = 0
            if clear_first:
                flags |= 0x01
            if press_enter:
                flags |= 0x02
            if hide_keyboard:
                flags |= 0x04

            text_bytes = text.encode('utf-8')
            payload = struct.pack(
                '>BBHHHH',
                use_method,
                flags,
                target_x,
                target_y,
                delay_ms,
                len(text_bytes),
            ) + text_bytes

            response = self._transport.send_reliable(CMD_INPUT_TEXT, payload)
            if len(response) >= 2:
                return int(response[0]), int(response[1])
            return 0, int(use_method)

        def _contains_non_ascii(s: str) -> bool:
            return any(ord(ch) > 127 for ch in (s or ""))

        if method == INPUT_METHOD_AUTO:
            if _contains_non_ascii(text):
                candidates = [INPUT_METHOD_CLIPBOARD, INPUT_METHOD_ADB]
            else:
                candidates = [INPUT_METHOD_ADB, INPUT_METHOD_CLIPBOARD]
        else:
            candidates = [int(method)]
            # Keep one fallback to improve robustness.
            if int(method) != INPUT_METHOD_ADB:
                candidates.append(INPUT_METHOD_ADB)

        seen = set()
        ordered_methods = []
        for m in candidates:
            if m in seen:
                continue
            seen.add(m)
            ordered_methods.append(m)

        logger.info(
            f"Sending INPUT_TEXT command: text='{text[:20]}...', method={method}, tries={ordered_methods}"
        )

        last_status, last_actual = 0, ordered_methods[0] if ordered_methods else INPUT_METHOD_ADB
        for idx, m in enumerate(ordered_methods):
            status, actual = _send_once(m)
            last_status, last_actual = status, actual
            if status == 1:
                logger.info(f"INPUT_TEXT successful: status={status}, actual_method={actual}, try={idx + 1}")
                return status, actual
            logger.warning(f"INPUT_TEXT failed: status={status}, method={m}, actual={actual}, try={idx + 1}")

        logger.info(f"INPUT_TEXT final failure: status={last_status}, actual_method={last_actual}")
        return last_status, last_actual

    def key_event(
        self,
        keycode: int,
        action: int = 2,
        meta_state: int = 0
    ) -> bytes:
        """
        Send physical key event (HOME, BACK, ENTER, etc.).

        Args:
            keycode: Android KeyEvent code
                - KEY_HOME (3): HOME button
                - KEY_BACK (4): BACK button
                - KEY_ENTER (66): ENTER key
                - KEY_DELETE (67): DELETE key
                - KEY_MENU (82): MENU button
                - KEY_RECENT (187): Recent apps (multitasking)
            action: Key action (default: 2)
                - 0: Key down
                - 1: Key up
                - 2: Click (down + up)
            meta_state: Modifier keys state (Shift/Ctrl/Alt bitmask)

        Returns:
            Response payload from device

        Raises:
            LXBTimeoutError: If command times out
            RuntimeError: If client is not connected

        Example:
            >>> # Press BACK button
            >>> client.key_event(KEY_BACK)

            >>> # Press HOME button
            >>> client.key_event(KEY_HOME)

            >>> # Press ENTER
            >>> client.key_event(KEY_ENTER)
        """
        self._ensure_connected()

        logger.info(f"Sending KEY_EVENT command: keycode={keycode}, action={action}")

        # 简化格式: keycode[1B] + action[1B]
        import struct
        payload = struct.pack('>BB', keycode & 0xFF, action & 0xFF)

        response = self._transport.send_reliable(CMD_KEY_EVENT, payload)

        logger.info(f"KEY_EVENT successful: keycode={keycode}")
        return response

    # =========================================================================
    # New Commands - Screen & Lifecycle APIs ⭐
    # =========================================================================

    def unlock(self) -> bool:
        """
        Unlock the device screen.

        Returns:
            True if unlock successful, False otherwise

        Raises:
            LXBTimeoutError: If command times out
            RuntimeError: If client is not connected
        """
        self._ensure_connected()

        logger.info("Sending UNLOCK command")
        response = self._transport.send_reliable(CMD_UNLOCK, b'')

        success = len(response) > 0 and response[0] == 0x01
        logger.info(f"UNLOCK {'successful' if success else 'failed'}")
        return success

    def set_touch_mode(self, shell_first: bool = True) -> bool:
        """
        Configure touch execution priority on Android side.

        Args:
            shell_first: True for input(shell) -> uiautomation fallback,
                         False for uiautomation -> shell fallback.

        Returns:
            True if mode switch acknowledged.
        """
        self._ensure_connected()
        mode = b'\x01' if shell_first else b'\x00'
        logger.info(f"Sending SET_TOUCH_MODE: {'shell_first' if shell_first else 'uiautomation_first'}")
        response = self._transport.send_reliable(CMD_SET_TOUCH_MODE, mode)
        ok = len(response) > 0 and response[0] == 0x01
        logger.info(f"SET_TOUCH_MODE {'successful' if ok else 'failed'}")
        return ok

    def set_screenshot_quality(self, quality: int) -> bool:
        """
        Configure screenshot JPEG quality on Android side.

        Args:
            quality: JPEG quality in [1, 100].

        Returns:
            True if quality update acknowledged.
        """
        self._ensure_connected()
        q = max(1, min(100, int(quality)))
        payload = bytes([q & 0xFF])
        logger.info(f"Sending SET_SCREENSHOT_QUALITY: {q}")
        response = self._transport.send_reliable(CMD_SET_SCREENSHOT_QUALITY, payload)
        ok = len(response) > 0 and response[0] == 0x01
        logger.info(f"SET_SCREENSHOT_QUALITY {'successful' if ok else 'failed'}")
        return ok

    def get_screen_state(self) -> tuple[bool, int]:
        """
        Get current screen state.

        Returns:
            Tuple of (success, state)
            - state: 0=off, 1=on_unlocked, 2=on_locked

        Raises:
            LXBTimeoutError: If command times out
            RuntimeError: If client is not connected
        """
        self._ensure_connected()

        logger.info("Sending GET_SCREEN_STATE command")
        response = self._transport.send_reliable(CMD_GET_SCREEN_STATE, b'')

        if len(response) >= 2:
            success = response[0] == 0x01
            state = response[1]
            logger.info(f"GET_SCREEN_STATE: state={state}")
            return success, state
        return False, 0

    def dump_actions(self) -> dict:
        """
        Dump actionable UI nodes for path planning and navigation.

        This method returns only meaningful nodes (clickable, editable, scrollable,
        or nodes with text). It's optimized for building app navigation maps and
        semantic matching for AI agents.

        Unlike dump_hierarchy() which returns the complete UI tree, this method
        filters and flattens the tree to focus on actionable elements, with
        automatic text association from child nodes to parent containers.

        Returns:
            Dictionary with structure:
            {
                'version': int,
                'node_count': int,
                'nodes': [
                    {
                        'type': int,          # Bitmask: 0x01=clickable, 0x02=editable,
                                              # 0x04=scrollable, 0x08=text_only
                        'bounds': [left, top, right, bottom],
                        'class': str,
                        'text': str,          # Associated text (from self or first child)
                        'resource_id': str,
                        'content_desc': str,
                        'clickable': bool,
                        'editable': bool,
                        'scrollable': bool,
                        'text_only': bool,
                    },
                    ...
                ]
            }

        Raises:
            LXBTimeoutError: If command times out
            RuntimeError: If client is not connected

        Example:
            >>> actions = client.dump_actions()
            >>> for node in actions['nodes']:
            ...     if node['clickable'] and node['text']:
            ...         print(f"Tap target: {node['text']} at {node['bounds']}")
        """
        self._ensure_connected()

        logger.info("Sending DUMP_ACTIONS command")

        # 使用 send_reliable 发送 (使用更长的超时)
        original_timeout = self._transport.timeout
        self._transport.timeout = self.timeout * 3
        try:
            response = self._transport.send_reliable(CMD_DUMP_ACTIONS, b'')
        finally:
            self._transport.timeout = original_timeout

        # 解析响应
        result = self._parse_dump_actions_response(response)

        logger.info(f"DUMP_ACTIONS successful: {result['node_count']} nodes")
        return result

    def _parse_dump_actions_response(self, data: bytes) -> dict:
        """
        Parse DUMP_ACTIONS binary response.

        Format:
            version[1B] + count[2B] + nodes[20B each] + string_pool[...]

        ActionNode (20 bytes):
            type[1B] + bounds[8B] + class_id[1B] + text_id[2B] + res_id[1B] + desc_id[1B] + reserved[6B]

        String pool:
            short_count[1B] + short_entries[...] + long_count[2B] + long_entries[...]
        """
        import struct

        if len(data) < 3:
            return {'version': 0, 'node_count': 0, 'nodes': []}

        offset = 0

        # Version
        version = data[offset]
        offset += 1

        # Node count (2 bytes, big endian)
        node_count = struct.unpack('>H', data[offset:offset+2])[0]
        offset += 2

        # Parse nodes (20 bytes each)
        raw_nodes = []
        for _ in range(node_count):
            if offset + 20 > len(data):
                break

            node_data = data[offset:offset+20]
            offset += 20

            node_type = node_data[0]
            left, top, right, bottom = struct.unpack('>HHHH', node_data[1:9])
            class_id = node_data[9]
            text_id = struct.unpack('>H', node_data[10:12])[0]
            res_id = node_data[12]
            desc_id = node_data[13]
            # reserved: 14-19

            raw_nodes.append({
                'type': node_type,
                'bounds': [left, top, right, bottom],
                'class_id': class_id,
                'text_id': text_id,
                'res_id': res_id,
                'desc_id': desc_id,
            })

        # Parse string pool
        short_strings = []
        long_strings = []

        if offset < len(data):
            # Short strings
            short_count = data[offset]
            offset += 1

            for _ in range(short_count):
                if offset >= len(data):
                    break
                str_len = data[offset]
                offset += 1
                if offset + str_len > len(data):
                    break
                s = data[offset:offset+str_len].decode('utf-8', errors='replace')
                offset += str_len
                short_strings.append(s)

            # Long strings
            if offset + 2 <= len(data):
                long_count = struct.unpack('>H', data[offset:offset+2])[0]
                offset += 2

                for _ in range(long_count):
                    if offset + 2 > len(data):
                        break
                    str_len = struct.unpack('>H', data[offset:offset+2])[0]
                    offset += 2
                    if offset + str_len > len(data):
                        break
                    s = data[offset:offset+str_len].decode('utf-8', errors='replace')
                    offset += str_len
                    long_strings.append(s)

        # Resolve string IDs to actual strings
        def get_short_string(id: int) -> str:
            if id == 0xFF or id >= len(short_strings):
                return ''
            return short_strings[id]

        def get_long_string(id: int) -> str:
            if id == 0xFFFF or id >= len(long_strings):
                return ''
            return long_strings[id]

        # Build final nodes
        nodes = []
        for raw in raw_nodes:
            node = {
                'type': raw['type'],
                'bounds': raw['bounds'],
                'class': get_short_string(raw['class_id']),
                'text': get_long_string(raw['text_id']),
                'resource_id': get_short_string(raw['res_id']),
                'content_desc': get_short_string(raw['desc_id']),
                'clickable': bool(raw['type'] & 0x01),
                'editable': bool(raw['type'] & 0x02),
                'scrollable': bool(raw['type'] & 0x04),
                'text_only': bool(raw['type'] & 0x08),
            }
            nodes.append(node)

        return {
            'version': version,
            'node_count': node_count,
            'nodes': nodes,
        }

    def get_screen_size(self) -> tuple[bool, int, int, int]:
        """
        Get device screen size and density.

        Returns:
            Tuple of (success, width, height, density)

        Raises:
            LXBTimeoutError: If command times out
            RuntimeError: If client is not connected
        """
        self._ensure_connected()

        logger.info("Sending GET_SCREEN_SIZE command")
        response = self._transport.send_reliable(CMD_GET_SCREEN_SIZE, b'')

        if len(response) >= 7:
            import struct
            success = response[0] == 0x01
            width, height, density = struct.unpack('>HHH', response[1:7])
            logger.info(f"GET_SCREEN_SIZE: {width}x{height} @{density}dpi")
            return success, width, height, density
        return False, 0, 0, 0

    def launch_app(self, package_name: str, clear_task: bool = False, wait: bool = False) -> bool:
        """
        Launch an application by package name.

        Args:
            package_name: Package name (e.g., "com.tencent.mm")
            clear_task: Clear task stack before launch
            wait: Wait for activity to start

        Returns:
            True if launch successful, False otherwise

        Raises:
            LXBTimeoutError: If command times out
            RuntimeError: If client is not connected
        """
        self._ensure_connected()

        logger.info(f"Sending LAUNCH_APP command: {package_name}")

        import struct
        flags = 0
        if clear_task:
            flags |= 0x01
        if wait:
            flags |= 0x02

        pkg_bytes = package_name.encode('utf-8')
        payload = struct.pack('>BH', flags, len(pkg_bytes)) + pkg_bytes

        response = self._transport.send_reliable(CMD_LAUNCH_APP, payload)

        success = len(response) > 0 and response[0] == 0x01
        logger.info(f"LAUNCH_APP {'successful' if success else 'failed'}")
        return success

    def stop_app(self, package_name: str) -> bool:
        """
        Stop an application by package name.

        Args:
            package_name: Package name (e.g., "com.tencent.mm")

        Returns:
            True if stop successful, False otherwise

        Raises:
            LXBTimeoutError: If command times out
            RuntimeError: If client is not connected
        """
        self._ensure_connected()

        logger.info(f"Sending STOP_APP command: {package_name}")

        import struct
        pkg_bytes = package_name.encode('utf-8')
        payload = struct.pack('>H', len(pkg_bytes)) + pkg_bytes

        response = self._transport.send_reliable(CMD_STOP_APP, payload)

        success = len(response) > 0 and response[0] == 0x01
        logger.info(f"STOP_APP {'successful' if success else 'failed'}")
        return success

    def list_apps(self, filter: str = "all") -> list[str]:
        """
        List installed applications on the device.

        Args:
            filter: Application filter type
                - "all": All applications (default)
                - "user": User-installed applications only
                - "system": System applications only

        Returns:
            List of package names (e.g., ["com.tencent.mm", "com.baidu.tieba", ...])

        Raises:
            LXBTimeoutError: If command times out
            RuntimeError: If client is not connected

        Example:
            >>> # List all user-installed apps
            >>> apps = client.list_apps("user")
            >>> for pkg in apps:
            ...     print(pkg)
        """
        self._ensure_connected()

        logger.info(f"Sending LIST_APPS command: filter={filter}")

        # Map filter string to filter code
        filter_map = {"all": 0, "user": 1, "system": 2}
        filter_code = filter_map.get(filter.lower(), 0)

        import struct
        import json
        payload = struct.pack('>B', filter_code)

        response = self._transport.send_reliable(CMD_LIST_APPS, payload)

        # Parse response: status[1B] + json_len[2B] + json_data
        if len(response) >= 3:
            status = response[0]
            json_len = struct.unpack('>H', response[1:3])[0]

            if status == 0x01 and len(response) >= 3 + json_len:
                json_data = response[3:3 + json_len].decode('utf-8')
                apps = json.loads(json_data)
                logger.info(f"LIST_APPS successful: {len(apps)} apps found")
                return apps

        logger.warning("LIST_APPS failed or returned empty")
        return []

    # =========================================================================
    # Context Manager & Utilities
    # =========================================================================

    def send_custom_command(
        self,
        cmd: int,
        payload: bytes = b'',
        reliable: bool = True
    ) -> Optional[bytes]:
        """
        Send a custom command to the device.

        This method allows sending arbitrary commands not covered by the
        standard API methods.

        Args:
            cmd: Command ID (0 to 255)
            payload: Command payload (default: empty)
            reliable: Use reliable delivery with ACK (default: True)

        Returns:
            Response payload if reliable=True, None otherwise

        Raises:
            LXBTimeoutError: If reliable command times out
            RuntimeError: If client is not connected
            ValueError: If command ID is out of range
        """
        if not (0 <= cmd <= 255):
            raise ValueError(f"Command ID {cmd} out of range [0, 255]")

        self._ensure_connected()

        logger.info(f"Sending custom command: cmd=0x{cmd:02X}, "
                    f"payload={len(payload)} bytes, reliable={reliable}")

        if reliable:
            response = self._transport.send_reliable(cmd, payload)
            logger.info(f"Custom command successful: 0x{cmd:02X}")
            return response
        else:
            self._transport.send_and_forget(cmd, payload)
            logger.info(f"Custom command sent (unreliable): 0x{cmd:02X}")
            return None

    def __enter__(self):
        """Context manager entry: establish connection."""
        self.connect()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        """Context manager exit: close connection."""
        self.disconnect()
        return False

    def __repr__(self) -> str:
        """String representation of client."""
        status = "connected" if self._connected else "disconnected"
        return (
            f"LXBLinkClient(host='{self.host}', port={self.port}, "
            f"timeout={self.timeout}, status='{status}')"
        )
