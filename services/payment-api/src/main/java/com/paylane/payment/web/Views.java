package com.paylane.payment.web;

import com.paylane.payment.domain.Payment;

import java.time.Instant;
import java.util.UUID;

/** JSON response shapes. */
public final class Views {
    private Views() {}

    public record PaymentResponse(UUID id, UUID merchantId, long amountMinor, String currency,
                                  String status, Instant createdAt, Instant updatedAt) {
        public static PaymentResponse of(Payment p) {
            return new PaymentResponse(p.id(), p.merchantId(), p.amountMinor(), p.currency(),
                    p.status().name(), p.createdAt(), p.updatedAt());
        }
    }
}
