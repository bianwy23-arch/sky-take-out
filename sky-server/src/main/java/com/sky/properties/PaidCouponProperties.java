package com.sky.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sky.paid-coupon")
@Data
public class PaidCouponProperties {

    /** Available-unit pool cap. Measured parameter, initial value 1000. */
    private int poolCapacity = 1000;

    private int reservationTtlSeconds = 600;

    /**
     * Finite reserve budget. Provisional until a local MySQL baseline replaces it.
     * Not a retry-count policy.
     */
    private long reserveTimeoutMs = 1500L;

    private long expireScanDelayMs = 10000L;

    private long expireScanInitialDelayMs = 10000L;

    private int expireScanBatchSize = 100;

    /** Mock claim and inventory init stay off unless this demo switch is on. */
    private boolean demoEnabled = false;
}
