package com.paylane.payment.web;

import com.paylane.payment.domain.Merchant;
import com.paylane.payment.idempotency.IdempotencyService;
import com.paylane.payment.idempotency.IdempotencyService.StoredResponse;
import com.paylane.payment.service.PaymentService;
import com.paylane.payment.web.Requests.CreateMerchantRequest;
import com.paylane.payment.web.Requests.CreatePaymentRequest;
import com.paylane.payment.web.Views.PaymentResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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
    public ResponseEntity<String> createMerchant(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
            @Valid @RequestBody CreateMerchantRequest req) {
        String fp = IdempotencyService.fingerprint("POST /merchants", req.toString());
        StoredResponse res = idempotency.runIdempotent(key, fp, 201,
                () -> payments.createMerchant(req.name(), req.settlementAccount()));
        return json(res);
    }

    @PostMapping("/payments")
    public ResponseEntity<String> createPayment(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
            @Valid @RequestBody CreatePaymentRequest req) {
        String fp = IdempotencyService.fingerprint("POST /payments", req.toString());
        StoredResponse res = idempotency.runIdempotent(key, fp, 201,
                () -> payments.createAndAuthorize(req.merchantId(), req.amountMinor(), req.currencyOrDefault()));
        return json(res);
    }

    @PostMapping("/payments/{id}/capture")
    public ResponseEntity<String> capture(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
            @PathVariable UUID id) {
        String fp = IdempotencyService.fingerprint("POST /payments/capture", id.toString());
        StoredResponse res = idempotency.runIdempotent(key, fp, 200, () -> payments.capture(id));
        return json(res);
    }

    @GetMapping("/payments/{id}")
    public ResponseEntity<PaymentResponse> get(@PathVariable UUID id) {
        return payments.find(id).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/payments")
    public List<PaymentResponse> list(@RequestParam(defaultValue = "50") int limit) {
        return payments.listRecent(Math.min(Math.max(limit, 1), 500));
    }

    @GetMapping("/merchants")
    public List<Merchant> merchants() {
        return payments.listMerchants();
    }

    private static ResponseEntity<String> json(StoredResponse res) {
        return ResponseEntity.status(res.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(res.body());
    }
}
