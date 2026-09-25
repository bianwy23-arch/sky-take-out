package com.sky.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import com.sky.mq.OutboxPublishTracker;
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

    @Value("${sky.mq.coupon-queue}")
    private String couponQueueName;

    @Value("${sky.mq.coupon-dlx-exchange}")
    private String couponDlxExchangeName;

    @Value("${sky.mq.coupon-dlq-queue}")
    private String couponDlqQueueName;

    @Value("${sky.mq.routing-key-created}")
    private String createdRoutingKey;

    @Value("${sky.mq.routing-key-paid}")
    private String paidRoutingKey;

    @Value("${sky.mq.routing-key-cancelled}")
    private String cancelledRoutingKey;

    @Value("${sky.mq.routing-key-logistics-status}")
    private String logisticsStatusRoutingKey;

    @Value("${sky.mq.routing-key-coupon-grab}")
    private String couponGrabRoutingKey;

    @Value("${sky.mq.routing-key-coupon-dead}")
    private String couponDeadRoutingKey;

    @Value("${sky.mq.coupon-consumer.concurrent-consumers}")
    private Integer couponConcurrentConsumers;

    @Value("${sky.mq.coupon-consumer.max-concurrent-consumers}")
    private Integer couponMaxConcurrentConsumers;

    @Value("${sky.mq.coupon-consumer.prefetch-count}")
    private Integer couponPrefetchCount;

    @Bean
    public TopicExchange orderEventExchange() {
        return new TopicExchange(exchangeName, true, false);
    }

    @Bean
    public DirectExchange couponGrabDlxExchange() {
        return new DirectExchange(couponDlxExchangeName, true, false);
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
    public Queue couponGrabQueue() {
        return QueueBuilder.durable(couponQueueName)
                .withArgument("x-dead-letter-exchange", couponDlxExchangeName)
                .withArgument("x-dead-letter-routing-key", couponDeadRoutingKey)
                .build();
    }

    @Bean
    public Queue couponGrabDlqQueue() {
        return QueueBuilder.durable(couponDlqQueueName).build();
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
    public Binding couponGrabBinding(Queue couponGrabQueue, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(couponGrabQueue).to(orderEventExchange).with(couponGrabRoutingKey);
    }

    @Bean
    public Binding couponGrabDeadBinding(Queue couponGrabDlqQueue, DirectExchange couponGrabDlxExchange) {
        return BindingBuilder.bind(couponGrabDlqQueue).to(couponGrabDlxExchange).with(couponDeadRoutingKey);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory couponRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setConcurrentConsumers(couponConcurrentConsumers);
        factory.setMaxConcurrentConsumers(couponMaxConcurrentConsumers);
        factory.setPrefetchCount(couponPrefetchCount);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(500, 2.0, 5000)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        return factory;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory couponDlqRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    @Bean
    @DependsOn("orderEventExchange")
    public Object rabbitTemplateCallbacks(RabbitTemplate rabbitTemplate, OutboxPublishTracker outboxPublishTracker) {
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            String id = correlationData == null ? "null" : correlationData.getId();
            outboxPublishTracker.onConfirm(id, ack, cause);
            if (!ack) {
                log.error("mq publish nack, correlationId={}, cause={}", id, cause);
            }
        });
        rabbitTemplate.setReturnsCallback(returned -> {
            String correlationId = returned.getMessage().getMessageProperties().getHeader("spring_returned_message_correlation");
            outboxPublishTracker.onReturn(correlationId, returned);
            log.error(
                    "mq publish returned, correlationId={}, exchange={}, routingKey={}, replyCode={}, replyText={}, body={}",
                    correlationId,
                    returned.getExchange(),
                    returned.getRoutingKey(),
                    returned.getReplyCode(),
                    returned.getReplyText(),
                    new String(returned.getMessage().getBody())
            );
        });
        return new Object();
    }
}
