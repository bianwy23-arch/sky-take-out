package com.sky.task;

import com.alibaba.fastjson.JSON;
import com.sky.dto.CouponGrabMessage;
import com.sky.entity.Coupon;
import com.sky.entity.CouponGrabDeadMessage;
import com.sky.entity.UserCoupon;
import com.sky.mapper.CouponGrabDeadMessageMapper;
import com.sky.mapper.CouponMapper;
import com.sky.mapper.UserCouponMapper;
import com.sky.service.CouponStockRefreshMarkService;
import com.sky.service.CouponRedisCompensationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sky.config.AsyncTaskExecutorConfiguration;

@Component
@Slf4j
public class CouponGrabDeadMessageTask {

    private static final String STOCK_KEY_PREFIX = "coupon:stock:";
    private static final String GRABBED_KEY_PREFIX = "coupon:grabbed:";
    private static final String TASK_LABEL = "task.coupon-grab-dlq-handle";
    private static final int MAX_REPLAY = 5;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final RedisScript<Long> couponGrabRollbackScript = couponGrabRollbackScript();

    @Autowired
    private CouponGrabDeadMessageMapper deadMessageMapper;

    @Autowired
    private CouponMapper couponMapper;

    @Autowired
    private UserCouponMapper userCouponMapper;

    @Autowired
    private CouponStockRefreshMarkService couponStockRefreshMarkService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private CouponRedisCompensationService couponRedisCompensationService;

    @Scheduled(fixedDelay = 60_000)
    @Async(AsyncTaskExecutorConfiguration.ASYNC_TASK_EXECUTOR)
    public void handlePending() {
        long startNanos = System.nanoTime();
        if (!running.compareAndSet(false, true)) {
            log.info("async task skipped, label={}, reason=previous-round-running", TASK_LABEL);
            return;
        }
        int handled = 0;
        try {
            List<CouponGrabDeadMessage> messages = deadMessageMapper.listPending(50);
            if (messages == null || messages.isEmpty()) {
                return;
            }
            for (CouponGrabDeadMessage message : messages) {
                try {
                    handleOne(message);
                    handled++;
                } catch (Exception e) {
                    log.error("coupon dlq handle failed, id={}, eventId={}", message.getId(), message.getEventId(), e);
                    handleReplayFailure(message);
                }
            }
        } finally {
            running.set(false);
            long costMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("async task done, label={}, handled={}, costMs={}", TASK_LABEL, handled, costMs);
        }
    }

    public void handleOne(CouponGrabDeadMessage deadMessage) {
        CouponGrabMessage grabMessage;
        try {
            grabMessage = JSON.parseObject(deadMessage.getPayload(), CouponGrabMessage.class);
        } catch (Exception e) {
            mark(deadMessage, CouponGrabDeadMessage.UNKNOWN, "UNKNOWN_PAYLOAD");
            return;
        }
        if (grabMessage == null || grabMessage.getCouponId() == null || grabMessage.getUserId() == null) {
            mark(deadMessage, CouponGrabDeadMessage.UNKNOWN, "UNKNOWN_PAYLOAD");
            return;
        }

        Long couponId = grabMessage.getCouponId();
        Long userId = grabMessage.getUserId();

        if (userCouponMapper.existsByUserIdAndCouponId(userId, couponId) > 0) {
            mark(deadMessage, CouponGrabDeadMessage.IGNORED, "DB_ALREADY_SUCCESS");
            return;
        }

        Boolean redisGrabbed = stringRedisTemplate.opsForSet().isMember(GRABBED_KEY_PREFIX + couponId, String.valueOf(userId));
        Coupon coupon = couponMapper.getById(couponId);
        if (!Boolean.TRUE.equals(redisGrabbed)) {
            mark(deadMessage, CouponGrabDeadMessage.IGNORED, "REDIS_GRAB_NOT_FOUND");
            return;
        }
        if (coupon == null) {
            throw new IllegalStateException("coupon not found, couponId=" + couponId + ", userId=" + userId);
        }
        if (replayCount(deadMessage) >= MAX_REPLAY) {
            compensateRedis(deadMessage, couponId, userId, replayCount(deadMessage));
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(status -> {
                userCouponMapper.insert(UserCoupon.builder()
                        .userId(userId)
                        .couponId(couponId)
                        .status(0)
                        .grabTime(grabMessage.getGrabTime() == null ? LocalDateTime.now() : grabMessage.getGrabTime())
                        .build());
            });
        } catch (DuplicateKeyException e) {
            mark(deadMessage, CouponGrabDeadMessage.IGNORED, "DB_ALREADY_SUCCESS");
            return;
        }

        couponStockRefreshMarkService.mark(couponId);

        mark(deadMessage, CouponGrabDeadMessage.REPLAYED, "DB_REPLAY_SUCCESS");
    }

    private void handleReplayFailure(CouponGrabDeadMessage deadMessage) {
        CouponGrabMessage grabMessage;
        try {
            grabMessage = JSON.parseObject(deadMessage.getPayload(), CouponGrabMessage.class);
        } catch (Exception e) {
            mark(deadMessage, CouponGrabDeadMessage.UNKNOWN, "UNKNOWN_PAYLOAD");
            return;
        }
        if (grabMessage == null || grabMessage.getCouponId() == null || grabMessage.getUserId() == null) {
            mark(deadMessage, CouponGrabDeadMessage.UNKNOWN, "UNKNOWN_PAYLOAD");
            return;
        }
        if (userCouponMapper.existsByUserIdAndCouponId(grabMessage.getUserId(), grabMessage.getCouponId()) > 0) {
            mark(deadMessage, CouponGrabDeadMessage.IGNORED, "DB_ALREADY_SUCCESS");
            return;
        }

        int nextReplayCount = replayCount(deadMessage) + 1;
        if (nextReplayCount < MAX_REPLAY) {
            mark(deadMessage, CouponGrabDeadMessage.PENDING, nextReplayCount, "DB_REPLAY_FAILED");
            return;
        }
        compensateRedis(deadMessage, grabMessage.getCouponId(), grabMessage.getUserId(), nextReplayCount);
    }

    private void compensateRedis(CouponGrabDeadMessage deadMessage, Long couponId, Long userId, int replayCount) {
        try {
            Long rolledBack = stringRedisTemplate.execute(
                    couponGrabRollbackScript,
                    java.util.Arrays.asList(STOCK_KEY_PREFIX + couponId, GRABBED_KEY_PREFIX + couponId),
                    String.valueOf(userId)
            );
            String action = Long.valueOf(1L).equals(rolledBack) ? "REDIS_ROLLBACK" : "REDIS_ALREADY_RELEASED";
            mark(deadMessage, CouponGrabDeadMessage.COMPENSATED, replayCount, action);
        } catch (Exception e) {
            couponRedisCompensationService.record(couponId, userId, "DLQ_REPLAY_EXHAUSTED", e.getMessage());
            mark(deadMessage, CouponGrabDeadMessage.COMPENSATING, replayCount, "REDIS_ROLLBACK_PENDING");
            log.error("coupon dlq redis rollback failed, compensation recorded, deadMessageId={}, couponId={}, userId={}",
                    deadMessage.getId(), couponId, userId, e);
        }
    }

    private void mark(CouponGrabDeadMessage message, Integer status, String action) {
        mark(message, status, replayCount(message), action);
    }

    private void mark(CouponGrabDeadMessage message, Integer status, int replayCount, String action) {
        LocalDateTime now = LocalDateTime.now();
        deadMessageMapper.markHandled(message.getId(), status, replayCount, action, now, now);
    }

    private int replayCount(CouponGrabDeadMessage message) {
        return message.getReplayCount() == null ? 0 : message.getReplayCount();
    }

    private RedisScript<Long> couponGrabRollbackScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/coupon_grab_rollback.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
