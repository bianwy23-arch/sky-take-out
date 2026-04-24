package com.sky.mq;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LogisticsEventMqPublisher {

    @Value("${sky.mq.exchange}")
    private String exchangeName;

    @Value("${sky.mq.routing-key-logistics-status}")
    private String logisticsStatusRoutingKey;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    public void publish(String payload) {
        rabbitTemplate.convertAndSend(exchangeName, logisticsStatusRoutingKey, payload);
    }
}

