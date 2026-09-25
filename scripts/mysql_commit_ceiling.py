#!/usr/bin/env python3
"""Durable-commit ceiling of the local MySQL: mysqlslap single-row autocommit INSERTs in a scratch schema."""
import subprocess, time
import pymysql

STATUS = ('Com_insert', 'Innodb_os_log_fsyncs', 'Innodb_data_fsyncs')


def status(c):
    with c.cursor() as cur:
        cur.execute("SHOW GLOBAL STATUS WHERE Variable_name IN %s", (STATUS,))
        return {k: int(v) for k, v in cur.fetchall()}


c = pymysql.connect(host='127.0.0.1', user='root', password='123456', autocommit=True)
with c.cursor() as cur:
    cur.execute("SELECT @@innodb_flush_log_at_trx_commit, @@sync_binlog, @@log_bin")
    print('durability flush_log/sync_binlog/log_bin =', cur.fetchone())
    cur.execute("CREATE DATABASE IF NOT EXISTS perf_probe_tmp")
    cur.execute("CREATE TABLE IF NOT EXISTS perf_probe_tmp.t (id BIGINT AUTO_INCREMENT PRIMARY KEY, v INT) ENGINE=InnoDB")
try:
    for conc in (16, 32, 64, 128):
        before = status(c); start = time.time()
        r = subprocess.run(['mysqlslap', '-uroot', '-p123456', '-h127.0.0.1', f'--concurrency={conc}',
                            f'--number-of-queries={conc * 400}', '--no-drop', '--create-schema=perf_probe_tmp',
                            '--query=INSERT INTO perf_probe_tmp.t(v) VALUES (1)'], capture_output=True, text=True)
        wall = time.time() - start; after = status(c)
        d = {k: after[k] - before[k] for k in STATUS}
        if r.returncode != 0:
            print('mysqlslap failed', r.stderr[-300:]); break
        print(f"c={conc:<4} commits/s={d['Com_insert'] / wall:7.0f}  inserts={d['Com_insert']}  "
              f"redo_fsync/s={d['Innodb_os_log_fsyncs'] / wall:6.0f}  commits/redo_fsync={d['Com_insert'] / max(1, d['Innodb_os_log_fsyncs']):.1f}")
finally:
    with c.cursor() as cur:
        cur.execute("DROP DATABASE IF EXISTS perf_probe_tmp")
    print('scratch schema dropped')
