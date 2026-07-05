package com.paylane.ledger.repo;

import java.time.Instant;
import java.util.List;

/** Read-model records returned by {@link LedgerRepository}. */
public final class Records {
    private Records() {}

    public record PostingRow(String accountCode, long amountMinor) {}

    public record EntryRow(long id, String externalRef, String description,
                           Instant createdAt, List<PostingRow> postings) {}

    public record Balance(String code, String name, String type, String currency,
                          long balanceMinor, long postingCount) {}
}
