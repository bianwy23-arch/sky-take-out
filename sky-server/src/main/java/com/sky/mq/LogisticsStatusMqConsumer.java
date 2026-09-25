package com.sky.mq;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.entity.Dish;
import com.sky.entity.LogisticsEventConsumeLog;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.mapper.DishMapper;
import com.sky.mapper.LogisticsEventConsumeLogMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
public class LogisticsStatusMqConsumer {

    private static final int LOGISTICS_STATUS_CONFIRMED = 1;
    private static final int LOGISTICS_STATUS_DELIVERING = 2;
    private static final int LOGISTICS_STATUS_DELIVERED = 3;
    private static final int LOGISTICS_STATUS_FAILED = 4;

    @Value("${sky.mq.logistics-queue}")
    private String logisticsQueueName;

    private static final int MAX_OPTIMISTIC_RETRY = 3;

    private static final String ES_ORDER_INDEX = "sky_orders";

    @Autowired
    private LogisticsEventConsumeLogMapper consumeLogMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private ElasticsearchOperations esOperations;

    @Autowired
    private com.sky.websocket.OrderStatusNotifier orderStatusNotifier;

    @Transactional
    @RabbitListener(queues = "${sky.mq.logistics-queue}")
    public void consume(Message message) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        JSONObject jsonObject = JSON.parseObject(payload);
        String eventId = jsonObject.getString("eventId");
        String orderNo = jsonObject.getString("orderNo");
        Integer logisticsStatus = jsonObject.getInteger("logisticsStatus");

        if (!StringUtils.hasText(eventId) || !StringUtils.hasText(orderNo) || logisticsStatus == null) {
            log.warn("logistics mq invalid payload, queue={}, payload={}", logisticsQueueName, payload);
            return;
        }

        try {
            consumeLogMapper.insert(LogisticsEventConsumeLog.builder()
                    .eventId(eventId)
                    .orderNo(orderNo)
                    .logisticsStatus(logisticsStatus)
                    .payload(payload)
                    .createdTime(LocalDateTime.now())
                    .build());
        } catch (DuplicateKeyException ex) {
            log.info("logistics mq duplicate event, eventId={}, orderNo={}", eventId, orderNo);
            return;
        }

        Orders ordersDB = orderMapper.getByNumber(orderNo);
        if (ordersDB == null) {
            log.warn("logistics mq order not found, eventId={}, orderNo={}", eventId, orderNo);
            return;
        }

        Integer targetStatus = toOrderStatus(logisticsStatus);
        if (targetStatus == null) {
            log.warn("logistics mq unknown logisticsStatus, eventId={}, logisticsStatus={}", eventId, logisticsStatus);
            return;
        }

        if (!canTransit(ordersDB.getStatus(), targetStatus)) {
            log.warn("logistics mq invalid transit, orderNo={}, currentStatus={}, targetStatus={}",
                    orderNo, ordersDB.getStatus(), targetStatus);
            return;
        }

        // 物流失败取消时恢复已确认的库存（支付后库存已真实扣减，stock_locked 为 0）
        if (Orders.CANCELLED.equals(targetStatus)) {
            List<OrderDetail> detailList = orderDetailMapper.getByOrderIdAndUserId(ordersDB.getId(), ordersDB.getUserId());
            restoreConfirmedStock(detailList);
        }

        Orders update = new Orders();
        update.setId(ordersDB.getId());
        update.setUserId(ordersDB.getUserId()); // 带上分片键，避免广播查询
        update.setStatus(targetStatus);
        if (Orders.CANCELLED.equals(targetStatus)) {
            update.setCancelReason("物流配送失败");
            update.setCancelTime(LocalDateTime.now());
        }
        if (Orders.COMPLETED.equals(targetStatus)) {
            update.setDeliveryTime(LocalDateTime.now());
        }
        orderMapper.update(update);
        log.info("logistics mq consume success, eventId={}, orderNo={}, targetStatus={}",
                eventId, orderNo, targetStatus);

        // 同步 ES 状态
        syncEsStatus(ordersDB.getId(), targetStatus,
                Orders.CANCELLED.equals(targetStatus) ? "物流配送失败" : null);

        // WebSocket 推送用户
        String wsMsg = toUserMessage(targetStatus);
        orderStatusNotifier.notifyUser(ordersDB.getUserId(), ordersDB.getId(), targetStatus, wsMsg);
    }

    private String toUserMessage(Integer status) {
        if (Orders.CONFIRMED.equals(status)) return "您的订单已被接单";
        if (Orders.DELIVERY_IN_PROGRESS.equals(status)) return "您的订单正在配送中";
        if (Orders.COMPLETED.equals(status)) return "您的订单已完成，感谢您的惠顾";
        if (Orders.CANCELLED.equals(status)) return "您的订单因物流配送失败已取消";
        return "订单状态已更新";
    }

    private void syncEsStatus(Long orderId, Integer status, String cancelReason) {
        try {
            Document doc = Document.create();
            doc.put("status", status);
            if (cancelReason != null) {
                doc.put("cancelReason", cancelReason);
            }
            UpdateQuery updateQuery = UpdateQuery.builder(String.valueOf(orderId))
                    .withDocument(doc)
                    .build();
            esOperations.update(updateQuery, IndexCoordinates.of(ES_ORDER_INDEX));
        } catch (Exception e) {
            log.error("ES status sync failed in logistics consumer, orderId={}, status={}", orderId, status, e);
            throw new IllegalStateException("logistics ES sync failed", e);
        }
    }

    private Integer toOrderStatus(Integer logisticsStatus) {
        if (LOGISTICS_STATUS_CONFIRMED == logisticsStatus) {
            return Orders.CONFIRMED;
        }
        if (LOGISTICS_STATUS_DELIVERING == logisticsStatus) {
            return Orders.DELIVERY_IN_PROGRESS;
        }
        if (LOGISTICS_STATUS_DELIVERED == logisticsStatus) {
            return Orders.COMPLETED;
        }
        if (LOGISTICS_STATUS_FAILED == logisticsStatus) {
            return Orders.CANCELLED;
        }
        return null;
    }

    private void restoreConfirmedStock(List<OrderDetail> detailList) {
        for (OrderDetail detail : detailList) {
            if (detail.getDishId() == null) {
                continue;
            }
            boolean updated = false;
            for (int i = 0; i < MAX_OPTIMISTIC_RETRY; i++) {
                Dish dish = dishMapper.getById(detail.getDishId());
                if (dish == null) {
                    break;
                }
                int rows = dishMapper.restoreConfirmedStock(detail.getDishId(), detail.getNumber(), dish.getVersion());
                if (rows == 1) {
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                log.error("logistics cancel restoreConfirmedStock failed, dishId={}", detail.getDishId());
            }
        }
    }

    private boolean canTransit(Integer currentStatus, Integer targetStatus) {
        if (currentStatus == null || targetStatus == null) {
            return false;
        }
        // 状态相同视为重复事件，拒绝处理（避免重复执行库存恢复等操作）
        if (currentStatus.equals(targetStatus)) {
            return false;
        }
        if (Orders.CONFIRMED.equals(targetStatus)) {
            return Orders.TO_BE_CONFIRMED.equals(currentStatus);
        }
        if (Orders.DELIVERY_IN_PROGRESS.equals(targetStatus)) {
            return Orders.CONFIRMED.equals(currentStatus);
        }
        if (Orders.COMPLETED.equals(targetStatus)) {
            return Orders.DELIVERY_IN_PROGRESS.equals(currentStatus);
        }
        if (Orders.CANCELLED.equals(targetStatus)) {
            return Orders.TO_BE_CONFIRMED.equals(currentStatus)
                    || Orders.CONFIRMED.equals(currentStatus)
                    || Orders.DELIVERY_IN_PROGRESS.equals(currentStatus);
        }
        return false;
    }
}
