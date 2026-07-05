package com.paylane.ledger.domain;

import java.util.List;

/**
 * A balanced, atomic set of postings. The balance invariant (Σ postings = 0) is enforced
 * here in the domain layer <em>and</em> again by a deferred constraint trigger in the
 * database — an unbalanced entry cannot exist at either boundary.
 *
 * @param externalRef caller-supplied idempotency key; a repeat post with the same ref is a no-op
 */
public record JournalEntry(String externalRef, String description, List<Posting> postings) {

    public JournalEntry {
        if (externalRef == null || externalRef.isBlank()) {
            throw new IllegalArgumentException("journalEntry.externalRef is required");
        }
        if (postings == null || postings.size() < 2) {
            throw new IllegalArgumentException("a journal entry needs at least two postings");
        }
        postings = List.copyOf(postings);
        long sum = 0;
        for (Posting p : postings) {
            sum += p.amountMinor();
        }
        if (sum != 0) {
            throw new IllegalArgumentException(
                    "journal entry does not balance: sum(postings) = " + sum + " (must be 0)");
        }
    }
}
