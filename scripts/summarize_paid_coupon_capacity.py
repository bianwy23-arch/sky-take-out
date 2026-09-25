#!/usr/bin/env python3
"""Summarize explicitly selected comparable capacity samples, excluding warmups."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent / 'report' / 'paid_capacity'
GROUPS = {
    'baseline64': ['baseline_final64_b', 'baseline_final64_c'],
    'optimized64': ['optimized_final64_a', 'optimized_final64_b'],
    'baseline16': ['baseline_final16_a', 'baseline_final16_b'],
    'optimized16': ['optimized_final16_a', 'optimized_final16_b'],
}


def metric_delta(runs, key):
    count = nanos = 0
    for run in runs:
        before = run['metrics_before'].get(key, {})
        after = run['metrics_after'].get(key, {})
        count += int(after.get('count', 0)) - int(before.get('count', 0))
        nanos += int(after.get('totalNanos', 0)) - int(before.get('totalNanos', 0))
    return {'count': count, 'mean_ms': nanos / count / 1e6 if count else None}


def main():
    results = {}
    for group, names in GROUPS.items():
        runs = [json.loads((ROOT / (name + '.json')).read_text()) for name in names]
        assert all(r['correct'] and r['fixture_cleaned'] and not r['errors']
                   and r['started_cycles'] == r['completed_cycles'] for r in runs)
        results[group] = {
            'files': names,
            'completed_cycles': sum(r['completed_cycles'] for r in runs),
            'tps_weighted': sum(r['completed_cycles'] for r in runs) / sum(r['elapsed_s'] for r in runs),
            # Individual samples preserve exact percentiles. Their arithmetic
            # mean below is not a pooled percentile over all requests.
            'cycle_p99_ms_each': [r['latency']['cycle']['p99_ms'] for r in runs],
            'cycle_p99_ms_run_mean': sum(r['latency']['cycle']['p99_ms'] for r in runs) / len(runs),
            'claim_hold': metric_delta(runs, 'claim.ds_0.hold'),
            'claim_acquire': metric_delta(runs, 'claim.ds_0.acquire'),
            'reserve_hold': metric_delta(runs, 'reserve.ds_0.hold'),
            'reserve_acquire': metric_delta(runs, 'reserve.ds_0.acquire'),
            'hot_mapper': metric_delta(runs, 'claim.mapper.' + ('decrementBatch' if group.startswith('optimized') else 'lockByCouponIds')),
            'max_sampled_pending': max(int(pool['pending']) for r in runs for s in r['samples'] for pool in s['pools']),
        }
    (ROOT / 'comparison.json').write_text(json.dumps(results, indent=2) + '\n')
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
