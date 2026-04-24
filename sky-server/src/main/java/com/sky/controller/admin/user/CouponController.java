package com.sky.controller.admin.user;

import com.sky.context.BaseContext;
import com.sky.entity.UserCoupon;
import com.sky.result.Result;
import com.sky.service.CouponService;
import com.sky.vo.CouponVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController("userCouponController")
@RequestMapping("/user/coupon")
@Api(tags = "C端优惠券接口")
@Slf4j
public class CouponController {

    @Autowired
    private CouponService couponService;

    @GetMapping("/list")
    @ApiOperation("查看可领的优惠券列表")
    public Result<List<CouponVO>> list() {
        Long userId = BaseContext.getCurrentId();
        List<CouponVO> list = couponService.listAvailable(userId);
        return Result.success(list);
    }

    @PostMapping("/grab/{couponId}")
    @ApiOperation("抢券")
    public Result grabCoupon(@PathVariable Long couponId) {
        Long userId = BaseContext.getCurrentId();
        log.info("用户 {} 抢券 {}", userId, couponId);
        couponService.grabCoupon(couponId, userId);
        return Result.success();
    }

    @GetMapping("/my")
    @ApiOperation("查看我的优惠券")
    public Result<List<UserCoupon>> myCoupons() {
        Long userId = BaseContext.getCurrentId();
        List<UserCoupon> list = couponService.myCoupons(userId);
        return Result.success(list);
    }
}
