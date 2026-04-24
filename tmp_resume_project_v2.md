# 简历 - 项目经历

## 苍穹外卖 — 外卖平台后端

**技术栈**：Spring Boot / MyBatis / ShardingSphere-JDBC / MySQL / Redis / Redisson / RabbitMQ / Elasticsearch / Caffeine / WebSocket

**项目描述**：面向用户下单支付与商家履约的外卖后端系统；在基础业务功能之上，独立设计并实现优惠券抢购防超卖、订单最终一致性、分库分表路由治理、ES 检索与缓存优化等模块。

### 核心工作

**1. 优惠券抢购高并发与 Redis-DB 一致性补偿**
- 设计“快速失败 + Redisson 按 `couponId` 粒度加锁 + Redis `DECR` 原子扣减 + 锁外异步持久化”链路，避免全局串行，缩短持锁时间
- 将 DB 落库拆为 `insert` / `decrementStock` 两段独立重试，并实现每 5 分钟对账任务扫描 Redis grabbed Set 补写缺失记录，使用 `remaining_count = total_count - COUNT(user_coupon)` 校准库存
- 使用 JMeter 压测 `1000` 并发抢 `500` 张券：平均 RT `8.95ms`、P99 `107ms`、吞吐 `203 QPS`，验证零超卖且 Redis / DB 数据一致

**2. 订单链路四层数据一致性**
- 库存层采用 `stock_available / stock_locked / version` 三段式模型，下单预占、支付确认、取消释放均在本地事务内完成，结合乐观锁与重试防止超卖
- 消息层采用 Outbox 模式，将订单状态变更与消息写入同事务提交；Scheduler 每 `5s` 扫描投递 RabbitMQ，Consumer 通过消费日志唯一键实现幂等
- 物流层接入“HTTP 回调 -> MQ -> Consumer”异步链路，使用状态机校验合法流转；ES 层每小时对账修复，菜品全量、订单近 24 小时增量比对兜底同步失败

**3. 订单分库分表与分片键治理**
- 基于 ShardingSphere-JDBC 按 `user_id` 路由到 `2 库 x 8 表` 共 `16` 个分片，关联表统一复用 `userId` 作为分片键，确保订单、明细、支付、Outbox 在同分片内完成本地事务
- 定位并修复 ShardingSphere Snowflake ID 不回写实体导致的下游空指针问题：放弃 `getGeneratedKeys()`，改为按 `number + userId` 精确回查订单 ID，避免广播查询
- 设计带分片信息的订单号编码，支持从订单号反解路由，降低管理端按单号查询时的分片广播成本

**4. 检索与缓存优化**
- 使用 ES + IK 分词实现菜品全文检索，并将管理端订单条件查询迁移至 ES，减少复杂条件下的数据库广播查询
- 构建“布隆过滤器 -> Caffeine L1 -> Redis L2 -> DB”四层缓存链路，Redis TTL 加随机偏移防雪崩，管理端使用 SCAN 替代 KEYS 做缓存失效
- 结合 Redis Pipeline 优化 UV / PV 报表查询，将 30 天查询的网络往返由 `60` 次降为 `1` 次

**5. 订单链路工程化能力**
- 实现下单 Token 幂等（Redis 原子 `DELETE` 防重复提交）、支付回调幂等（`pay_transaction` 唯一键 + `DuplicateKeyException`）、订单超时关闭双路径兜底（Redis ZSET + DB 扫描）
- 基于 WebSocket 按 `userId` 维护长连接 Session，在支付成功、接单、配送、完成及物流状态变更等多个节点实时推送订单状态
