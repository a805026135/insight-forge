#!/usr/bin/env python3
"""Authenticated end-to-end seckill capacity benchmark (standard library only).

One JWT represents one user because the API deliberately ignores body.userId. Supply at
least one distinct token per request so the test measures new reservations, not duplicate
idempotency hits. The report separates business rejection from infrastructure failure.
"""
from __future__ import annotations

import argparse
import base64
import concurrent.futures
import hashlib
import hmac
import json
import math
import os
import statistics
import time
import urllib.error
import urllib.request


def percentile(values: list[float], ratio: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * ratio) - 1)] if ordered else 0.0


def load_tokens(path: str) -> list[str]:
    tokens = []
    with open(path, encoding="utf-8") as source:
        for raw in source:
            value = raw.strip()
            if not value:
                continue
            # Accept either a raw JWT per line or {"token":"..."} JSONL.
            tokens.append(json.loads(value)["token"] if value.startswith("{") else value)
    return tokens

def login_one(base_url: str, username: str, password: str) -> str:
    payload = json.dumps({"username": username, "password": password}).encode()
    req = urllib.request.Request(f"{base_url.rstrip('/')}/api/login", data=payload,
                                 method="POST", headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as response:
        body = json.loads(response.read())
    if int(body.get("code", -1)) != 0:
        raise RuntimeError(f"login failed for {username}: {body.get('message')}")
    return body["data"]["token"]


def login_tokens(base_url: str, prefix: str, password: str,
                 count: int, concurrency: int) -> list[str]:
    usernames = [f"{prefix}{index:05d}" for index in range(1, count + 1)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(concurrency, 32)) as pool:
        futures = [pool.submit(login_one, base_url, username, password)
                   for username in usernames]
        return [future.result() for future in futures]


def local_benchmark_tokens(secret: str, first_user_id: int, count: int) -> list[str]:
    """Generate HS256 JWTs for pre-seeded local benchmark users without disk token files.

    This mode is deliberately gated by an environment variable and is only suitable for an
    isolated local benchmark whose API uses the same non-production secret.
    """
    def encode(value: bytes) -> str:
        return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")

    now = int(time.time())
    header = encode(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    tokens = []
    for user_id in range(first_user_id, first_user_id + count):
        payload = encode(json.dumps({
            "sub": str(user_id),
            "username": f"qps-local-{user_id}",
            "role": "user",
            "displayName": f"QPS User {user_id}",
            "merchantId": 0,
            "iat": now,
            "exp": now + 3600,
        }, separators=(",", ":")).encode())
        signing_input = f"{header}.{payload}"
        signature = encode(hmac.new(secret.encode(), signing_input.encode(), hashlib.sha256).digest())
        tokens.append(f"{signing_input}.{signature}")
    return tokens


def request_once(url: str, token: str, activity_id: int, sku_id: int) -> dict:
    payload = json.dumps({
        "activityId": activity_id, "skuId": sku_id, "qty": 1,
        # Server overwrites this with the authenticated identity; keep it explicit for schema compatibility.
        "userId": 0,
    }).encode()
    req = urllib.request.Request(url, data=payload, method="POST", headers={
        "Authorization": f"Bearer {token}", "Content-Type": "application/json",
    })
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            body = json.loads(response.read())
        business_code = int(body.get("code", -1))
        kind = ("accepted" if business_code == 0 else
                "capacity_rejected" if business_code in (429, 503) else "business_rejected")
        return {"kind": kind, "businessCode": business_code,
                "latencyMs": (time.perf_counter() - started) * 1000}
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, ValueError) as error:
        return {"kind": "infrastructure_error", "error": type(error).__name__,
                "latencyMs": (time.perf_counter() - started) * 1000}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--tokens-file",
                        help="one distinct user's JWT per line; never commit this file")
    parser.add_argument("--login-prefix", help="login pre-seeded users prefix00001..N")
    parser.add_argument("--login-password", default="QpsTest123!")
    parser.add_argument("--local-jwt-secret-env",
                        help="environment variable containing the isolated local benchmark JWT secret")
    parser.add_argument("--user-id-start", type=int, default=1,
                        help="first pre-seeded user id for --local-jwt-secret-env")
    parser.add_argument("--activity-id", type=int, required=True)
    parser.add_argument("--sku-id", type=int, required=True,
                        help="activity_sku.id, not product.id")
    parser.add_argument("--concurrency", type=int, default=50)
    parser.add_argument("--requests", type=int,
                        help="defaults to token count; cannot exceed it")
    parser.add_argument("--variant", choices=("sync-ack", "async-reservation"), required=True)
    parser.add_argument("--output")
    args = parser.parse_args()

    if args.tokens_file:
        tokens = load_tokens(args.tokens_file)
    elif args.local_jwt_secret_env and args.requests:
        secret = os.environ.get(args.local_jwt_secret_env, "")
        if len(secret.encode()) < 32:
            raise SystemExit("local benchmark JWT secret must be at least 32 bytes")
        tokens = local_benchmark_tokens(secret, args.user_id_start, args.requests)
    elif args.login_prefix and args.requests:
        tokens = login_tokens(args.base_url, args.login_prefix, args.login_password,
                              args.requests, args.concurrency)
    else:
        raise SystemExit("provide --tokens-file, --local-jwt-secret-env, or --login-prefix with --requests")
    count = args.requests or len(tokens)
    if count > len(tokens):
        raise SystemExit("requests cannot exceed distinct token count; token reuse only measures duplicates")
    if not tokens:
        raise SystemExit("tokens file is empty")

    target = f"{args.base_url.rstrip('/')}/api/seckill"
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(request_once, target, token, args.activity_id, args.sku_id)
                   for token in tokens[:count]]
        samples = [future.result() for future in futures]
    duration = time.perf_counter() - started
    latencies = [sample["latencyMs"] for sample in samples]
    categories = {name: sum(sample["kind"] == name for sample in samples)
                  for name in ("accepted", "capacity_rejected", "business_rejected", "infrastructure_error")}
    codes = {}
    for sample in samples:
        if "businessCode" in sample:
            key = str(sample["businessCode"])
            codes[key] = codes.get(key, 0) + 1

    report = {
        "variant": args.variant,
        "scope": "authenticated end-to-end new-user seckill admission; not DB settlement TPS",
        "target": target, "concurrency": args.concurrency, "requests": count,
        "durationSeconds": round(duration, 3),
        "offeredQps": round(count / duration, 2),
        "acceptedQps": round(categories["accepted"] / duration, 2),
        **categories, "businessCodes": codes,
        "meanMs": round(statistics.fmean(latencies), 2),
        "p50Ms": round(percentile(latencies, .50), 2),
        "p95Ms": round(percentile(latencies, .95), 2),
        "p99Ms": round(percentile(latencies, .99), 2),
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as destination:
            destination.write(rendered + "\n")


if __name__ == "__main__":
    main()
