package com.sky.task;

import com.sky.mapper.CouponMapper;
import com.sky.service.impl.CouponStockRefreshMarkServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sky.config.AsyncTaskExecutorConfiguration;

@Component
@Slf4j
public class CouponStockRefreshTask {

    private static final String TASK_LABEL = "task.coupon-stock-refresh";
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    private CouponMapper couponMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Scheduled(fixedDelay = 5000)
    @Async(AsyncTaskExecutorConfiguration.ASYNC_TASK_EXECUTOR)
    public void refresh() {
        long startNanos = System.nanoTime();
        if (!running.compareAndSet(false, true)) {
            log.info("async task skipped, label={}, reason=previous-round-running", TASK_LABEL);
            return;
        }
        int refreshed = 0;
        try {
            Set<String> couponIds = stringRedisTemplate.opsForSet().members(CouponStockRefreshMarkServiceImpl.DIRTY_COUPON_KEY);
            if (couponIds == null || couponIds.isEmpty()) {
                long costMs = (System.nanoTime() - startNanos) / 1_000_000;
                log.info("async task done, label={}, refreshed=0, costMs={}", TASK_LABEL, costMs);
                return;
            }
            for (String couponIdStr : couponIds) {
                Long couponId;
                try {
                    couponId = Long.parseLong(couponIdStr);
                } catch (Exception e) {
                    stringRedisTemplate.opsForSet().remove(CouponStockRefreshMarkServiceImpl.DIRTY_COUPON_KEY, couponIdStr);
                    continue;
                }
                couponMapper.fixRemainingCount(couponId);
                stringRedisTemplate.opsForSet().remove(CouponStockRefreshMarkServiceImpl.DIRTY_COUPON_KEY, couponIdStr);
                refreshed++;
            }
            long costMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("async task done, label={}, refreshed={}, costMs={}", TASK_LABEL, refreshed, costMs);
        } catch (Exception e) {
            long costMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.error("async task failed, label={}, costMs={}", TASK_LABEL, costMs, e);
        } finally {
            running.set(false);
        }
    }
}
