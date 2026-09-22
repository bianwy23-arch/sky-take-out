package com.sky.service;

import com.sky.dto.PaidCouponReserveDTO;
import com.sky.vo.PaidCouponReservationVO;

public interface PaidCouponReservationService {

    PaidCouponReservationVO reserve(Long userId, PaidCouponReserveDTO dto);

    PaidCouponReservationVO claim(Long userId, String requestId);

    PaidCouponReservationVO release(Long userId, String requestId);

    PaidCouponReservationVO query(Long userId, String requestId);

    void initCoupon(Long couponId, Integer total);

    int releaseExpired();
}
