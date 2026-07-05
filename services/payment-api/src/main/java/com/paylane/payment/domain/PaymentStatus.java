package com.paylane.payment.domain;

import java.util.Set;

/**
 * Payment lifecycle. The legal transitions live here so the state machine is one readable table,
 * and both the aggregate and any reader can ask {@link #canTransitionTo}.
 *
 * <pre>
 *   CREATED ──▶ AUTHORIZED ──▶ CAPTURED ──▶ SETTLED
 *      │            │
 *      └────────────┴──▶ FAILED
 * </pre>
 */
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
