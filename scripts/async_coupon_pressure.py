#!/usr/bin/env python3
import argparse
import asyncio
import csv
import json
import statistics
import time
from pathlib import Path

import aiohttp


def parse_args():
    parser = argparse.ArgumentParser(description="Async coupon pressure test with unique tokens.")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--coupon-id", type=int, required=True)
    parser.add_argument("--tokens", default="scripts/test_tokens.csv")
    parser.add_argument("--requests", type=int, default=100000)
    parser.add_argument("--concurrency", type=int, default=3000)
    parser.add_argument("--ramp", type=float, default=60.0)
    parser.add_argument("--connect-timeout", type=float, default=5.0)
    parser.add_argument("--read-timeout", type=float, default=10.0)
    parser.add_argument("--client", choices=("raw", "aiohttp"), default="aiohttp")
    parser.add_argument("--output", default="")
    return parser.parse_args()


def load_tokens(path, limit):
    tokens = []
    with open(path, newline="", encoding="utf-8") as file:
        reader = csv.DictReader(file)
        for row in reader:
            token = row.get("token")
            user_id = row.get("userId")
            if token and user_id:
                tokens.append((token, user_id))
            if len(tokens) >= limit:
                break
    if len(tokens) < limit:
        raise SystemExit(f"not enough tokens: need={limit}, found={len(tokens)}")
    return tokens


async def send_request(args, token, user_id):
    started = time.perf_counter()
    path = f"/user/coupon/grab/{args.coupon_id}"
    request = (
        f"POST {path} HTTP/1.1\r\n"
        f"Host: {args.host}:{args.port}\r\n"
        f"authentication: {token}\r\n"
        "Content-Type: application/json\r\n"
        "Content-Length: 0\r\n"
        "Connection: close\r\n"
        "\r\n"
    ).encode("utf-8")

    try:
        reader, writer = await asyncio.wait_for(
            asyncio.open_connection(args.host, args.port),
            timeout=args.connect_timeout,
        )
        writer.write(request)
        await writer.drain()
        raw = await asyncio.wait_for(reader.read(), timeout=args.read_timeout)
        writer.close()
        try:
            await writer.wait_closed()
        except Exception:
            pass
        elapsed_ms = (time.perf_counter() - started) * 1000
        header_blob, _, body_blob = raw.partition(b"\r\n\r\n")
        status_line = header_blob.splitlines()[0].decode("utf-8", errors="replace") if header_blob else ""
        status_code = 0
        parts = status_line.split()
        if len(parts) >= 2 and parts[1].isdigit():
            status_code = int(parts[1])
        body = body_blob.decode("utf-8", errors="replace")
        biz_code = None
        biz_msg = ""
        try:
            payload = json.loads(body) if body else {}
            biz_code = payload.get("code")
            biz_msg = str(payload.get("msg") or payload.get("message") or "")
        except Exception:
            biz_msg = body[:120]
        return {
            "ok": status_code == 200,
            "status": status_code,
            "elapsedMs": elapsed_ms,
            "bizCode": biz_code,
            "bizMsg": biz_msg,
            "error": "",
            "userId": user_id,
        }
    except Exception as exc:
        elapsed_ms = (time.perf_counter() - started) * 1000
        return {
            "ok": False,
            "status": 0,
            "elapsedMs": elapsed_ms,
            "bizCode": None,
            "bizMsg": "",
            "error": f"{type(exc).__name__}: {exc}",
            "userId": user_id,
        }


async def send_request_aiohttp(args, session, token, user_id):
    started = time.perf_counter()
    url = f"http://{args.host}:{args.port}/user/coupon/grab/{args.coupon_id}"
    try:
        async with session.post(url, headers={"authentication": token}) as response:
            body = await response.text()
            elapsed_ms = (time.perf_counter() - started) * 1000
            biz_code = None
            biz_msg = ""
            try:
                payload = json.loads(body) if body else {}
                biz_code = payload.get("code")
                biz_msg = str(payload.get("msg") or payload.get("message") or "")
            except Exception:
                biz_msg = body[:120]
            return {
                "ok": response.status == 200,
                "status": response.status,
                "elapsedMs": elapsed_ms,
                "bizCode": biz_code,
                "bizMsg": biz_msg,
                "error": "",
                "userId": user_id,
            }
    except Exception as exc:
        elapsed_ms = (time.perf_counter() - started) * 1000
        return {
            "ok": False,
            "status": 0,
            "elapsedMs": elapsed_ms,
            "bizCode": None,
            "bizMsg": "",
            "error": f"{type(exc).__name__}: {exc}",
            "userId": user_id,
        }


async def run(args):
    tokens = load_tokens(args.tokens, args.requests)
    queue = asyncio.Queue()
    results = []
    start_time = time.perf_counter()

    async def producer():
        if args.requests <= 1 or args.ramp <= 0:
            delay = 0
        else:
            delay = args.ramp / args.requests
        for token in tokens:
            await queue.put(token)
            if delay > 0:
                await asyncio.sleep(delay)
        for _ in range(args.concurrency):
            await queue.put(None)

    async def worker(session=None):
        while True:
            item = await queue.get()
            if item is None:
                queue.task_done()
                break
            token, user_id = item
            if session is None:
                results.append(await send_request(args, token, user_id))
            else:
                results.append(await send_request_aiohttp(args, session, token, user_id))
            queue.task_done()

    if args.client == "aiohttp":
        timeout = aiohttp.ClientTimeout(
            total=args.connect_timeout + args.read_timeout,
            connect=args.connect_timeout,
            sock_read=args.read_timeout,
        )
        connector = aiohttp.TCPConnector(
            limit=args.concurrency,
            limit_per_host=args.concurrency,
            force_close=False,
            enable_cleanup_closed=True,
        )
        async with aiohttp.ClientSession(timeout=timeout, connector=connector) as session:
            workers = [asyncio.create_task(worker(session)) for _ in range(args.concurrency)]
            producer_task = asyncio.create_task(producer())
            await producer_task
            await queue.join()
            await asyncio.gather(*workers)
    else:
        workers = [asyncio.create_task(worker()) for _ in range(args.concurrency)]
        producer_task = asyncio.create_task(producer())
        await producer_task
        await queue.join()
        await asyncio.gather(*workers)

    duration = time.perf_counter() - start_time
    elapsed_values = sorted(result["elapsedMs"] for result in results)
    total = len(results)
    ok = sum(1 for result in results if result["ok"])
    errors = total - ok
    p95 = elapsed_values[max(0, int(total * 0.95) - 1)] if total else 0
    p99 = elapsed_values[max(0, int(total * 0.99) - 1)] if total else 0
    avg = statistics.fmean(elapsed_values) if elapsed_values else 0

    status_counts = {}
    biz_counts = {}
    error_counts = {}
    for result in results:
        status_counts[str(result["status"])] = status_counts.get(str(result["status"]), 0) + 1
        if result["bizCode"] is not None:
            key = f"{result['bizCode']}:{result['bizMsg'][:40]}"
            biz_counts[key] = biz_counts.get(key, 0) + 1
        if result["error"]:
            error_counts[result["error"][:120]] = error_counts.get(result["error"][:120], 0) + 1

    summary = {
        "url": f"http://{args.host}:{args.port}/user/coupon/grab/{args.coupon_id}",
        "requests": args.requests,
        "concurrency": args.concurrency,
        "rampSec": args.ramp,
        "client": args.client,
        "total": total,
        "httpSuccess": ok,
        "errors": errors,
        "errorRate": round(errors * 100 / total, 2) if total else 0,
        "throughputReqPerSec": round(total / duration, 2) if duration > 0 else 0,
        "avgMs": round(avg, 2),
        "p95Ms": round(p95, 2),
        "p99Ms": round(p99, 2),
        "durationSec": round(duration, 2),
        "statusCounts": status_counts,
        "bizCounts": biz_counts,
        "topErrors": dict(sorted(error_counts.items(), key=lambda item: item[1], reverse=True)[:10]),
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if args.output:
        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")


def main():
    args = parse_args()
    asyncio.run(run(args))


if __name__ == "__main__":
    main()
