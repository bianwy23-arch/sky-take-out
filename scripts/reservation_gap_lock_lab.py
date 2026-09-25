#!/usr/bin/env python3
"""Controlled RR/RC experiment on the existing paid-coupon table; all writes rollback."""
import argparse
import concurrent.futures
import getpass
import json
import os
from pathlib import Path
import secrets
import threading
import time

import pymysql


SELECT_UNITS = """SELECT coupon_id, unit_id FROM paid_coupon_available_unit
WHERE coupon_id = %s ORDER BY unit_id LIMIT 1 FOR UPDATE SKIP LOCKED"""


def query(conn, sql, params=()):
    with conn.cursor() as cursor:
        cursor.execute(sql, params)
        return cursor.fetchall()


def locks(observer, ids):
    return query(observer, """
        SELECT t.PROCESSLIST_ID AS connection_id, l.OBJECT_NAME, l.INDEX_NAME,
               l.LOCK_TYPE, l.LOCK_MODE, l.LOCK_STATUS, l.LOCK_DATA
        FROM performance_schema.data_locks l
        JOIN performance_schema.threads t ON t.THREAD_ID = l.THREAD_ID
        WHERE t.PROCESSLIST_ID IN (%s, %s)
        ORDER BY connection_id, l.OBJECT_NAME, l.LOCK_TYPE
    """, ids)


def waits(observer, ids):
    return query(observer, """
        SELECT r.PROCESSLIST_ID AS waiting_connection,
               b.PROCESSLIST_ID AS blocking_connection,
               l.OBJECT_NAME, l.INDEX_NAME, l.LOCK_MODE, l.LOCK_DATA
        FROM performance_schema.data_lock_waits w
        JOIN performance_schema.threads r ON r.THREAD_ID = w.REQUESTING_THREAD_ID
        JOIN performance_schema.threads b ON b.THREAD_ID = w.BLOCKING_THREAD_ID
        JOIN performance_schema.data_locks l
          ON l.ENGINE = w.ENGINE AND l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
        WHERE r.PROCESSLIST_ID = %s AND b.PROCESSLIST_ID = %s
    """, (ids[1], ids[0]))


def run_case(connect, observer, coupon_id, isolation, rollback_first, observe_seconds):
    name = isolation.lower().replace(" ", "_") + ("_rollback_first" if rollback_first else "_held")
    result = {"case": name, "isolation": isolation, "rollback_first": rollback_first}
    with connect() as reader, connect() as writer:
        ids = (query(reader, "SELECT CONNECTION_ID() AS id")[0]["id"],
               query(writer, "SELECT CONNECTION_ID() AS id")[0]["id"])
        result["connection_ids"] = ids
        query(reader, "SET SESSION TRANSACTION ISOLATION LEVEL " + isolation)
        query(writer, "SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED")
        query(writer, "SET SESSION innodb_lock_wait_timeout = 5")
        reader.begin()
        result["actual_isolation"] = query(reader, "SELECT @@transaction_isolation AS value")[0]["value"]
        started = threading.Event()
        inserted = threading.Event()
        release_writer = threading.Event()

        def insert():
            writer.begin()
            started.set()
            begin = time.monotonic()
            try:
                query(writer, "INSERT INTO paid_coupon_available_unit(coupon_id, unit_id) VALUES (%s, 1)", (coupon_id,))
                elapsed = time.monotonic() - begin
                inserted.set()
                # Keep the successful insert visible to lock observation until explicitly released.
                release_writer.wait(8)
                return {"inserted": True, "insert_ms": round(elapsed * 1000, 3)}
            finally:
                writer.rollback()

        try:
            selected = query(reader, SELECT_UNITS, (coupon_id,))
            if selected:
                raise RuntimeError("Experimental coupon ID is not empty; refusing to continue")
            result["locks_after_empty_select"] = locks(observer, ids)
            if rollback_first:
                reader.rollback()
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
                future = pool.submit(insert)
                try:
                    if not started.wait(3):
                        raise RuntimeError("Writer did not start")
                    deadline = time.monotonic() + observe_seconds
                    observed_waits = []
                    observed_locks = []
                    while time.monotonic() < deadline:
                        observed_waits = waits(observer, ids)
                        observed_locks = locks(observer, ids)
                        if observed_waits or inserted.is_set() or future.done():
                            break
                        time.sleep(0.02)
                    result["waits_before_reader_rollback"] = observed_waits
                    result["locks_before_reader_rollback"] = observed_locks
                    inserted_before_rollback = inserted.is_set()
                    result["insert_completed_before_reader_rollback"] = inserted_before_rollback
                finally:
                    reader.rollback()
                    release_writer.set()
                result.update(future.result(timeout=8))
            expect_wait = isolation == "REPEATABLE READ" and not rollback_first
            result["passed"] = (bool(observed_waits) if expect_wait else
                                not observed_waits and inserted_before_rollback) and result["inserted"]
        finally:
            reader.rollback()
            release_writer.set()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1", choices=["127.0.0.1", "localhost", "::1"])
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user", default="root")
    parser.add_argument("--database", default="sky_order_0")
    parser.add_argument("--observe-seconds", type=float, default=2)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not 0.1 <= args.observe_seconds <= 3:
        parser.error("--observe-seconds must be between 0.1 and 3")
    if args.output.exists():
        parser.error("Output already exists; choose a new report path")
    password = os.environ.get("MYSQL_PASSWORD")
    if password is None:
        password = getpass.getpass("Local MySQL password: ")

    def connect():
        return pymysql.connect(host=args.host, port=args.port, user=args.user,
                               password=password, database=args.database, autocommit=True,
                               connect_timeout=3, read_timeout=12, write_timeout=5,
                               cursorclass=pymysql.cursors.DictCursor)

    report = {"evidence_class": "controlled_real_mysql_sql_experiment",
              "database": args.database, "cases": [],
              "limitations": "Direct SQL, not Spring/API load test; inserted units always rolled back."}
    # High random ID identifies only this run; no existing rows are deleted or committed.
    coupon_id = 8_000_000_000_000_000_000 + secrets.randbelow(100_000_000_000)
    report["coupon_id"] = coupon_id
    try:
        with connect() as observer:
            report["server"] = query(observer, "SELECT VERSION() AS version, @@hostname AS hostname")[0]
            tables = query(observer, """SELECT ENGINE FROM information_schema.tables
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'paid_coupon_available_unit'""")
            if len(tables) != 1 or tables[0]["ENGINE"] != "InnoDB":
                raise RuntimeError("Existing InnoDB paid_coupon_available_unit table is required")
            locks(observer, (0, 0))  # Fail before writes if observation privileges are missing.
            waits(observer, (0, 0))
            for table in ("paid_coupon_inventory", "paid_coupon_available_unit", "paid_coupon_reserved_unit"):
                if query(observer, "SELECT coupon_id FROM " + table + " WHERE coupon_id=%s LIMIT 1", (coupon_id,)):
                    raise RuntimeError("Random coupon ID collision; no changes made")
            for isolation, rollback_first in (("REPEATABLE READ", False), ("READ COMMITTED", False),
                                               ("REPEATABLE READ", True)):
                case = run_case(connect, observer, coupon_id, isolation, rollback_first, args.observe_seconds)
                report["cases"].append(case)
                print(case["case"], "PASS" if case["passed"] else "NOT_REPRODUCED")
            report["remaining_test_rows"] = query(observer,
                "SELECT COUNT(*) AS n FROM paid_coupon_available_unit WHERE coupon_id=%s", (coupon_id,))[0]["n"]
            report["passed"] = all(c["passed"] for c in report["cases"]) and report["remaining_test_rows"] == 0
    except Exception as exc:
        report["passed"] = False
        report["error"] = str(exc)
        raise
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str) + "\n")
        print("Report:", args.output)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
