package com.sky.service.impl;

import com.sky.entity.CouponRedisCompensation;
import com.sky.mapper.CouponRedisCompensationMapper;
import com.sky.service.CouponRedisCompensationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class CouponRedisCompensationServiceImpl implements CouponRedisCompensationService {

    @Autowired
    private CouponRedisCompensationMapper compensationMapper;

    @Override
    public void record(Long couponId, Long userId, String source, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        compensationMapper.upsert(CouponRedisCompensation.builder()
                .couponId(couponId)
                .userId(userId)
                .source(source)
                .status(CouponRedisCompensation.PENDING)
                .retryCount(0)
                .nextRetryTime(now)
                .lastError(truncate(errorMessage))
                .createdTime(now)
                .updateTime(now)
                .build());
    }

    private String truncate(String message) {
        if (message == null || message.length() <= 500) {
            return message;
        }
        return message.substring(0, 500);
    }
}
