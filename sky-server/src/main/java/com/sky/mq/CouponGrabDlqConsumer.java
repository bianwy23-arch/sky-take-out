package com.sky.mq;

import com.alibaba.fastjson.JSON;
import com.sky.dto.CouponGrabMessage;
import com.sky.entity.CouponGrabDeadMessage;
import com.sky.mapper.CouponGrabDeadMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class CouponGrabDlqConsumer {

    @Autowired
    private CouponGrabDeadMessageMapper deadMessageMapper;

    @RabbitListener(queues = "${sky.mq.coupon-dlq-queue}", containerFactory = "couponDlqRabbitListenerContainerFactory")
    public void consume(Message message) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        CouponGrabMessage grabMessage = null;
        try {
            grabMessage = JSON.parseObject(payload, CouponGrabMessage.class);
        } catch (Exception e) {
            log.warn("coupon dlq payload parse failed, payload={}", payload, e);
        }

        LocalDateTime now = LocalDateTime.now();
        CouponGrabDeadMessage deadMessage = CouponGrabDeadMessage.builder()
                .eventId(resolveEventId(message))
                .couponId(grabMessage == null ? null : grabMessage.getCouponId())
                .userId(grabMessage == null ? null : grabMessage.getUserId())
                .payload(payload)
                .errorType(resolveErrorType(message))
                .errorMessage(resolveErrorMessage(message))
                .status(CouponGrabDeadMessage.PENDING)
                .replayCount(0)
                .handleAction(null)
                .deadTime(now)
                .handleTime(null)
                .createdTime(now)
                .updateTime(now)
                .build();
        try {
            deadMessageMapper.insert(deadMessage);
        } catch (DuplicateKeyException e) {
            log.info("coupon dlq duplicate dead message, eventId={}", deadMessage.getEventId());
            return;
        }
        log.error("coupon grab message moved to dlq, eventId={}, couponId={}, userId={}, errorType={}",
                deadMessage.getEventId(), deadMessage.getCouponId(), deadMessage.getUserId(), deadMessage.getErrorType());
    }

    private String resolveEventId(Message message) {
        Object eventId = message.getMessageProperties().getHeaders().get("eventId");
        if (eventId != null) {
            return String.valueOf(eventId);
        }
        String messageId = message.getMessageProperties().getMessageId();
        return messageId == null ? null : messageId;
    }

    private String resolveErrorType(Message message) {
        Map<String, Object> death = firstDeath(message);
        if (death == null) {
            return "UNKNOWN";
        }
        Object reason = death.get("reason");
        return reason == null ? "UNKNOWN" : String.valueOf(reason);
    }

    private String resolveErrorMessage(Message message) {
        Map<String, Object> death = firstDeath(message);
        if (death == null) {
            return null;
        }
        Object exchange = death.get("exchange");
        Object routingKeys = death.get("routing-keys");
        Object count = death.get("count");
        return "exchange=" + exchange + ", routingKeys=" + routingKeys + ", count=" + count;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstDeath(Message message) {
        Object xDeath = message.getMessageProperties().getHeaders().get("x-death");
        if (!(xDeath instanceof List) || ((List<?>) xDeath).isEmpty()) {
            return null;
        }
        Object first = ((List<?>) xDeath).get(0);
        if (first instanceof Map) {
            return (Map<String, Object>) first;
        }
        return null;
    }
}
