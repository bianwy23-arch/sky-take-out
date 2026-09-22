package com.sky.task;

import com.sky.service.PaidCouponReservationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class PaidCouponReservationExpireTask {

    @Autowired
    private PaidCouponReservationService reservationService;

    @Scheduled(fixedDelayString = "${sky.paid-coupon.expire-scan-delay-ms:10000}",
            initialDelayString = "${sky.paid-coupon.expire-scan-initial-delay-ms:10000}")
    public void scan() {
        int released = reservationService.releaseExpired();
        if (released > 0) {
            log.info("paid coupon expired reservations released: {}", released);
        }
    }
}
