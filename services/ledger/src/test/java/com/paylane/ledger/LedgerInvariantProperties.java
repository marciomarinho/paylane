package com.paylane.ledger;

import com.paylane.ledger.domain.JournalEntry;
import com.paylane.ledger.domain.Posting;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based exhibit: for any random sequence of captures and payouts, the books balance.
 * Every {@link JournalEntry} we build must satisfy Σ postings = 0 (or construction throws), and
 * the aggregate across all accounts must net to zero — money is only ever moved, never created.
 */
class LedgerInvariantProperties {

    // Fee model mirrored by the settlement-worker: 2.9% + 30c.
    private static long feeFor(long amountMinor) {
        return Math.round(amountMinor * 0.029) + 30;
    }

    @Property(tries = 200)
    void capturesAndPayoutsAlwaysBalance(@ForAll("amounts") List<Long> amounts) {
        Map<String, Long> accounts = new HashMap<>();

        for (long amount : amounts) {
            long fee = feeFor(amount);
            long net = amount - fee;

            // Capture: scheme owes us `amount`; we owe the merchant `net`; we earn `fee`.
            JournalEntry capture = new JournalEntry(
                    "cap-" + System.identityHashCode(amounts) + "-" + amount + "-" + accounts.size(),
                    "capture",
                    List.of(
                            new Posting("scheme_receivable", amount),
                            new Posting("merchant_payable", -net),
                            new Posting("platform_fees", -fee)));
            apply(accounts, capture);

            // Payout: settle what we owe the merchant out of cash.
            JournalEntry payout = new JournalEntry(
                    "pay-" + System.identityHashCode(amounts) + "-" + amount + "-" + accounts.size(),
                    "payout",
                    List.of(
                            new Posting("merchant_payable", net),
                            new Posting("cash", -net)));
            apply(accounts, payout);
        }

        long total = accounts.values().stream().mapToLong(Long::longValue).sum();
        assertThat(total).as("sum of all account balances must be zero").isZero();
    }

    private static void apply(Map<String, Long> accounts, JournalEntry entry) {
        long entrySum = 0;
        for (Posting p : entry.postings()) {
            accounts.merge(p.accountCode(), p.amountMinor(), Long::sum);
            entrySum += p.amountMinor();
        }
        assertThat(entrySum).as("each entry must balance").isZero();
    }

    @Provide
    Arbitrary<List<Long>> amounts() {
        return Arbitraries.longs().between(1, 5_000_00).list().ofMinSize(1).ofMaxSize(40);
    }
}
