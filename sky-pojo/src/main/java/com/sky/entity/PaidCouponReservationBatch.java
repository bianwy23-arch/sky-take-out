package com.sky.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class PaidCouponReservationBatch implements Serializable {

    private static final long serialVersionUID = 1L;

    private String requestId;
    private Long userId;
    private String items;
    private String state;
    private LocalDateTime expiresAt;
    /** 1 when expires_at <= database NOW() */
    private Integer due;
}
