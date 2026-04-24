# 简历 - 项目经历

## 苍穹外卖 — 外卖平台后端

**技术栈**：Spring Boot / MyBatis / ShardingSphere-JDBC / MySQL / Redis / Redisson / RabbitMQ / Elasticsearch / Caffeine / WebSocket

**项目描述**：外卖平台后端，涵盖用户端下单支付与管理端订单处理。在业务基础上重点解决了分库分表路由治理、跨组件数据一致性、高并发防超卖等技术问题。

### 核心工作

**1. 订单分库分表与全链路分片键治理**
- 基于 ShardingSphere-JDBC 按 user_id 哈希路由到 2 库 × 8 表共 16 个分片；自定义订单号编码规则将 userId 后 6 位嵌入订单号，支持从订单号反解分片路由，避免管理端查询时的全片广播
- 发现并解决 ShardingSphere Snowflake ID 不回写 Java 实体的问题：ID 在中间件层生成并拼入 SQL，MyBatis `getGeneratedKeys()` 无法获取，导致下游 orderDetail 和 Outbox 写入空指针。修复方案为 insert 后用 `number + userId` 联合查询精准回查（带分片键，单片定位，不广播）
- 所有关联表（order_detail、pay_transaction、outbox_message）均以 userId 为分片键，保证同一用户的订单与明细路由到同一分片，事务内跨表操作不跨库

**2. 四层数据一致性方案**
- **库存**：设计三段式库存模型（stock_available / stock_locked / version），下单预占→支付确认→取消释放，全程乐观锁（`WHERE version = ? AND stock >= ?`）+ 3 次重试，与业务写库在同一事务内，失败整体回滚
- **消息（Outbox 模式）**：订单状态变更与 Outbox 消息在同一事务提交，Scheduler 每 5 秒轮询投递 RabbitMQ（publisher-confirm 同步等待 ACK），失败指数退避重试最多 5 次；Consumer 端通过消费日志表唯一键去重实现幂等
- **物流事件**：外部物流回调先入 MQ 毫秒级响应，下游 Consumer 用状态机校验合法跳转（canTransit），配送失败时乐观锁恢复已扣库存，非法状态跳转直接拒绝并记录日志
- **ES 对账兜底**：定时任务每小时执行，菜品全量比对（百级数据量）、订单增量比对近 24 小时（避免分片表全量广播），自动修复 status/price 等字段不一致

**3. 优惠券抢购防超卖与 Redis-DB 一致性补偿**
- 三层防护：无锁快速校验拦截已售罄请求 → Redisson 分布式锁按 couponId 粒度串行化 → 锁内 Redis DECR 原子扣减（扣为负数立即 INCR 回补）；锁外异步持久化 DB，缩短持锁时间提高吞吐
- DB 持久化失败时指数退避重试 3 次（insert 与 decrementStock 独立重试，防止 insert 成功后重试触发主键冲突）；定时对账任务每 5 分钟扫 Redis grabbed Set 补写缺失记录，并用 `remaining_count = total_count - COUNT(user_coupon)` 一次性校准库存，兜底 insert 成功但 decrementStock 失败的边界情况
- JMeter 1000 并发抢 500 张券压测：平均 RT 8.95ms，P99 107ms，吞吐量 203 QPS，零超卖，Redis 与 DB 数据完全一致

### 其他技术亮点

- **缓存**：Redisson 布隆过滤器启动时预热全量 categoryId 拦截不存在的查询（防穿透）→ Caffeine 本地缓存 L1（60s TTL）→ Redis L2（30min + 随机偏移防雪崩）→ DB；管理端缓存清理使用 SCAN 替代 KEYS 避免阻塞 Redis
- **UV/PV 统计**：拦截器透明埋点，PV 用 INCR 原子计数，UV 用 HyperLogLog 去重（12KB / 0.81% 误差）；Pipeline 批量合并，写入端 2 次→1 次往返，查询端 30 天报表 60 次→1 次往返
- **订单超时**：Redis ZSET（score=下单时间戳）每 60 秒 rangeByScore 精准取消 + 数据库广播扫描每 5 分钟兜底，双路径互补
- **限流**：Lua 脚本原子执行 ZSet 滑动窗口限流（60s / 30 次），Redis 故障时降级放行
- **检索**：ES + IK 分词器实现菜品全文检索，数据通过 MQ Consumer 异步同步，管理端订单搜索走 ES 避免分片广播
- **实时推送**：WebSocket 长连接按 userId 维护 Session，7 个订单状态变更点 + 物流 MQ 消费端触发推送
- **幂等**：下单接口 Token 机制（Redis 原子 DELETE 防重复提交）；支付回调通过 pay_transaction 唯一键 + DuplicateKeyException 实现幂等，重试时跳过库存确认但仍补写 Outbox 保证消息不丢
