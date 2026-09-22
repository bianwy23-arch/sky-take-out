# 多种付费券抢购：最小库存预留 Spec

版本：v0.3，2026-09-22。状态：待确认，本轮不开始实现。
目标工程：`/Users/bianwenyuan/Desktop/sky-take-out_v2`。保留当前免费券 Redis 路线及已有工作区修改。

## 1. 范围与依据

只实现“同时预留多种券 → 模拟支付成功后确认消耗，或取消/超时释放”的库存内核，以接口和运行脚本演示，不建立完整电商订单或支付系统。

依据：[Shopify 原文](https://shopify.engineering/scaling-inventory-reservations)及其[预留 SQL 示例](https://gist.github.com/CourtneySymons/cb5ecbe86331047aae166d5b1f1d555c)。沿用：单位库存行、有界可用池、协调补池、SKIP LOCKED、多商品 UNION ALL、同库事务、复合主键、RC、统一操作顺序及连接占用验证。

Shopify 未公开完整 DDL、账本实现和释放流程。下文用“项目补全”标识运行所需的最少约定；这些不是其已验证实现。原文示例先 INSERT 后 DELETE，正文修正为先 DELETE 后 INSERT；本项目采用正文修正顺序，不声称复现了文章未完整公开的死锁。

## 2. 最小业务流程

- 请求携带唯一 request_id 和券清单 `items[{coupon_id, quantity}]`，数量为正整数，重复券项先合并；默认每种一份。单次请求所有券的数量合计不得超过 1000 份（重复券项同样计入），超限在访问库存前拒绝。所有券属于同一物理数据库。
- 预留必须整批成功，否则全部回滚；不留部分占用。
- 模拟支付成功：整批 Claim。取消或预留到期：整批 Release。
- 同一 request_id 重放相同规范化清单返回原结果，清单不同返回冲突；确认、释放可重复调用，但最多生效一次。
- 不限制用户购买次数，不实现限购、购物车、金额结算、真实支付、退款、发券权益、核销、页面或消息通知。Claim 成功记录就是本次演示的购买完成结果。

## 3. 最少数据（项目补全）

沿用现有 MySQL/MyBatis 技术栈，四张新表，固定同库，不进入原免费券或餐饮订单表。

| 表 | 必须保存的内容 |
|---|---|
| paid_coupon_inventory | coupon_id 主键、固定 total、remaining（尚未正式售出的数量）、next_unit_id |
| paid_coupon_available_unit | 主键 `(coupon_id, unit_id)`；每行一份可用库存 |
| paid_coupon_reserved_unit | 主键 `(request_id, coupon_id, unit_id)`；唯一 `(coupon_id, unit_id)`；每行一份活跃预留 |
| paid_coupon_reservation_batch | request_id 主键、user_id、规范化 items、state、expires_at；到期扫描索引 `(state, expires_at, request_id)` |

batch 只保存幂等、预留生命周期和确认结果，取代独立订单、明细、限购槽位、权益和模拟渠道表。items 存储完整规范化清单，不能只保存不可还原的摘要。

逐券定义：L=remaining，A=可用单位行数，R=活跃预留行数。未物化额度计算为 `L-A-R`，不另外维护 U 字段。

- 初始 L=total；补池只把未物化额度转换为单位行，不改变 L。
- Reserve：A 减、R 加；Claim：R 减、L 同量减；Release：R 减、L 不变，释放的额度可再次补池。
- 始终满足 `0 <= A+R <= L <= total`，可用池 `A <= capacity`；成功购买总数为 `total-L`，应等于 CLAIMED 批次清单中该券的数量和。
- 单位编号单调生成，释放不复用旧编号。释放不直接回插可用池，避免池超上限；这是最小实现约定，不是原文公开的释放算法。

## 4. 事务与操作

所有业务写事务使用 READ COMMITTED；通过实际 Spring 事务入口执行，不在事务内等待支付。复合主键匹配查询条件。MySQL 锁语义参考[锁定读](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)。

### 4.1 Reserve

1. 创建/锁定 batch，核对 request_id、用户和清单；已存在的同请求直接返回其结果。新批次必须在读取库存前插入，利用 request_id 唯一键协调并发重放；并发插入冲突回滚后重读原结果，库存不足则新批次与库存操作一起回滚。
2. 一条 UNION ALL 语句读取所有券的单位：每个分支按 coupon_id 过滤、按 unit_id 排序、LIMIT 对应 quantity，并在分支内 FOR UPDATE SKIP LOCKED；所有值参数绑定。
3. 按每种券分别检查数量。任一不足，回滚本次事务，释放已取得的全部锁，再进入补池及重试。
4. 全部满足时，先 DELETE 选中的可用单位，再 INSERT 全部预留行，batch 置 RESERVED 并写入到期时间，同一事务提交。

批量只减少这一步锁定查询的数据库往返，不把整笔事务称为一次往返。分支按 coupon_id 排序生成，但不把 SQL 书写顺序视为引擎加锁顺序保证。

### 4.2 补池

每种券独立短事务：锁 inventory 行，计算 `n=min(capacity-A, L-A-R)`，批量插入 n 个新单位并推进编号，提交。初始 capacity=1000，作为待测参数。

A 和 R 必须用同一条一致性读语句取得同一快照，不能分两条 RC 查询；期间所有 Claim、Release、其他补池都由该 inventory 行锁协调。并发 Reserve 原子地在 A/R 间移动数量，不改变两者之和。

初始化时补池；池不足时同步触发，其他补池者等待同一行锁。不新增低水位后台补池系统。多券请求先回滚，再分别补不足的券，之后重试完整 Reserve。

不能将 SKIP LOCKED 空结果当作售罄：还有未物化额度则补池；有行被占锁则重试；可售额度全部被预留则返回暂不可用；L=0 才是已售罄。工程请求必须有有限超时，耗尽返回繁忙，不伪报售罄；具体预算在真实 MySQL 基线后固定，不预设 3 次/2 秒参数。

### 4.3 Claim

模拟付款成功直接调用 Claim：锁 batch，检查 RESERVED 且尚未到期，按 coupon_id 升序锁定所有 inventory 行；校验全部预留数量、删除预留、各券 L 扣减对应数量，batch 置 CLAIMED，一次提交。任一异常整笔回滚；已经 CLAIMED 返回原结果，已经 RELEASED 拒绝确认。

逐笔更新账本是本项目最小补全，可能形成确认阶段热点，必须测量，不声称 Shopify 采用相同账本结构或整链路没有行锁竞争。

### 4.4 Release 与模拟支付边界

取消或扫描到期批次：锁 batch，确认 RESERVED；按 coupon_id 升序锁定全部 inventory，删除预留、L 保持不变，batch 置 RELEASED，一次提交。已经 RELEASED 幂等返回，已经 CLAIMED 不释放。

一个简单定时扫描按索引分页调用同一 Release 方法；到期释放在事务内重新检查时间。全部状态持久化，进程重启后继续扫描。

项目补全：只有 RESERVED、CLAIMED、RELEASED 三种状态。mock 成功和超时释放竞争同一 batch 行锁；Claim 在锁内检查期限。期限之前已提交 Claim 则购买成功，否则到期后只能释放。期限可配置，演示初值 10 分钟。

本版本没有独立支付渠道，“模拟支付成功”与本地 Claim 是同一个动作；它验证库存并发和幂等，不验证真实渠道已扣款但本地未确认、迟到支付或退款。不得表述为真实支付一致性已经解决。

### 4.5 锁顺序

Reserve：batch → 可用单位锁定/删除 → 预留插入。Claim/Release：batch → 全部 inventory（按 coupon_id 升序）→ 预留删除。补池：inventory → 新单位插入；计数使用非锁定一致性读。

Reserve 不获取 inventory 锁；补池前先回滚 Reserve。发生死锁时整事务回滚，有限重试。不存在“采用统一顺序便绝无死锁”的验收承诺。

## 5. 演示 Interface

一个库存预留模块提供 reserve、claim、release、query 四个操作。以项目现有控制器/鉴权接入，request_id 必须校验用户所属；初始化券及库存由测试/演示脚本完成。模拟确认入口仅在显式演示/测试配置下启用，不复用真实支付回调。

演示脚本完成：初始化多种券 → 多券预留 → 模拟确认 → 查询；另一路预留后取消/超时 → 再次预留。无需独立页面或额外管理后台。

## 6. 分层实现和最低验证

| 层 | 实现 | 必须验证 |
|---|---|---|
| L1 存储及查询 | 四张表、Mapper、RC、UNION ALL 预留查询 | 隔离真实 MySQL 8 测试库；实际路由、分支锁有效、跳过锁、事务回滚；不使用 H2 代替 |
| L2 库存事务 | Reserve、补池、Claim、Release | 多券任一不足整批回滚；并发补池不增发；确认/释放互斥幂等；逐券账本与预留守恒；池有界 |
| L3 接口及演示 | 四个操作、到期扫描、演示脚本 | 重复请求、超时竞争、提交后响应丢失、重启扫描；免费券路径不受影响 |
| L4 对照验证 | 真实 MySQL 锁和性能记录 | 单行预留 vs 单位池；逐券 SELECT vs UNION ALL；预留及确认分别测吞吐、延迟、锁等待、连接等待与持有时间 |

L1/L2 用多个数据库会话及 EXPLAIN/锁记录验证；不靠 SQL 外观认定锁行为。批量语法若被实际驱动/路由拒绝，先明确适配方式，不静默改成逐券查询。

最低并发用例：最后一份、部分券缺货、[A,B]/[B,A] 交叉请求、重复 Claim/Release、确认与超时同时发生、事务中途失败。对账用同一快照或停写后读取，不能拼接不同时间点的计数。

L4 记录环境、请求券数和数量、连接池配置及最终正确性。用最少的分阶段计时和连接池观测判断瓶颈，不引入 ProxySQL/监控平台，不照抄数据库参数或承诺未经验证的 QPS。只报告本地实际测得结果。

确认本文后按 L1→L4 实现；当前不执行数据库迁移、不修改业务代码。
