"""
LXB-Link: Reliable UDP Protocol for Android Device Control

A high-performance, reliable communication library for controlling Android devices
over UDP using Stop-and-Wait ARQ protocol with automatic retry mechanism.

Features:
- Binary protocol with CRC32 checksum validation
- Automatic retry with configurable timeout
- Sequence number management for packet ordering
- Support for tap, swipe, screenshot, and wake commands
- Context manager support for easy resource management

Example:
    >>> from lxb_link import LXBLinkClient
    >>>
    >>> with LXBLinkClient('192.168.1.100') as client:
    ...     client.handshake()
    ...     client.tap(500, 800)
    ...     data = client.screenshot()

Version: 1.0.0
Author: WuWei
License: MIT
"""

from .client import LXBLinkClient
from .constants import (
    # Protocol constants
    MAGIC,
    VERSION,

    # Command set
    CMD_HANDSHAKE,
    CMD_ACK,
    CMD_TAP,
    CMD_SWIPE,
    CMD_SCREENSHOT,
    CMD_WAKE,

    # Configuration
    DEFAULT_PORT,
    DEFAULT_TIMEOUT,
    MAX_RETRIES,

    # Exceptions
    LXBLinkError,
    LXBTimeoutError,
    LXBProtocolError,
    LXBChecksumError,
)
from .protocol import ProtocolFrame
from .transport import Transport


__version__ = '1.0.0'
__author__ = 'WuWei'
__all__ = [
    # Main client class
    'LXBLinkClient',

    # Protocol components
    'ProtocolFrame',
    'Transport',

    # Constants
    'MAGIC',
    'VERSION',
    'CMD_HANDSHAKE',
    'CMD_ACK',
    'CMD_TAP',
    'CMD_SWIPE',
    'CMD_SCREENSHOT',
    'CMD_WAKE',
    'DEFAULT_PORT',
    'DEFAULT_TIMEOUT',
    'MAX_RETRIES',

    # Exceptions
    'LXBLinkError',
    'LXBTimeoutError',
    'LXBProtocolError',
    'LXBChecksumError',
]


# Configure package-level logging
import logging

logging.getLogger(__name__).addHandler(logging.NullHandler())
