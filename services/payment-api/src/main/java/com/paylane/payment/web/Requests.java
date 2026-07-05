package com.paylane.payment.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/** Request DTOs. record toString() gives a stable fingerprint for idempotency. */
public final class Requests {
    private Requests() {}

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
}
