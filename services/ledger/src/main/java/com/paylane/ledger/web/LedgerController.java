package com.paylane.ledger.web;

import com.paylane.ledger.domain.JournalEntry;
import com.paylane.ledger.domain.Posting;
import com.paylane.ledger.repo.Records.Balance;
import com.paylane.ledger.repo.Records.EntryRow;
import com.paylane.ledger.service.LedgerService;
import com.paylane.ledger.web.Dtos.PostEntryRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class LedgerController {

    private final LedgerService ledger;

    public LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping("/journal-entries")
    public EntryRow post(@Valid @RequestBody PostEntryRequest req) {
        JournalEntry entry = new JournalEntry(
                req.externalRef(),
                req.description(),
                req.postings().stream()
                        .map(p -> new Posting(p.account(), p.amountMinor()))
                        .toList());
        return ledger.postEntry(entry);
    }

    @GetMapping("/journal-entries/{ref}")
    public ResponseEntity<EntryRow> byRef(@PathVariable String ref) {
        return ledger.findByRef(ref).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/accounts")
    public List<Balance> accounts() {
        return ledger.balances();
    }

    @GetMapping("/accounts/{code}/balance")
    public ResponseEntity<Balance> balance(@PathVariable String code) {
        return ledger.balance(code).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
