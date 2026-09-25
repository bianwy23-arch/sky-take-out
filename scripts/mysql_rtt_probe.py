#!/usr/bin/env python3
"""SELECT 1 round-trip latency from an independent connection, to split server-side vs client-side delay."""
import sys, time
import pymysql

c = pymysql.connect(host='127.0.0.1', user='root', password='123456', autocommit=True)
cur = c.cursor()
rounds = int(sys.argv[1]) if len(sys.argv) > 1 else 2000
samples = []
for _ in range(rounds):
    t = time.perf_counter(); cur.execute('SELECT 1'); cur.fetchall(); samples.append((time.perf_counter() - t) * 1000)
samples.sort()
pick = lambda q: samples[min(len(samples) - 1, int(len(samples) * q))]
print(f"SELECT 1 rtt ms: p50={pick(0.5):.3f} p90={pick(0.9):.3f} p99={pick(0.99):.3f} max={samples[-1]:.3f}")
