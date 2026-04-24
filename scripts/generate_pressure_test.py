#!/usr/bin/env python3
"""
Generate test users in DB + JWT tokens CSV for JMeter pressure test.
Usage: python3 scripts/generate_pressure_test.py [user_count]
"""
import subprocess
import sys
import jwt  # PyJWT

SECRET = "itheima"
USER_COUNT = int(sys.argv[1]) if len(sys.argv) > 1 else 1000
START_USER_ID = 100

print(f"Inserting {USER_COUNT} test users (id {START_USER_ID} ~ {START_USER_ID + USER_COUNT - 1})...")

# Batch insert in chunks of 500 to avoid SQL too long
chunk_size = 500
for start in range(0, USER_COUNT, chunk_size):
    end = min(start + chunk_size, USER_COUNT)
    sql_values = []
    for i in range(start, end):
        uid = START_USER_ID + i
        sql_values.append(f"({uid}, 'test_openid_{uid}', 'test_user_{uid}')")
    sql = f"INSERT IGNORE INTO user (id, openid, name) VALUES {','.join(sql_values)};"
    result = subprocess.run(
        ["mysql", "-uroot", "-p123456", "sky_order_0", "-e", sql],
        capture_output=True, text=True
    )
    if result.returncode != 0:
        print(f"DB insert error: {result.stderr}")

print("DB insert done.")

csv_path = "scripts/test_tokens.csv"
exp = 9999999999999
with open(csv_path, "w") as f:
    f.write("token,userId\n")
    for i in range(USER_COUNT):
        uid = START_USER_ID + i
        token = jwt.encode({"userId": uid, "exp": exp}, SECRET, algorithm="HS256")
        f.write(f"{token},{uid}\n")

print(f"Generated {USER_COUNT} tokens -> {csv_path}")
