package com.paylane.paymentreactive.web;

import com.paylane.paymentreactive.domain.Payment;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.UUID;

/** Request and response shapes — identical to the MVC twin. */
public final class Dtos {
    private Dtos() {}

    public record CreateMerchantRequest(
            @NotBlank String name,
            @NotBlank String settlementAccount) {}

    public record CreatePaymentRequest(
            @NotNull UUID merchantId,
            @Positive long amountMinor,
            String currency) {
        public String currencyOrDefault() {
            return currency == null || currency.isBlank() ? "AUD" : currency;
        }
    }

    public record PaymentResponse(UUID id, UUID merchantId, long amountMinor, String currency,
                                  String status, Instant createdAt, Instant updatedAt) {
        public static PaymentResponse of(Payment p) {
            return new PaymentResponse(p.id(), p.merchantId(), p.amountMinor(), p.currency(),
                    p.status().name(), p.createdAt(), p.updatedAt());
        }
    }
}
