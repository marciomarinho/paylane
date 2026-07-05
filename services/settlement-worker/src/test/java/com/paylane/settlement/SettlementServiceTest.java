package com.paylane.settlement;

import com.paylane.settlement.events.CapturedEvent;
import com.paylane.settlement.ledger.LedgerClient;
import com.paylane.settlement.repo.SettlementRepository;
import com.paylane.settlement.repo.SettlementRepository.Batch;
import com.paylane.settlement.service.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Testcontainers
class SettlementServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("paylane.sqs.enabled", () -> "false");           // no poller in tests
        registry.add("paylane.sqs.queue-url", () -> "http://unused");
    }

    @MockitoBean
    LedgerClient ledger;   // no HTTP to the ledger in unit tests

    @Autowired
    SettlementService service;
    @Autowired
    SettlementRepository repo;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE settlement_item, settlement_batch, processed_message RESTART IDENTITY CASCADE").update();
    }

    private CapturedEvent capture(UUID merchant, long amount) {
        return new CapturedEvent(UUID.randomUUID(), merchant, amount, "AUD", Instant.now());
    }

    @Test
    void handleCapture_isEffectivelyOnce() {
        CapturedEvent event = capture(UUID.randomUUID(), 10_000);
        service.handleCapture(event);
        service.handleCapture(event);   // redelivery

        Long items = jdbc.sql("SELECT count(*) FROM settlement_item WHERE payment_id = :p")
                .param("p", event.paymentId()).query(Long.class).single();
        assertThat(items).isEqualTo(1);
        verify(ledger, times(1)).postCapture(eq(event.paymentId()), eq(10_000L), eq(320L));
    }

    @Test
    void runSettlement_batchesReconcilesAndPaysOut() {
        UUID merchant = UUID.randomUUID();
        service.handleCapture(capture(merchant, 10_000));   // fee 320
        service.handleCapture(capture(merchant, 5_000));    // fee 175

        List<Batch> batches = service.runSettlement();

        assertThat(batches).hasSize(1);
        Batch b = batches.get(0);
        assertThat(b.grossMinor()).isEqualTo(15_000);
        assertThat(b.feeMinor()).isEqualTo(495);
        assertThat(b.payoutMinor()).isEqualTo(14_505);
        assertThat(b.status()).isEqualTo("SETTLED");
        verify(ledger).postPayout(eq(b.id()), eq(14_505L));
    }

    @Test
    void tinyPaymentWhereFeeEqualsAmount_settlesWithNoPayout() {
        // amount 31c -> fee round(0.899)+30 = 31 -> net 0. Capture posts (zero leg omitted in the
        // ledger client), the batch settles, but there is nothing to pay out.
        UUID merchant = UUID.randomUUID();
        service.handleCapture(capture(merchant, 31));

        List<Batch> batches = service.runSettlement();

        assertThat(batches).hasSize(1);
        Batch b = batches.get(0);
        assertThat(b.grossMinor()).isEqualTo(31);
        assertThat(b.feeMinor()).isEqualTo(31);
        assertThat(b.payoutMinor()).isZero();
        assertThat(b.status()).isEqualTo("SETTLED");
        verify(ledger).postCapture(org.mockito.ArgumentMatchers.any(), eq(31L), eq(31L));
        verify(ledger, never()).postPayout(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void badFeeParksBatchAsSuspended() {
        UUID merchant = UUID.randomUUID();
        // Insert an item whose recorded fee does NOT match the fee policy -> reconciliation fails.
        repo.insertItem(UUID.randomUUID(), merchant, 10_000, 999);

        List<Batch> batches = service.runSettlement();

        assertThat(batches).hasSize(1);
        assertThat(batches.get(0).status()).isEqualTo("SUSPENDED");
        verify(ledger, never()).postPayout(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
    }
}
