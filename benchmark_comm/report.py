from __future__ import annotations

import argparse
import csv
import json
import statistics
from collections import defaultdict


def percentile(values: list[float], q: float) -> float:
    if not values:
        return 0.0
    xs = sorted(values)
    if len(xs) == 1:
        return xs[0]
    pos = (len(xs) - 1) * q
    lo = int(pos)
    hi = min(lo + 1, len(xs) - 1)
    w = pos - lo
    return xs[lo] * (1 - w) + xs[hi] * w


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate summary CSV from benchmark_comm JSONL")
    parser.add_argument("--file", required=True, help="Input JSONL")
    parser.add_argument("--csv", required=True, help="Output CSV")
    args = parser.parse_args()

    groups = defaultdict(list)
    with open(args.file, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            key = (row.get("method", ""), row.get("profile", ""), row.get("command", ""))
            groups[key].append(row)

    with open(args.csv, "w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow([
            "method", "profile", "command", "count", "success_rate",
            "latency_p50_ms", "latency_p95_ms", "latency_p99_ms",
            "avg_payload_bytes", "avg_retries", "timeout_or_error_rate",
        ])
        for key in sorted(groups.keys()):
            rows = groups[key]
            lats = [float(r.get("latency_ms", 0.0)) for r in rows]
            succ = [r for r in rows if bool(r.get("success", False))]
            fail = [r for r in rows if not bool(r.get("success", False))]
            avg_payload = statistics.mean([int(r.get("payload_size", 0)) for r in rows]) if rows else 0.0
            avg_retries = statistics.mean([int(r.get("retries", 0)) for r in rows]) if rows else 0.0
            writer.writerow([
                key[0], key[1], key[2], len(rows),
                round(len(succ) / len(rows), 4) if rows else 0.0,
                round(percentile(lats, 0.50), 2),
                round(percentile(lats, 0.95), 2),
                round(percentile(lats, 0.99), 2),
                round(avg_payload, 2),
                round(avg_retries, 2),
                round(len(fail) / len(rows), 4) if rows else 0.0,
            ])


if __name__ == "__main__":
    main()
