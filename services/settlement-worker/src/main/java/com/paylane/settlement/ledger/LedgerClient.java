package com.paylane.settlement.ledger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

/**
 * Posts double-entry journal entries to the ledger over HTTP. Every entry carries a stable
 * external_ref, so the ledger's idempotency makes these calls safe to retry after any redelivery.
 */
@Component
public class LedgerClient {

    public record PostingDto(String account, long amountMinor) {}

    public record EntryRequest(String externalRef, String description, List<PostingDto> postings) {}

    private final RestClient http;

    public LedgerClient(RestClient.Builder builder, @Value("${paylane.ledger.base-url}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    /** Capture: scheme owes us the gross; we owe the merchant the net; we book the fee as revenue. */
    public void postCapture(UUID paymentId, long amountMinor, long feeMinor) {
        long net = amountMinor - feeMinor;
        post(new EntryRequest(
                "capture:" + paymentId,
                "capture " + paymentId,
                List.of(
                        new PostingDto("scheme_receivable", amountMinor),
                        new PostingDto("merchant_payable", -net),
                        new PostingDto("platform_fees", -feeMinor))));
    }

    /** Payout: settle what we owe the merchant out of cash. */
    public void postPayout(long batchId, long payoutMinor) {
        post(new EntryRequest(
                "payout:" + batchId,
                "settlement payout batch " + batchId,
                List.of(
                        new PostingDto("merchant_payable", payoutMinor),
                        new PostingDto("cash", -payoutMinor))));
    }

    private void post(EntryRequest entry) {
        http.post()
                .uri("/journal-entries")
                .body(entry)
                .retrieve()
                .toBodilessEntity();
    }
}
