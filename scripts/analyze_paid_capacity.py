#!/usr/bin/env python3
"""Per-caller connection attribution for paid_coupon_capacity.py reports (metrics_after - metrics_before)."""
import json, sys
from pathlib import Path


def delta(report):
    before, after = report['metrics_before'], report['metrics_after']
    out = {}
    for key, value in after.items():
        if not isinstance(value, dict) or 'count' not in value:
            continue
        prev = before.get(key, {'count': 0, 'totalNanos': 0})
        count = int(value['count']) - int(prev['count'])
        if count > 0:
            out[key] = (count, (int(value['totalNanos']) - int(prev['totalNanos'])) / count / 1e6)
    return out


def cpu(report):
    app, db, client = [], [], []
    pids = report.get('pids')
    if not pids:
        return None, None, None
    for sample in report.get('samples', []):
        rows = [line.split() for line in sample.get('process_cpu_rss', '').splitlines() if line.strip()]
        by_pid = {int(r[0]): float(r[1]) for r in rows}
        if len(by_pid) == 3:
            app.append(by_pid[pids['app']]); db.append(by_pid[pids['mysql']]); client.append(by_pid[pids['client']])
    mean = lambda xs: sum(xs) / len(xs) if xs else 0
    return mean(app), mean(db), mean(client)


def main():
    for name in sys.argv[1:]:
        report = json.loads(Path(name).read_text())
        d = delta(report)
        cycles = report['completed_cycles']
        pending = max((p['pending'] for s in report.get('samples', []) for p in (s.get('pools') or [])), default=0)
        app_cpu, db_cpu, client_cpu = cpu(report)
        cpu_text = f"cpu app/mysql/client={app_cpu:.0f}%/{db_cpu:.0f}%/{client_cpu:.0f}%" if app_cpu is not None else ''
        lat = report['latency']
        print(f"== {Path(name).name}  c={report['concurrency']}  TPS={report['completed_cycles_per_s']:.0f}  "
              f"cycle p50/p99={lat['cycle']['p50_ms']:.1f}/{lat['cycle']['p99_ms']:.1f}ms  correct={report.get('correct')}  "
              f"maxPending={pending}  {cpu_text}")
        total_hold = 0.0
        rows = []
        for key, (count, mean_ms) in sorted(d.items()):
            if key.endswith('.hold'):
                total_hold += count * mean_ms
        for key, (count, mean_ms) in sorted(d.items(), key=lambda kv: -kv[1][0] * kv[1][1]):
            share = f"{count * mean_ms / total_hold * 100:5.1f}% of hold" if key.endswith('.hold') and total_hold else ''
            rows.append(f"   {key:<45} n/cycle={count / max(1, cycles):6.2f}  mean={mean_ms:8.3f}ms  {share}")
        print('\n'.join(rows[:28]))


if __name__ == '__main__':
    main()
