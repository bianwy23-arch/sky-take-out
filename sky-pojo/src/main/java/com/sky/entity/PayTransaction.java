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
public class PayTransaction implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String orderNo;
    private String transactionId;
    private String eventType;
    private LocalDateTime createdTime;
    // 分片键：与 orders.user_id 相同，保证路由到同一分片
    private Long userId;
}

