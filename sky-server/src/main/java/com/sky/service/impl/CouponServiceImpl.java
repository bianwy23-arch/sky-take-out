package com.sky.service.impl;

import com.sky.dto.CouponCreateDTO;
import com.sky.entity.Coupon;
import com.sky.entity.UserCoupon;
import com.sky.exception.BaseException;
import com.sky.mapper.CouponMapper;
import com.sky.mapper.UserCouponMapper;
import com.sky.service.CouponService;
import com.sky.vo.CouponVO;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CouponServiceImpl implements CouponService {

    private static final String STOCK_KEY_PREFIX = "coupon:stock:";
    private static final String GRABBED_KEY_PREFIX = "coupon:grabbed:";
    private static final String LOCK_KEY_PREFIX = "coupon:lock:";

    @Autowired
    private CouponMapper couponMapper;

    @Autowired
    private UserCouponMapper userCouponMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Override
    public void createCoupon(CouponCreateDTO dto) {
        Coupon coupon = Coupon.builder()
                .name(dto.getName())
                .type(dto.getType())
                .threshold(dto.getThreshold())
                .discount(dto.getDiscount())
                .totalCount(dto.getTotalCount())
                .remainingCount(dto.getTotalCount())
                .status(1)
                .startTime(dto.getStartTime())
                .endTime(dto.getEndTime())
                .createTime(LocalDateTime.now())
                .build();
        couponMapper.insert(coupon);

        // 初始化 Redis 库存
        stringRedisTemplate.opsForValue().set(STOCK_KEY_PREFIX + coupon.getId(), String.valueOf(coupon.getTotalCount()));
        log.info("优惠券创建成功，id={}，库存已初始化到 Redis", coupon.getId());
    }

    @Override
    public List<CouponVO> listAll() {
        return couponMapper.listAll().stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public void endCoupon(Long id) {
        couponMapper.updateStatus(id, 0);
        // 清理 Redis
        stringRedisTemplate.delete(STOCK_KEY_PREFIX + id);
        stringRedisTemplate.delete(GRABBED_KEY_PREFIX + id);
        log.info("优惠券活动已结束，id={}", id);
    }

    @Override
    public List<CouponVO> listAvailable(Long userId) {
        List<Coupon> coupons = couponMapper.listAvailable();
        return coupons.stream().map(c -> {
            CouponVO vo = toVO(c);
            // 检查当前用户是否已领取
            Boolean grabbed = stringRedisTemplate.opsForSet().isMember(GRABBED_KEY_PREFIX + c.getId(), String.valueOf(userId));
            vo.setGrabbed(Boolean.TRUE.equals(grabbed));
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public void grabCoupon(Long couponId, Long userId) {
        // 1. 无锁快速校验
        Coupon coupon = couponMapper.getById(couponId);
        if (coupon == null) {
            throw new BaseException("优惠券不存在");
        }
        if (coupon.getStatus() != 1) {
            throw new BaseException("该优惠券活动已结束");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(coupon.getStartTime()) || now.isAfter(coupon.getEndTime())) {
            throw new BaseException("不在活动时间范围内");
        }
        String stockStr = stringRedisTemplate.opsForValue().get(STOCK_KEY_PREFIX + couponId);
        if (stockStr == null || Integer.parseInt(stockStr) <= 0) {
            throw new BaseException("优惠券已被抢光");
        }

        // 2. Redisson 分布式锁
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + couponId);
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 5, TimeUnit.SECONDS);
            if (!locked) {
                throw new BaseException("系统繁忙，请稍后再试");
            }

            // 3. 锁内操作
            // 检查是否已领取
            Boolean already = stringRedisTemplate.opsForSet().isMember(GRABBED_KEY_PREFIX + couponId, String.valueOf(userId));
            if (Boolean.TRUE.equals(already)) {
                throw new BaseException("您已领取过该优惠券");
            }

            // 扣减库存
            Long newStock = stringRedisTemplate.opsForValue().decrement(STOCK_KEY_PREFIX + couponId);
            if (newStock == null || newStock < 0) {
                // 库存不足，回补
                stringRedisTemplate.opsForValue().increment(STOCK_KEY_PREFIX + couponId);
                throw new BaseException("优惠券已被抢光");
            }

            // 记录已领取
            stringRedisTemplate.opsForSet().add(GRABBED_KEY_PREFIX + couponId, String.valueOf(userId));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BaseException("抢券被中断");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        // 4. 锁外：DB 持久化（带重试，处理瞬时 DB 抖动）
        persistGrab(couponId, userId);

        log.info("用户 {} 成功抢到优惠券 {}", userId, couponId);
    }

    @Override
    public List<UserCoupon> myCoupons(Long userId) {
        return userCouponMapper.listByUserId(userId);
    }

    /**
     * 将领券记录持久化到 DB，失败时指数退避重试最多 3 次。
     * 耗尽后只打 ERROR 日志，由 CouponReconcileTask 定时补偿。
     */
    private void persistGrab(Long couponId, Long userId) {
        UserCoupon userCoupon = UserCoupon.builder()
                .userId(userId)
                .couponId(couponId)
                .status(0)
                .grabTime(LocalDateTime.now())
                .build();
        // 第一步：insert 领券记录（带重试）
        boolean inserted = retryInsert(userCoupon, couponId, userId);
        if (!inserted) {
            return; // insert 失败，等对账任务补偿
        }
        // 第二步：扣减 DB 库存（带重试）
        retryDecrementStock(couponId, userId);
    }

    private boolean retryInsert(UserCoupon userCoupon, Long couponId, Long userId) {
        Exception lastEx = null;
        for (int i = 0; i < 3; i++) {
            try {
                if (userCouponMapper.existsByUserIdAndCouponId(userId, couponId) > 0) {
                    return true; // 上一轮 insert 已成功，跳过
                }
                userCouponMapper.insert(userCoupon);
                return true;
            } catch (Exception e) {
                lastEx = e;
                if (i < 2) {
                    try {
                        Thread.sleep(300L * (1 << i));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.error("coupon grab insert failed after 3 retries, couponId={}, userId={}, will be reconciled",
                couponId, userId, lastEx);
        return false;
    }

    private void retryDecrementStock(Long couponId, Long userId) {
        Exception lastEx = null;
        for (int i = 0; i < 3; i++) {
            try {
                couponMapper.decrementStock(couponId);
                return;
            } catch (Exception e) {
                lastEx = e;
                if (i < 2) {
                    try {
                        Thread.sleep(300L * (1 << i));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.error("coupon decrementStock failed after 3 retries, couponId={}, userId={}, will be reconciled",
                couponId, userId, lastEx);
    }

    private CouponVO toVO(Coupon coupon) {
        CouponVO vo = new CouponVO();
        BeanUtils.copyProperties(coupon, vo);
        return vo;
    }
}
