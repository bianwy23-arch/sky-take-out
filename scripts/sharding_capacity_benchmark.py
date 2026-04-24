#!/usr/bin/env python3
"""
Benchmark routed-vs-broadcast style queries directly on physical shard tables.
"""

from __future__ import annotations

import argparse
import json
import statistics
import subprocess
import time
from datetime import datetime
from pathlib import Path
from typing import Dict, List


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Benchmark sharded query patterns.")
    parser.add_argument(
        "--metadata",
        default="scripts/report/sharding_capacity_seed_metadata.json",
        help="Metadata JSON emitted by sharding_capacity_seed.py",
    )
    parser.add_argument("--iterations", type=int, default=20)
    parser.add_argument("--warmup", type=int, default=3)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user", default="root")
    parser.add_argument("--password", default="123456")
    parser.add_argument(
        "--report-out",
        default="scripts/report/sharding_capacity_benchmark_report.json",
        help="Benchmark report output path",
    )
    return parser.parse_args()


def mysql_query(host: str, port: int, user: str, password: str, query: str) -> None:
    cmd = [
        "mysql",
        "-h",
        host,
        "-P",
        str(port),
        "-u",
        user,
        f"-p{password}",
        "-N",
        "-s",
        "-e",
        query,
    ]
    result = subprocess.run(cmd, capture_output=True)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace"))


def run_case(name: str, query: str, args: argparse.Namespace) -> Dict[str, object]:
    for _ in range(args.warmup):
        mysql_query(args.host, args.port, args.user, args.password, query)

    timings: List[float] = []
    for _ in range(args.iterations):
        start = time.perf_counter()
        mysql_query(args.host, args.port, args.user, args.password, query)
        timings.append((time.perf_counter() - start) * 1000)

    timings_sorted = sorted(timings)
    p95_index = max(0, min(len(timings_sorted) - 1, int(len(timings_sorted) * 0.95) - 1))
    p99_index = max(0, min(len(timings_sorted) - 1, int(len(timings_sorted) * 0.99) - 1))
    return {
        "case": name,
        "avg_ms": round(statistics.mean(timings), 3),
        "min_ms": round(min(timings), 3),
        "max_ms": round(max(timings), 3),
        "p95_ms": round(timings_sorted[p95_index], 3),
        "p99_ms": round(timings_sorted[p99_index], 3),
        "iterations": args.iterations,
        "query": query,
    }


def build_broadcast_union(where_clause: str, limit_clause: str = "") -> str:
    subqueries = []
    for db_idx in range(2):
        for table_idx in range(8):
            subqueries.append(
                "SELECT id, number, status, user_id, order_time FROM "
                f"sky_order_{db_idx}.orders_{table_idx} WHERE {where_clause}"
            )
    union_sql = " UNION ALL ".join(subqueries)
    outer = f"SELECT SQL_NO_CACHE * FROM ({union_sql}) t"
    if limit_clause:
        outer += " " + limit_clause
    return outer + ";"


def main() -> None:
    args = parse_args()
    metadata = json.loads(Path(args.metadata).read_text(encoding="utf-8"))
    sample = metadata["sample_order"]
    user_id = sample["user_id"]
    status = sample["status"]
    number = sample["order_number"]
    db_name = sample["db_name"]
    table_name = sample["orders_table"]

    cases = [
        (
            "routed_order_lookup",
            "SELECT SQL_NO_CACHE id FROM "
            f"{db_name}.{table_name} "
            f"WHERE number = '{number}' AND user_id = {user_id};",
        ),
        (
            "broadcast_order_lookup",
            build_broadcast_union(f"number = '{number}'", "LIMIT 1"),
        ),
        (
            "routed_user_order_list",
            "SELECT SQL_NO_CACHE id, number, status, order_time FROM "
            f"{db_name}.{table_name} "
            f"WHERE user_id = {user_id} AND status = {status} ORDER BY order_time DESC LIMIT 20;",
        ),
        (
            "broadcast_status_query",
            build_broadcast_union(f"status = {status}", "ORDER BY order_time DESC LIMIT 20"),
        ),
    ]

    results = [run_case(name, query, args) for name, query in cases]
    report = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "metadata_file": str(Path(args.metadata).resolve()),
        "sample_order": sample,
        "results": results,
    }

    report_path = Path(args.report_out)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=True), encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=True))


if __name__ == "__main__":
    main()
