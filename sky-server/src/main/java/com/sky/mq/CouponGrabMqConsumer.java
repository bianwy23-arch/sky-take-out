package com.sky.mq;

import com.alibaba.fastjson.JSON;
import com.sky.dto.CouponGrabMessage;
import com.sky.entity.UserCoupon;
import com.sky.mapper.UserCouponMapper;
import com.sky.service.CouponStockRefreshMarkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Component
@Slf4j
public class CouponGrabMqConsumer {

    @Autowired
    private UserCouponMapper userCouponMapper;

    @Autowired
    private CouponStockRefreshMarkService couponStockRefreshMarkService;

    @Transactional
    @RabbitListener(queues = "${sky.mq.coupon-queue}", containerFactory = "couponRabbitListenerContainerFactory")
    public void consume(Message message) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        CouponGrabMessage grabMessage = JSON.parseObject(payload, CouponGrabMessage.class);
        if (grabMessage == null || grabMessage.getUserId() == null || grabMessage.getCouponId() == null) {
            log.warn("coupon grab mq invalid payload, payload={}", payload);
            return;
        }

        Long userId = grabMessage.getUserId();
        Long couponId = grabMessage.getCouponId();
        LocalDateTime grabTime = grabMessage.getGrabTime() == null ? LocalDateTime.now() : grabMessage.getGrabTime();

        try {
            userCouponMapper.insert(UserCoupon.builder()
                    .userId(userId)
                    .couponId(couponId)
                    .status(0)
                    .grabTime(grabTime)
                    .build());
        } catch (DuplicateKeyException e) {
            log.info("coupon grab duplicate message, couponId={}, userId={}", couponId, userId);
            return;
        }

        couponStockRefreshMarkService.mark(couponId);

        log.info("coupon grab consumed, couponId={}, userId={}", couponId, userId);
    }
}
