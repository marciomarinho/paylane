package com.paylane.paymentreactive.domain;

public class IllegalStateTransitionException extends RuntimeException {
    public IllegalStateTransitionException(PaymentStatus from, PaymentStatus to) {
        super("illegal payment transition: " + from + " -> " + to);
    }
}
