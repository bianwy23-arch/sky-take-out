#!/usr/bin/env python3
"""Same client, same endpoints and interceptors (JWT, Redis rate limit, UV/PV) as paid_coupon_capacity.py,
but every request is rejected by parameter validation before any database access: the no-DB ceiling."""
import argparse, asyncio, json, time
import aiohttp
from paid_coupon_capacity import jwt


async def run(args):
    base = f'http://127.0.0.1:{args.port}'
    done = 0; started = 0; bad = 0
    stop = time.perf_counter() + args.seconds
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=40),
                                     connector=aiohttp.TCPConnector(limit=args.concurrency + 4)) as session:
        async def worker():
            nonlocal done, started, bad
            while time.perf_counter() < stop:
                seq = started; started += 1
                headers = {'authentication': jwt(5_000_000_000 + seq, args.secret)}
                for path, body in (('/user/paid-coupon/reservations', {'requestId': '', 'items': [{'couponId': 1}]}),
                                   ('/user/paid-coupon/demo/reservations/' + 'x' * 65 + '/claim', None)):
                    async with session.post(base + path, headers=headers, json=body) as r:
                        payload = json.loads(await r.text())
                        if r.status != 200 or payload.get('msg') != '请求参数不完整':
                            bad += 1
                done += 1
        begin = time.perf_counter()
        await asyncio.gather(*(worker() for _ in range(args.concurrency)))
        elapsed = time.perf_counter() - begin
    print(json.dumps({'concurrency': args.concurrency, 'cycles_per_s': done / elapsed,
                      'http_requests_per_s': 2 * done / elapsed, 'unexpected_responses': bad}))


p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--port', type=int, default=8081); p.add_argument('--concurrency', type=int, default=128)
p.add_argument('--seconds', type=int, default=15); p.add_argument('--secret', default='itheima')
asyncio.run(run(p.parse_args()))
