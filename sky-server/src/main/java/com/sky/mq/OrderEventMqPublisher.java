package com.sky.mq;

import com.sky.entity.OutboxMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OrderEventMqPublisher {

    @Value("${sky.mq.exchange}")
    private String exchangeName;

    @Value("${sky.mq.routing-key-created}")
    private String createdRoutingKey;

    @Value("${sky.mq.routing-key-paid}")
    private String paidRoutingKey;

    @Value("${sky.mq.routing-key-cancelled}")
    private String cancelledRoutingKey;

    @Value("${sky.mq.routing-key-coupon-grab}")
    private String couponGrabRoutingKey;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OutboxPublishTracker outboxPublishTracker;

    public String publish(OutboxMessage message) {
        String routingKey = toRoutingKey(message.getEventType());
        String correlationId = "outbox-" + message.getId();
        org.springframework.amqp.rabbit.connection.CorrelationData correlationData =
                new org.springframework.amqp.rabbit.connection.CorrelationData(correlationId);
        outboxPublishTracker.register(correlationId, message.getId(), message.getUserId());

        rabbitTemplate.convertAndSend(exchangeName, routingKey, message.getPayload(), msg -> {
            msg.getMessageProperties().setHeader("eventId", String.valueOf(message.getId()));
            msg.getMessageProperties().setHeader("bizKey", message.getBizKey());
            msg.getMessageProperties().setHeader("eventType", message.getEventType());
            return msg;
        }, correlationData);
        return correlationId;
    }

    private String toRoutingKey(String eventType) {
        if ("ORDER_CREATED".equals(eventType)) {
            return createdRoutingKey;
        }
        if ("ORDER_PAID".equals(eventType)) {
            return paidRoutingKey;
        }
        if ("ORDER_CANCELLED".equals(eventType)) {
            return cancelledRoutingKey;
        }
        if ("COUPON_GRAB".equals(eventType)) {
            return couponGrabRoutingKey;
        }
        throw new IllegalArgumentException("unsupported eventType: " + eventType);
    }
}
