#!/usr/bin/env python3
"""Idle-server probe: how many connection borrows and how much hold time background tasks use after startup."""
import argparse, json, time, urllib.request
from paid_coupon_capacity import jwt

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--port', type=int, default=8081)
p.add_argument('--seconds', type=int, default=50)
p.add_argument('--secret', default='itheima')
args = p.parse_args()


def metrics(seq):
    req = urllib.request.Request(f'http://127.0.0.1:{args.port}/user/paid-coupon/benchmark/metrics',
                                 headers={'authentication': jwt(4_000_000_000 + seq, args.secret)})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.load(r)['data']


start = time.time(); seq = 0; last = None
while time.time() - start < args.seconds:
    seq += 1; last = metrics(seq); time.sleep(2)
hold = {k: v for k, v in last.items() if k.startswith('background.') and k.endswith('.hold')}
borrows = sum(int(v['count']) for v in hold.values())
hold_ms = sum(int(v['totalNanos']) for v in hold.values()) / 1e6
print(json.dumps({'window_s': args.seconds, 'background_borrows': borrows, 'background_hold_ms': round(hold_ms, 1),
                  'per_pool': {k: int(v['count']) for k, v in hold.items()}}))
