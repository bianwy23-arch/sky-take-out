package com.sky.controller.admin;

import com.sky.dto.CouponCreateDTO;
import com.sky.result.Result;
import com.sky.service.CouponService;
import com.sky.vo.CouponVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/coupon")
@Api(tags = "管理端优惠券接口")
@Slf4j
public class CouponController {

    @Autowired
    private CouponService couponService;

    @PostMapping
    @ApiOperation("创建优惠券活动")
    public Result createCoupon(@RequestBody CouponCreateDTO dto) {
        log.info("创建优惠券活动：{}", dto);
        couponService.createCoupon(dto);
        return Result.success();
    }

    @GetMapping("/list")
    @ApiOperation("查看所有优惠券")
    public Result<List<CouponVO>> list() {
        List<CouponVO> list = couponService.listAll();
        return Result.success(list);
    }

    @PutMapping("/{id}/end")
    @ApiOperation("结束优惠券活动")
    public Result endCoupon(@PathVariable Long id) {
        log.info("结束优惠券活动：{}", id);
        couponService.endCoupon(id);
        return Result.success();
    }
}
