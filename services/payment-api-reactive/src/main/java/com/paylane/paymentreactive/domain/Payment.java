package com.paylane.paymentreactive.domain;

import java.time.Instant;
import java.util.UUID;

/** Payment aggregate — identical behaviour to the MVC twin; state changes go through the machine. */
public class Payment {

    private final UUID id;
    private final UUID merchantId;
    private final long amountMinor;
    private final String currency;
    private PaymentStatus status;
    private final Instant createdAt;
    private Instant updatedAt;

    public Payment(UUID id, UUID merchantId, long amountMinor, String currency,
                   PaymentStatus status, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Payment createIntent(UUID merchantId, long amountMinor, String currency) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }
        return new Payment(null, merchantId, amountMinor, currency,
                PaymentStatus.CREATED, null, null);
    }

    public void authorize() {
        transitionTo(PaymentStatus.AUTHORIZED);
    }

    public void capture() {
        transitionTo(PaymentStatus.CAPTURED);
    }

    public void transitionTo(PaymentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateTransitionException(status, target);
        }
        this.status = target;
    }

    public UUID id() { return id; }
    public UUID merchantId() { return merchantId; }
    public long amountMinor() { return amountMinor; }
    public String currency() { return currency; }
    public PaymentStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
