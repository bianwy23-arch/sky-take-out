package com.sky.task;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 订单超时扫描任务
 *
 * 主路径：读 Redis ZSET "order:pending"，精确路由到对应分片取消订单。
 * 兜底路径：每 5 分钟全库广播扫描，防止 Redis 丢数据。
 */
@Component
@Slf4j
public class OrderTimeoutTask {

    private static final String PENDING_ORDER_ZSET = "order:pending";

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderService orderService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    /**
     * 主路径：每 60 秒从 Redis ZSET 取超时订单，逐条取消
     * O(log N) 范围查询，不需要广播数据库
     */
    @Scheduled(fixedDelay = 60000)
    public void processTimeoutOrders() {
        long threshold = System.currentTimeMillis() - 15L * 60 * 1000;

        Set<String> members = redisTemplate.opsForZSet()
                .rangeByScore(PENDING_ORDER_ZSET, 0, threshold);

        if (members == null || members.isEmpty()) {
            return;
        }

        for (String member : members) {
            try {
                String[] parts = member.split(":");
                if (parts.length != 2) {
                    log.warn("invalid pending member: {}", member);
                    redisTemplate.opsForZSet().remove(PENDING_ORDER_ZSET, member);
                    continue;
                }
                Long orderId = Long.parseLong(parts[0]);
                // 取消操作（内部仍通过 orderMapper.getById 广播查找，后续可优化为精确路由）
                orderService.cancelTimeoutOrder(orderId);
                redisTemplate.opsForZSet().remove(PENDING_ORDER_ZSET, member);
            } catch (Exception ex) {
                log.error("redis zset timeout cancel failed, member={}", member, ex);
                // 不移除，下次循环重试
            }
        }
    }

    /**
     * 兜底路径：每 5 分钟全库广播扫描（防止 Redis 丢数据或宕机）
     * 使用 20 分钟阈值（比 Redis 路径多 5 分钟），避免与主路径重复处理
     */
    @Scheduled(fixedDelay = 300000)
    public void fallbackTimeoutScan() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(20);
        List<Orders> timeoutOrders = orderMapper.listTimeoutOrders(Orders.PENDING_PAYMENT, threshold);
        if (timeoutOrders == null || timeoutOrders.isEmpty()) {
            return;
        }
        log.info("fallback scan found {} timeout orders", timeoutOrders.size());
        for (Orders orders : timeoutOrders) {
            try {
                orderService.cancelTimeoutOrder(orders.getId());
            } catch (Exception ex) {
                log.error("fallback timeout cancel failed, orderId={}", orders.getId(), ex);
            }
        }
    }
}
