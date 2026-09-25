package com.sky.mq;

import com.sky.document.OrderDocument;
import com.sky.entity.OrderDetail;
import com.sky.entity.OrderEventConsumeLog;
import com.sky.entity.Orders;
import com.sky.mapper.OrderEventConsumeLogMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.stream.Collectors;

@Component
@Slf4j
public class OrderEventMqConsumer {
    private static final String CONSUMER_NAME = "order-event-consumer";
    private static final String ES_INDEX = "sky_orders";

    @Value("${sky.mq.queue}")
    private String queueName;

    @Autowired
    private OrderEventConsumeLogMapper orderEventConsumeLogMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @Autowired
    private ElasticsearchOperations esOperations;

    @Transactional
    @RabbitListener(queues = "${sky.mq.queue}")
    public void consume(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        Object eventIdHeader = message.getMessageProperties().getHeaders().get("eventId");
        String eventId = eventIdHeader == null ? null : String.valueOf(eventIdHeader);
        Object bizKeyHeader = message.getMessageProperties().getHeaders().get("bizKey");
        String bizKey = bizKeyHeader == null ? null : String.valueOf(bizKeyHeader);
        Object eventTypeHeader = message.getMessageProperties().getHeaders().get("eventType");
        String eventType = eventTypeHeader == null ? null : String.valueOf(eventTypeHeader);

        if (!StringUtils.hasText(eventId)) {
            log.warn("mq consume missing eventId, queue={}, routingKey={}, payload={}",
                    queueName, message.getMessageProperties().getReceivedRoutingKey(), body);
            return;
        }

        try {
            orderEventConsumeLogMapper.insert(OrderEventConsumeLog.builder()
                    .consumerName(CONSUMER_NAME)
                    .eventId(eventId)
                    .bizKey(bizKey)
                    .eventType(eventType)
                    .payload(body)
                    .createdTime(LocalDateTime.now())
                    .build());
        } catch (DuplicateKeyException ex) {
            log.info("mq duplicate ignored, consumer={}, eventId={}, bizKey={}, eventType={}",
                    CONSUMER_NAME, eventId, bizKey, eventType);
            return;
        }

        log.info("mq consume success, consumer={}, queue={}, eventId={}, bizKey={}, eventType={}, routingKey={}, payload={}",
                CONSUMER_NAME, queueName, eventId, bizKey, eventType,
                message.getMessageProperties().getReceivedRoutingKey(), body);

        syncOrderToEs(bizKey, eventType);
    }

    /**
     * 从 MySQL 拉取订单最新状态，全量写入 ES sky_orders 索引。
     * bizKey = 订单号（number），通过 getByNumber 广播查询获取完整订单。
     */
    private void syncOrderToEs(String orderNumber, String eventType) {
        if (!StringUtils.hasText(orderNumber)) {
            return;
        }
        try {
            Orders order = orderMapper.getByNumber(orderNumber);
            if (order == null) {
                log.warn("ES sync skipped: order not found, number={}, eventType={}", orderNumber, eventType);
                return;
            }
            List<OrderDetail> details = orderDetailMapper.getByOrderIdAndUserId(order.getId(), order.getUserId());
            String orderDishes = buildOrderDishesStr(details);

            OrderDocument doc = OrderDocument.builder()
                    .id(order.getId())
                    .userId(order.getUserId())
                    .number(order.getNumber())
                    .status(order.getStatus())
                    .payStatus(order.getPayStatus())
                    .phone(order.getPhone())
                    .consignee(order.getConsignee())
                    .address(order.getAddress())
                    .amount(order.getAmount() != null ? order.getAmount().doubleValue() : null)
                    .orderTimeMillis(toEpochMillis(order.getOrderTime()))
                    .cancelReason(order.getCancelReason())
                    .orderDishes(orderDishes)
                    .build();

            ensureIndexExists();
            esOperations.save(doc, IndexCoordinates.of(ES_INDEX));
            log.info("ES sync done, orderId={}, eventType={}", order.getId(), eventType);
        } catch (Exception e) {
            log.error("ES sync failed, orderNumber={}, eventType={}", orderNumber, eventType, e);
            throw e; // 让 MQ 重试
        }
    }

    private String buildOrderDishesStr(List<OrderDetail> details) {
        if (details == null || details.isEmpty()) {
            return "";
        }
        return details.stream()
                .map(d -> d.getName() + "*" + d.getNumber() + ";")
                .collect(Collectors.joining());
    }

    private Long toEpochMillis(LocalDateTime ldt) {
        if (ldt == null) return null;
        return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** 首次消费时确保索引存在（自动建 mapping） */
    private void ensureIndexExists() {
        IndexOperations indexOps = esOperations.indexOps(OrderDocument.class);
        if (!indexOps.exists()) {
            indexOps.createWithMapping();
        }
    }
}
