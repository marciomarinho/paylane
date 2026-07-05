package com.paylane.settlement.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/** The payment.captured event as emitted by payment-api's outbox. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CapturedEvent(UUID paymentId, UUID merchantId, long amountMinor,
                            String currency, Instant capturedAt) {
}
