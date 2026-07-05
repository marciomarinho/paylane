package com.paylane.ledger.service;

import com.paylane.ledger.domain.JournalEntry;
import com.paylane.ledger.repo.LedgerRepository;
import com.paylane.ledger.repo.Records.Balance;
import com.paylane.ledger.repo.Records.EntryRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);
    private static final int MAX_ATTEMPTS = 5;

    private final LedgerRepository repo;

    public LedgerService(LedgerRepository repo) {
        this.repo = repo;
    }

    /**
     * Post a balanced entry under SERIALIZABLE isolation, retrying when Postgres aborts the
     * transaction with a serialization failure (SQLSTATE 40001). This is the concurrency story:
     * we take the strongest isolation and let the DB tell us when to retry, rather than reaching
     * for row locks. See ADR 002.
     */
    public EntryRow postEntry(JournalEntry entry) {
        ConcurrencyFailureException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return repo.postEntry(entry);
            } catch (ConcurrencyFailureException e) {
                last = e;
                log.warn("serialization conflict posting entry {} (attempt {}/{})",
                        entry.externalRef(), attempt, MAX_ATTEMPTS);
                backoff(attempt);
            }
        }
        throw last;
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(50L * attempt, 250L));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during serialization retry backoff", ie);
        }
    }

    public Optional<EntryRow> findByRef(String externalRef) {
        return repo.findByRef(externalRef);
    }

    public List<Balance> balances() {
        return repo.balances();
    }

    public Optional<Balance> balance(String code) {
        return repo.balance(code);
    }
}
