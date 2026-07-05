package com.paylane.paymentreactive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paylane.paymentreactive.domain.Merchant;
import com.paylane.paymentreactive.domain.Payment;
import com.paylane.paymentreactive.outbox.OutboxRepository;
import com.paylane.paymentreactive.repo.MerchantRepository;
import com.paylane.paymentreactive.repo.PaymentRepository;
import com.paylane.paymentreactive.web.Dtos.PaymentResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Reactive domain orchestration — behaviourally identical to the MVC twin's PaymentService. */
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

    public Mono<Merchant> createMerchant(String name, String settlementAccount) {
        return merchants.insert(name, settlementAccount);
    }

    public Mono<PaymentResponse> createAndAuthorize(UUID merchantId, long amountMinor, String currency) {
        return merchants.exists(merchantId).flatMap(exists -> {
            if (!exists) {
                return Mono.error(new NoSuchElementException("unknown merchant: " + merchantId));
            }
            Payment intent = Payment.createIntent(merchantId, amountMinor, currency);
            return payments.insert(intent).flatMap(saved -> {
                saved.authorize();
                return payments.updateStatus(saved).thenReturn(PaymentResponse.of(saved));
            });
        });
    }

    public Mono<PaymentResponse> capture(UUID paymentId) {
        return payments.find(paymentId)
                .switchIfEmpty(Mono.error(new NoSuchElementException("unknown payment: " + paymentId)))
                .flatMap(payment -> {
                    payment.capture();
                    CapturedEvent event = new CapturedEvent(
                            payment.id(), payment.merchantId(), payment.amountMinor(),
                            payment.currency(), Instant.now());
                    return payments.updateStatus(payment)
                            .then(outbox.append("payment", payment.id().toString(),
                                    EVENT_PAYMENT_CAPTURED, toJson(event)))
                            .thenReturn(PaymentResponse.of(payment));
                });
    }

    public Mono<PaymentResponse> find(UUID id) {
        return payments.find(id).map(PaymentResponse::of);
    }

    /** Stream all payments, backpressure preserved end to end (R2DBC cursor → HTTP). */
    public Flux<PaymentResponse> streamAll() {
        return payments.streamAll().map(PaymentResponse::of);
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize outbox payload", e);
        }
    }
}
