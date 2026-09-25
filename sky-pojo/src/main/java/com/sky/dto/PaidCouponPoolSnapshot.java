package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class PaidCouponPoolSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long couponId;
    private Integer remaining;
    private Integer total;
    private Integer availableCount;
    private Integer reservedCount;
}
