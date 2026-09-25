package com.sky.service.paidcoupon;

import com.sky.properties.PaidCouponProperties;
import com.sky.service.PaidCouponReservationTxService;
import com.sky.vo.PaidCouponReservationVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Optional;

/** Group commit for reserve and claim; empty results mean "run the single-request path". */
@Component
public class PaidCouponBatchers {

    public static final String CLAIM = "claim";
    public static final String RESERVE = "reserve";

    @Autowired
    private PaidCouponReservationTxService txService;

    @Autowired
    private PaidCouponProperties properties;

    private GroupCommitQueue<PaidCouponClaimKey, PaidCouponReservationVO> claims;

    private GroupCommitQueue<PaidCouponReserveKey, PaidCouponReservationVO> reserves;

    @PostConstruct
    public void start() {
        claims = new GroupCommitQueue<>(CLAIM, properties.getClaimBatch(), txService::claimBatch);
        reserves = new GroupCommitQueue<>(RESERVE, properties.getReserveBatch(),
                keys -> txService.reserveBatch(keys, properties.getReservationTtlSeconds()));
        claims.start();
        reserves.start();
    }

    @PreDestroy
    public void stop() {
        claims.stop();
        reserves.stop();
    }

    public Optional<PaidCouponReservationVO> claim(Long userId, String requestId) {
        return claims.submit(new PaidCouponClaimKey(userId, requestId));
    }

    /** Waits within the caller's reserve budget; the single path then gets whatever is left. */
    public Optional<PaidCouponReservationVO> reserve(PaidCouponReserveKey key, long timeoutMs) {
        return reserves.submit(key, timeoutMs);
    }
}
