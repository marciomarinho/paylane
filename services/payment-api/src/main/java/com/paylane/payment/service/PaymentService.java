package com.paylane.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paylane.payment.domain.Merchant;
import com.paylane.payment.domain.Payment;
import com.paylane.payment.outbox.OutboxRepository;
import com.paylane.payment.repo.MerchantRepository;
import com.paylane.payment.repo.PaymentRepository;
import com.paylane.payment.web.Views.PaymentResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Domain orchestration. These methods run inside the idempotency transaction, so the payment
 * state change and the outbox event are one atomic unit — the transactional outbox pattern.
 */
@Service
public class PaymentService {

    public static final String EVENT_PAYMENT_CAPTURED = "payment.captured";

    private final MerchantRepository merchants;
    private final PaymentRepository payments;
    private final OutboxRepository outbox;
    private final ObjectMapper mapper;

    public PaymentService(MerchantRepository merchants, PaymentRepository payments,
                          OutboxRepository outbox, ObjectMapper mapper) {
        this.merchants = merchants;
        this.payments = payments;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    public record CapturedEvent(UUID paymentId, UUID merchantId, long amountMinor,
                                String currency, Instant capturedAt) {}

    @Transactional
    public Merchant createMerchant(String name, String settlementAccount) {
        return merchants.insert(name, settlementAccount);
    }

    /** Create an intent and authorize it in one step (no external auth call in this demo). */
    @Transactional
    public PaymentResponse createAndAuthorize(UUID merchantId, long amountMinor, String currency) {
        if (!merchants.exists(merchantId)) {
            throw new NoSuchElementException("unknown merchant: " + merchantId);
        }
        Payment intent = Payment.createIntent(merchantId, amountMinor, currency);
        Payment saved = payments.insert(intent);
        saved.authorize();
        payments.updateStatus(saved);
        return PaymentResponse.of(saved);
    }

    /** Capture an authorized payment and emit payment.captured via the outbox — atomically. */
    @Transactional
    public PaymentResponse capture(UUID paymentId) {
        Payment payment = payments.find(paymentId)
                .orElseThrow(() -> new NoSuchElementException("unknown payment: " + paymentId));
        payment.capture();
        payments.updateStatus(payment);

        CapturedEvent event = new CapturedEvent(
                payment.id(), payment.merchantId(), payment.amountMinor(),
                payment.currency(), Instant.now());
        outbox.append("payment", payment.id().toString(), EVENT_PAYMENT_CAPTURED, toJson(event));

        return PaymentResponse.of(payment);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<PaymentResponse> find(UUID id) {
        return payments.find(id).map(PaymentResponse::of);
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize outbox payload", e);
        }
    }
}
