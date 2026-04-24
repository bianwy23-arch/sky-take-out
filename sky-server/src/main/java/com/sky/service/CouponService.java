package com.sky.service;

import com.sky.dto.CouponCreateDTO;
import com.sky.entity.UserCoupon;
import com.sky.vo.CouponVO;

import java.util.List;

public interface CouponService {

    void createCoupon(CouponCreateDTO dto);

    List<CouponVO> listAll();

    void endCoupon(Long id);

    List<CouponVO> listAvailable(Long userId);

    void grabCoupon(Long couponId, Long userId);

    List<UserCoupon> myCoupons(Long userId);
}
