package com.paylane.paymentreactive.domain;

import java.util.Set;

/** Payment lifecycle — identical to the MVC twin's state machine. */
public enum PaymentStatus {
    CREATED,
    AUTHORIZED,
    CAPTURED,
    SETTLED,
    FAILED;

    static {
        CREATED.allowed = Set.of(AUTHORIZED, FAILED);
        AUTHORIZED.allowed = Set.of(CAPTURED, FAILED);
        CAPTURED.allowed = Set.of(SETTLED);
        SETTLED.allowed = Set.of();
        FAILED.allowed = Set.of();
    }

    private Set<PaymentStatus> allowed;

    public boolean canTransitionTo(PaymentStatus target) {
        return allowed.contains(target);
    }

    public boolean isTerminal() {
        return allowed.isEmpty();
    }
}
