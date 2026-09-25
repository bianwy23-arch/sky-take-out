package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.sky.dto.CouponCreateDTO;
import com.sky.dto.CouponGrabMessage;
import com.sky.entity.OutboxMessage;
import com.sky.entity.Coupon;
import com.sky.entity.UserCoupon;
import com.sky.exception.BaseException;
import com.sky.mapper.CouponMapper;
import com.sky.mapper.OutboxMessageMapper;
import com.sky.mapper.UserCouponMapper;
import com.sky.service.CouponRedisCompensationService;
import com.sky.service.CouponService;
import com.sky.vo.CouponVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CouponServiceImpl implements CouponService {

    private static final String STOCK_KEY_PREFIX = "coupon:stock:";
    private static final String GRABBED_KEY_PREFIX = "coupon:grabbed:";
    private static final int OUTBOX_INSERT_MAX_ATTEMPTS = 3;
    private static final Long GRAB_SUCCESS = 0L;
    private static final Long GRAB_STOCK_EMPTY = 1L;
    private static final Long GRAB_ALREADY_EXISTS = 2L;

    @Autowired
    private CouponMapper couponMapper;

    @Autowired
    private UserCouponMapper userCouponMapper;

    @Autowired
    private OutboxMessageMapper outboxMessageMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CouponRedisCompensationService couponRedisCompensationService;

    private final RedisScript<Long> couponGrabScript = couponGrabScript();
    private final RedisScript<Long> couponGrabRollbackScript = couponGrabRollbackScript();

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
        // 1. 活动校验
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

        // 2. Redis Lua 原子完成：判重、判断库存、预扣库存、记录用户
        Long result = stringRedisTemplate.execute(
                couponGrabScript,
                Arrays.asList(STOCK_KEY_PREFIX + couponId, GRABBED_KEY_PREFIX + couponId),
                String.valueOf(userId)
        );
        if (GRAB_ALREADY_EXISTS.equals(result)) {
            throw new BaseException("您已领取过该优惠券");
        }
        if (GRAB_STOCK_EMPTY.equals(result)) {
            throw new BaseException("优惠券已被抢光");
        }
        if (!GRAB_SUCCESS.equals(result)) {
            throw new BaseException("抢券失败，请稍后再试");
        }

        // 3. 写 outbox，后续由 OutboxDispatchTask 可靠投递 MQ。
        try {
            LocalDateTime messageTime = LocalDateTime.now();
            CouponGrabMessage grabMessage = CouponGrabMessage.builder()
                    .couponId(couponId)
                    .userId(userId)
                    .grabTime(messageTime)
                    .build();
            OutboxMessage outboxMessage = OutboxMessage.builder()
                    .userId(userId)
                    .bizKey(couponId + ":" + userId)
                    .eventType("COUPON_GRAB")
                    .payload(JSON.toJSONString(grabMessage))
                    .status(OutboxMessage.NEW)
                    .retryCount(0)
                    .nextRetryTime(messageTime)
                    .lastError(null)
                    .createdTime(messageTime)
                    .updateTime(messageTime)
                    .build();
            insertOutboxWithRetry(outboxMessage, couponId, userId);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.info("coupon grab outbox duplicate, couponId={}, userId={}", couponId, userId);
        } catch (Exception e) {
            rollbackRedisGrab(couponId, userId);
            log.error("coupon grab outbox insert failed, couponId={}, userId={}", couponId, userId, e);
            throw new BaseException("系统繁忙，请稍后再试");
        }

        log.info("用户 {} 抢券请求已受理，优惠券 {}", userId, couponId);
    }

    @Override
    public List<UserCoupon> myCoupons(Long userId) {
        return userCouponMapper.listByUserId(userId);
    }

    private void rollbackRedisGrab(Long couponId, Long userId) {
        try {
            Long rolledBack = stringRedisTemplate.execute(
                    couponGrabRollbackScript,
                    Arrays.asList(STOCK_KEY_PREFIX + couponId, GRABBED_KEY_PREFIX + couponId),
                    String.valueOf(userId)
            );
            log.info("coupon grab redis rollback, couponId={}, userId={}, rolledBack={}", couponId, userId, rolledBack);
        } catch (Exception e) {
            couponRedisCompensationService.record(couponId, userId, "OUTBOX_INSERT_FAILED", e.getMessage());
            log.error("coupon grab redis rollback failed, compensation recorded, couponId={}, userId={}", couponId, userId, e);
        }
    }

    private void insertOutboxWithRetry(OutboxMessage outboxMessage, Long couponId, Long userId) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= OUTBOX_INSERT_MAX_ATTEMPTS; attempt++) {
            try {
                outboxMessageMapper.insert(outboxMessage);
                return;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw e;
            } catch (RuntimeException e) {
                lastException = e;
                log.warn("coupon grab outbox insert retry, couponId={}, userId={}, attempt={}",
                        couponId, userId, attempt, e);
            }
        }
        throw lastException == null ? new IllegalStateException("outbox insert failed") : lastException;
    }

    private RedisScript<Long> couponGrabScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/coupon_grab.lua")));
        script.setResultType(Long.class);
        return script;
    }

    private RedisScript<Long> couponGrabRollbackScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/coupon_grab_rollback.lua")));
        script.setResultType(Long.class);
        return script;
    }

    private CouponVO toVO(Coupon coupon) {
        CouponVO vo = new CouponVO();
        BeanUtils.copyProperties(coupon, vo);
        return vo;
    }
}
