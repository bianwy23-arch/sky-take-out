# 付费券预留：连接占用排查与单机容量验证

测量日期：2026-09-23。工作目录 `/Users/bianwenyuan/Desktop/sky-take-out_v2`，分支 `codex/reservation-failure-lab`。本次已实现、运行并验证；没有使用简历中已有的 5,200 QPS 推导结果。

## 结果与口径

每个业务循环通过真实 HTTP 完成：预留两种热点券各 1 张 → Claim 确认。所有客户端竞争同两种券；一次完整循环记作 1 TPS，包含 2 次 HTTP 请求。Claim 是本项目的演示确认接口，不包含外部支付网关。认证、Redis 限流、ShardingSphere 路由、MySQL 提交均实际执行。

每种版本、每个正式并发档位运行两轮，每轮 30 秒。TPS 按成功循环总数/实际总耗时计算，耗时包括停止发新请求后的在途请求排空。P99 保留各轮数值；“轮均 P99”只是两轮 P99 的算术均值，不是合并请求后的 P99。

| 指标 | 优化前 | 优化后 | 变化 |
|---|---:|---:|---:|
| 64 并发完整交易 TPS | 972.43 | 1,531.72 | +57.5% |
| 64 并发全流程 P99，两轮 | 152.75 / 155.07ms | 107.26 / 113.60ms | 两轮均改善 |
| 64 并发轮均 P99 | 153.91ms | 110.43ms | -28.2% |
| 64 并发 Claim 平均物理连接占用 | 47.15ms | 28.21ms | -40.2% |
| 64 并发 Claim 平均取连接等待 | 5.97ms | 3.47ms | -41.8% |
| 16 并发完整交易 TPS | 1,017.75 | 1,627.99 | +60.0% |
| 16 并发全流程 P99，两轮 | 41.62 / 32.76ms | 27.68 / 28.49ms | 两轮均改善 |
| 16 并发 Claim 平均物理连接占用 | 12.78ms | 6.22ms | -51.3% |

以上八轮正式样本共完成 309,315 次业务循环，全部成功，HTTP 状态和业务状态均检查通过。每轮都核对每张券 `初始库存 - remaining = CLAIMED 批次数`，并检查 `0 <= available + reserved <= remaining`、可用池不超过 1,000；没有观察到库存扣减不一致。低库存、幂等重放、Claim/Release 竞争等由集成测试另行覆盖，不能用充足库存压测代替这些测试。

## 从现象定位原因

1. 初始阶梯压测中，8 并发约 983 TPS，16 并发约 951 TPS，64 并发约 895 TPS。并发增加没有带来吞吐增长，全流程 P99 却从约 21ms 升至 240ms。
2. 在 HTTP 层按 Reserve/Claim 标记调用，在 Hikari 层分别记录取得连接的等待时间、借出至归还的占用时间，再记录各 Mapper 的耗时。它们回答“哪个业务长期占用连接”，而不只看连接池是否满。
3. 正式基线 64 并发，Claim 平均占用连接 47.15ms，其中 `lockByCouponIds` Mapper 平均 45.88ms。Mapper 耗时包含数据库等待与框架处理，不是纯 SQL CPU 时间；结合事务代码可定位到库存锁竞争。
4. 单独的 5 秒诊断负载还取得 19 个非空数据库锁等待快照：等待对象为 `paid_coupon_inventory` 的 `PRIMARY`，模式为 `X,REC_NOT_GAP`。这证明存在库存账本热点行的记录锁等待，与之前 RR 空池的间隙锁实验不同。
5. 因果链为：同券 Claim 争抢库存行 → 等待事务持续占用连接 → 高并发时物理池达到上限，后续请求排队。16 并发几乎没有取连接等待，却已经出现热点锁等待，因此单纯增大连接池不是针对根因的修复。

诊断快照中的 `waiting` 是 `data_lock_waits` 关系行数；一个等待事务可能对应多个阻塞关系，不能把 406 条关系解释成 406 个连接。两次手动优化版锁快照为空，只说明采样时没有捕获到，不能证明优化后不存在锁竞争。

可用于复查的只读 SQL：

```sql
SELECT r.OBJECT_NAME, r.INDEX_NAME, r.LOCK_MODE, r.LOCK_DATA,
       COUNT(*) AS wait_edges,
       COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID) AS waiting_transactions
FROM performance_schema.data_lock_waits w
JOIN performance_schema.data_locks r
  ON r.ENGINE = w.ENGINE
 AND r.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
WHERE r.OBJECT_SCHEMA = 'sky_order_0'
  AND r.OBJECT_NAME LIKE 'paid_coupon%'
GROUP BY r.OBJECT_NAME, r.INDEX_NAME, r.LOCK_MODE, r.LOCK_DATA;
```

## 实际优化

**Reserve 删除一次冗余锁定读取。** 插入预留记录后，直接用本事务已写入的字段构造返回值，不再按同一 requestId 执行第二次 `SELECT FOR UPDATE`。初始幂等锁定读取仍保留。

**Claim 缩短热点锁的持有区间。** 先持有本请求的批次锁，校验预留明细并在事务内更新本请求状态，再处理共享库存账本。将逐券 UPDATE 合并为带数量条件的 `UPDATE ... CASE ... ORDER BY coupon_id`，由 UPDATE 自身获取库存锁，去掉重复的 `SELECT FOR UPDATE`。最后删除已确认的预留单位并提交。

正常双券、无补池重试路径的 Mapper 调用数从 14 次降至 11 次：Reserve 7→6、Claim 7→5。这不是每次业务循环的无条件 SQL 次数；空池补充、重试、后台任务会增加实际调用。

`CLAIMED` 的提前更新仍处于同一数据库事务中，并未提前提交，也不产生外部成功通知。受影响库存行数必须等于券种数；任一券不足时，库存修改、请求状态和明细变化全部回滚。保留库存按 couponId 排序及库存→预留明细的写锁顺序，未为追求吞吐再次反转锁顺序。

物理连接池上限没有调整，仍为每个数据源 50。64 并发优化后依然会出现连接排队，采样到的最大 pending 并未下降；改进的是单位连接占用时间和相同池容量的业务吞吐，不能声称“连接数降低 40%”。

## 容量拐点，而非机器绝对极限

| 并发 | 优化后 TPS | 全流程 P99 | 样本 |
|---:|---:|---:|---|
| 8 | 1,587 | 13.01ms | 12 秒探索 |
| 16 | 1,628 | 27.68 / 28.49ms | 两轮各 30 秒 |
| 32 | 1,713 | 60.11ms | 12 秒探索 |
| 64 | 1,532 | 107.26 / 113.60ms | 两轮各 30 秒 |
| 128 | 1,496 | 262.29ms | 12 秒探索 |

当前实现、双热点券负载下，实测吞吐平台约为 **1,600～1,700 TPS**。16 并发的正式结果约 1,628 TPS，达到探索峰值的约 95%，尾延迟明显优于 32/64/128 并发；可作为本轮更合理的运行点。没有把这个数值写入全局限流配置，它只适用于当前场景。

这证明了该实现下增加并发已接近收益上限，不证明数据库硬件的绝对极限。Claim 仍必须串行更新同一热点库存行。改变库存账本模型、拆分热点、减少持久化保证或移除一致性校验都将改变比较条件，本次没有这样做。未做多小时稳定性测试、多机压测或开放到达率负载，不能当作生产 SLA。

## 环境与公平性

- 本机 Mac16,8，12 逻辑 CPU、24GiB；Java 17.0.15，MySQL 9.6.0。服务、数据库、压测客户端同机，非独占压测服务器。
- JVM 固定 `-Xms512m -Xmx1536m`，同一台机器、同一数据库、同样池上限和观测代码。用户原有 8080 服务保留，本次另启 8081，结束后已关闭。
- 使用现有 `sky_order_0`，没有新建独立数据库。每轮随机生成专属大数 couponId、独立 `cap-` requestId 前缀；每种券总库存 1,000 万、初始可用池 1,000，运行中走真实补池路径。只清理本轮自有数据，最终所有对应表残留计数均为 0。
- `innodb_flush_log_at_trx_commit=1`、`sync_binlog=1`、`log_bin=ON`；未牺牲提交持久化保证。缓冲池 128MiB，`innodb_thread_concurrency=0`，`max_connections=151`；本次未调这些参数。
- 关闭锁实验暂停；预留 TTL 为 3,600 秒，过期扫描首次延迟 3,600 秒，避免这个即时确认容量场景混入过期释放。其他既有后台任务保留。Elasticsearch 不可用的既有后台报错没有计入本轮业务失败，也没有借机修改。
- 每次业务循环使用独立模拟用户、真实 JWT，不让同一测试用户的限流误判成系统容量。未关闭业务限流器。JWT 不保存在结果文件中。
- 连接占用由 Hikari 毫秒级回调测量，Mapper 为客户端侧方法耗时；CPU 为 `ps` 采样，仅作辅助，未宣称精确 CPU 利用率模型。未新增 ProxySQL。
- 保留前后 Jar 的 SHA256、基线到优化版的事务代码补丁。工作区原本有其他未提交改动，所以 Git HEAD 不是两个 Jar 的完整源码标识。

## 验证与排除样本

15 项真实数据库集成测试、5 项锁实验单元测试，共 20 项通过，0 failure/error。新增回归特意让批量扣减中的第二张券库存不足，验证第一张券已执行的扣减和提前更新的 CLAIMED 状态均回滚。其他测试包含最后一张券竞争、同 requestId 并发重放、Claim/Release 互斥、补池守恒、多券整批回滚。

`baseline_final64_a.json` 是一次监控身份触发限流的无效样本，且运行期间存在其他构建活动，已排除。修复监控身份后重跑；正式八轮期间没有并行构建或测试。预热、12 秒探索和带额外数据库轮询的短诊断均不纳入正式聚合。

既有请求超时的严格数据库等待预算、等锁后再次检查到期时间，未在此次性能优化中修复；压测使用长 TTL，不能据此宣称这些边界已关闭。

## 复跑与证据

启动编译后的优化版独立服务（保留开发环境凭据在本地配置中）：

```bash
java -Xms512m -Xmx1536m -jar sky-server/target/sky-server-1.0-SNAPSHOT.jar \
  --spring.profiles.active=dev --server.port=8081 \
  --sky.paid-coupon.benchmark-enabled=true \
  --sky.paid-coupon.demo-enabled=true \
  --sky.paid-coupon.lock-lab.enabled=false \
  --sky.paid-coupon.reservation-ttl-seconds=3600 \
  --sky.paid-coupon.expire-scan-initial-delay-ms=3600000 \
  --logging.level.root=WARN --spring.shardingsphere.props.sql-show=false
```

压测脚本需要 `aiohttp`、`pymysql`。本次使用临时 venv，没有修改项目运行依赖；重复运行必须换输出文件名，脚本拒绝覆盖结果。

```bash
/private/tmp/reservation-lab-venv/bin/python scripts/paid_coupon_capacity.py \
  --concurrency 16 --seconds 30 \
  --output scripts/report/paid_capacity/new-run.json
python3 scripts/summarize_paid_coupon_capacity.py
```

本次原始报告在 `scripts/report/paid_capacity/`，正式样本名单由 `summarize_paid_coupon_capacity.py` 显式列出；汇总为 `comparison.json`，数据库锁证据为 `baseline_lock_snapshots.json`，版本记录为 `provenance.json`，清理结果为 `cleanup-verification.json`。临时 Jar 位于 `/private/tmp/paid-perf-baseline.jar` 和 `/private/tmp/paid-perf-optimized.jar`，它们不是永久归档文件；持久保留的 `transaction-optimization.patch` 记录本轮事务改动，不混入前面的间隙锁实验改动。

性能观测组件仅在 `benchmark-enabled=true` 时启用。Hikari 绑定适配当前 ShardingSphere 5.2.1 内部结构，升级框架时需复核。新增测试文件位于现有被 Git 忽略的 `src/test` 目录，后续提交实验成果时需要显式包含测试。本次未提交或推送，也未修改原简历 PDF。

## 建议简历表述

**多券预留与性能优化：** 基于 MySQL `SKIP LOCKED` 实现多券整批预留与幂等确认；通过连接占用监控和锁等待分析定位热点库存行竞争，合并扣减 SQL、缩短持锁区间，在单机 64 并发双热点券压测下，将预留—确认吞吐由约 972 提升至 1,532 TPS（+58%），确认事务平均连接占用由 47ms 降至 28ms，压测库存扣减与确认数量一致。

建议作为现有高并发项目中的一条新增经历。简历原有“10 万用户抢 1 万张券、5,200 QPS”描述的是 Redis Lua + MQ 异步抢券，本轮没有复核该历史数据，也不能与同步双券 Reserve+Claim 的 TPS 直接比较。原项目日期截至 2026.03，而这轮工作发生在 2026.09，加入前应同步真实维护时间。

参考：[Shopify 原文](https://shopify.engineering/scaling-inventory-reservations)中的按业务追踪连接占用、缩短数据库访问思路；[MySQL UPDATE 文档](https://dev.mysql.com/doc/refman/8.0/en/update.html)中的单表 UPDATE 排序语义。具体瓶颈、改法和数字来自本项目实测，不声称复现 Shopify 全部线上条件。
