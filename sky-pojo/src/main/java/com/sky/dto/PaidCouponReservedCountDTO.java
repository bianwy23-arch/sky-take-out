package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class PaidCouponReservedCountDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String requestId;
    private Long couponId;
    private Integer quantity;
}
