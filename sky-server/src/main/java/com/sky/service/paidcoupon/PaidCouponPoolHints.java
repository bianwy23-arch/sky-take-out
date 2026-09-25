package com.sky.service.paidcoupon;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-JVM, best-effort view of each coupon's available-unit pool. Nothing here is used
 * for correctness: it only chooses where SKIP LOCKED starts and when to refill early.
 */
@Component
public class PaidCouponPoolHints {

    private static final long UNKNOWN = Long.MIN_VALUE;

    private final ConcurrentMap<Long, Pool> pools = new ConcurrentHashMap<>();

    /** Highest unit id this JVM locked; 0 when unknown. */
    public long scanFrom(Long couponId) {
        return pool(couponId).scanFrom.get();
    }

    public void locked(Long couponId, long highestUnitId, int count) {
        Pool pool = pool(couponId);
        pool.scanFrom.accumulateAndGet(highestUnitId, Math::max);
        pool.available.getAndUpdate(v -> v == UNKNOWN ? UNKNOWN : v - count);
    }

    /** Exact pool size seen by replenish under the inventory lock. */
    public void replenished(Long couponId, long availableAfter) {
        pool(couponId).available.set(availableAfter);
    }

    /**
     * Pool size as last seen by replenish minus what this JVM has locked since, or -1 when
     * this JVM has not replenished the coupon yet. Other JVMs' reserves make it optimistic,
     * which only delays the early refill; an empty pool still refills inline.
     */
    public long estimatedAvailable(Long couponId) {
        long available = pool(couponId).available.get();
        return available == UNKNOWN ? -1 : Math.max(0, available);
    }

    /** Single flight per coupon; the caller must call {@link #refillDone} when finished. */
    public boolean tryStartRefill(Long couponId) {
        return pool(couponId).refilling.compareAndSet(false, true);
    }

    public void refillDone(Long couponId) {
        pool(couponId).refilling.set(false);
    }

    private Pool pool(Long couponId) {
        return pools.computeIfAbsent(couponId, k -> new Pool());
    }

    private static final class Pool {
        final AtomicLong scanFrom = new AtomicLong();
        final AtomicLong available = new AtomicLong(UNKNOWN);
        final AtomicBoolean refilling = new AtomicBoolean();
    }
}
