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
public class OrderEventConsumeLog implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String consumerName;
    private String eventId;
    private String bizKey;
    private String eventType;
    private String payload;
    private LocalDateTime createdTime;
}

