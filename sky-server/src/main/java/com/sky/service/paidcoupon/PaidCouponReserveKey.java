package com.sky.service.paidcoupon;

import com.sky.dto.PaidCouponItemDTO;
import lombok.Value;

import java.util.List;

@Value
public class PaidCouponReserveKey {
    Long userId;
    String requestId;
    String canonicalItems;
    /** Normalized: merged per coupon and sorted by couponId. */
    List<PaidCouponItemDTO> items;
}
