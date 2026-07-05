package com.paylane.ledger.repo;

import com.paylane.ledger.domain.JournalEntry;
import com.paylane.ledger.domain.Posting;
import com.paylane.ledger.repo.Records.Balance;
import com.paylane.ledger.repo.Records.EntryRow;
import com.paylane.ledger.repo.Records.PostingRow;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class LedgerRepository {

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Persist a balanced entry. Runs SERIALIZABLE so concurrent postings that read the same
     * balances cannot interleave into an inconsistent state; the caller retries on conflict.
     * Idempotent on {@code externalRef}: a repeat post returns the originally stored entry.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public EntryRow postEntry(JournalEntry entry) {
        Optional<EntryRow> existing = findByRef(entry.externalRef());
        if (existing.isPresent()) {
            return existing.get();
        }

        long entryId = jdbc.sql("""
                        INSERT INTO journal_entry (external_ref, description)
                        VALUES (:ref, :desc)
                        RETURNING id
                        """)
                .param("ref", entry.externalRef())
                .param("desc", entry.description())
                .query(Long.class)
                .single();

        for (Posting p : entry.postings()) {
            long accountId = accountIdByCode(p.accountCode());
            jdbc.sql("""
                            INSERT INTO posting (journal_entry_id, account_id, amount_minor)
                            VALUES (:entryId, :accountId, :amount)
                            """)
                    .param("entryId", entryId)
                    .param("accountId", accountId)
                    .param("amount", p.amountMinor())
                    .update();
        }

        return findById(entryId).orElseThrow();
    }

    private long accountIdByCode(String code) {
        try {
            return jdbc.sql("SELECT id FROM account WHERE code = :code")
                    .param("code", code)
                    .query(Long.class)
                    .single();
        } catch (EmptyResultDataAccessException e) {
            throw new UnknownAccountException(code);
        }
    }

    @Transactional(readOnly = true)
    public Optional<EntryRow> findByRef(String externalRef) {
        List<Long> ids = jdbc.sql("SELECT id FROM journal_entry WHERE external_ref = :ref")
                .param("ref", externalRef)
                .query(Long.class)
                .list();
        return ids.isEmpty() ? Optional.empty() : findById(ids.get(0));
    }

    @Transactional(readOnly = true)
    public Optional<EntryRow> findById(long id) {
        var headers = jdbc.sql("""
                        SELECT id, external_ref, description, created_at
                        FROM journal_entry WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, rn) -> new Object[]{
                        rs.getLong("id"),
                        rs.getString("external_ref"),
                        rs.getString("description"),
                        rs.getTimestamp("created_at").toInstant()
                })
                .list();
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        Object[] h = headers.get(0);

        List<PostingRow> postings = jdbc.sql("""
                        SELECT a.code AS code, p.amount_minor AS amount
                        FROM posting p JOIN account a ON a.id = p.account_id
                        WHERE p.journal_entry_id = :id
                        ORDER BY p.id
                        """)
                .param("id", id)
                .query((rs, rn) -> new PostingRow(rs.getString("code"), rs.getLong("amount")))
                .list();

        return Optional.of(new EntryRow(
                (Long) h[0], (String) h[1], (String) h[2], (Instant) h[3], postings));
    }

    @Transactional(readOnly = true)
    public List<Balance> balances() {
        return jdbc.sql("""
                        SELECT code, name, type, currency, balance_minor, posting_count
                        FROM account_balance ORDER BY code
                        """)
                .query((rs, rn) -> new Balance(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("type"),
                        rs.getString("currency"),
                        rs.getLong("balance_minor"),
                        rs.getLong("posting_count")))
                .list();
    }

    @Transactional(readOnly = true)
    public Optional<Balance> balance(String code) {
        return jdbc.sql("""
                        SELECT code, name, type, currency, balance_minor, posting_count
                        FROM account_balance WHERE code = :code
                        """)
                .param("code", code)
                .query((rs, rn) -> new Balance(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("type"),
                        rs.getString("currency"),
                        rs.getLong("balance_minor"),
                        rs.getLong("posting_count")))
                .optional();
    }
}
