package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxMessage implements Serializable {

    public static final Integer NEW = 0;
    public static final Integer SENT = 1;
    public static final Integer FAILED = 2;

    private static final long serialVersionUID = 1L;

    private Long id;
    private String bizKey;
    private String eventType;
    private String payload;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryTime;
    private String lastError;
    private LocalDateTime createdTime;
    private LocalDateTime updateTime;
    // 分片键：与 orders.user_id 相同，保证路由到同一分片
    private Long userId;
}

