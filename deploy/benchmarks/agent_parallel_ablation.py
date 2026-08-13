#!/usr/bin/env python3
"""Derive the serial-role latency counterfactual from a real Agent evaluation report.

The four role calls have no dependency on one another in the current DAG.  The
deployed orchestrator runs them in parallel, so the role stage costs max(role
latencies).  A serial scheduler would cost sum(role latencies).  All remaining
trace time is retained.  This isolates scheduling latency only; it is not a
single-Agent quality experiment.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path


ROLES = {"SELECTOR", "PRICER", "WARMUP", "RISK"}


def percentile(values: list[float], p: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * p) - 1)]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    source = json.loads(args.report.read_text(encoding="utf-8"))
    data = source["data"]
    cases = [*data["positiveCases"], *data["safetyCases"]]
    rows = []
    for item in cases:
        trace = item["trace"]
        role_spans = [
            span for span in trace["spans"]
            if span["role"] in ROLES and span["stepName"] == "produce"
        ]
        if len(role_spans) != 4:
            raise ValueError(f"trace {trace['traceId']} does not contain four role spans")
        role_sum = sum(span["latencyMs"] for span in role_spans)
        role_max = max(span["latencyMs"] for span in role_spans)
        actual = trace["totalLatencyMs"]
        non_role = max(0, actual - role_max)
        serial = non_role + role_sum
        rows.append({
            "traceId": trace["traceId"],
            "status": trace["status"],
            "parallelActualMs": actual,
            "serialCounterfactualMs": serial,
            "savedMs": serial - actual,
            "speedup": round(serial / max(1, actual), 4),
            "tokensInBothVariants": trace["budgetTokens"],
            "costUsdInBothVariants": trace["budgetCostUsd"],
        })

    parallel = [row["parallelActualMs"] for row in rows]
    serial = [row["serialCounterfactualMs"] for row in rows]
    result = {
        "method": "span-derived serial scheduling counterfactual",
        "scope": "latency-only ablation; not a single-Agent quality comparison",
        "cases": len(rows),
        "parallelActual": {
            "p50Ms": percentile(parallel, .50),
            "p95Ms": percentile(parallel, .95),
        },
        "serialCounterfactual": {
            "p50Ms": percentile(serial, .50),
            "p95Ms": percentile(serial, .95),
        },
        "meanSavedMs": round(sum(row["savedMs"] for row in rows) / len(rows), 2),
        "meanSpeedup": round(sum(row["speedup"] for row in rows) / len(rows), 4),
        "totalTokensInBothVariants": sum(row["tokensInBothVariants"] for row in rows),
        "totalCostUsdInBothVariants": round(sum(row["costUsdInBothVariants"] for row in rows), 8),
        "rows": rows,
    }
    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
