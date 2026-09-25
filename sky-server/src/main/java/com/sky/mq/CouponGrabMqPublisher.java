package com.sky.mq;

import com.alibaba.fastjson.JSON;
import com.sky.dto.CouponGrabMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CouponGrabMqPublisher {

    @Value("${sky.mq.exchange}")
    private String exchangeName;

    @Value("${sky.mq.routing-key-coupon-grab}")
    private String couponGrabRoutingKey;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    public void publish(CouponGrabMessage message) {
        rabbitTemplate.convertAndSend(exchangeName, couponGrabRoutingKey, JSON.toJSONString(message));
    }
}
