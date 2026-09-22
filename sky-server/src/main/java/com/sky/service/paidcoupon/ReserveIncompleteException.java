package com.sky.service.paidcoupon;

/**
 * Signals that the current reserve transaction must roll back before replenish or retry.
 */
public class ReserveIncompleteException extends RuntimeException {

    public ReserveIncompleteException() {
        super("reserve incomplete", null, false, false);
    }
}
