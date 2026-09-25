package com.sky.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** One SKIP LOCKED branch: up to quantity units of couponId within [fromUnitId, belowUnitId). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaidCouponUnitScanDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long couponId;
    private Integer quantity;
    /** Inclusive lower bound, null for unbounded. */
    private Long fromUnitId;
    /** Exclusive upper bound, null for unbounded. */
    private Long belowUnitId;
}
