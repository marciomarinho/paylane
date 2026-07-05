package com.paylane.payment.domain;

public class IllegalStateTransitionException extends RuntimeException {
    public IllegalStateTransitionException(PaymentStatus from, PaymentStatus to) {
        super("illegal payment transition: " + from + " -> " + to);
    }
}
