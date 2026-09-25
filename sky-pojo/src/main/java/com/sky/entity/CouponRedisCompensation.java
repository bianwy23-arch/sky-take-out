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
public class CouponRedisCompensation implements Serializable {

    public static final Integer PENDING = 0;
    public static final Integer SUCCESS = 1;
    public static final Integer FAILED = 2;

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long couponId;
    private Long userId;
    private String source;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryTime;
    private String lastError;
    private LocalDateTime createdTime;
    private LocalDateTime updateTime;
}
