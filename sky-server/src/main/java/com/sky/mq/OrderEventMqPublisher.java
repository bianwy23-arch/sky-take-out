package com.sky.mq;

import com.sky.entity.OutboxMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

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

    @Autowired
    private RabbitTemplate rabbitTemplate;

    public void publish(OutboxMessage message) {
        String routingKey = toRoutingKey(message.getEventType());
        org.springframework.amqp.rabbit.connection.CorrelationData correlationData =
                new org.springframework.amqp.rabbit.connection.CorrelationData("outbox-" + message.getId());

        rabbitTemplate.convertAndSend(exchangeName, routingKey, message.getPayload(), msg -> {
            msg.getMessageProperties().setHeader("eventId", String.valueOf(message.getId()));
            msg.getMessageProperties().setHeader("bizKey", message.getBizKey());
            msg.getMessageProperties().setHeader("eventType", message.getEventType());
            return msg;
        }, correlationData);

        try {
            org.springframework.amqp.rabbit.connection.CorrelationData.Confirm confirm =
                    correlationData.getFuture().get(3, TimeUnit.SECONDS);
            if (confirm == null || !confirm.isAck()) {
                String reason = confirm == null ? "confirm timeout" : confirm.getReason();
                throw new IllegalStateException("mq publish not ack, outboxId=" + message.getId() + ", reason=" + reason);
            }
        } catch (Exception ex) {
            throw new RuntimeException("mq publish failed, outboxId=" + message.getId(), ex);
        }
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
        throw new IllegalArgumentException("unsupported eventType: " + eventType);
    }
}
