package com.sky.service;

import com.sky.dto.PaidCouponItemDTO;
import com.sky.service.paidcoupon.BatchOutcome;
import com.sky.service.paidcoupon.PaidCouponClaimKey;
import com.sky.service.paidcoupon.PaidCouponReserveKey;
import com.sky.vo.PaidCouponReservationVO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface PaidCouponReservationTxService {

    PaidCouponReservationVO tryReserve(Long userId, String requestId, String canonicalItems,
                                       List<PaidCouponItemDTO> items, int ttlSeconds);

    int replenish(Long couponId, int poolCapacity);

    PaidCouponReservationVO claim(Long userId, String requestId);

    /**
     * Claims several requests in one transaction. Per-request rejections are returned as
     * outcomes; a ledger shortfall throws and rolls back the whole batch.
     */
    Map<PaidCouponClaimKey, BatchOutcome<PaidCouponReservationVO>> claimBatch(Collection<PaidCouponClaimKey> keys);

    /**
     * Reserves several requests with one SKIP LOCKED scan per coupon and one commit.
     * Requests the batch cannot fully decide come back as fallback with no change made.
     */
    Map<PaidCouponReserveKey, BatchOutcome<PaidCouponReservationVO>> reserveBatch(
            Collection<PaidCouponReserveKey> keys, int ttlSeconds);

    PaidCouponReservationVO release(Long userId, String requestId, boolean requireExpired);

    String currentIsolation();
}
