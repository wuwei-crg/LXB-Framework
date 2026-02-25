from __future__ import annotations

import base64
import json
import os
import socket
import subprocess
import sys
from dataclasses import dataclass
from typing import Any

# Ensure src/ is importable when running without pip install -e .
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from lxb_link import LXBLinkClient


@dataclass
class CommandOutput:
    ok: bool
    payload_size: int
    error: str = ""


class BaseAdapter:
    name = "base"

    def connect(self) -> None:
        raise NotImplementedError

    def close(self) -> None:
        raise NotImplementedError

    def execute(self, command: str, timeout_sec: float) -> CommandOutput:
        raise NotImplementedError


class UDPAdapter(BaseAdapter):
    name = "udp"

    def __init__(self, device_ip: str, device_port: int) -> None:
        self._device_ip = device_ip
        self._device_port = device_port
        self._client: LXBLinkClient | None = None

    def connect(self) -> None:
        self._client = LXBLinkClient(self._device_ip, self._device_port)
        self._client.connect()
        self._client.handshake()

    def close(self) -> None:
        if self._client:
            self._client.disconnect()
            self._client = None

    def execute(self, command: str, timeout_sec: float) -> CommandOutput:
        if not self._client:
            return CommandOutput(ok=False, payload_size=0, error="udp_client_not_connected")

        try:
            self._client.timeout = float(timeout_sec)
            if command == "handshake":
                data = self._client.handshake()
                size = len(data or b"")
                return CommandOutput(ok=True, payload_size=size)
            if command == "dump":
                dump = self._client.dump_actions()
                raw = json.dumps(dump, ensure_ascii=False).encode("utf-8", errors="ignore")
                return CommandOutput(ok=True, payload_size=len(raw))
            if command == "screenshot":
                shot = self._client.request_screenshot()
                return CommandOutput(ok=bool(shot), payload_size=len(shot or b""), error="" if shot else "empty_screenshot")
            return CommandOutput(ok=False, payload_size=0, error=f"unsupported_command:{command}")
        except Exception as exc:
            return CommandOutput(ok=False, payload_size=0, error=str(exc))


class ADBTcpAdapter(BaseAdapter):
    name = "adb_tcp"

    def __init__(self, adb_bin: str, adb_serial: str) -> None:
        self._adb_bin = adb_bin
        self._adb_serial = adb_serial

    def connect(self) -> None:
        # Quick health check: device online
        self._run(["get-state"], timeout_sec=6.0)

    def close(self) -> None:
        return

    def execute(self, command: str, timeout_sec: float) -> CommandOutput:
        try:
            if command == "handshake":
                out = self._run(["shell", "echo", "pong"], timeout_sec=timeout_sec)
                ok = b"pong" in out
                return CommandOutput(ok=ok, payload_size=len(out), error="" if ok else "adb_handshake_mismatch")

            if command == "dump":
                # Direct dump to stdout keeps it as a small-ish text payload benchmark.
                out = self._run(["exec-out", "uiautomator", "dump", "--compressed", "/dev/tty"], timeout_sec=timeout_sec)
                ok = b"<hierarchy" in out
                return CommandOutput(ok=ok, payload_size=len(out), error="" if ok else "adb_dump_no_hierarchy")

            if command == "screenshot":
                out = self._run(["exec-out", "screencap", "-p"], timeout_sec=timeout_sec)
                # PNG header: 89 50 4E 47
                ok = len(out) > 8 and out[:4] == b"\x89PNG"
                return CommandOutput(ok=ok, payload_size=len(out), error="" if ok else "adb_screenshot_not_png")

            return CommandOutput(ok=False, payload_size=0, error=f"unsupported_command:{command}")
        except Exception as exc:
            return CommandOutput(ok=False, payload_size=0, error=str(exc))

    def _run(self, args: list[str], timeout_sec: float) -> bytes:
        cmd = [self._adb_bin, "-s", self._adb_serial, *args]
        proc = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout_sec,
            check=False,
        )
        if proc.returncode != 0:
            stderr = proc.stderr.decode("utf-8", errors="ignore")[:300]
            raise RuntimeError(f"adb_failed:{proc.returncode}:{stderr}")
        return proc.stdout


class TCPAdapter(BaseAdapter):
    """
    Optional generic TCP adapter.

    Requires phone-side TCP service with JSON-lines protocol:
      request : {"cmd":"handshake|dump|screenshot","request_id":"..."}\n
      response: {"ok":true,"payload_b64":"...","error":"..."}\n
    """

    name = "tcp"

    def __init__(self, host: str, port: int) -> None:
        self._host = host
        self._port = port
        self._sock: socket.socket | None = None
        self._reader: Any = None

    def connect(self) -> None:
        self._sock = socket.create_connection((self._host, self._port), timeout=6.0)
        self._reader = self._sock.makefile("rb")

    def close(self) -> None:
        if self._reader:
            self._reader.close()
            self._reader = None
        if self._sock:
            self._sock.close()
            self._sock = None

    def execute(self, command: str, timeout_sec: float) -> CommandOutput:
        if not self._sock or not self._reader:
            return CommandOutput(ok=False, payload_size=0, error="tcp_not_connected")

        req = {"cmd": command}
        try:
            self._sock.settimeout(timeout_sec)
            self._sock.sendall((json.dumps(req, ensure_ascii=False) + "\n").encode("utf-8"))
            line = self._reader.readline()
            if not line:
                return CommandOutput(ok=False, payload_size=0, error="tcp_empty_response")
            resp = json.loads(line.decode("utf-8", errors="ignore"))
            ok = bool(resp.get("ok", False))
            payload_b64 = resp.get("payload_b64", "")
            payload_size = len(base64.b64decode(payload_b64)) if payload_b64 else 0
            err = str(resp.get("error", "")) if not ok else ""
            return CommandOutput(ok=ok, payload_size=payload_size, error=err)
        except Exception as exc:
            return CommandOutput(ok=False, payload_size=0, error=str(exc))


def build_adapter(method: str, cfg: Any) -> BaseAdapter:
    if method == "udp":
        return UDPAdapter(cfg.UDP_DEVICE_IP, int(cfg.UDP_DEVICE_PORT))
    if method == "adb_tcp":
        return ADBTcpAdapter(cfg.ADB_BIN, cfg.ADB_SERIAL)
    if method == "tcp":
        return TCPAdapter(cfg.TCP_DEVICE_IP, int(cfg.TCP_DEVICE_PORT))
    raise ValueError(f"unknown_method:{method}")
