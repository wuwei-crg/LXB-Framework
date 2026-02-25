from __future__ import annotations

import argparse
import csv
import dataclasses
import importlib
import json
import os
import statistics
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

from benchmark_comm.adapters import build_adapter
from benchmark_comm.impairment import ImpairmentEngine, NetworkProfile


@dataclass
class CommRunResult:
    ts: str
    method: str
    profile: str
    command: str
    round_idx: int
    success: bool
    latency_ms: float
    payload_size: int
    retries: int
    error: str


def _load_config() -> Any:
    try:
        return importlib.import_module("benchmark_comm.config")
    except ModuleNotFoundError:
        # Fallback lets users run --help/smoke before creating local config.py.
        return importlib.import_module("benchmark_comm.config_example")


def _append_result(row: CommRunResult, filepath: str) -> None:
    os.makedirs(os.path.dirname(filepath), exist_ok=True)
    with open(filepath, "a", encoding="utf-8") as f:
        f.write(json.dumps(dataclasses.asdict(row), ensure_ascii=False) + "\n")


def _percentile(values: list[float], q: float) -> float:
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


def _write_summary(results: list[CommRunResult], filepath: str) -> None:
    os.makedirs(os.path.dirname(filepath), exist_ok=True)
    groups: dict[tuple[str, str, str], list[CommRunResult]] = {}
    for row in results:
        groups.setdefault((row.method, row.profile, row.command), []).append(row)

    with open(filepath, "w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow([
            "method", "profile", "command", "count", "success_rate",
            "latency_p50_ms", "latency_p95_ms", "latency_p99_ms",
            "avg_payload_bytes", "avg_retries", "timeout_or_error_rate",
        ])
        for key in sorted(groups.keys()):
            rows = groups[key]
            lats = [r.latency_ms for r in rows]
            succ = [r for r in rows if r.success]
            fail = [r for r in rows if not r.success]
            avg_payload = statistics.mean([r.payload_size for r in rows]) if rows else 0.0
            avg_retries = statistics.mean([r.retries for r in rows]) if rows else 0.0
            writer.writerow([
                key[0], key[1], key[2], len(rows),
                round(len(succ) / len(rows), 4) if rows else 0.0,
                round(_percentile(lats, 0.50), 2),
                round(_percentile(lats, 0.95), 2),
                round(_percentile(lats, 0.99), 2),
                round(avg_payload, 2),
                round(avg_retries, 2),
                round(len(fail) / len(rows), 4) if rows else 0.0,
            ])


def _run_single(
    adapter,
    impairment: ImpairmentEngine,
    command: str,
    timeout_sec: float,
    max_retries: int,
) -> tuple[bool, float, int, int, str]:
    started = time.perf_counter()
    retries = 0
    payload_size = 0
    last_error = ""

    for attempt in range(0, max_retries + 1):
        retries = attempt

        impairment.apply_request_delay()
        if impairment.maybe_drop():
            last_error = "simulated_drop_before_send"
            impairment.apply_response_delay()
            continue

        out = adapter.execute(command, timeout_sec)

        impairment.apply_response_delay()
        if impairment.maybe_drop():
            last_error = "simulated_drop_after_recv"
            continue

        payload_size = out.payload_size
        if out.ok:
            elapsed_ms = (time.perf_counter() - started) * 1000.0
            return True, elapsed_ms, payload_size, retries, ""

        last_error = out.error or "command_failed"

    elapsed_ms = (time.perf_counter() - started) * 1000.0
    return False, elapsed_ms, payload_size, retries, last_error


def _log_line(payload: dict[str, Any]) -> None:
    print(f"[benchmark_comm] {json.dumps(payload, ensure_ascii=False)}", flush=True)


def main() -> None:
    cfg = _load_config()

    parser = argparse.ArgumentParser(description="Communication benchmark for phone control links")
    parser.add_argument("--methods", nargs="+", default=list(cfg.DEFAULT_METHODS), choices=["udp", "tcp", "adb_tcp"])
    parser.add_argument("--commands", nargs="+", default=list(cfg.DEFAULT_COMMANDS), choices=["handshake", "dump", "screenshot"])
    parser.add_argument("--profiles", nargs="+", default=list(cfg.DEFAULT_PROFILES), help="Network profile names from NET_PROFILES")
    parser.add_argument("--rounds", type=int, default=int(cfg.ROUNDS_PER_CASE))
    parser.add_argument("--retries", type=int, default=int(cfg.MAX_RETRIES))
    parser.add_argument("--timeout", type=float, default=float(cfg.BASE_TIMEOUT_SEC))
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--results", default=str(cfg.RESULTS_FILE))
    parser.add_argument("--summary", default=str(cfg.SUMMARY_CSV))
    args = parser.parse_args()

    profile_objs: dict[str, NetworkProfile] = {}
    for name in args.profiles:
        if name not in cfg.NET_PROFILES:
            raise ValueError(f"unknown_profile:{name}")
        p = cfg.NET_PROFILES[name]
        profile_objs[name] = NetworkProfile(
            name=name,
            delay_ms=float(p.get("delay_ms", 0.0)),
            jitter_ms=float(p.get("jitter_ms", 0.0)),
            loss_pct=float(p.get("loss_pct", 0.0)),
        )

    total = len(args.methods) * len(args.profiles) * len(args.commands) * args.rounds
    done = 0
    results: list[CommRunResult] = []

    _log_line({
        "事件": "开始",
        "方法": args.methods,
        "环境": args.profiles,
        "指令": args.commands,
        "轮数": args.rounds,
        "总样本": total,
        "结果文件": args.results,
    })

    for method in args.methods:
        adapter = build_adapter(method, cfg)
        adapter.connect()
        try:
            for profile_name in args.profiles:
                profile = profile_objs[profile_name]
                impairment = ImpairmentEngine(profile=profile, seed=args.seed)

                for command in args.commands:
                    for i in range(1, args.rounds + 1):
                        done += 1
                        ok, latency_ms, payload_size, retries, err = _run_single(
                            adapter=adapter,
                            impairment=impairment,
                            command=command,
                            timeout_sec=args.timeout,
                            max_retries=args.retries,
                        )
                        row = CommRunResult(
                            ts=datetime.now(timezone.utc).isoformat(),
                            method=method,
                            profile=profile_name,
                            command=command,
                            round_idx=i,
                            success=ok,
                            latency_ms=round(latency_ms, 2),
                            payload_size=payload_size,
                            retries=retries,
                            error=err,
                        )
                        results.append(row)
                        _append_result(row, args.results)

                        _log_line({
                            "进度": f"{done}/{total}",
                            "方法": method,
                            "环境": profile_name,
                            "指令": command,
                            "轮次": i,
                            "成功": ok,
                            "耗时ms": row.latency_ms,
                            "负载字节": payload_size,
                            "重试": retries,
                            "错误": err,
                        })
        finally:
            adapter.close()

    _write_summary(results, args.summary)
    _log_line({"事件": "完成", "结果": args.results, "汇总": args.summary})


if __name__ == "__main__":
    main()
