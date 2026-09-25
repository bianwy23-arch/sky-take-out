package com.sky.service.paidcoupon;

import lombok.Value;

@Value
public class PaidCouponClaimKey {
    Long userId;
    String requestId;
}
