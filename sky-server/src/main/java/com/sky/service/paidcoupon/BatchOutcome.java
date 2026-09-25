package com.sky.service.paidcoupon;

import com.sky.exception.BaseException;

/** Per-key result of a group transaction. */
public final class BatchOutcome<V> {

    private static final BatchOutcome<?> FALLBACK = new BatchOutcome<>(null, null);

    private final V value;
    /** Client message of the BaseException the single path would have thrown. */
    private final String error;

    private BatchOutcome(V value, String error) {
        this.value = value;
        this.error = error;
    }

    public static <V> BatchOutcome<V> success(V value) {
        return new BatchOutcome<>(value, null);
    }

    public static <V> BatchOutcome<V> failure(String error) {
        return new BatchOutcome<>(null, error);
    }

    /** The batch made no change for this key; the caller runs the single-request path. */
    @SuppressWarnings("unchecked")
    public static <V> BatchOutcome<V> fallback() {
        return (BatchOutcome<V>) FALLBACK;
    }

    public boolean isFallback() {
        return this == FALLBACK;
    }

    public V getValue() {
        return value;
    }

    public String getError() {
        return error;
    }

    V valueOrThrow() {
        if (error != null) {
            throw new BaseException(error);
        }
        return value;
    }
}
