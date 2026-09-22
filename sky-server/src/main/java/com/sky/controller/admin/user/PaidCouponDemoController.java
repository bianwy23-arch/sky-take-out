package com.sky.controller.admin.user;

import com.sky.context.BaseContext;
import com.sky.dto.PaidCouponInitDTO;
import com.sky.result.Result;
import com.sky.service.PaidCouponReservationService;
import com.sky.vo.PaidCouponReservationVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/paid-coupon/demo")
@ConditionalOnProperty(prefix = "sky.paid-coupon", name = "demo-enabled", havingValue = "true")
@Api(tags = "付费券预留演示")
public class PaidCouponDemoController {

    @Autowired
    private PaidCouponReservationService reservationService;

    @PostMapping("/coupons")
    @ApiOperation("初始化付费券库存并补池")
    public Result init(@RequestBody PaidCouponInitDTO dto) {
        reservationService.initCoupon(dto.getCouponId(), dto.getTotal());
        return Result.success();
    }

    @PostMapping("/reservations/{requestId}/claim")
    @ApiOperation("模拟支付成功并确认预留")
    public Result<PaidCouponReservationVO> claim(@PathVariable String requestId) {
        return Result.success(reservationService.claim(BaseContext.getCurrentId(), requestId));
    }
}
