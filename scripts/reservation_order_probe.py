#!/usr/bin/env python3
"""Bounded direct-SQL reserve/claim overlap probe, not an HTTP or Shopify reproduction."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import getpass
import json
import os
from pathlib import Path
import secrets
import threading
import uuid
import pymysql


def run(args, password):
    def connect():
        c = pymysql.connect(host='127.0.0.1', port=3306, user=args.user, password=password,
                            database='sky_order_0', autocommit=True, connect_timeout=3,
                            read_timeout=10, write_timeout=5)
        sql(c, 'SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED')
        sql(c, 'SET SESSION innodb_lock_wait_timeout=3')
        return c

    coupon = 7_000_000_000_000_000_000 + secrets.randbelow(100_000_000)
    prefix = 'order-probe-' + uuid.uuid4().hex
    old, new = prefix + '-old', prefix + '-new'
    report = {'evidence': 'direct_sql_controlled_overlap', 'coupon_id': coupon,
              'limitations': 'RC; single coupon, distinct requests/units; no retries; not HTTP; no claim of universal deadlock freedom',
              'cases': []}
    with connect() as setup:
        report['version'] = sql(setup, 'SELECT VERSION()')[0][0]
        # Do not touch any colliding fixture. Setup is one atomic transaction.
        for table in ('paid_coupon_inventory', 'paid_coupon_available_unit', 'paid_coupon_reserved_unit'):
            assert not sql(setup, 'SELECT coupon_id FROM ' + table + ' WHERE coupon_id=%s LIMIT 1', (coupon,))
        setup.begin()
        try:
            sql(setup, 'INSERT INTO paid_coupon_inventory VALUES (%s,2,2,3)', (coupon,))
            sql(setup, 'INSERT INTO paid_coupon_available_unit VALUES (%s,2)', (coupon,))
            sql(setup, "INSERT INTO paid_coupon_reservation_batch VALUES (%s,1,%s,'RESERVED',DATE_ADD(NOW(),INTERVAL 1 DAY))",
                (old, json.dumps([{'couponId': coupon, 'quantity': 1}])))
            sql(setup, 'INSERT INTO paid_coupon_reserved_unit VALUES (%s,%s,1)', (old, coupon))
            setup.commit()
        except BaseException:
            setup.rollback()
            raise
        try:
            for reverse in (True, False):
                for iteration in range(args.rounds):
                    barrier = threading.Barrier(2)
                    def worker(reserve):
                        with connect() as c:
                            c.begin()
                            try:
                                req = new if reserve else old
                                rows = sql(c, 'SELECT state FROM paid_coupon_reservation_batch WHERE request_id=%s FOR UPDATE', (req,))
                                if reserve:
                                    assert not rows
                                    sql(c, "INSERT INTO paid_coupon_reservation_batch VALUES (%s,1,%s,'RESERVED',DATE_ADD(NOW(),INTERVAL 1 DAY))",
                                        (new, json.dumps([{'couponId': coupon, 'quantity': 1}])))
                                    assert sql(c, 'SELECT coupon_id,unit_id FROM paid_coupon_available_unit WHERE coupon_id=%s ORDER BY unit_id LIMIT 1 FOR UPDATE SKIP LOCKED', (coupon,)) == ((coupon, 2),)
                                    writes = [('INSERT INTO paid_coupon_reserved_unit VALUES (%s,%s,2)', (new, coupon)),
                                              ('DELETE FROM paid_coupon_available_unit WHERE (coupon_id,unit_id) IN ((%s,2))', (coupon,))]
                                    if not reverse:
                                        writes.reverse()
                                    sql(c, *writes[0])
                                    barrier.wait(timeout=5)
                                    sql(c, *writes[1])
                                else:
                                    assert rows == (('RESERVED',),)
                                    sql(c, 'SELECT coupon_id FROM paid_coupon_inventory WHERE coupon_id IN (%s) ORDER BY coupon_id FOR UPDATE', (coupon,))
                                    assert sql(c, 'SELECT COUNT(*) FROM paid_coupon_reserved_unit WHERE request_id=%s', (old,))[0][0] == 1
                                    sql(c, 'DELETE FROM paid_coupon_reserved_unit WHERE request_id=%s', (old,))
                                    barrier.wait(timeout=5)
                                    sql(c, 'UPDATE paid_coupon_inventory SET remaining=remaining-1 WHERE coupon_id=%s AND remaining>=1', (coupon,))
                                    sql(c, "UPDATE paid_coupon_reservation_batch SET state='CLAIMED' WHERE request_id=%s AND state='RESERVED'", (old,))
                                return {'operation': 'reserve' if reserve else 'claim', 'result': 'completed_then_rolled_back'}
                            except Exception as exc:
                                barrier.abort()
                                return {'operation': 'reserve' if reserve else 'claim', 'error': str(exc),
                                        'mysql_code': exc.args[0] if isinstance(exc, pymysql.MySQLError) else None}
                            finally:
                                c.rollback()
                    with ThreadPoolExecutor(max_workers=2) as pool:
                        futures = [pool.submit(worker, reserve) for reserve in (True, False)]
                        results = [f.result(timeout=15) for f in futures]
                    report['cases'].append({'order': 'insert_delete' if reverse else 'delete_insert',
                                            'iteration': iteration, 'results': results})
            report['deadlocks'] = sum(r.get('mysql_code') == 1213 for c in report['cases'] for r in c['results'])
            report['other_errors'] = sum('error' in r and r.get('mysql_code') != 1213 for c in report['cases'] for r in c['results'])
            assert sql(setup, 'SELECT remaining FROM paid_coupon_inventory WHERE coupon_id=%s', (coupon,)) == ((2,),)
            assert sql(setup, 'SELECT COUNT(*) FROM paid_coupon_available_unit WHERE coupon_id=%s', (coupon,)) == ((1,),)
            assert sql(setup, 'SELECT COUNT(*) FROM paid_coupon_reserved_unit WHERE coupon_id=%s', (coupon,)) == ((1,),)
            report['rollback_invariants_ok'] = True
        finally:
            setup.begin()
            try:
                sql(setup, 'DELETE FROM paid_coupon_reserved_unit WHERE request_id IN (%s,%s)', (old, new))
                sql(setup, 'DELETE FROM paid_coupon_available_unit WHERE coupon_id=%s', (coupon,))
                sql(setup, 'DELETE FROM paid_coupon_reservation_batch WHERE request_id IN (%s,%s)', (old, new))
                sql(setup, 'DELETE FROM paid_coupon_inventory WHERE coupon_id=%s', (coupon,))
                setup.commit()
                report['fixture_cleanup_committed'] = True
            except BaseException:
                setup.rollback()
                raise
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k != 'cases'}))
    return 1 if report['other_errors'] else 0


def sql(c, statement, params=()):
    with c.cursor() as cursor:
        cursor.execute(statement, params)
        return cursor.fetchall()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--user', default='root')
    parser.add_argument('--rounds', type=int, default=10)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if not 1 <= args.rounds <= 20 or args.output.exists():
        parser.error('rounds must be 1..20 and output must not exist')
    password = os.environ.get('MYSQL_PASSWORD')
    if password is None:
        password = getpass.getpass('Local MySQL password: ')
    raise SystemExit(run(args, password))
