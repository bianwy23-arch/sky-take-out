#!/usr/bin/env python3
"""Samples InnoDB redo/checkpoint and buffer-pool pressure while another load runs. Read-only."""
import sys, time
import pymysql

METRICS = ('log_lsn_checkpoint_age', 'log_max_modified_age_async', 'trx_rseg_history_len',
           'buffer_pool_dirty_pages_ratio' if False else 'buffer_pool_pages_dirty', 'buffer_pool_pages_total')
STATUS = ('Innodb_log_waits', 'Innodb_buffer_pool_wait_free', 'Innodb_os_log_written', 'Com_commit',
          'Innodb_rows_inserted', 'Innodb_rows_deleted', 'Threads_running', 'Innodb_buffer_pool_reads')

c = pymysql.connect(host='127.0.0.1', user='root', password='123456', autocommit=True)


def read():
    with c.cursor() as cur:
        cur.execute("SELECT NAME, COUNT FROM information_schema.INNODB_METRICS WHERE NAME IN %s", (METRICS,))
        m = dict(cur.fetchall())
        cur.execute("SHOW GLOBAL STATUS WHERE Variable_name IN %s", (STATUS,))
        s = {k: int(v) for k, v in cur.fetchall()}
        cur.execute("SELECT @@innodb_redo_log_capacity")
        m['redo_capacity'] = cur.fetchone()[0]
    return m, s


samples = int(sys.argv[1]) if len(sys.argv) > 1 else 8
prev_m, prev = read(); prev_t = time.time()
for _ in range(samples):
    time.sleep(1.5)
    m, s = read(); t = time.time(); dt = t - prev_t
    rate = lambda k: (s[k] - prev[k]) / dt
    print(f"ckpt_age={m.get('log_lsn_checkpoint_age', 0) / 1048576:6.1f}MB/{m['redo_capacity'] / 1048576:.0f}MB "
          f"async_limit={m.get('log_max_modified_age_async', 0) / 1048576:6.1f}MB "
          f"dirty={m.get('buffer_pool_pages_dirty', 0)}/{m.get('buffer_pool_pages_total', 0)} "
          f"hll={m.get('trx_rseg_history_len')} redo={rate('Innodb_os_log_written') / 1048576:5.1f}MB/s "
          f"log_waits/s={rate('Innodb_log_waits'):.0f} wait_free/s={rate('Innodb_buffer_pool_wait_free'):.0f} "
          f"disk_reads/s={rate('Innodb_buffer_pool_reads'):.0f} commits/s={rate('Com_commit'):.0f} running={s['Threads_running']}")
    prev, prev_t = s, t
