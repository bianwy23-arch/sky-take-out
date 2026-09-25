package com.sky.controller.admin.user;

import com.sky.exception.BaseException;
import com.sky.properties.PaidCouponProperties;
import com.sky.result.Result;
import com.sky.service.PaidCouponReservationTxService;
import com.sky.service.paidcoupon.PaidCouponLockLab;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** Uses the existing user-path authentication interceptor. */
@RestController
@RequestMapping("/user/paid-coupon/lock-lab")
@ConditionalOnProperty(prefix = "sky.paid-coupon.lock-lab", name = "enabled", havingValue = "true")
public class PaidCouponLockLabController {
    @Autowired private PaidCouponLockLab lab;
    @Autowired private PaidCouponProperties properties;
    @Autowired private PaidCouponReservationTxService transactions;

    @PostMapping("/coupons/{couponId}/replenish")
    public Result<Integer> replenish(@PathVariable Long couponId) {
        if (!couponId.equals(lab.getCouponId())) {
            throw new BaseException("只能补充实验配置指定的测试券");
        }
        return Result.success(transactions.replenish(couponId, properties.getPoolCapacity()));
    }
}
