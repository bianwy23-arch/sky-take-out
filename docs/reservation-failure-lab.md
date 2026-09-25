# 库存预留故障实验

本地分支：`codex/reservation-failure-lab`。当前已实现第 ② 条的 SQL 机制实验和服务实验开关、暂停点、专用补池入口；第 ③ 条顺序开关及受控探针已实现，见 [锁顺序实验](reservation-lock-order-lab.md)；连接占用实验尚未实现。原有未提交修改保留在当前工作目录，本分支没有推送远程。

## 第 ② 条：空池查询与补池

入口：`scripts/reservation_gap_lock_lab.py`。使用现有本地 `sky_order_0.paid_coupon_available_unit`，不新建数据库或表。SQL 与单券预留 Mapper 的锁定查询一致。每次生成专用随机券 ID，启动前检查冲突；只插入一条未提交单位行，最终回滚，不写 batch、账本或预留，不执行 DELETE/TRUNCATE，不改全局隔离级别。

这属于直接 SQL 的受控并发实验，不是完整 Spring 服务回退，也不是业务压测。这里的“补池”只复现补池 INSERT 的锁竞争，不执行完整补池算法或守恒状态转换。

运行前停止其他压测。现有表的范围锁可能影响相邻范围，专用 ID 并不提供锁隔离。脚本只允许 localhost 地址，使用三个连接，锁等待上限为 5 秒。观察权限不足会在写入前失败。

```sh
python3 -m venv /private/tmp/reservation-lab-venv
/private/tmp/reservation-lab-venv/bin/pip install pymysql==1.2.3
/private/tmp/reservation-lab-venv/bin/python scripts/reservation_gap_lock_lab.py \
  --database sky_order_0 --output scripts/report/reservation_gap_lock_manual.json
```

密码交互输入，也可以由环境变量 `MYSQL_PASSWORD` 提供；不会写入报告。输出文件必须不存在，避免覆盖先前证据。

### 三组时序与判断

1. **RR 持锁**：A 开启 RR 事务，对不存在的券 ID 执行 `FOR UPDATE SKIP LOCKED` 并保持事务；B 插入同券单位。要求捕获 B 等待 A 的真实 `data_lock_waits` 记录。随后回滚 A，B 应能完成插入，最后回滚 B。
2. **RC 持锁**：相同时序，仅把 A 改为 RC。要求 B 在 A 回滚之前完成 INSERT，且没有观察到 B 等待 A。
3. **RR 先回滚**：A 查询后先回滚，再启动 B 插入。要求 INSERT 正常完成。这是当前服务“预留失败先回滚，再补池”的 SQL 层对照，不是对 Spring 事务代理的运行验证。

报告保存实际隔离级别、数据库版本、连接 ID、锁模式、索引、锁数据、等待关系及插入完成事件。不能用 `data_locks` 缺少显式记录锁判断插入失败：INSERT 的隐式锁未必展示为显式记录锁。正常分支用线程事件证明 SQL 已返回；RR 阻塞分支必须有真实等待关系，不能仅凭耗时推断。

### 2026-09-22 本地验证

权威结果：`scripts/report/reservation_gap_lock_validated.json`，MySQL **9.6.0**，现有 `sky_order_0`。

| 情况 | 实际结果 |
|---|---|
| RR 保持事务 | A 持有 PRIMARY 的 `supremum pseudo-record` X 锁；B 的 `X,INSERT_INTENTION` 等待 A；A 回滚后插入完成 |
| RC 保持事务 | INSERT 在 A 回滚之前完成，未捕获两者间锁等待 |
| RR 先回滚 | INSERT 正常完成，未捕获两者间锁等待 |
| 清理 | 本轮随机券 ID 的可用单位行数为 0 |

三项断言通过。它证明了本机上的锁机制和事务结束对阻塞的影响，不证明当前接口存在该故障，不构成 MySQL 8 版本验证，也没有复现死锁。插入耗时受刻意控制的会话时序影响，不用于吞吐或性能收益声明。

初次运行记录 `reservation_gap_lock_first.json` 是沙箱连接失败；`reservation_gap_lock_verified.json` 是修正“显式记录锁”观测断言前的中间报告，两者不作为最终验收结果。

练习时先观察症状和锁快照，自己回答“谁阻塞谁、空结果为什么仍持锁、SKIP LOCKED 为什么不能避免这个 INSERT 等待”，再对照另外两组。面试可表述为在实验分支验证隔离级别和事务边界，不表述为线上事故。

## 后续第 ③ 条的边界

先保留当前 batch 协调和 SELECT FOR UPDATE，只交换可用单位 DELETE 与预留 INSERT，验证是否真的形成等待环。SELECT 已经获得可用单位锁，因此交换语句不等于交换首次加锁顺序。若未复现，应记录未复现，不能加入业务不存在的锁依赖冒充原实现故障。

## 直接启动服务实验（2026-09-23 新增）

服务预留入口现已支持实验配置。默认 `enabled=false`，所有预留仍使用 RC。开启后，只有请求清单恰好包含配置指定的一种券时，才使用配置隔离级别；其他券和多券请求仍使用 RC。Claim、Release、补池仍使用 RC。暂停只在指定券的可用池锁定查询返回空列表后触发，暂停发生在事务内；暂停结束后正常抛出库存不足异常、回滚，然后沿用原补池重试流程。

新增补池接口仅在 `lock-lab.enabled=true` 时注册，沿用用户 authentication 鉴权，只允许指定测试券。实验请求占用线程和连接；不要同时运行其他压测。RR 范围锁可能影响相邻键，专用券 ID 并不等于锁隔离。补池成功会真实写入测试券单位行，预留重试成功后会真实保存 RESERVED 批次，和之前全回滚的 SQL 脚本不同。

### 1. 初始化“有账本、空池”的专用券

使用现有 MySQL 客户端连接 `sky_order_0`，执行：

```sql
USE sky_order_0;
SELECT * FROM paid_coupon_inventory WHERE coupon_id = 88990001;
SELECT * FROM paid_coupon_available_unit WHERE coupon_id = 88990001;
SELECT * FROM paid_coupon_reserved_unit WHERE coupon_id = 88990001;
-- 三处都没有记录时才执行。若 ID 已存在，换一个新 ID，并同步替换下文配置与请求。
INSERT INTO paid_coupon_inventory(coupon_id,total,remaining,next_unit_id)
VALUES (88990001,10,10,1);
```

不要使用普通 demo 初始化接口，它会自动补池，无法留下本实验需要的空池。

### 2. 启动实验分支服务

IDE 运行 SkyApplication 时，Active profiles 选择 `dev`，Program arguments 填入：

```text
--sky.paid-coupon.lock-lab.enabled=true
--sky.paid-coupon.lock-lab.coupon-id=88990001
--sky.paid-coupon.lock-lab.isolation=RR
--sky.paid-coupon.lock-lab.pause-ms=20000
--sky.paid-coupon.reserve-timeout-ms=60000
```

或在仓库根目录打包后启动（不要同时再起一个连接同库的服务）：

```sh
mvn -pl sky-server -am -DskipTests package
SPRING_OPTS='--sky.paid-coupon.lock-lab.enabled=true --sky.paid-coupon.lock-lab.coupon-id=88990001 --sky.paid-coupon.lock-lab.isolation=RR --sky.paid-coupon.lock-lab.pause-ms=20000 --sky.paid-coupon.reserve-timeout-ms=60000' \
  bash scripts/run-sky-server.sh dev small
```

暂停上限 30000ms。这里把预留重试预算加至 60 秒，否则原 1500ms 预算在暂停结束后可能已经耗尽。实验配置关闭后移除这项预算覆盖。不得把暂停时间理解为系统自身慢查询。

### 3. 终端 A：正常预留接口

将 TOKEN 替换为当前登录用户的有效 JWT（沿用现有登录流程），BASE 按实际服务端口调整。requestId 每轮必须全新，避免命中幂等结果。

```sh
BASE=http://localhost:8080
TOKEN='替换为用户登录token'
curl --max-time 70 -sS "$BASE/user/paid-coupon/reservations" \
  -H "authentication: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"requestId":"gap-lab-rr-001","items":[{"couponId":88990001,"quantity":1}]}'
```

服务日志出现 `LOCK_LAB_PAUSE`，含 requestId、couponId、数据库实际隔离级别和暂停毫秒数。此时在 20 秒内执行下一步。

### 4. 终端 B：触发独立补池事务

```sh
BASE=http://localhost:8080
TOKEN='替换为用户登录token'
curl --max-time 70 -sS -w '\nHTTP耗时=%{time_total}s\n' \
  -X POST "$BASE/user/paid-coupon/lock-lab/coupons/88990001/replenish" \
  -H "authentication: $TOKEN"
```

RR 组预期补池暂时不返回。暂停结束后预留事务回滚释放锁，补池与预留重试继续。补池返回的新增数量可能为 0（原预留流程的补池竞争者已经补完），不能单凭数量判断失败；应检查锁等待证据与最终预留结果。

### 5. 暂停期间，在 MySQL 客户端观察

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
  AND l.OBJECT_NAME='paid_coupon_available_unit';

SELECT t.PROCESSLIST_ID,l.OBJECT_NAME,l.INDEX_NAME,
       l.LOCK_TYPE,l.LOCK_MODE,l.LOCK_STATUS,l.LOCK_DATA
FROM performance_schema.data_locks l
JOIN performance_schema.threads t ON t.THREAD_ID=l.THREAD_ID
WHERE l.OBJECT_SCHEMA='sky_order_0'
  AND l.OBJECT_NAME='paid_coupon_available_unit';
```

重点看 INSERT 的 `INSERT_INTENTION` 等待及其 blocker。测试 ID 落在索引尾部时可能显示 `supremum pseudo-record`；若后面还有其他键，也可能显示具体边界记录的 GAP/next-key 锁，不能要求一定是 supremum。

### 6. RC 对照与结束实验

另取一个未使用 ID，例如 88990002，重复步骤 1；重启服务，把 coupon-id 改为新 ID、isolation 改为 `RC`，仍保留同样暂停。A 使用新 ID、新 requestId，B 也改用新 ID。预期 A 仍暂停 20 秒，但 B 可在暂停结束前补池完成，锁查询不再出现该插入等待链。不要直接重复使用已补满的池进行对照。

完成后调用正常释放接口释放自己的实验预留：

```sh
curl -X POST "$BASE/user/paid-coupon/reservations/gap-lab-rr-001/release" \
  -H "authentication: $TOKEN"
```

RC 批次同样释放。保留测试券账本供核查，不执行清空业务表的命令。重启时移除实验参数（或明确设置 enabled=false），即可恢复默认 RC、无暂停、无实验补池入口。

本轮新增测试验证配置作用范围、REQUIRES_NEW 与 RC/RR 选择、空池异常回滚、中断回滚路径。服务 HTTP 实验结果需以实际操作时的日志和数据库锁记录为准，不能套用上面 SQL 脚本的历史通过记录。
