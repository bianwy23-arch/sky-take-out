package com.sky.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaidCouponReservedUnitDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String requestId;
    private Long couponId;
    private Long unitId;
}
