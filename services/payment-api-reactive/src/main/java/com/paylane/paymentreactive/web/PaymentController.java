package com.paylane.paymentreactive.web;

import com.paylane.paymentreactive.idempotency.IdempotencyService;
import com.paylane.paymentreactive.idempotency.IdempotencyService.StoredResponse;
import com.paylane.paymentreactive.service.PaymentService;
import com.paylane.paymentreactive.web.Dtos.CreateMerchantRequest;
import com.paylane.paymentreactive.web.Dtos.CreatePaymentRequest;
import com.paylane.paymentreactive.web.Dtos.PaymentResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
public class PaymentController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final PaymentService payments;
    private final IdempotencyService idempotency;

    public PaymentController(PaymentService payments, IdempotencyService idempotency) {
        this.payments = payments;
        this.idempotency = idempotency;
    }

    @PostMapping("/merchants")
    public Mono<ResponseEntity<String>> createMerchant(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
            @Valid @RequestBody CreateMerchantRequest req) {
        String fp = IdempotencyService.fingerprint("POST /merchants", req.toString());
        return idempotency.runIdempotent(key, fp, 201,
                        () -> payments.createMerchant(req.name(), req.settlementAccount()).cast(Object.class))
                .map(PaymentController::json);
    }

    @PostMapping("/payments")
    public Mono<ResponseEntity<String>> createPayment(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
            @Valid @RequestBody CreatePaymentRequest req) {
        String fp = IdempotencyService.fingerprint("POST /payments", req.toString());
        return idempotency.runIdempotent(key, fp, 201,
                        () -> payments.createAndAuthorize(req.merchantId(), req.amountMinor(),
                                req.currencyOrDefault()).cast(Object.class))
                .map(PaymentController::json);
    }

    @PostMapping("/payments/{id}/capture")
    public Mono<ResponseEntity<String>> capture(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
            @PathVariable UUID id) {
        String fp = IdempotencyService.fingerprint("POST /payments/capture", id.toString());
        return idempotency.runIdempotent(key, fp, 200,
                        () -> payments.capture(id).cast(Object.class))
                .map(PaymentController::json);
    }

    @GetMapping("/payments/{id}")
    public Mono<ResponseEntity<PaymentResponse>> get(@PathVariable UUID id) {
        return payments.find(id)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    private static ResponseEntity<String> json(StoredResponse res) {
        return ResponseEntity.status(res.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(res.body());
    }
}
