package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaidCouponInventory implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long couponId;
    private Integer total;
    private Integer remaining;
    private Long nextUnitId;
}
