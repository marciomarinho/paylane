package com.paylane.settlement.service;

import com.paylane.settlement.domain.FeePolicy;
import com.paylane.settlement.events.CapturedEvent;
import com.paylane.settlement.ledger.LedgerClient;
import com.paylane.settlement.repo.ProcessedMessageRepository;
import com.paylane.settlement.repo.SettlementRepository;
import com.paylane.settlement.repo.SettlementRepository.Batch;
import com.paylane.settlement.repo.SettlementRepository.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    static final String STATUS_SETTLED = "SETTLED";
    static final String STATUS_SUSPENDED = "SUSPENDED";

    private final ProcessedMessageRepository processed;
    private final SettlementRepository settlements;
    private final LedgerClient ledger;
    private final SettlementService self;

    public SettlementService(ProcessedMessageRepository processed,
                             SettlementRepository settlements, LedgerClient ledger,
                             @Lazy SettlementService self) {
        this.processed = processed;
        this.settlements = settlements;
        this.ledger = ledger;
        this.self = self;   // proxy, so per-merchant @Transactional applies from runSettlement()
    }

    /**
     * Handle one payment.captured event. Dedupe marker, item insert and ledger post all commit
     * together; if the ledger call fails the whole thing rolls back and SQS redelivers. Every
     * step is idempotent, so redelivery is safe — effectively-once.
     */
    @Transactional
    public void handleCapture(CapturedEvent event) {
        String key = event.paymentId().toString();
        if (!processed.markProcessed(key)) {
            log.debug("skipping already-processed capture {}", key);
            return;
        }
        long fee = FeePolicy.feeFor(event.amountMinor());
        settlements.insertItem(event.paymentId(), event.merchantId(), event.amountMinor(), fee);
        ledger.postCapture(event.paymentId(), event.amountMinor(), fee);
        log.info("recorded capture {} for merchant {} (amount={}, fee={})",
                key, event.merchantId(), event.amountMinor(), fee);
    }

    /** Batch every merchant's pending captures into a settlement. Returns the batches created. */
    public List<Batch> runSettlement() {
        List<Batch> created = new ArrayList<>();
        for (UUID merchantId : settlements.merchantsWithUnbatchedItems()) {
            self.settleMerchant(merchantId).ifPresent(created::add);
        }
        return created;
    }

    @Transactional
    public java.util.Optional<Batch> settleMerchant(UUID merchantId) {
        List<Item> items = settlements.unbatchedItems(merchantId);
        if (items.isEmpty()) {
            return java.util.Optional.empty();
        }

        long gross = items.stream().mapToLong(Item::amountMinor).sum();
        long fees = items.stream().mapToLong(Item::feeMinor).sum();
        long payout = gross - fees;

        // Reconciliation: every batch must satisfy sum(payments) - fees = payout, and each item's
        // recorded fee must match the fee policy. A mismatch parks the batch for manual review.
        boolean balanced = reconcile(items, gross, fees, payout);
        String status = balanced ? STATUS_SETTLED : STATUS_SUSPENDED;

        long batchId = settlements.createBatch(merchantId, gross, fees, payout, status);
        settlements.assignItemsToBatch(items.stream().map(Item::id).toList(), batchId);

        if (balanced) {
            // Only move cash when there is a positive payout (fees can consume a tiny batch entirely).
            if (payout > 0) {
                ledger.postPayout(batchId, payout);
            }
            log.info("settled batch {} for merchant {}: gross={} fees={} payout={}",
                    batchId, merchantId, gross, fees, payout);
        } else {
            log.warn("SUSPENDED batch {} for merchant {}: failed reconciliation", batchId, merchantId);
        }
        return settlements.findBatch(batchId);
    }

    private boolean reconcile(List<Item> items, long gross, long fees, long payout) {
        if (gross - fees != payout) {
            return false;
        }
        for (Item item : items) {
            if (item.feeMinor() != FeePolicy.feeFor(item.amountMinor())) {
                return false;
            }
        }
        return true;
    }
}
