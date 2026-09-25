package com.sky.properties;

import lombok.Data;
import lombok.NoArgsConstructor;
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

    /** Start SKIP LOCKED scans above the last locked unit instead of the coupon's first unit. */
    private boolean scanHintEnabled = true;

    /** Refill in the background once the estimated pool drops below this share; 0 disables. */
    private int refillAheadPercent = 70;

    /**
     * Claims waiting at the same time share one transaction, so the hot inventory
     * rows are updated and committed once per batch instead of once per request.
     */
    private Batch claimBatch = new Batch(2);

    /**
     * Reserves waiting at the same time share one SKIP LOCKED scan and one commit. One
     * worker: two concurrent multi-unit DELETEs near the tail of a coupon's pool were
     * observed to deadlock each other even though their unit sets were disjoint.
     */
    private Batch reserveBatch = new Batch(1);

    @Data
    @NoArgsConstructor
    public static class Batch {
        private boolean enabled = true;
        private int workers = 1;
        private int maxSize = 64;
        /** Requests beyond this backlog run on the caller thread as single requests. */
        private int queueCapacity = 4096;
        /** Claim wait; reserve waits within reserveTimeoutMs instead. */
        private long waitTimeoutMs = 1500L;

        Batch(int workers) {
            this.workers = workers;
        }
    }
}
