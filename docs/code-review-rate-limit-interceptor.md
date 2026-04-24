# RateLimitInterceptor Code Review Report

## 评审概览

- 变更意图: 为 `/user/**` 接口增加基于 Redis ZSet + Lua 的滑动窗口限流能力，限制单用户 `60s` 内最多 `30` 次请求。
- 影响范围:
  - [RateLimitInterceptor.java](/Users/bianwenyuan/Desktop/sky-take-out_v2/sky-server/src/main/java/com/sky/interceptor/RateLimitInterceptor.java)
  - [rate_limiter.lua](/Users/bianwenyuan/Desktop/sky-take-out_v2/sky-server/src/main/resources/scripts/rate_limiter.lua)
  - [WebMvcConfiguration.java](/Users/bianwenyuan/Desktop/sky-take-out_v2/sky-server/src/main/java/com/sky/config/WebMvcConfiguration.java)
- 整体评分: 3/5 分

## 🔴 Critical 问题 (必须修复)

### 1. 同一毫秒内的请求会互相覆盖，导致限流计数偏小

- 问题类型: Critical
- 位置:
  - [rate_limiter.lua:20](/Users/bianwenyuan/Desktop/sky-take-out_v2/sky-server/src/main/resources/scripts/rate_limiter.lua#L20)
  - [RateLimitInterceptor.java:46](/Users/bianwenyuan/Desktop/sky-take-out_v2/sky-server/src/main/java/com/sky/interceptor/RateLimitInterceptor.java#L46)
- 问题描述:
  - 当前脚本把 `ARGV[2]` 同时作为 `ZADD` 的 `score` 和 `member`。
  - `ARGV[2]` 是毫秒级时间戳，多个请求如果落在同一毫秒，Redis Sorted Set 会把它们当成同一个 member，后写覆盖前写。
  - 结果是 `ZCARD` 得到的窗口内请求数会小于真实请求数，限流会被高并发同毫秒请求绕过。
- 影响:
  - 在高并发场景下，单用户可以在同一毫秒内发送多次请求而不被正确计数，限流失效。
  - 这类缺陷会直接影响热点接口保护能力，属于功能正确性问题。
- 建议:
  - `member` 需要保证唯一，不能直接使用毫秒时间戳。
  - 可以使用“时间戳 + 自增序列 / 随机后缀 / 请求唯一ID”作为 member，保留时间戳作为 score。
  - 增加并发测试，重点覆盖“同一用户同一毫秒连续请求”的情况。
- 处理情况:
  - 是否处理:
  - 处理人:
  - 时间:

## 🟡 Warning 问题 (建议修复)

### 2. 限流粒度仅按 `userId` 维度，容易让整个 `/user/**` 命名空间互相抢配额

- 问题类型: Warning
- 位置:
  - [RateLimitInterceptor.java:24](/Users/bianwenyuan/Desktop/sky-take-out_v2/sky-server/src/main/java/com/sky/interceptor/RateLimitInterceptor.java#L24)
  - [RateLimitInterceptor.java:45](/Users/bianwenyuan/Desktop/sky-take-out_v2/sky-server/src/main/java/com/sky/interceptor/RateLimitInterceptor.java#L45)
  - [WebMvcConfiguration.java:58](/Users/bianwenyuan/Desktop/sky-take-out_v2/sky-server/src/main/java/com/sky/config/WebMvcConfiguration.java#L58)
- 问题描述:
  - 当前限流 key 为 `rate:{userId}`，且拦截范围是整个 `/user/**`。
  - 这意味着用户在浏览菜品、查询订单、提交订单等不同接口上的请求都会共享同一个 `30 req / 60s` 配额。
- 影响:
  - 业务热点接口和普通查询接口会互相影响，容易出现“正常用户因为多页面/API联动请求被误伤”的情况。
  - 后续如果前端增加轮询、预加载或并行请求，误限流概率会进一步提高。
- 建议:
  - 根据业务目标明确限流维度。
  - 如果目标是保护特定高风险接口，建议把 key 细化为 `userId + URI` 或 `userId + 业务动作`。
  - 如果确实要做用户全局限流，建议把阈值配置化，并区分只读接口与交易接口。
- 处理情况:
  - 是否处理:
  - 处理人:
  - 时间:

### 3. Redis 异常时直接 fail-open 放行，缺少监控与退化策略闭环

- 问题类型: Warning
- 位置:
  - [RateLimitInterceptor.java:65](/Users/bianwenyuan/Desktop/sky-take-out_v2/sky-server/src/main/java/com/sky/interceptor/RateLimitInterceptor.java#L65)
- 问题描述:
  - 当前 Redis 调用异常时仅打印 warn 日志，然后直接放行请求。
  - 这种 fail-open 在可用性上是合理取舍，但缺少进一步的监控、告警或降级兜底策略。
- 影响:
  - Redis 一旦异常，限流会在运行时静默失效，系统只能依赖日志发现问题。
  - 对于热点接口或恶意流量场景，风险暴露较大。
- 建议:
  - 至少补充指标埋点或告警，例如 Redis 限流执行异常计数。
  - 对关键接口可以考虑更轻量的本地兜底限流，避免完全裸奔。
  - 如果继续采用 fail-open，建议在设计说明中明确这是业务取舍，而不是默认行为。
- 处理情况:
  - 是否处理:
  - 处理人:
  - 时间:

## 🔵 Info 优化建议

### 4. 返回体建议复用统一响应约定，降低前后端解析分叉

- 问题类型: Info
- 位置:
  - [RateLimitInterceptor.java:60](/Users/bianwenyuan/Desktop/sky-take-out_v2/sky-server/src/main/java/com/sky/interceptor/RateLimitInterceptor.java#L60)
  - [Result.java:31](/Users/bianwenyuan/Desktop/sky-take-out_v2/sky-common/src/main/java/com/sky/result/Result.java#L31)
- 问题描述:
  - 当前限流响应直接手写 JSON 字符串，内容上与项目的统一响应结构兼容，但实现方式与控制器层 `Result.error(...)` 的约定割裂。
- 影响:
  - 后续如果统一响应结构扩展字段，拦截器里的手写 JSON 容易漏改。
  - 不利于统一错误码治理和响应序列化维护。
- 建议:
  - 保持 HTTP 429 不变，但响应体建议统一复用项目的错误响应结构生成方式。
  - 如果拦截器层不方便直接复用 `Result`，至少提炼一个统一的响应输出工具，避免散落硬编码 JSON。
- 处理情况:
  - 是否处理:
  - 处理人:
  - 时间:

### 5. 缺少针对 Lua 脚本和限流边界的自动化测试

- 问题类型: Info
- 位置:
  - [RateLimitInterceptor.java](/Users/bianwenyuan/Desktop/sky-take-out_v2/sky-server/src/main/java/com/sky/interceptor/RateLimitInterceptor.java)
  - [rate_limiter.lua](/Users/bianwenyuan/Desktop/sky-take-out_v2/sky-server/src/main/resources/scripts/rate_limiter.lua)
- 问题描述:
  - 当前实现涉及拦截器顺序、ThreadLocal 用户上下文、Redis Lua 脚本以及限流边界判断，但未看到对应自动化测试。
- 影响:
  - 后续调整阈值、key 维度或 Lua 实现时，容易引入回归。
  - 对“窗口边界”“同一毫秒多请求”“Redis 异常降级”等关键场景没有回归保障。
- 建议:
  - 补充以下测试:
    - 单用户窗口内 `29/30/31` 次请求边界测试
    - 同一毫秒多请求并发测试
    - Redis 异常降级测试
    - 不同 URI 是否共享限流配额的验证测试
- 处理情况:
  - 是否处理:
  - 处理人:
  - 时间:

## 总结

本次限流实现的整体思路是合理的：通过 `JwtTokenUserInterceptor` 先写入 `BaseContext`，再在 [WebMvcConfiguration.java:58](/Users/bianwenyuan/Desktop/sky-take-out_v2/sky-server/src/main/java/com/sky/config/WebMvcConfiguration.java#L58) 注册 `RateLimitInterceptor`，结合 Redis Lua 脚本完成单用户滑动窗口限流，技术路线正确、接入点也清晰。

但当前实现存在一个必须修复的正确性问题：Lua 脚本使用毫秒时间戳作为 ZSet member，会导致同一毫秒内请求被覆盖，限流计数失真。这会直接影响限流生效的准确性。除此之外，限流粒度设计和 Redis 异常降级策略也建议尽快补强，否则在真实流量和热点场景下容易出现误伤或保护失效。
