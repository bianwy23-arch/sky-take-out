package com.sky.task;

import com.sky.config.AsyncTaskExecutorConfiguration;
import com.sky.entity.CouponRedisCompensation;
import com.sky.mapper.CouponRedisCompensationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
public class CouponRedisCompensationTask {

    private static final String STOCK_KEY_PREFIX = "coupon:stock:";
    private static final String GRABBED_KEY_PREFIX = "coupon:grabbed:";
    private static final String TASK_LABEL = "task.coupon-redis-compensation";
    private static final int MAX_RETRY = 20;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final RedisScript<Long> couponGrabRollbackScript = couponGrabRollbackScript();

    @Autowired
    private CouponRedisCompensationMapper compensationMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Scheduled(fixedDelay = 60_000)
    @Async(AsyncTaskExecutorConfiguration.ASYNC_TASK_EXECUTOR)
    public void compensate() {
        long startNanos = System.nanoTime();
        if (!running.compareAndSet(false, true)) {
            log.info("async task skipped, label={}, reason=previous-round-running", TASK_LABEL);
            return;
        }
        int handled = 0;
        try {
            List<CouponRedisCompensation> list = compensationMapper.listReady(50);
            if (list == null || list.isEmpty()) {
                return;
            }
            for (CouponRedisCompensation compensation : list) {
                handleOne(compensation);
                handled++;
            }
        } finally {
            running.set(false);
            long costMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("async task done, label={}, handled={}, costMs={}", TASK_LABEL, handled, costMs);
        }
    }

    private void handleOne(CouponRedisCompensation compensation) {
        try {
            Long rolledBack = stringRedisTemplate.execute(
                    couponGrabRollbackScript,
                    Arrays.asList(STOCK_KEY_PREFIX + compensation.getCouponId(), GRABBED_KEY_PREFIX + compensation.getCouponId()),
                    String.valueOf(compensation.getUserId())
            );
            String result = Long.valueOf(1L).equals(rolledBack) ? "REDIS_ROLLBACK" : "REDIS_ALREADY_RELEASED";
            compensationMapper.mark(
                    compensation.getId(),
                    CouponRedisCompensation.SUCCESS,
                    retryCount(compensation),
                    null,
                    result,
                    LocalDateTime.now()
            );
        } catch (Exception e) {
            int nextRetryCount = retryCount(compensation) + 1;
            Integer status = nextRetryCount >= MAX_RETRY ? CouponRedisCompensation.FAILED : CouponRedisCompensation.PENDING;
            LocalDateTime nextRetryTime = LocalDateTime.now().plusSeconds(Math.min(300, 10L * nextRetryCount));
            compensationMapper.mark(
                    compensation.getId(),
                    status,
                    nextRetryCount,
                    nextRetryTime,
                    truncate(e.getMessage()),
                    LocalDateTime.now()
            );
            if (CouponRedisCompensation.FAILED.equals(status)) {
                log.error("coupon redis compensation exhausted, id={}, couponId={}, userId={}",
                        compensation.getId(), compensation.getCouponId(), compensation.getUserId(), e);
            } else {
                log.warn("coupon redis compensation retry scheduled, id={}, couponId={}, userId={}, retryCount={}",
                        compensation.getId(), compensation.getCouponId(), compensation.getUserId(), nextRetryCount, e);
            }
        }
    }

    private int retryCount(CouponRedisCompensation compensation) {
        return compensation.getRetryCount() == null ? 0 : compensation.getRetryCount();
    }

    private String truncate(String message) {
        if (message == null || message.length() <= 500) {
            return message;
        }
        return message.substring(0, 500);
    }

    private RedisScript<Long> couponGrabRollbackScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/coupon_grab_rollback.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
