package com.sky.service.paidcoupon;

import com.sky.dto.PaidCouponItemDTO;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import javax.annotation.PostConstruct;
import java.util.List;

/** Opt-in, single-coupon experiment. Never changes global/session pool defaults. */
@Component
@ConfigurationProperties(prefix = "sky.paid-coupon.lock-lab")
@Data
@Slf4j
public class PaidCouponLockLab {
    private boolean enabled = false;
    private Long couponId;
    private String isolation = "RC";
    private long pauseMs = 0;
    private boolean insertBeforeDelete = false;
    private long writePauseMs = 0;

    @PostConstruct
    public void validate() {
        if (enabled && (couponId == null || couponId <= 0 || writePauseMs < 0 || writePauseMs > 30000 || pauseMs < 0 || pauseMs > 30000
                || !("RR".equals(isolation) || "RC".equals(isolation)))) {
            throw new IllegalArgumentException("lock-lab requires coupon-id > 0, isolation RC/RR and pause-ms/write-pause-ms 0..30000");
        }
    }

    public boolean matches(List<PaidCouponItemDTO> items) {
        return enabled && items.size() == 1 && couponId != null && couponId.equals(items.get(0).getCouponId());
    }

    public int isolationFor(List<PaidCouponItemDTO> items) {
        return matches(items) && "RR".equals(isolation)
                ? TransactionDefinition.ISOLATION_REPEATABLE_READ : TransactionDefinition.ISOLATION_READ_COMMITTED;
    }

    public boolean reverseWritesFor(List<PaidCouponItemDTO> items) {
        return matches(items) && insertBeforeDelete;
    }

    public void pauseAfterFirstWrite(List<PaidCouponItemDTO> items, String requestId, String actualIsolation) {
        if (!matches(items) || writePauseMs == 0) {
            return;
        }
        log.warn("LOCK_ORDER_PAUSE requestId={} couponId={} isolation={} firstWrite={} pauseMs={}",
                requestId, couponId, actualIsolation,
                insertBeforeDelete ? "INSERT_RESERVED" : "DELETE_AVAILABLE", writePauseMs);
        try {
            Thread.sleep(writePauseMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Lock order experiment interrupted; rollback reservation", ex);
        } finally {
            log.warn("LOCK_ORDER_RESUME requestId={} couponId={}", requestId, couponId);
        }
    }

    public void pauseAfterEmptyRead(List<PaidCouponItemDTO> items, String requestId, String actualIsolation) {
        if (!matches(items) || pauseMs == 0) {
            return;
        }
        log.warn("LOCK_LAB_PAUSE requestId={} couponId={} isolation={} pauseMs={}",
                requestId, couponId, actualIsolation, pauseMs);
        try {
            Thread.sleep(pauseMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Lock experiment interrupted; rollback reservation", ex);
        } finally {
            log.warn("LOCK_LAB_RESUME requestId={} couponId={}", requestId, couponId);
        }
    }
}
