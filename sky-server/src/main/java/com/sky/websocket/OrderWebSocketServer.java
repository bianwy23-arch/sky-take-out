package com.sky.websocket;

import com.sky.constant.JwtClaimsConstant;
import com.sky.properties.JwtProperties;
import com.sky.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 端点：用户端订单状态实时推送
 *
 * 连接地址：ws://host/ws/order/{token}
 * token 为用户 JWT，握手时校验身份，校验通过后按 userId 维护 session。
 */
@ServerEndpoint("/ws/order/{token}")
@Component
@Slf4j
public class OrderWebSocketServer {

    private static final ConcurrentHashMap<Long, Session> SESSION_MAP = new ConcurrentHashMap<>();

    // @ServerEndpoint 实例由容器创建，非 Spring 管理，需用静态变量注入
    private static JwtProperties jwtProperties;

    @Autowired
    public void setJwtProperties(JwtProperties jwtProperties) {
        OrderWebSocketServer.jwtProperties = jwtProperties;
    }

    private Long userId;

    @OnOpen
    public void onOpen(Session session, @PathParam("token") String token) {
        try {
            Claims claims = JwtUtil.parseJWT(jwtProperties.getUserSecretKey(), token);
            this.userId = Long.valueOf(claims.get(JwtClaimsConstant.USER_ID).toString());
            SESSION_MAP.put(userId, session);
            log.info("WebSocket connected, userId={}", userId);
        } catch (Exception e) {
            log.warn("WebSocket auth failed, closing connection", e);
            try {
                session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "auth failed"));
            } catch (IOException ignored) {
            }
        }
    }

    @OnClose
    public void onClose() {
        if (userId != null) {
            SESSION_MAP.remove(userId);
            log.info("WebSocket closed, userId={}", userId);
        }
    }

    @OnError
    public void onError(Session session, Throwable error) {
        if (userId != null) {
            SESSION_MAP.remove(userId);
        }
        log.error("WebSocket error, userId={}", userId, error);
    }

    @OnMessage
    public void onMessage(String message) {
        // 客户端一般不会发消息，仅做心跳保活用
        log.debug("WebSocket received from userId={}: {}", userId, message);
    }

    /**
     * 向指定用户推送消息
     */
    public static void sendToUser(Long userId, String message) {
        Session session = SESSION_MAP.get(userId);
        if (session != null && session.isOpen()) {
            try {
                session.getBasicRemote().sendText(message);
            } catch (IOException e) {
                log.error("WebSocket send failed, userId={}", userId, e);
            }
        }
    }
}
