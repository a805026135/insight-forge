#!/usr/bin/env python3
"""Measure the real Redis Lua hot path and verify recovery after a restart.

The target must be an isolated Redis instance. This probe only uses the Python
standard library and the Redis wire protocol, so it is reproducible without a
redis-py dependency. It is a component benchmark, not an end-to-end HTTP SLA.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import math
import socket
import statistics
import time
from datetime import datetime, timezone


LUA = """
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then return -2 end
local qty = tonumber(ARGV[1])
if stock < qty then return -1 end
redis.call('DECRBY', KEYS[1], qty)
return stock - qty
""".strip()


def encode(*parts: object) -> bytes:
    out = [f"*{len(parts)}\r\n".encode()]
    for part in parts:
        value = str(part).encode()
        out.extend((f"${len(value)}\r\n".encode(), value, b"\r\n"))
    return b"".join(out)


def read(sock: socket.socket):
    stream = sock.makefile("rb")
    marker = stream.read(1)
    line = stream.readline().rstrip(b"\r\n")
    if marker == b"+": return line.decode()
    if marker == b":": return int(line)
    if marker == b"$":
        length = int(line)
        if length < 0: return None
        value = stream.read(length)
        stream.read(2)
        return value.decode()
    if marker == b"-": raise RuntimeError(line.decode())
    raise RuntimeError(f"unexpected Redis response marker: {marker!r}")


def command(host: str, port: int, *parts: object, timeout: float = 2.0):
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.sendall(encode(*parts))
        return read(sock)


def percentile(values: list[float], p: float) -> float:
    values = sorted(values)
    return values[max(0, math.ceil(len(values) * p) - 1)]


def wait_ready(host: str, port: int, timeout: float) -> float:
    start = time.perf_counter()
    deadline = start + timeout
    while time.perf_counter() < deadline:
        try:
            if command(host, port, "PING", timeout=.25) == "PONG":
                return (time.perf_counter() - start) * 1000
        except (OSError, RuntimeError):
            time.sleep(.05)
    raise TimeoutError("Redis did not recover before timeout")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=16379)
    parser.add_argument("--requests", type=int, default=300)
    parser.add_argument("--stock", type=int, default=100)
    parser.add_argument("--workers", type=int, default=100)
    parser.add_argument("--verify-restart-only", action="store_true")
    parser.add_argument("--output")
    args = parser.parse_args()

    ready_ms = wait_ready(args.host, args.port, 20)
    if args.verify_restart_only:
        report = {
            "measuredAt": datetime.now(timezone.utc).isoformat(),
            "scope": "isolated Redis restart recovery verification",
            "restartRecovery": {
                "readyMs": round(ready_ms, 3),
                "persistedValue": command(args.host, args.port, "GET", "if:benchmark:recovery"),
            },
        }
        text = json.dumps(report, ensure_ascii=False, indent=2)
        print(text)
        if args.output:
            with open(args.output, "w", encoding="utf-8") as handle:
                handle.write(text + "\n")
        return
    key = "if:benchmark:stock"
    command(args.host, args.port, "SET", key, args.stock)
    sha = command(args.host, args.port, "SCRIPT", "LOAD", LUA)

    def deduct(_):
        started = time.perf_counter()
        result = command(args.host, args.port, "EVALSHA", sha, 1, key, 1)
        return result, (time.perf_counter() - started) * 1000

    wall = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        rows = list(pool.map(deduct, range(args.requests)))
    wall_ms = (time.perf_counter() - wall) * 1000
    values = [x[0] for x in rows]
    latencies = [x[1] for x in rows]
    remaining = int(command(args.host, args.port, "GET", key))
    command(args.host, args.port, "SET", "if:benchmark:recovery", "survives-restart")
    command(args.host, args.port, "SAVE")

    report = {
        "measuredAt": datetime.now(timezone.utc).isoformat(),
        "scope": "isolated real Redis 7 Lua component benchmark; not end-to-end HTTP capacity",
        "requests": args.requests,
        "workers": args.workers,
        "initialStock": args.stock,
        "success": sum(v >= 0 for v in values),
        "soldOut": sum(v == -1 for v in values),
        "remainingStock": remaining,
        "oversold": remaining < 0 or sum(v >= 0 for v in values) > args.stock,
        "throughputRps": round(args.requests / (wall_ms / 1000), 2),
        "latencyMs": {
            "mean": round(statistics.mean(latencies), 3),
            "p50": round(percentile(latencies, .50), 3),
            "p95": round(percentile(latencies, .95), 3),
            "max": round(max(latencies), 3),
        },
        "restartRecovery": "run container restart, then use --verify-restart-only",
    }
    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")


if __name__ == "__main__":
    main()
