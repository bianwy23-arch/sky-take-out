package com.sky.service.impl;

import com.sky.service.CouponStockRefreshMarkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class CouponStockRefreshMarkServiceImpl implements CouponStockRefreshMarkService {

    public static final String DIRTY_COUPON_KEY = "coupon:dirty:remaining";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void mark(Long couponId) {
        if (couponId == null) {
            return;
        }
        stringRedisTemplate.opsForSet().add(DIRTY_COUPON_KEY, String.valueOf(couponId));
    }
}
