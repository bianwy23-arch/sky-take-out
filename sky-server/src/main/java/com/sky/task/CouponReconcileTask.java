package com.sky.task;

import com.sky.entity.UserCoupon;
import com.sky.mapper.CouponMapper;
import com.sky.mapper.CouponRedisCompensationMapper;
import com.sky.mapper.UserCouponMapper;
import com.sky.service.CouponStockRefreshMarkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sky.config.AsyncTaskExecutorConfiguration;

/**
 * 优惠券 Redis-DB 一致性对账任务
 *
 * 兜底 MQ 投递成功但消费链路长期失败的场景：
 * 每 5 分钟扫 Redis coupon:grabbed:* 所有 Set，
 * 对每个 (couponId, userId) 检查 DB 是否存在记录，不存在则补写。
 */
@Component
@Slf4j
public class CouponReconcileTask {

    private static final String GRABBED_KEY_PREFIX = "coupon:grabbed:";
    private static final String TASK_LABEL = "task.coupon-reconcile";
    private static final int EXISTS_BATCH = 500;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UserCouponMapper userCouponMapper;

    @Autowired
    private CouponMapper couponMapper;

    @Autowired
    private CouponRedisCompensationMapper compensationMapper;

    @Autowired
    private CouponStockRefreshMarkService couponStockRefreshMarkService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelay = 300_000) // 每 5 分钟
    @Async(AsyncTaskExecutorConfiguration.ASYNC_TASK_EXECUTOR)
    public void reconcile() {
        long startNanos = System.nanoTime();
        if (!running.compareAndSet(false, true)) {
            log.info("async task skipped, label={}, reason=previous-round-running", TASK_LABEL);
            return;
        }
        log.info("[CouponReconcileTask] 开始对账");
        int fixed = 0;
        try {
            // 用 SCAN 找出所有 coupon:grabbed:* 的 key，避免 KEYS 阻塞
            ScanOptions options = ScanOptions.scanOptions().match(GRABBED_KEY_PREFIX + "*").count(100).build();
            try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
                while (cursor.hasNext()) {
                    String key = cursor.next();
                    Long couponId = parseCouponId(key);
                    if (couponId == null) {
                        continue;
                    }

                    // 取出该券所有已领 userId
                    Set<String> members = stringRedisTemplate.opsForSet().members(key);
                    if (members == null || members.isEmpty()) {
                        continue;
                    }

                    // 按批查询已落库的用户，避免每个成员单独查询、反复占用共享连接池
                    List<Long> userIds = new ArrayList<>(members.size());
                    for (String userIdStr : members) {
                        try {
                            userIds.add(Long.parseLong(userIdStr));
                        } catch (NumberFormatException e) {
                            log.error("[CouponReconcileTask] 补写失败，couponId={}, userId={}", couponId, userIdStr, e);
                        }
                    }
                    for (int from = 0; from < userIds.size(); from += EXISTS_BATCH) {
                        List<Long> chunk = userIds.subList(from, Math.min(from + EXISTS_BATCH, userIds.size()));
                        Set<Long> granted = new HashSet<>(userCouponMapper.findGrantedUserIds(couponId, chunk));
                        for (Long userId : chunk) {
                            if (!granted.contains(userId)) {
                                fixed += repair(couponId, userId);
                            }
                        }
                    }
                }
            }
            long costMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("[CouponReconcileTask] 对账结束，共补写 {} 条", fixed);
            log.info("async task done, label={}, fixedCount={}, costMs={}", TASK_LABEL, fixed, costMs);
        } catch (Exception e) {
            long costMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.error("[CouponReconcileTask] 对账异常", e);
            log.error("async task failed, label={}, costMs={}", TASK_LABEL, costMs, e);
        } finally {
            running.set(false);
        }
    }

    private int repair(Long couponId, Long userId) {
        try {
            if (compensationMapper.existsUnresolvedRollback(couponId, userId) > 0) {
                log.info("[CouponReconcileTask] 跳过补写，Redis 回滚补偿未解决，couponId={}, userId={}", couponId, userId);
                return 0;
            }
            transactionTemplate.executeWithoutResult(status -> {
                userCouponMapper.insert(UserCoupon.builder()
                        .userId(userId)
                        .couponId(couponId)
                        .status(0)
                        .grabTime(LocalDateTime.now())
                        .build());
            });
            couponStockRefreshMarkService.mark(couponId);
            log.info("[CouponReconcileTask] 补写领券记录，couponId={}, userId={}", couponId, userId);
            return 1;
        } catch (DuplicateKeyException e) {
            // 并发补写，忽略
            return 0;
        } catch (Exception e) {
            log.error("[CouponReconcileTask] 补写失败，couponId={}, userId={}", couponId, userId, e);
            return 0;
        }
    }

    private Long parseCouponId(String key) {
        try {
            return Long.parseLong(key.substring(GRABBED_KEY_PREFIX.length()));
        } catch (Exception e) {
            log.warn("[CouponReconcileTask] 无法解析 couponId，key={}", key);
            return null;
        }
    }
}
