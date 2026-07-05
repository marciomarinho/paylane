package com.paylane.ledger;

import com.paylane.ledger.domain.JournalEntry;
import com.paylane.ledger.domain.Posting;
import com.paylane.ledger.repo.LedgerRepository;
import com.paylane.ledger.repo.Records.Balance;
import com.paylane.ledger.repo.Records.EntryRow;
import com.paylane.ledger.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class LedgerRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void serializable(DynamicPropertyRegistry registry) {
        // Match production: run the app's transactions SERIALIZABLE.
        registry.add("spring.datasource.hikari.transaction-isolation", () -> "TRANSACTION_SERIALIZABLE");
    }

    @Autowired
    LedgerService ledger;
    @Autowired
    LedgerRepository repo;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void reset() {
        // Static container is shared across tests; start each from a clean journal.
        // Row-level append-only triggers do not fire on TRUNCATE, so this is allowed.
        jdbc.sql("TRUNCATE posting, journal_entry RESTART IDENTITY").update();
    }

    private JournalEntry capture(String ref, long amount, long fee) {
        return new JournalEntry(ref, "capture " + ref, List.of(
                new Posting("scheme_receivable", amount),
                new Posting("merchant_payable", -(amount - fee)),
                new Posting("platform_fees", -fee)));
    }

    @Test
    void postsBalancedEntryAndDerivesBalances() {
        EntryRow row = ledger.postEntry(capture("cap-1", 10_000, 320));

        assertThat(row.id()).isPositive();
        assertThat(row.postings()).hasSize(3);

        assertThat(balanceOf("scheme_receivable")).isEqualTo(10_000);
        assertThat(balanceOf("merchant_payable")).isEqualTo(-9_680);
        assertThat(balanceOf("platform_fees")).isEqualTo(-320);

        // Global invariant: the whole ledger nets to zero.
        long total = ledger.balances().stream().mapToLong(Balance::balanceMinor).sum();
        assertThat(total).isZero();
    }

    @Test
    void isIdempotentOnExternalRef() {
        EntryRow first = ledger.postEntry(capture("cap-idem", 5_000, 175));
        EntryRow second = ledger.postEntry(capture("cap-idem", 5_000, 175));

        assertThat(second.id()).isEqualTo(first.id());
        // Balance reflects a single capture, not two.
        assertThat(balanceOf("scheme_receivable")).isEqualTo(5_000);
    }

    @Test
    void databaseRejectsUnbalancedEntry() {
        // Bypass the domain guard and try to persist a lone, unbalanced posting.
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            Long entryId = jdbc.sql("""
                            INSERT INTO journal_entry (external_ref, description)
                            VALUES ('bad-entry', 'unbalanced') RETURNING id
                            """).query(Long.class).single();
            Long accountId = jdbc.sql("SELECT id FROM account WHERE code = 'cash'")
                    .query(Long.class).single();
            jdbc.sql("INSERT INTO posting (journal_entry_id, account_id, amount_minor) VALUES (:e,:a,:amt)")
                    .param("e", entryId).param("a", accountId).param("amt", 999L)
                    .update();
            // Force the deferred balance check to run now, in-statement, rather than at commit
            // (a commit-time failure would surface as TransactionSystemException instead).
            jdbc.sql("SET CONSTRAINTS ALL IMMEDIATE").update();
        }))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("does not balance");
    }

    @Test
    void ledgerIsAppendOnly() {
        ledger.postEntry(capture("cap-append", 2_000, 88));
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                jdbc.sql("UPDATE posting SET amount_minor = 0 WHERE amount_minor > 0").update()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    private long balanceOf(String code) {
        return repo.balance(code).orElseThrow().balanceMinor();
    }
}
