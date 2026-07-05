package com.paylane.settlement.web;

import com.paylane.settlement.repo.SettlementRepository;
import com.paylane.settlement.repo.SettlementRepository.Batch;
import com.paylane.settlement.service.SettlementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class SettlementController {

    private final SettlementService service;
    private final SettlementRepository repo;

    public SettlementController(SettlementService service, SettlementRepository repo) {
        this.service = service;
        this.repo = repo;
    }

    /** On-demand trigger for the batch run (the nightly job calls the same path). */
    @PostMapping("/settlements/run")
    public List<Batch> run() {
        return service.runSettlement();
    }

    @GetMapping("/settlements")
    public List<Batch> list() {
        return repo.listBatches();
    }

    @GetMapping("/settlements/{id}")
    public ResponseEntity<Batch> get(@PathVariable long id) {
        return repo.findBatch(id).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
