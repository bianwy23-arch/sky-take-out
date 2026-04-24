#!/usr/bin/env python3
"""
Generate sharded capacity-test data directly into physical shard tables.

Usage:
  python3 scripts/sharding_capacity_seed.py --orders 100000
"""

from __future__ import annotations

import argparse
import json
import random
import subprocess
import tempfile
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import DefaultDict, Dict, List, Tuple


@dataclass(frozen=True)
class DbConfig:
    host: str
    port: int
    user: str
    password: str


STATUSES = [2, 3, 4, 5, 6]
STATUS_WEIGHTS = [10, 15, 20, 45, 10]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Seed sharded order capacity data.")
    parser.add_argument("--orders", type=int, required=True, help="Total order rows to insert")
    parser.add_argument("--start-user-id", type=int, default=200000, help="First synthetic user id")
    parser.add_argument("--user-count", type=int, default=50000, help="Synthetic user cardinality")
    parser.add_argument("--details-per-order", type=int, default=2, help="Order detail rows per order")
    parser.add_argument("--batch-size", type=int, default=1000, help="Rows per multi-value insert")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user", default="root")
    parser.add_argument("--password", default="123456")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--metadata-out",
        default="scripts/report/sharding_capacity_seed_metadata.json",
        help="Metadata JSON output path",
    )
    return parser.parse_args()


def sql_quote(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"


def chunked(items: List[str], size: int) -> List[List[str]]:
    return [items[i : i + size] for i in range(0, len(items), size)]


def mysql_exec(config: DbConfig, sql_path: Path) -> None:
    cmd = [
        "mysql",
        "-h",
        config.host,
        "-P",
        str(config.port),
        "-u",
        config.user,
        f"-p{config.password}",
        "--binary-mode=1",
    ]
    with sql_path.open("rb") as fh:
        result = subprocess.run(cmd, stdin=fh, capture_output=True)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace"))


def main() -> None:
    args = parse_args()
    random.seed(args.seed)
    config = DbConfig(args.host, args.port, args.user, args.password)

    report_dir = Path(args.metadata_out).resolve().parent
    report_dir.mkdir(parents=True, exist_ok=True)

    order_rows: DefaultDict[str, DefaultDict[str, List[str]]] = defaultdict(lambda: defaultdict(list))
    detail_rows: DefaultDict[str, DefaultDict[str, List[str]]] = defaultdict(lambda: defaultdict(list))
    outbox_rows: DefaultDict[str, DefaultDict[str, List[str]]] = defaultdict(lambda: defaultdict(list))

    shard_counts: Dict[str, int] = defaultdict(int)
    sample_orders: Dict[str, object] = {}

    run_nonce = int(datetime.now().timestamp())
    base_order_id = 9_000_000_000_000 + run_nonce * 100_000
    base_detail_id = 9_100_000_000_000 + run_nonce * 100_000
    base_outbox_id = 9_200_000_000_000 + run_nonce * 100_000
    base_time = datetime.now() - timedelta(days=180)

    for i in range(args.orders):
        user_id = args.start_user_id + (i % args.user_count)
        db_idx = user_id % 2
        table_idx = user_id % 8
        db_name = f"sky_order_{db_idx}"
        orders_table = f"orders_{table_idx}"
        details_table = f"order_detail_{table_idx}"
        outbox_table = f"outbox_message_{table_idx}"

        order_id = base_order_id + i
        status = random.choices(STATUSES, weights=STATUS_WEIGHTS, k=1)[0]
        pay_status = 1 if status in (2, 3, 4, 5) else 0
        order_time = base_time + timedelta(minutes=i % 525600)
        checkout_time = order_time + timedelta(minutes=5) if pay_status else None
        delivery_time = order_time + timedelta(minutes=45) if status in (4, 5) else None
        cancel_time = order_time + timedelta(minutes=12) if status == 6 else None
        amount = round(18 + (i % 17) * 1.35, 2)
        phone = f"13{(user_id % 1000000000):09d}"
        suffix = str(user_id % 1_000_000).zfill(6)
        seq = str(i % 10_000).zfill(4)
        number = order_time.strftime("%Y%m%d%H%M%S") + suffix + seq

        order_sql = (
            "("
            f"{order_id},"
            f"{sql_quote(number)},"
            f"{status},"
            f"{user_id},"
            "2,"
            f"{sql_quote(order_time.strftime('%Y-%m-%d %H:%M:%S'))},"
            f"{'NULL' if checkout_time is None else sql_quote(checkout_time.strftime('%Y-%m-%d %H:%M:%S'))},"
            f"{1 if pay_status else 'NULL'},"
            f"{pay_status},"
            f"{amount:.2f},"
            f"{sql_quote('capacity-test')},"
            f"{sql_quote(phone)},"
            f"{sql_quote('北京市西城区文渊阁')},"
            f"{sql_quote(f'user_{user_id}')},"
            f"{sql_quote(f'user_{user_id}')},"
            f"{'NULL' if status != 6 else sql_quote('timeout auto cancel')},"
            "NULL,"
            f"{'NULL' if cancel_time is None else sql_quote(cancel_time.strftime('%Y-%m-%d %H:%M:%S'))},"
            f"{sql_quote((order_time + timedelta(minutes=35)).strftime('%Y-%m-%d %H:%M:%S'))},"
            "1,"
            f"{'NULL' if delivery_time is None else sql_quote(delivery_time.strftime('%Y-%m-%d %H:%M:%S'))},"
            "1,"
            f"{(i % 4) + 1},"
            "0"
            ")"
        )
        order_rows[db_name][orders_table].append(order_sql)

        for detail_idx in range(args.details_per_order):
            detail_id = base_detail_id + i * args.details_per_order + detail_idx
            detail_amount = round(amount / args.details_per_order, 2)
            detail_sql = (
                "("
                f"{detail_id},"
                f"{sql_quote(f'dish_{detail_idx + 1}')},"
                f"{order_id},"
                f"{user_id},"
                f"{100 + detail_idx},"
                "NULL,"
                f"{sql_quote('normal')},"
                "1,"
                f"{detail_amount:.2f},"
                f"{sql_quote('https://example.com/dish.png')}"
                ")"
            )
            detail_rows[db_name][details_table].append(detail_sql)

        outbox_id = base_outbox_id + i
        event_type = "ORDER_PAID" if pay_status else "ORDER_CREATED"
        payload = f"orderId={order_id},userId={user_id},status={status},amount={amount:.2f}"
        outbox_sql = (
            "("
            f"{outbox_id},"
            f"{sql_quote(number)},"
            f"{sql_quote(event_type)},"
            f"{sql_quote(payload)},"
            "1,"
            "0,"
            f"{sql_quote(order_time.strftime('%Y-%m-%d %H:%M:%S'))},"
            "NULL,"
            f"{sql_quote(order_time.strftime('%Y-%m-%d %H:%M:%S'))},"
            f"{sql_quote(order_time.strftime('%Y-%m-%d %H:%M:%S'))},"
            f"{user_id}"
            ")"
        )
        outbox_rows[db_name][outbox_table].append(outbox_sql)

        shard_key = f"{db_name}.{orders_table}"
        shard_counts[shard_key] += 1
        if not sample_orders:
            sample_orders = {
                "user_id": user_id,
                "status": status,
                "order_id": order_id,
                "order_number": number,
                "db_name": db_name,
                "orders_table": orders_table,
            }

    with tempfile.TemporaryDirectory(prefix="sharding_capacity_seed_") as tmp_dir_name:
        tmp_dir = Path(tmp_dir_name)
        for db_name, table_map in order_rows.items():
            sql_path = tmp_dir / f"{db_name}.sql"
            with sql_path.open("w", encoding="utf-8") as fh:
                fh.write("SET autocommit = 0;\n")
                fh.write(f"USE {db_name};\n")
                for table_name, rows in table_map.items():
                    for group in chunked(rows, args.batch_size):
                        fh.write(
                            "INSERT INTO "
                            f"{table_name} "
                            "(id, number, status, user_id, address_book_id, order_time, checkout_time, pay_method, "
                            "pay_status, amount, remark, phone, address, user_name, consignee, cancel_reason, "
                            "rejection_reason, cancel_time, estimated_delivery_time, delivery_status, delivery_time, "
                            "pack_amount, tableware_number, tableware_status) VALUES\n"
                        )
                        fh.write(",\n".join(group))
                        fh.write(";\n")
                for table_name, rows in detail_rows[db_name].items():
                    for group in chunked(rows, args.batch_size):
                        fh.write(
                            "INSERT INTO "
                            f"{table_name} "
                            "(id, name, order_id, user_id, dish_id, setmeal_id, dish_flavor, number, amount, image) "
                            "VALUES\n"
                        )
                        fh.write(",\n".join(group))
                        fh.write(";\n")
                for table_name, rows in outbox_rows[db_name].items():
                    for group in chunked(rows, args.batch_size):
                        fh.write(
                            "INSERT INTO "
                            f"{table_name} "
                            "(id, biz_key, event_type, payload, status, retry_count, next_retry_time, last_error, "
                            "created_time, update_time, user_id) VALUES\n"
                        )
                        fh.write(",\n".join(group))
                        fh.write(";\n")
                fh.write("COMMIT;\n")
            mysql_exec(config, sql_path)

    metadata = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "total_orders": args.orders,
        "details_per_order": args.details_per_order,
        "start_user_id": args.start_user_id,
        "user_count": args.user_count,
        "sample_order": sample_orders,
        "shard_counts": dict(sorted(shard_counts.items())),
    }
    metadata_path = Path(args.metadata_out)
    metadata_path.write_text(json.dumps(metadata, indent=2, ensure_ascii=True), encoding="utf-8")
    print(json.dumps(metadata, indent=2, ensure_ascii=True))


if __name__ == "__main__":
    main()
