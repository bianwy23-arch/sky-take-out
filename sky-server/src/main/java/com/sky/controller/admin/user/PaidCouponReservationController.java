package com.sky.controller.admin.user;

import com.sky.context.BaseContext;
import com.sky.dto.PaidCouponReserveDTO;
import com.sky.result.Result;
import com.sky.service.PaidCouponReservationService;
import com.sky.vo.PaidCouponReservationVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/paid-coupon/reservations")
@Api(tags = "付费券库存预留")
@Slf4j
public class PaidCouponReservationController {

    @Autowired
    private PaidCouponReservationService reservationService;

    @PostMapping
    @ApiOperation("预留多种付费券")
    public Result<PaidCouponReservationVO> reserve(@RequestBody PaidCouponReserveDTO dto) {
        return Result.success(reservationService.reserve(BaseContext.getCurrentId(), dto));
    }

    @PostMapping("/{requestId}/release")
    @ApiOperation("取消预留")
    public Result<PaidCouponReservationVO> release(@PathVariable String requestId) {
        return Result.success(reservationService.release(BaseContext.getCurrentId(), requestId));
    }

    @GetMapping("/{requestId}")
    @ApiOperation("查询预留")
    public Result<PaidCouponReservationVO> query(@PathVariable String requestId) {
        return Result.success(reservationService.query(BaseContext.getCurrentId(), requestId));
    }
}
