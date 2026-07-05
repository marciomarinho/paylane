package com.paylane.paymentreactive.web;

import com.paylane.paymentreactive.service.PaymentService;
import com.paylane.paymentreactive.web.Dtos.PaymentResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

/**
 * The backpressure exhibit — the one place WebFlux structurally beats a blocking model.
 *
 * <p>Both endpoints return a {@link Flux} as newline-delimited JSON. WebFlux writes each element
 * to the Netty response only when the transport signals write demand, and that demand propagates
 * back up the {@code Flux} — to the R2DBC cursor for {@code /payments/stream}, or to the generator
 * for {@code /payments/firehose}. A slow client therefore throttles the <em>source</em>; the
 * service never buffers the whole stream. Run the firehose against a rate-limited reader with a
 * small heap and watch memory stay flat (see {@code scripts/backpressure.sh}).
 */
@RestController
public class StreamController {

    private final PaymentService payments;

    public StreamController(PaymentService payments) {
        this.payments = payments;
    }

    /** Stream real payments straight from the database, backpressured DB → HTTP. */
    @GetMapping(value = "/payments/stream", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<PaymentResponse> stream() {
        return payments.streamAll();
    }

    /**
     * Synthetic firehose of {@code count} rows — a fast producer to make backpressure observable
     * at any scale without needing a huge table. The client's read rate governs delivery.
     */
    @GetMapping(value = "/payments/firehose", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<PaymentResponse> firehose(@RequestParam(defaultValue = "1000000") int count) {
        UUID merchant = new UUID(0L, 0L);
        return Flux.range(0, Math.max(0, count))
                .map(i -> new PaymentResponse(
                        new UUID(0L, i), merchant, 1000L + i, "AUD", "CAPTURED",
                        Instant.EPOCH, Instant.EPOCH));
    }
}
