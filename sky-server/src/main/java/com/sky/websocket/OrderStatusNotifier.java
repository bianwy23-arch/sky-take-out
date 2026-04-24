package com.sky.websocket;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 订单状态变更推送器，封装 WebSocket 消息构造与发送。
 * 被 OrderServiceImpl 和 LogisticsStatusMqConsumer 调用。
 */
@Component
@Slf4j
public class OrderStatusNotifier {

    /**
     * 向用户推送订单状态变更通知
     *
     * @param userId  目标用户
     * @param orderId 订单 ID
     * @param status  新状态
     * @param message 用户可读的提示信息
     */
    public void notifyUser(Long userId, Long orderId, Integer status, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "ORDER_STATUS");
        payload.put("orderId", orderId);
        payload.put("status", status);
        payload.put("message", message);

        String json = JSON.toJSONString(payload);
        OrderWebSocketServer.sendToUser(userId, json);
        log.debug("Order status pushed, userId={}, orderId={}, status={}", userId, orderId, status);
    }
}
