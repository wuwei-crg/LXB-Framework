"""
benchmark_comm local config template.

Copy to benchmark_comm/config.py and edit values.
"""

# Results
RESULTS_FILE = "benchmark_comm/results.jsonl"
SUMMARY_CSV = "benchmark_comm/summary.csv"

# Common benchmark defaults
DEFAULT_METHODS = ["udp", "adb_tcp"]
DEFAULT_COMMANDS = ["handshake", "dump", "screenshot"]
DEFAULT_PROFILES = ["strong", "weak", "tunnel"]
ROUNDS_PER_CASE = 10
MAX_RETRIES = 2
BASE_TIMEOUT_SEC = 8.0

# LXB UDP device endpoint
UDP_DEVICE_IP = "192.168.1.100"
UDP_DEVICE_PORT = 12345

# Optional TCP device endpoint (requires phone-side TCP service)
TCP_DEVICE_IP = "192.168.1.100"
TCP_DEVICE_PORT = 22345

# ADB target (for adb-over-tcp set like "192.168.1.100:5555")
ADB_SERIAL = "192.168.1.100:5555"
ADB_BIN = "adb"

# Mocked network profile at PC sender side (application-layer impairment)
# delay_ms: one-way base delay, jitter_ms: +/- jitter, loss_pct: per-attempt simulated drop
NET_PROFILES = {
    "strong": {"delay_ms": 10, "jitter_ms": 3, "loss_pct": 0.0},
    "weak": {"delay_ms": 120, "jitter_ms": 60, "loss_pct": 3.0},
    "tunnel": {"delay_ms": 70, "jitter_ms": 40, "loss_pct": 1.0},
}
