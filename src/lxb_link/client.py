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
    CMD_TAP,
    CMD_SWIPE,
    CMD_SCREENSHOT,
    CMD_WAKE,
    # Sense Layer commands
    CMD_GET_ACTIVITY,
    CMD_FIND_NODE,
    CMD_DUMP_HIERARCHY,
    # Input Extension commands
    CMD_INPUT_TEXT,
    CMD_KEY_EVENT,
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

    def screenshot(self) -> bytes:
        """
        Capture a screenshot from the device (legacy single-frame mode).

        This method uses the legacy CMD_SCREENSHOT which sends the entire
        screenshot in a single UDP frame. For large screenshots (>50KB),
        this relies on IP-layer fragmentation which may be inefficient.

        For better performance with large screenshots, use request_screenshot()
        which implements application-layer fragmentation with selective repeat.

        Returns:
            Screenshot image data (format depends on device implementation,
            typically JPEG or PNG encoded)

        Raises:
            LXBTimeoutError: If screenshot command times out
            RuntimeError: If client is not connected
        """
        self._ensure_connected()

        logger.info("Sending SCREENSHOT command (legacy mode)")
        response = self._transport.send_reliable(CMD_SCREENSHOT, b'')

        logger.info(f"SCREENSHOT successful: {len(response)} bytes received")
        return response

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

        logger.info("Requesting screenshot (fragmented mode)")
        response = self._transport.request_screenshot_fragmented() # type: ignore

        logger.info(
            f"Screenshot transfer successful: {len(response)} bytes "
            f"({len(response) / 1024:.1f} KB)"
        )
        return response

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

        # Pack using ProtocolFrame
        frame = ProtocolFrame.pack_get_activity(self._transport._seq) # type: ignore
        self._transport._seq += 1 # type: ignore

        # Send and wait for response
        response = self._transport.send_reliable(CMD_GET_ACTIVITY, b'') # type: ignore

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

        # Pack using ProtocolFrame
        frame = ProtocolFrame.pack_find_node(
            self._transport._seq, # type: ignore
            match_type,
            return_mode,
            query,
            multi_match,
            timeout_ms
        )
        self._transport._seq += 1 # type: ignore

        # Send frame and wait for response
        self._transport._sock.sendto(frame, (self.host, self.port)) # type: ignore
        response_frame = self._transport._wait_for_ack(self._transport._seq - 1, self.timeout) # type: ignore

        # Unpack response based on return_mode
        _, _, payload = ProtocolFrame.unpack(response_frame)

        if return_mode == RETURN_COORDS:
            status, results = ProtocolFrame.unpack_find_node_coords(payload)
            logger.info(f"FIND_NODE successful: status={status}, found {len(results)} nodes")
            return status, results
        elif return_mode == RETURN_BOUNDS:
            status, results = ProtocolFrame.unpack_find_node_bounds(payload)
            logger.info(f"FIND_NODE successful: status={status}, found {len(results)} nodes")
            return status, results
        else:
            # RETURN_FULL not yet implemented in this version
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

        # Pack using ProtocolFrame
        frame = ProtocolFrame.pack_dump_hierarchy(
            self._transport._seq, # type: ignore
            format,
            compress,
            max_depth
        )
        self._transport._seq += 1 # type: ignore

        # Send frame
        self._transport._sock.sendto(frame, (self.host, self.port)) # type: ignore

        # Wait for response (may be large, handled by fragmentation if needed)
        response_frame = self._transport._wait_for_ack(self._transport._seq - 1, self.timeout * 3) # type: ignore

        # Unpack response
        _, _, payload = ProtocolFrame.unpack(response_frame)

        if format == HIERARCHY_FORMAT_BINARY:
            hierarchy, pool = ProtocolFrame.unpack_dump_hierarchy_binary(payload)
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
        method: int = INPUT_METHOD_CLIPBOARD,
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
            method: Input method (default: INPUT_METHOD_CLIPBOARD)
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

        logger.info(f"Sending INPUT_TEXT command: text='{text[:20]}...', method={method}")

        # Pack using ProtocolFrame
        frame = ProtocolFrame.pack_input_text(
            self._transport._seq, # type: ignore
            text,
            method,
            clear_first,
            press_enter,
            hide_keyboard,
            target_x,
            target_y,
            delay_ms
        )
        self._transport._seq += 1 # type: ignore

        # Send frame and wait for response
        self._transport._sock.sendto(frame, (self.host, self.port)) # type: ignore
        response_frame = self._transport._wait_for_ack(self._transport._seq - 1, self.timeout) # type: ignore

        # Unpack response
        _, _, payload = ProtocolFrame.unpack(response_frame)
        status, actual_method = ProtocolFrame.unpack_input_text_response(payload)

        logger.info(f"INPUT_TEXT successful: status={status}, actual_method={actual_method}")
        return status, actual_method

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

        # Pack using ProtocolFrame
        frame = ProtocolFrame.pack_key_event(
            self._transport._seq, # type: ignore
            keycode,
            action,
            meta_state
        )
        self._transport._seq += 1 # type: ignore

        # Send and wait for response
        response = self._transport.send_reliable(CMD_KEY_EVENT, frame[14:-4]) # type: ignore
        # Note: send_reliable expects just payload, strip header+CRC

        logger.info(f"KEY_EVENT successful: keycode={keycode}")
        return response

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
