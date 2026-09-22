package com.sky.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaidCouponItemDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long couponId;
    private Integer quantity;
}
