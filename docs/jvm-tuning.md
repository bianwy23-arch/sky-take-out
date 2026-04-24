# Sky Server JVM 调优建议

这个项目的后端服务是典型的 Spring Boot 2.7 Web 应用，集成了 MySQL、Redis、RabbitMQ、WebSocket、Elasticsearch、ShardingSphere。它可以做 JVM 调优，但应该先做基础参数收敛，再结合压测和 GC 日志继续调整。

## 先定边界

- JDK 建议统一到 17。项目根目录已经有 `bin -> JDK 17` 的软链接，运行和压测最好保持一致。
- 这类服务的 JVM 调优目标通常不是单纯压低内存，而是稳定 RT、降低 Full GC 风险、让内存曲线可预测。
- 如果没有压测数据，先用保守参数，不要上来就改太多 GC 细节。

## 第一版参数

推荐先用 G1，固定堆大小，避免堆动态扩缩容带来的抖动。

### 小规格机器

适用场景：开发环境、1C2G、小流量测试机。

```bash
-Xms512m -Xmx512m -XX:MaxMetaspaceSize=256m -Xss512k \
-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ParallelRefProcEnabled \
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./logs
```

### 中规格机器

适用场景：2C4G 或 4C4G 测试环境，常规联调和压测。

```bash
-Xms1g -Xmx1g -XX:MaxMetaspaceSize=384m -Xss512k \
-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ParallelRefProcEnabled \
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./logs
```

### 大一点的规格

适用场景：4C8G 及以上，订单、搜索、MQ 消费同时跑。

```bash
-Xms2g -Xmx2g -XX:MaxMetaspaceSize=512m -Xss512k \
-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ParallelRefProcEnabled \
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./logs
```

## 为什么先这样配

- `-Xms = -Xmx`：避免运行中堆扩容带来的额外停顿。
- `G1GC`：对 Spring Boot 这类中等堆、延迟敏感服务更稳妥。
- `MaxGCPauseMillis=200`：不是强保证，但适合作为第一版目标。
- `Xss512k`：线程较多时可以降低单线程栈占用，但前提是业务没有特别深的递归调用。
- `MaxMetaspaceSize`：控制类元数据上限，防止无限增长。

## 项目里的真实关注点

这个项目里，JVM 不是唯一瓶颈，下面这些点经常比 JVM 更先出问题：

- ShardingSphere 路由和 SQL 放大
- Elasticsearch 查询和批量写入
- Redis 大 key 或热点 key
- RabbitMQ 消费吞吐和堆积
- 数据源连接池大小与 Tomcat 线程数不匹配
- 调试级 SQL 日志导致对象分配量上升

例如当前 [application.yml](/Users/bianwenyuan/Desktop/sky-take-out_v2/sky-server/src/main/resources/application.yml) 里 `mapper` 是 `debug` 日志，压测时应先降到 `info` 或 `warn`，否则会明显放大 GC 压力。

## 启动脚本

仓库里新增了 [run-sky-server.sh](/Users/bianwenyuan/Desktop/sky-take-out_v2/scripts/run-sky-server.sh)，内置三档 JVM 预设。

先打包：

```bash
mvn -pl sky-server -am clean package -DskipTests
```

再启动：

```bash
scripts/run-sky-server.sh dev small
scripts/run-sky-server.sh test medium
scripts/run-sky-server.sh prod large
```

也可以追加自定义参数：

```bash
JAVA_OPTS="-XX:InitiatingHeapOccupancyPercent=30" scripts/run-sky-server.sh prod medium
```

## 压测时重点观察

- `Young GC` 是否过于频繁
- 是否出现 `Full GC`
- GC 停顿时间是否明显拉高接口 `P95/P99`
- 堆使用率是否长期贴近 `Xmx`
- Old 区是否只涨不回落

建议配合：

- `jstat -gcutil <pid> 1s`
- `jcmd <pid> GC.heap_info`
- `jcmd <pid> Thread.print`
- Arthas 的 `dashboard`、`thread`、`heapdump`

## 下一步怎么做

如果你要继续往“面试可讲”或“线上可落地”走，建议按这个顺序：

1. 先用 `medium` 方案跑一次压测。
2. 收集 GC 日志、吞吐、P95/P99、CPU、RSS。
3. 再决定是否需要继续调 G1 的触发阈值、线程栈、元空间和容器内存比例。

不要跳过基线数据，纯靠经验值很容易把问题调偏。
