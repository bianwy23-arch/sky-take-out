package com.sky.service;

import com.sky.dto.PaidCouponItemDTO;
import com.sky.vo.PaidCouponReservationVO;

import java.util.List;

public interface PaidCouponReservationTxService {

    PaidCouponReservationVO tryReserve(Long userId, String requestId, String canonicalItems,
                                       List<PaidCouponItemDTO> items, int ttlSeconds);

    int replenish(Long couponId, int poolCapacity);

    PaidCouponReservationVO claim(Long userId, String requestId);

    PaidCouponReservationVO release(Long userId, String requestId, boolean requireExpired);

    String currentIsolation();
}
