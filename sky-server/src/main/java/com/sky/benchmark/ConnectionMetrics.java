package com.sky.benchmark;

import com.sky.service.paidcoupon.GroupCommitQueue;
import com.sky.service.paidcoupon.PaidCouponBatchers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;

public final class ConnectionMetrics {
    public static final ThreadLocal<String> CALLER = new ThreadLocal<>();
    private static final Map<String, Metric> METRICS = new ConcurrentHashMap<>();
    private ConnectionMetrics() {}
    public static void record(String kind, long nanos) {
        String caller = CALLER.get();
        if (caller == null) {
            String thread = Thread.currentThread().getName();
            caller = thread.startsWith(GroupCommitQueue.threadPrefix(PaidCouponBatchers.CLAIM)) ? "claimBatch"
                    : thread.startsWith(GroupCommitQueue.threadPrefix(PaidCouponBatchers.RESERVE)) ? "reserveBatch"
                    : "background";
        }
        METRICS.computeIfAbsent(caller + "." + kind, k -> new Metric()).add(nanos);
    }
    public static Map<String, Object> snapshot() {
        Map<String, Object> result = new TreeMap<>();
        METRICS.forEach((k,v) -> result.put(k, v.snapshot()));
        return result;
    }
    private static final class Metric {
        final LongAdder count = new LongAdder(), total = new LongAdder();
        final AtomicLong max = new AtomicLong();
        final AtomicLongArray buckets = new AtomicLongArray(32);
        void add(long value) {
            count.increment(); total.add(value); max.accumulateAndGet(value, Math::max);
            long micros = Math.max(1, value / 1000);
            buckets.incrementAndGet(Math.min(31, 64 - Long.numberOfLeadingZeros(micros)));
        }
        Map<String,Object> snapshot() {
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("count",count.sum()); result.put("totalNanos",total.sum()); result.put("maxNanos",max.get());
            long[] counts = new long[32];
            for(int i=0;i<32;i++) counts[i]=buckets.get(i);
            result.put("log2MicrosBuckets",counts); return result;
        }
    }
}
