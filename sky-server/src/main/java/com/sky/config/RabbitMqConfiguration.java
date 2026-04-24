package com.sky.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

@Configuration
@Slf4j
public class RabbitMqConfiguration {

    @Value("${sky.mq.exchange}")
    private String exchangeName;

    @Value("${sky.mq.queue}")
    private String queueName;

    @Value("${sky.mq.logistics-queue}")
    private String logisticsQueueName;

    @Value("${sky.mq.routing-key-created}")
    private String createdRoutingKey;

    @Value("${sky.mq.routing-key-paid}")
    private String paidRoutingKey;

    @Value("${sky.mq.routing-key-cancelled}")
    private String cancelledRoutingKey;

    @Value("${sky.mq.routing-key-logistics-status}")
    private String logisticsStatusRoutingKey;

    @Bean
    public TopicExchange orderEventExchange() {
        return new TopicExchange(exchangeName, true, false);
    }

    @Bean
    public Queue orderEventQueue() {
        return new Queue(queueName, true);
    }

    @Bean
    public Queue logisticsEventQueue() {
        return new Queue(logisticsQueueName, true);
    }

    @Bean
    public Binding createdBinding(Queue orderEventQueue, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(orderEventQueue).to(orderEventExchange).with(createdRoutingKey);
    }

    @Bean
    public Binding paidBinding(Queue orderEventQueue, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(orderEventQueue).to(orderEventExchange).with(paidRoutingKey);
    }

    @Bean
    public Binding cancelledBinding(Queue orderEventQueue, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(orderEventQueue).to(orderEventExchange).with(cancelledRoutingKey);
    }

    @Bean
    public Binding logisticsStatusBinding(Queue logisticsEventQueue, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(logisticsEventQueue).to(orderEventExchange).with(logisticsStatusRoutingKey);
    }

    @Bean
    @DependsOn("orderEventExchange")
    public Object rabbitTemplateCallbacks(RabbitTemplate rabbitTemplate) {
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                String id = correlationData == null ? "null" : correlationData.getId();
                log.error("mq publish nack, correlationId={}, cause={}", id, cause);
            }
        });
        rabbitTemplate.setReturnsCallback(returned -> log.error(
                "mq publish returned, exchange={}, routingKey={}, replyCode={}, replyText={}, body={}",
                returned.getExchange(),
                returned.getRoutingKey(),
                returned.getReplyCode(),
                returned.getReplyText(),
                new String(returned.getMessage().getBody())
        ));
        return new Object();
    }
}
