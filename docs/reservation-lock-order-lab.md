# 第 ③ 条：验证写入顺序是否造成死锁

分支：`codex/reservation-failure-lab`。使用现有 `sky_order_0`。保持 RC，关闭空池暂停，避免与第 ② 条间隙锁实验混淆。

## 当前结论与证据边界

2026-09-23，在 MySQL 9.6.0 上运行 `scripts/reservation_order_probe.py`：INSERT→DELETE 与 DELETE→INSERT 各 10 轮，每轮一个 Reserve 与一个已有批次的 Claim，在第一条写入后同步交错。两种顺序均未捕获 1213 死锁，也没有其他错误；每轮业务操作回滚、夹具状态校验通过，最后清理本轮随机券及批次。

报告：`scripts/report/reservation_order_probe_20260923.json`。

这是直接 SQL 的受控探针，不是 Spring/HTTP 验收，不包括多券、高并发或所有索引分布，不证明绝不会死锁。真实服务手工实验按下文执行。程序配置和语句顺序/第二条写入失败回滚由 `PaidCouponLockLabTest` 覆盖，本轮该类 5 项测试通过。

为何暂未复现：

- 两种写法都保留前置 `SELECT FOR UPDATE SKIP LOCKED`，所以 INSERT 前可用单位已经加锁。
- 当前 Claim 访问 batch、inventory、reserved，不访问 available。不存在从 Claim 指向 available 的已知等待边。
- 同一 requestId 的 Reserve/Claim 先争用 batch 行锁；不同请求使用不同的库存单位。
- 文章没有公开完整 DDL、事务代码和死锁日志，仅凭“两条写语句互换”不足以恢复原系统等待环。不能额外添加 Claim 锁 available 的逻辑，或把同一单位同时伪造为 available/reserved，冒充原业务故障。

参考原文：https://shopify.engineering/scaling-inventory-reservations

## 服务配置

Active profiles: `dev`。Program arguments 完整如下：

```text
--sky.paid-coupon.demo-enabled=true --sky.paid-coupon.lock-lab.enabled=true --sky.paid-coupon.lock-lab.coupon-id=88990002 --sky.paid-coupon.lock-lab.isolation=RC --sky.paid-coupon.lock-lab.pause-ms=0 --sky.paid-coupon.lock-lab.insert-before-delete=true --sky.paid-coupon.lock-lab.write-pause-ms=20000 --sky.paid-coupon.reserve-timeout-ms=60000
```

`insert-before-delete=true` 仅对配置指定的单券请求启用旧写法；默认 false，先删后插。`write-pause-ms` 默认 0，上限 30000，只在该券库存足够、第一条写入完成后暂停。日志为 `LOCK_ORDER_PAUSE` / `LOCK_ORDER_RESUME`，标明第一条操作和实际隔离级别。关闭总开关时两项均不生效。

## 手工准备（先准备齐，再并发发送）

1. 确认新测试 ID 88990002 在 inventory、available、reserved 都不存在。若已用过，选另一个新 ID并同步修改配置和请求。
2. 在 MySQL 执行以下事务，建立一个小而完整的池。本轮不是空池实验：

```sql
START TRANSACTION;
INSERT INTO sky_order_0.paid_coupon_inventory(coupon_id,total,remaining,next_unit_id)
VALUES (88990002,3,3,4);
INSERT INTO sky_order_0.paid_coupon_available_unit(coupon_id,unit_id)
VALUES (88990002,1),(88990002,2),(88990002,3);
COMMIT;
```

任何 INSERT 报错就 ROLLBACK，不继续 COMMIT，不对已有券清数据。

3. Apifox 使用有效用户 Token，请求头 authentication。先发一次普通预留并等待完成，建立稍后用于 Claim 的旧批次：

```http
POST /user/paid-coupon/reservations
Content-Type: application/json

{"requestId":"order-legacy-seed-001","items":[{"couponId":88990002,"quantity":1}]}
```

这次也暂停 20 秒，正常。应返回 RESERVED。接下来在 10 分钟到期之前完成并发实验。

4. 准备两个页签：

A 新预留（新 requestId）：

```http
POST /user/paid-coupon/reservations
Content-Type: application/json

{"requestId":"order-legacy-new-001","items":[{"couponId":88990002,"quantity":1}]}
```

B 确认旧批次，Body 为 none：

```http
POST /user/paid-coupon/demo/reservations/order-legacy-seed-001/claim
```

不要 Claim A 的新批次来代替 B；那主要是在测试 batch 锁串行化，不是两个不同业务事务之间的库存写入竞争。

## 连续执行和观察

发送 A，在 `LOCK_ORDER_PAUSE ... firstWrite=INSERT_RESERVED` 出现后，立即发送 B，不等 A 返回。数据库窗口执行：

```sql
SELECT r.PROCESSLIST_ID AS waiting_connection,
       b.PROCESSLIST_ID AS blocking_connection,
       l.OBJECT_NAME,l.INDEX_NAME,l.LOCK_MODE,l.LOCK_DATA
FROM performance_schema.data_lock_waits w
JOIN performance_schema.threads r ON r.THREAD_ID=w.REQUESTING_THREAD_ID
JOIN performance_schema.threads b ON b.THREAD_ID=w.BLOCKING_THREAD_ID
JOIN performance_schema.data_locks l
 ON l.ENGINE=w.ENGINE AND l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
WHERE l.OBJECT_SCHEMA='sky_order_0'
 AND l.OBJECT_NAME IN ('paid_coupon_available_unit','paid_coupon_reserved_unit',
                      'paid_coupon_inventory','paid_coupon_reservation_batch');
```

不要只盯可用池一张表。若出现异常或怀疑死锁，再执行：

```sql
SHOW ENGINE INNODB STATUS;
```

死锁检测通常会迅速回滚一个事务，瞬时等待查询可能看不到环。检查 `LATEST DETECTED DEADLOCK` 的时间及 SQL/表/连接是否属于本轮，防止把旧死锁误认作本轮证据。保留应用日志中的 1213/Deadlock；锁等待超时 1205 和请求耗时长不能替代死锁证据。预留外层会有限重试，HTTP 成功不代表期间一定没有死锁，需要结合日志。

若 Claim 很快成功、Reserve 20 秒后完成、无本轮死锁记录，应记录“该交错下未复现”，不人为制造额外冲突。

## 修正顺序对照

用全新测试券和 requestId 再做同一轮，配置只把 `insert-before-delete` 改成 false（以及对应新 coupon-id）。日志 firstWrite 应为 DELETE_AVAILABLE。隔离级别仍 RC、暂停仍 20 秒、池大小仍 3、单次数量仍 1。两组都没死锁时，没有依据宣称此次改序修复了本项目死锁。

实验结束：只对未确认的新预留调用正常 release 接口；已 CLAIMED 的 seed 保留为本轮购买结果，不尝试释放。移除实验参数或关闭 enabled 并恢复正常超时预算。所有手动测试数据均在指定券/批次范围，不清空表。
