#!/usr/bin/env python3
"""Zero-dependency HTTP capacity probe for an isolated InsightForge environment.

This is a stepped read-path benchmark, not a seckill correctness test. It logs in once,
warms up, then measures throughput/error rate/P50/P95/P99 for each concurrency level.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import math
import statistics
import time
import urllib.error
import urllib.request


def request(url: str, token: str | None = None, body: bytes | None = None) -> tuple[bool, float]:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=body, headers=headers, method="POST" if body else "GET")
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            response.read()
            ok = 200 <= response.status < 300
    except (urllib.error.URLError, TimeoutError):
        ok = False
    return ok, (time.perf_counter() - started) * 1000


def percentile(values: list[float], p: float) -> float:
    values = sorted(values)
    return values[max(0, math.ceil(len(values) * p) - 1)] if values else 0.0


def login(base_url: str, username: str, password: str) -> str:
    payload = json.dumps({"username": username, "password": password}).encode()
    req = urllib.request.Request(f"{base_url}/api/login", data=payload,
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=10) as response:
        data = json.loads(response.read())
    if data.get("code") != 0:
        raise RuntimeError(f"login failed: {data.get('message')}")
    return data["data"]["token"]


def run_step(url: str, token: str, concurrency: int, requests: int) -> dict:
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        samples = list(pool.map(lambda _: request(url, token), range(requests)))
    duration = time.perf_counter() - started
    latencies = [latency for _, latency in samples]
    successes = sum(ok for ok, _ in samples)
    return {
        "concurrency": concurrency, "requests": requests, "successes": successes,
        "errors": requests - successes, "errorRate": round((requests - successes) / requests, 6),
        "durationSeconds": round(duration, 3), "throughputRps": round(requests / duration, 2),
        "meanMs": round(statistics.fmean(latencies), 2), "p50Ms": round(percentile(latencies, .50), 2),
        "p95Ms": round(percentile(latencies, .95), 2), "p99Ms": round(percentile(latencies, .99), 2),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--path", default="/api/catalog/products?page=1&size=20")
    parser.add_argument("--concurrency", default="1,10,25,50")
    parser.add_argument("--requests-per-step", type=int, default=500)
    parser.add_argument("--warmup", type=int, default=30)
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin123")
    parser.add_argument("--output")
    args = parser.parse_args()

    token = login(args.base_url.rstrip("/"), args.username, args.password)
    target = f"{args.base_url.rstrip('/')}{args.path}"
    for _ in range(args.warmup):
        request(target, token)
    report = {
        "target": target, "method": "GET", "requestsPerStep": args.requests_per_step,
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "steps": [run_step(target, token, c, args.requests_per_step)
                  for c in map(int, args.concurrency.split(","))],
        "scope": "single-process read-path capacity; not a production SLA or seckill write benchmark",
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as output:
            output.write(rendered + "\n")


if __name__ == "__main__":
    main()
