package com.paylane.settlement.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly settlement run. On-demand runs go through the controller against the same service. */
@Component
public class SettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(SettlementScheduler.class);

    private final SettlementService service;

    public SettlementScheduler(SettlementService service) {
        this.service = service;
    }

    @Scheduled(cron = "${paylane.settlement.cron:0 0 2 * * *}")
    public void nightlyRun() {
        var batches = service.runSettlement();
        log.info("nightly settlement run created {} batch(es)", batches.size());
    }
}
