package com.sky.task;

import com.sky.entity.UserCoupon;
import com.sky.mapper.CouponMapper;
import com.sky.mapper.UserCouponMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 优惠券 Redis-DB 一致性对账任务
 *
 * 兜底 persistGrab 重试耗尽的场景：
 * 每 5 分钟扫 Redis coupon:grabbed:* 所有 Set，
 * 对每个 (couponId, userId) 检查 DB 是否存在记录，不存在则补写。
 */
@Component
@Slf4j
public class CouponReconcileTask {

    private static final String GRABBED_KEY_PREFIX = "coupon:grabbed:";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UserCouponMapper userCouponMapper;

    @Autowired
    private CouponMapper couponMapper;

    @Scheduled(fixedDelay = 300_000) // 每 5 分钟
    public void reconcile() {
        log.info("[CouponReconcileTask] 开始对账");
        int fixed = 0;

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

                boolean anyFixed = false;
                for (String userIdStr : members) {
                    try {
                        Long userId = Long.parseLong(userIdStr);
                        if (userCouponMapper.existsByUserIdAndCouponId(userId, couponId) == 0) {
                            // DB 缺失，补写领券记录
                            userCouponMapper.insert(UserCoupon.builder()
                                    .userId(userId)
                                    .couponId(couponId)
                                    .status(0)
                                    .grabTime(LocalDateTime.now())
                                    .build());
                            anyFixed = true;
                            fixed++;
                            log.info("[CouponReconcileTask] 补写领券记录，couponId={}, userId={}", couponId, userId);
                        }
                    } catch (DuplicateKeyException e) {
                        // 并发补写，忽略
                    } catch (Exception e) {
                        log.error("[CouponReconcileTask] 补写失败，couponId={}, userId={}", couponId, userIdStr, e);
                    }
                }
                // 每个 coupon 处理完后，用 DB count 统一校准 remaining_count
                // 同时兜底 insert 成功但 decrementStock 失败的情况
                if (anyFixed) {
                    try {
                        couponMapper.fixRemainingCount(couponId);
                    } catch (Exception e) {
                        log.error("[CouponReconcileTask] fixRemainingCount 失败，couponId={}", couponId, e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[CouponReconcileTask] 对账异常", e);
        }

        log.info("[CouponReconcileTask] 对账结束，共补写 {} 条", fixed);
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
