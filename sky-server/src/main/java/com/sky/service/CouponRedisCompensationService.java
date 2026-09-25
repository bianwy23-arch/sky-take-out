package com.sky.service;

public interface CouponRedisCompensationService {

    void record(Long couponId, Long userId, String source, String errorMessage);
}
