# 苍穹外卖 — 订单分库分表架构设计方案

## 一、背景与目标

### 1.1 问题

随着业务增长，`orders` 表数据量持续膨胀。MySQL InnoDB 单表数据超过 **2000 万行**后，B+ 树层数增加，索引查询效率下降；超过 **5000 万行**后，DDL 操作（加索引、改字段）可能导致长时间锁表。

### 1.2 目标

- 单表数据量控制在 **1500 万行以内**
- 用户侧核心查询（订单列表）响应时间 **< 50ms**
- 支持后续从 8 分片平滑扩容到 16/32 分片
- 对现有业务代码改动最小化

---

## 二、现有查询模式分析

对项目代码的完整分析，订单相关查询分为三类：

### 2.1 用户侧查询（最高频）

| 场景 | SQL 条件 | 频率 | 特点 |
|------|----------|------|------|
| 用户订单列表 | `WHERE user_id = ? AND status = ?` | 极高 | 分页，核心查询 |
| 订单详情 | `WHERE id = ?` + `order_detail.order_id = ?` | 高 | 主键查询 |
| 用户取消订单 | `WHERE id = ?` → 先查后改 | 中 | 需要释放库存 |
| 再来一单 | `WHERE order_detail.order_id = ?` | 低 | 复制到购物车 |

### 2.2 支付回调查询（中频）

| 场景 | SQL 条件 | 频率 | 特点 |
|------|----------|------|------|
| 微信支付成功回调 | `WHERE number = ?`（订单号） | 中 | 回调只带订单号，无 userId |

### 2.3 管理端查询（低频）

| 场景 | SQL 条件 | 频率 | 特点 |
|------|----------|------|------|
| 多条件搜索 | `number / phone / status / userId / 时间范围` | 低 | 多字段组合，跨用户 |
| 订单统计 | `COUNT(*) WHERE status = ?` | 低 | 3 次独立 COUNT |
| 超时订单扫描 | `status = 1 AND order_time <= ?` | 定时 | 全表扫描，60 秒一次 |
| 确认/拒绝/配送/完成 | `WHERE id = ?` | 低 | 管理员单条操作 |

### 2.4 关联表查询

- **无直接 SQL JOIN** — `orders` 与 `order_detail` 通过两次独立查询关联
- 用户信息（phone、address、consignee）冗余存储在 orders 表中
- 菜品信息（name、image、amount）冗余存储在 order_detail 表中

---

## 三、分片键选择：`user_id`

### 3.1 为什么选 user_id？

| 维度 | 分析 |
|------|------|
| **覆盖率** | 用户订单列表（`WHERE user_id = ?`）是最高频查询，选 user_id 可精确路由到单库单表 |
| **数据亲和性** | 同一用户的所有订单在同一分片，下单事务是本地事务，无需分布式事务 |
| **分布均匀性** | 用户数远大于分片数，user_id 取模分布均匀 |
| **绑定表友好** | order_detail 加上 user_id 字段后可路由到同一分片，避免跨分片关联 |

### 3.2 不选其他键的原因

| 候选键 | 否决理由 |
|--------|---------|
| `id`（自增主键） | 用户查订单列表时无法路由，必须广播所有分片 |
| `number`（订单号） | 同上，且订单号格式不固定 |
| `order_time`（下单时间） | 按时间分片会导致热点集中在最新分片，写入不均衡 |

### 3.3 分片键带来的问题与解决

| 问题 | 解决方案 |
|------|---------|
| 支付回调只有 orderNumber，无 userId | 订单号中编码 userId 后缀，可反解路由 |
| 管理端按 id 操作无 userId | 方案 A：广播查询（低频可接受）；方案 B：ES 索引记录 id→userId 映射 |
| 管理端跨用户模糊搜索 | 初期广播查询，中期引入 ES |
| 统计（COUNT by status） | Redis 实时计数替代 |

---

## 四、分片拓扑设计

### 4.1 推荐方案：2 库 × 4 表 = 8 分片

```
ds_0 (MySQL 实例 1: sky_order_0)
├── orders_0        (user_id % 8 == 0)
├── orders_1        (user_id % 8 == 1)
├── orders_2        (user_id % 8 == 2)
├── orders_3        (user_id % 8 == 3)
├── order_detail_0  ~ _3
├── pay_transaction_0 ~ _3
└── outbox_message_0  ~ _3

ds_1 (MySQL 实例 2: sky_order_1)
├── orders_4        (user_id % 8 == 4)
├── orders_5        (user_id % 8 == 5)
├── orders_6        (user_id % 8 == 6)
├── orders_7        (user_id % 8 == 7)
├── order_detail_4  ~ _7
├── pay_transaction_4 ~ _7
└── outbox_message_4  ~ _7
```

### 4.2 路由算法

```
分库路由：user_id % 2 → ds_0 或 ds_1
分表路由：user_id % 8 → 表后缀 _0 ~ _7
```

### 4.3 容量评估

| 指标 | 数值 |
|------|------|
| 假设日均订单 | 10 万单 |
| 年订单量 | 3650 万单 |
| 8 分片单表年增 | ~456 万行 |
| 单表达到 1500 万行 | ~3.3 年 |

### 4.4 扩容路径

选择 2 的幂次分片数，扩容时每个分片一分为二，只需迁移 50% 数据：

```
8 分片 → 16 分片：user_id % 16
  orders_0 (原 user_id % 8 == 0) → 拆为 orders_0 (% 16 == 0) + orders_8 (% 16 == 8)
  每个分片只需迁移一半数据
```

### 4.5 绑定表（Binding Tables）

以下表使用 **相同的 user_id 分片键**，路由到同一物理分片，避免跨分片关联：

```
orders          → user_id % 8
order_detail    → user_id % 8  (需新增 user_id 列)
pay_transaction → user_id % 8  (需新增 user_id 列)
outbox_message  → user_id % 8  (需新增 user_id 列)
```

### 4.6 广播表（Broadcast Tables）

数据量小、变更少的表，每个库保留完整副本：

```
dish, category, setmeal, setmeal_dish, dish_flavor
employee, address_book, shopping_cart, users
```

---

## 五、订单号重新设计

### 5.1 当前问题

```java
// OrderServiceImpl.java 第 101 行
orders.setNumber(String.valueOf(System.currentTimeMillis()));
```

- 纯时间戳，并发下可能重复
- 不含业务信息，分片后无法反解路由
- 不可读（13 位数字无语义）

### 5.2 新订单号格式

```
格式：{yyyyMMddHHmmss}{6位userId取模}{4位序列号}
长度：24 字符
示例：20260311143052_000123_0001
       └──时间可读──┘ └userId%1M┘ └唯一性┘
```

### 5.3 生成器实现

```java
public class OrderNumberGenerator {

    private static final AtomicInteger SEQUENCE = new AtomicInteger(0);
    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 生成订单号
     * 格式：{时间14位}{userId后6位}{4位序列号}
     */
    public static String generate(Long userId) {
        String timePart = LocalDateTime.now().format(FMT);
        String userPart = String.format("%06d", userId % 1_000_000);
        String seqPart  = String.format("%04d", SEQUENCE.getAndIncrement() % 10_000);
        return timePart + userPart + seqPart;
    }

    /**
     * 从订单号反解 userId 后缀（用于分片路由）
     * 支付回调场景：只有订单号，需要知道路由到哪个分片
     */
    public static long extractUserIdSuffix(String orderNumber) {
        return Long.parseLong(orderNumber.substring(14, 20));
    }
}
```

### 5.4 支付回调路由

微信支付回调只带 `out_trade_no`（订单号），无 userId：

```java
// PayNotifyController / OrderServiceImpl.paySuccess()

public void paySuccess(String outTradeNo, String transactionId) {
    // 从订单号反解 userId 后缀
    long userIdSuffix = OrderNumberGenerator.extractUserIdSuffix(outTradeNo);

    // 方案 A：ShardingSphere HintManager 指定路由
    try (HintManager hintManager = HintManager.getInstance()) {
        hintManager.addDatabaseShardingValue("orders", userIdSuffix);
        hintManager.addTableShardingValue("orders", userIdSuffix);
        Orders order = orderMapper.getByNumber(outTradeNo);
        // ... 后续业务逻辑
    }

    // 方案 B（更简单）：不用 Hint，orders 表 number 列有索引
    //   ShardingSphere 发现 WHERE 条件不含分片键时，自动广播查询
    //   支付回调频率不高（每笔订单只调一次），广播开销可接受
}
```

---

## 六、ID 生成策略

### 6.1 问题

MySQL `AUTO_INCREMENT` 在分库分表后各分片独立自增，会产生 ID 冲突。

### 6.2 方案：雪花算法（Snowflake）

ShardingSphere 内置雪花算法，无需额外部署 ID 服务：

```
| 1 bit 符号位 | 41 bit 时间戳 | 10 bit 工作节点 | 12 bit 序列号 |
|     0       | ~69年不重复   | 1024 个节点    | 每毫秒 4096 个 |
```

- 全局唯一，趋势递增（对 B+ 树索引友好）
- 无中心化依赖
- 每秒可生成 **409.6 万个 ID**

### 6.3 配置

```yaml
keyGenerateStrategy:
  column: id
  keyGeneratorName: snowflake
```

---

## 七、技术选型：ShardingSphere-JDBC

### 7.1 选型对比

| 对比项 | ShardingSphere-JDBC | MyCat / ProxySQL |
|-------|--------------------|--------------------|
| 部署方式 | JAR 嵌入应用，零额外进程 | 需要独立代理服务 |
| 性能 | 无网络跳转开销 | 多一次代理转发（+1~2ms） |
| 运维成本 | 低 | 高（代理高可用、监控） |
| Spring Boot 集成 | 原生 Starter | 需额外配置 |
| 事务支持 | LOCAL/XA/BASE | 有限 |
| 社区 | Apache 顶级项目 | MyCat 维护减少 |

### 7.2 Maven 依赖

```xml
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>shardingsphere-jdbc-core-spring-boot-starter</artifactId>
    <version>5.4.1</version>
</dependency>
```

### 7.3 核心配置（application-sharding.yml）

```yaml
spring:
  shardingsphere:
    # ========== 数据源 ==========
    datasource:
      names: ds_0,ds_1
      ds_0:
        type: com.alibaba.druid.pool.DruidDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        url: jdbc:mysql://host1:3306/sky_order_0?useSSL=false&serverTimezone=Asia/Shanghai
        username: ${sky.datasource.username}
        password: ${sky.datasource.password}
      ds_1:
        type: com.alibaba.druid.pool.DruidDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        url: jdbc:mysql://host2:3306/sky_order_1?useSSL=false&serverTimezone=Asia/Shanghai
        username: ${sky.datasource.username}
        password: ${sky.datasource.password}

    rules:
      sharding:
        # ========== 分片表 ==========
        tables:
          orders:
            actual-data-nodes: ds_$->{0..1}.orders_$->{0..7}
            database-strategy:
              standard:
                sharding-column: user_id
                sharding-algorithm-name: db-mod
            table-strategy:
              standard:
                sharding-column: user_id
                sharding-algorithm-name: table-mod
            key-generate-strategy:
              column: id
              key-generator-name: snowflake

          order_detail:
            actual-data-nodes: ds_$->{0..1}.order_detail_$->{0..7}
            database-strategy:
              standard:
                sharding-column: user_id
                sharding-algorithm-name: db-mod
            table-strategy:
              standard:
                sharding-column: user_id
                sharding-algorithm-name: table-mod
            key-generate-strategy:
              column: id
              key-generator-name: snowflake

          pay_transaction:
            actual-data-nodes: ds_$->{0..1}.pay_transaction_$->{0..7}
            database-strategy:
              standard:
                sharding-column: user_id
                sharding-algorithm-name: db-mod
            table-strategy:
              standard:
                sharding-column: user_id
                sharding-algorithm-name: table-mod

          outbox_message:
            actual-data-nodes: ds_$->{0..1}.outbox_message_$->{0..7}
            database-strategy:
              standard:
                sharding-column: user_id
                sharding-algorithm-name: db-mod
            table-strategy:
              standard:
                sharding-column: user_id
                sharding-algorithm-name: table-mod

        # ========== 绑定表（避免跨分片 JOIN）==========
        binding-tables:
          - orders,order_detail,pay_transaction,outbox_message

        # ========== 广播表 ==========
        broadcast-tables: dish,category,setmeal,setmeal_dish,dish_flavor,employee,users

        # ========== 分片算法 ==========
        sharding-algorithms:
          db-mod:
            type: MOD
            props:
              sharding-count: 2
          table-mod:
            type: MOD
            props:
              sharding-count: 8

        # ========== ID 生成器 ==========
        key-generators:
          snowflake:
            type: SNOWFLAKE
            props:
              worker-id: 1

    props:
      sql-show: true  # 开发环境打印路由后的真实 SQL
```

---

## 八、超时订单扫描改造：Redis ZSET

### 8.1 当前方案的问题

```java
// OrderTimeoutTask.java — 每 60 秒执行
SELECT * FROM orders WHERE status = 1 AND order_time <= ?
```

分库分表后，这条 SQL 不含分片键，ShardingSphere 会广播到所有 8 个分片，性能随分片数线性下降。

### 8.2 新方案：Redis ZSET + DB 兜底

```
                下单                  支付成功
                 │                      │
                 ▼                      ▼
  ZADD order:pending                ZREM order:pending
    score = orderTime 时间戳            member
    member = {orderId}:{userId}
                 │
                 │  每 60 秒
                 ▼
  ZRANGEBYSCORE order:pending 0 {15分钟前}
                 │
                 ▼
  解析 orderId + userId → 精确路由到分片 → 执行取消
                 │
                 ▼
  ZREM order:pending {member}
```

### 8.3 实现代码

```java
// ===== 下单时写入 Redis =====
// OrderServiceImpl.submitOrder()
public OrderSubmitVO submitOrder(OrdersSubmitDTO dto) {
    // ... 原有下单逻辑 ...
    orderMapper.insert(order);

    // 写入 Redis 待支付集合
    String member = order.getId() + ":" + order.getUserId();
    double score = order.getOrderTime().atZone(ZoneId.systemDefault())
                       .toInstant().toEpochMilli();
    redisTemplate.opsForZSet().add("order:pending", member, score);
    // ...
}

// ===== 支付成功时移除 =====
// OrderServiceImpl.paySuccess()
public void paySuccess(String outTradeNo, String transactionId) {
    Orders order = orderMapper.getByNumber(outTradeNo);
    // ... 原有支付逻辑 ...

    // 从待支付集合移除
    String member = order.getId() + ":" + order.getUserId();
    redisTemplate.opsForZSet().remove("order:pending", member);
}

// ===== 超时扫描任务 =====
@Scheduled(fixedDelay = 60000)
public void cancelTimeoutOrders() {
    long threshold = System.currentTimeMillis() - 15 * 60 * 1000;

    // 从 Redis 取出超时订单（O(log N) 范围查询）
    Set<String> members = redisTemplate.opsForZSet()
        .rangeByScore("order:pending", 0, threshold);

    if (members == null || members.isEmpty()) return;

    for (String member : members) {
        String[] parts = member.split(":");
        Long orderId = Long.parseLong(parts[0]);
        Long userId  = Long.parseLong(parts[1]);

        try {
            // 精确路由到分片执行取消（userId 是分片键）
            orderService.cancelTimeoutOrder(orderId, userId);
        } catch (Exception e) {
            log.error("超时取消失败, orderId={}", orderId, e);
            // 失败不移除，下次循环重试
            continue;
        }

        // 成功后移除
        redisTemplate.opsForZSet().remove("order:pending", member);
    }
}
```

### 8.4 DB 兜底扫描

Redis 可能丢数据（宕机、主从切换）。保留一个低频的 DB 广播扫描作为兜底：

```java
// 每 5 分钟执行一次 DB 兜底扫描（广播查询）
@Scheduled(fixedDelay = 300000)
public void fallbackTimeoutScan() {
    LocalDateTime threshold = LocalDateTime.now().minusMinutes(20); // 比 Redis 多留 5 分钟
    List<Orders> timeoutOrders = orderMapper.listTimeoutOrders(
        Orders.PENDING_PAYMENT, threshold);
    // 逐条取消...
}
```

### 8.5 一致性保证

| 异常场景 | 表现 | 影响 |
|---------|------|------|
| DB 写成功 + Redis 写失败 | 待支付集合缺少该订单 | DB 兜底扫描会兜住（延迟多几分钟） |
| Redis 写成功 + DB 写失败 | Redis 有脏数据 | 取消时查 DB 发现订单不存在，直接 ZREM |
| Redis 宕机 | 待支付集合丢失 | DB 兜底扫描全覆盖 |

结论：**最终一致**，不要求强一致，业务上可接受。

---

## 九、管理端搜索：ES 索引层

### 9.1 演进路线

| 阶段 | 方案 | 触发条件 |
|------|------|---------|
| 初期 | ShardingSphere 广播查询 | 分片 ≤ 8，管理端 QPS < 10 |
| 中期 | 引入 ES，管理端搜索走 ES | 分片 > 8 或广播查询 P99 > 500ms |
| 后期 | ES 承担所有搜索（含用户端历史搜索） | 业务需要全文检索 |

### 9.2 ES 索引设计

```json
PUT /sky_orders
{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 1
  },
  "mappings": {
    "properties": {
      "id":          { "type": "long" },
      "number":      { "type": "keyword" },
      "userId":      { "type": "long" },
      "status":      { "type": "integer" },
      "payStatus":   { "type": "integer" },
      "phone":       { "type": "keyword" },
      "consignee":   { "type": "text", "analyzer": "ik_smart" },
      "address":     { "type": "text", "analyzer": "ik_smart" },
      "userName":    { "type": "keyword" },
      "amount":      { "type": "scaled_float", "scaling_factor": 100 },
      "orderTime":   { "type": "date", "format": "yyyy-MM-dd HH:mm:ss||epoch_millis" },
      "cancelReason": { "type": "text", "analyzer": "ik_smart" }
    }
  }
}
```

### 9.3 数据同步方案

```
MySQL 分片库 ──binlog──→ Canal ──→ RabbitMQ ──→ ES Sink Consumer
                                   (缓冲削峰)
```

- Canal 监听所有分片的 binlog
- 通过 RabbitMQ 解耦，防止 ES 写入抖动影响同步
- Consumer 端做幂等（基于 orderId 的 upsert）

### 9.4 管理端查询改造

```java
public PageResult conditionSearch(OrdersPageQueryDTO dto) {
    // 管理端多条件搜索走 ES
    SearchRequest request = buildEsQuery(dto);
    SearchResponse response = esClient.search(request);

    // ES 返回 id + userId
    List<EsHit> hits = parseHits(response);

    // 用 userId 精确路由到 MySQL 取完整数据
    List<OrderVO> orders = hits.stream()
        .map(hit -> {
            // userId 是分片键，ShardingSphere 精确路由
            Orders order = orderMapper.getById(hit.getId());
            List<OrderDetail> details = orderDetailMapper.getByOrderId(hit.getId());
            return buildOrderVO(order, details);
        })
        .collect(Collectors.toList());

    return new PageResult(response.getHits().getTotalHits().value, orders);
}
```

### 9.5 管理端统计改造：Redis 实时计数

避免 `SELECT COUNT(*) WHERE status = ?` 的全分片广播：

```java
// ===== 订单状态变更时同步更新 Redis =====
private void updateOrderStatusCounter(Integer oldStatus, Integer newStatus) {
    if (oldStatus != null) {
        redisTemplate.opsForHash().increment("order:statistics",
            "status:" + oldStatus, -1);
    }
    redisTemplate.opsForHash().increment("order:statistics",
        "status:" + newStatus, 1);
}

// ===== 统计查询直接读 Redis =====
public OrderStatisticsVO statistics() {
    Map<Object, Object> stats = redisTemplate.opsForHash()
        .entries("order:statistics");
    return OrderStatisticsVO.builder()
        .toBeConfirmed(getCount(stats, Orders.TO_BE_CONFIRMED))
        .confirmed(getCount(stats, Orders.CONFIRMED))
        .deliveryInProgress(getCount(stats, Orders.DELIVERY_IN_PROGRESS))
        .build();
}
```

---

## 十、代码改造清单

### 10.1 order_detail 表增加 user_id 列

```sql
ALTER TABLE order_detail ADD COLUMN user_id BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_order_detail_user_id ON order_detail(user_id);
```

**涉及文件：**

| 文件 | 改动 |
|------|------|
| `sky-pojo/.../entity/OrderDetail.java` | 增加 `private Long userId` 字段 |
| `sky-server/.../mapper/OrderDetailMapper.xml` | `insertBatch` SQL 增加 `user_id` 列 |
| `sky-server/.../service/impl/OrderServiceImpl.java` | 插入 order_detail 时设置 userId |

### 10.2 pay_transaction / outbox_message 表增加 user_id 列

```sql
ALTER TABLE pay_transaction ADD COLUMN user_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE outbox_message ADD COLUMN user_id BIGINT NOT NULL DEFAULT 0;
```

**涉及文件：**

| 文件 | 改动 |
|------|------|
| `sky-pojo/.../entity/PayTransaction.java` | 增加 `private Long userId` |
| `sky-pojo/.../entity/OutboxMessage.java` | 增加 `private Long userId` |
| `sky-server/.../mapper/PayTransactionMapper.java` | insert 方法传递 userId |
| `sky-server/.../mapper/OutboxMessageMapper.java` | insert 方法传递 userId |
| `sky-server/.../service/impl/OrderServiceImpl.java` | 创建记录时传入 userId |

### 10.3 订单号生成改造

| 文件 | 改动 |
|------|------|
| 新建 `sky-common/.../utils/OrderNumberGenerator.java` | 订单号生成与解析 |
| `sky-server/.../service/impl/OrderServiceImpl.java` | `submitOrder()` 使用新生成器 |

### 10.4 超时扫描改造

| 文件 | 改动 |
|------|------|
| `sky-server/.../task/OrderTimeoutTask.java` | 改为从 Redis ZSET 读取 |
| `sky-server/.../service/impl/OrderServiceImpl.java` | 下单/支付时操作 ZSET |

### 10.5 ShardingSphere 配置

| 文件 | 改动 |
|------|------|
| `sky-server/pom.xml` | 引入 shardingsphere-jdbc 依赖 |
| `sky-server/.../resources/application-sharding.yml` | 新建，分片配置 |
| `sky-server/.../resources/application.yml` | 激活 sharding profile |

### 10.6 分片建表 SQL

| 文件 | 说明 |
|------|------|
| 新建 `sky-server/.../resources/sql/sharding_init.sql` | 各分片的建表语句（orders_0~7 等） |

---

## 十一、数据迁移方案

### 11.1 整体策略

```
阶段 0          阶段 1            阶段 2            阶段 3
─────────────────────────────────────────────────────────────
[旧单库]  →  [旧库 + 新分片库]  →  [灰度读新库]  →  [全量新库]
              双写                  按 userId 灰度     下线旧库
```

### 11.2 阶段 0：全量历史数据迁移

```
工具：DataX（阿里开源离线同步框架）

步骤：
1. 记录迁移起始 binlog position（不停服）
2. DataX 读旧库 orders 表 → 按 user_id % 8 分发 → 写入对应分片
3. DataX 读旧库 order_detail → 先查 orders 补充 user_id → 写入对应分片
4. 数据校验：
   旧库 SELECT COUNT(*), SUM(amount) FROM orders
   vs
   各分片汇总 COUNT + SUM
```

### 11.3 阶段 1：双写（1-2 周）

```java
@Transactional
public OrderSubmitVO submitOrder(OrdersSubmitDTO dto) {
    // 1. 写旧库（主）
    orderMapper.insert(order);

    // 2. 异步写新库（从）
    asyncExecutor.execute(() -> {
        try {
            shardedOrderMapper.insert(order);
        } catch (Exception e) {
            log.error("双写新库失败, orderId={}", order.getId(), e);
            compensationQueue.offer(order);  // 补偿队列
        }
    });
}
```

**数据对账任务（每小时）：**

```sql
-- 找出新库缺失的订单
SELECT id, number, user_id FROM old_orders
WHERE order_time >= '迁移起始时间'
  AND id NOT IN (SELECT id FROM 各分片 UNION ALL ...)
```

### 11.4 阶段 2：灰度切读（1 周）

```java
public Page<Orders> pageQuery(int page, int pageSize, Integer status) {
    Long userId = BaseContext.getCurrentId();

    // 灰度策略：userId % 100 < grayRatio 走新库
    int grayRatio = configService.getGrayRatio(); // 配置中心动态调

    if (userId % 100 < grayRatio) {
        return shardedOrderMapper.pageQuery(...);  // 新分片库
    } else {
        return orderMapper.pageQuery(...);          // 旧库
    }
}
```

**灰度节奏：**

```
Day 1:  grayRatio =   1  (1% 用户)   → 观察错误率、响应时间
Day 2:  grayRatio =   5  (5%)
Day 3:  grayRatio =  20  (20%)
Day 5:  grayRatio =  50  (50%)
Day 7:  grayRatio = 100  (100%)      → 全量切读
```

### 11.5 阶段 3：全量切换 & 下线旧库

```
1. 停止双写，所有写流量切到新库
2. 旧库设为只读：SET GLOBAL read_only = ON
3. Canal 增量同步旧库→新库的剩余 binlog
4. 保留旧库 1 个月作为回滚备份
5. 确认无问题后下线
```

### 11.6 回滚方案

| 阶段 | 回滚方式 |
|------|---------|
| 阶段 1（双写） | 停止新库写入，旧库不受影响 |
| 阶段 2（灰度读） | grayRatio 设为 0，立即回退到旧库 |
| 阶段 3（已切写） | 用 Canal 将新库增量反向同步回旧库 |

---

## 十二、面试常见追问与应对

### Q1: "为什么不选 order_id 做分片键？"

> order_id 是自增的，用它做分片键虽然单条查询快，但用户查自己的订单列表时必须广播所有分片。用户侧查询频率远高于管理侧，选 user_id 是**读优化**的策略——牺牲低频的管理端查询性能，换取高频的用户端查询精确路由。

### Q2: "分库分表后分布式事务怎么处理？"

> 项目已经用了 Outbox 模式。下单事务只涉及单个分片内的 orders、order_detail、outbox_message 三张表（同 user_id，同分片），所以是**本地事务**，不需要 2PC/TCC 等分布式事务。跨服务的一致性通过 Outbox + RabbitMQ 实现最终一致。

### Q3: "跨分片的分页查询怎么做？深分页性能差怎么办？"

> ShardingSphere 处理跨分片分页的逻辑：向所有分片发送 `LIMIT 0, offset+size`，内存归并排序取最终结果。深分页时（比如 offset=10000），每个分片返回 10000+size 条数据，内存压力大。
>
> 应对方案：
> 1. **禁止深分页**：管理端用游标分页 `WHERE id > lastId ORDER BY id LIMIT size`
> 2. **管理端搜索走 ES**：ES 天然支持 `search_after` 深分页
> 3. **限制最大页数**：前端限制最多查 100 页

### Q4: "后续怎么扩容？从 8 分片扩到 16 分片？"

> 选 2 的幂次分片数就是为了这个。扩容时每个分片一分为二，只需迁移 50% 的数据：
> - 用 Canal 监听源分片 binlog，按新路由规则写入目标分片
> - 切换 ShardingSphere 配置（sharding-count: 8 → 16）
> - 整个过程在线完成，不需要停服

### Q5: "Redis ZSET 和 DB 的一致性怎么保证？"

> 设计上是**最终一致**的：
> - DB 成功 + Redis 失败：兜底的 DB 广播扫描（每 5 分钟）会覆盖
> - Redis 成功 + DB 失败：取消时查 DB 发现订单不存在，直接 ZREM
> - Redis 宕机：DB 兜底全覆盖
>
> 这里不需要强一致——最坏情况是超时取消延迟几分钟，业务上完全可接受。

### Q6: "雪花算法的时钟回拨问题怎么处理？"

> ShardingSphere 内置的雪花算法支持设置 `max-tolerate-time-difference-milliseconds`（最大容忍时钟回拨毫秒数），默认 10ms 以内会等待追上，超过则抛异常。生产环境建议：
> 1. 使用 NTP 保证服务器时钟同步
> 2. 配置合理的容忍值（如 100ms）
> 3. 极端情况下人工干预（重启 NTP 同步）

### Q7: "分库分表后怎么做数据分析和报表？"

> 订单数据需要做 BI 分析时，不应该直接查分片库。方案：
> 1. **实时报表**：用 Redis 计数器（已实现的 statistics 改造）
> 2. **离线分析**：Canal 同步分片数据到数据仓库（如 ClickHouse、Hive）
> 3. **即席查询**：ES 索引支持管理端的灵活搜索

### Q8: "为什么不用分区表代替分库分表？"

> MySQL 分区表的局限：
> 1. 只能在单实例内分区，无法利用多机器的 CPU 和内存
> 2. 分区键必须是主键的一部分，限制了索引设计
> 3. 跨分区查询性能提升有限
> 4. 无法解决单机存储和连接数瓶颈
>
> 分库分表可以水平扩展到多台机器，是真正的分布式方案。

---

## 十三、架构全景图

```
                                    ┌─────────────┐
                                    │  前端 / APP  │
                                    └──────┬──────┘
                                           │
                                    ┌──────▼──────┐
                                    │  Spring Boot │
                                    │  Application │
                                    └──────┬──────┘
                                           │
                          ┌────────────────┼────────────────┐
                          │                │                │
                   ┌──────▼──────┐  ┌──────▼──────┐  ┌─────▼─────┐
                   │ ShardingSphere│  │    Redis    │  │ RabbitMQ  │
                   │    JDBC      │  │  (缓存+ZSET) │  │ (事件驱动) │
                   └──────┬──────┘  └─────────────┘  └───────────┘
                          │
               ┌──────────┼──────────┐
               │                     │
        ┌──────▼──────┐       ┌──────▼──────┐
        │   ds_0      │       │   ds_1      │
        │ sky_order_0 │       │ sky_order_1 │
        │             │       │             │
        │ orders_0~3  │       │ orders_4~7  │
        │ detail_0~3  │       │ detail_4~7  │
        │ pay_tx_0~3  │       │ pay_tx_4~7  │
        │ outbox_0~3  │       │ outbox_4~7  │
        │             │       │             │
        │ dish (广播)  │       │ dish (广播)  │
        │ users(广播)  │       │ users(广播)  │
        └──────┬──────┘       └──────┬──────┘
               │                     │
               └──────────┬──────────┘
                          │ binlog
                   ┌──────▼──────┐
                   │    Canal    │
                   └──────┬──────┘
                          │
               ┌──────────┼──────────┐
               │                     │
        ┌──────▼──────┐       ┌──────▼──────┐
        │Elasticsearch│       │  数据仓库    │
        │ (管理端搜索) │       │ (离线分析)   │
        └─────────────┘       └─────────────┘
```
