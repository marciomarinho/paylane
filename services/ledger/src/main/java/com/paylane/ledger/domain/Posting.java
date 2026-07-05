package com.paylane.ledger.domain;

/**
 * A single leg of a journal entry. {@code amountMinor} is a signed value in minor units
 * (cents): a positive amount debits the account, a negative amount credits it.
 */
public record Posting(String accountCode, long amountMinor) {
    public Posting {
        if (accountCode == null || accountCode.isBlank()) {
            throw new IllegalArgumentException("posting.accountCode is required");
        }
        if (amountMinor == 0) {
            throw new IllegalArgumentException("posting.amountMinor must be non-zero");
        }
    }
}
