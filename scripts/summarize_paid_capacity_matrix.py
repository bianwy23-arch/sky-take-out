#!/usr/bin/env python3
"""Summarize run_paid_capacity_matrix.sh output: per version and concurrency, both rounds."""
import json, re, sys
from collections import defaultdict
from pathlib import Path

BUSINESS = ('reserve', 'claim', 'reserveBatch', 'claimBatch')


def delta(report, suffix):
    before, after = report['metrics_before'], report['metrics_after']
    count = total = 0
    for key, value in after.items():
        if not isinstance(value, dict) or 'count' not in value:
            continue
        caller, _, kind = key.partition('.')
        if caller in BUSINESS and key.endswith(suffix):
            prev = before.get(key, {'count': 0, 'totalNanos': 0})
            count += int(value['count']) - int(prev['count'])
            total += int(value['totalNanos']) - int(prev['totalNanos'])
    return count, total / 1e6


def cpu(report):
    pids = report.get('pids') or {}
    app, db = [], []
    for sample in report.get('samples', []):
        rows = {int(r.split()[0]): float(r.split()[1]) for r in sample.get('process_cpu_rss', '').splitlines() if r.strip()}
        if pids.get('app') in rows and pids.get('mysql') in rows:
            app.append(rows[pids['app']]); db.append(rows[pids['mysql']])
    mean = lambda xs: sum(xs) / len(xs) if xs else 0.0
    return mean(app), mean(db)


def main(out_dir):
    runs = defaultdict(list)
    for path in sorted(Path(out_dir).glob('*_r[12]_c*.json')):
        m = re.match(r'(.+)_r(\d)_c(\d+)\.json', path.name)
        runs[(m.group(1), int(m.group(3)))].append((int(m.group(2)), json.loads(path.read_text())))
    summary = {}
    print(f"{'ver':<4}{'c':>5} {'TPS r1/r2':>17} {'mean':>7} {'p99 ms r1/r2':>15} {'conn ms/txn':>11} "
          f"{'borrows/txn':>11} {'acquire ms':>10} {'cpu app/db':>11} ok")
    for (version, conc), items in sorted(runs.items(), key=lambda kv: (kv[0][0], kv[0][1])):
        items.sort()
        tps = [r['completed_cycles_per_s'] for _, r in items]
        p99 = [r['latency']['cycle']['p99_ms'] for _, r in items]
        cycles = sum(r['completed_cycles'] for _, r in items)
        elapsed = sum(r['elapsed_s'] for _, r in items)
        holds = [delta(r, '.hold') for _, r in items]
        acquires = [delta(r, '.acquire') for _, r in items]
        hold_ms = sum(h[1] for h in holds) / cycles
        borrows = sum(h[0] for h in holds) / cycles
        acquire_ms = sum(a[1] for a in acquires) / max(1, sum(a[0] for a in acquires))
        cpus = [cpu(r) for _, r in items]
        ok = all(r.get('correct') and not r.get('errors') and r['completed_cycles'] == r['started_cycles'] for _, r in items)
        row = {'rounds': len(items), 'tps_each': tps, 'tps_weighted': cycles / elapsed, 'cycle_p99_ms_each': p99,
               'business_conn_hold_ms_per_cycle': hold_ms, 'connection_borrows_per_cycle': borrows,
               'mean_acquire_wait_ms': acquire_ms, 'cpu_app_pct': sum(c[0] for c in cpus) / len(cpus),
               'cpu_mysql_pct': sum(c[1] for c in cpus) / len(cpus), 'completed_cycles': cycles, 'all_correct': ok}
        summary[f'{version}_c{conc}'] = row
        print(f"{version:<4}{conc:>5} {'/'.join(f'{t:.0f}' for t in tps):>17} {cycles / elapsed:>7.0f} "
              f"{'/'.join(f'{p:.1f}' for p in p99):>15} {hold_ms:>11.2f} {borrows:>11.2f} {acquire_ms:>10.2f} "
              f"{row['cpu_app_pct']:>5.0f}/{row['cpu_mysql_pct']:<5.0f} {ok}")
    Path(out_dir, 'summary.json').write_text(json.dumps(summary, indent=2, ensure_ascii=False) + '\n')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'scripts/report/paid_capacity_main/formal')
