#!/usr/bin/env python3
"""Demo: init two paid coupons, reserve both, mock-claim, query, then cancel and reserve again.

Requires the dev server with sky.paid-coupon.demo-enabled=true.
"""

import base64
import hashlib
import hmac
import json
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"
USER_ID = 1
SECRET = b"itheima"
COUPON_A = 881001
COUPON_B = 881002


def b64(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=")


def token():
    header = b64(json.dumps({"alg": "HS256"}, separators=(",", ":")).encode())
    payload = b64(json.dumps(
        {"userId": USER_ID, "exp": int(time.time()) + 3600},
        separators=(",", ":"),
    ).encode())
    signing = header + b"." + payload
    signature = b64(hmac.new(SECRET, signing, hashlib.sha256).digest())
    return (signing + b"." + signature).decode()


def call(method, path, body=None, auth=None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(BASE + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if auth:
        request.add_header("authentication", auth)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        detail = error.read().decode()
        raise SystemExit("HTTP %s %s %s" % (error.code, path, detail))


def main():
    auth = token()
    for coupon_id in (COUPON_A, COUPON_B):
        created = call("POST", "/user/paid-coupon/demo/coupons",
                       {"couponId": coupon_id, "total": 5}, auth)
        print("init", coupon_id, created)

    reserved = call("POST", "/user/paid-coupon/reservations", {
        "requestId": "demo-claim",
        "items": [
            {"couponId": COUPON_A, "quantity": 1},
            {"couponId": COUPON_B, "quantity": 2},
        ],
    }, auth)
    print("reserve", reserved)
    claimed = call("POST", "/user/paid-coupon/demo/reservations/demo-claim/claim", None, auth)
    print("claim", claimed)
    queried = call("GET", "/user/paid-coupon/reservations/demo-claim", None, auth)
    print("query", queried)

    cancelled = call("POST", "/user/paid-coupon/reservations", {
        "requestId": "demo-cancel",
        "items": [{"couponId": COUPON_A, "quantity": 1}],
    }, auth)
    print("reserve-to-cancel", cancelled)
    released = call("POST", "/user/paid-coupon/reservations/demo-cancel/release", None, auth)
    print("release", released)
    again = call("POST", "/user/paid-coupon/reservations", {
        "requestId": "demo-after-release",
        "items": [{"couponId": COUPON_A, "quantity": 1}],
    }, auth)
    print("reserve-again", again)


if __name__ == "__main__":
    main()
