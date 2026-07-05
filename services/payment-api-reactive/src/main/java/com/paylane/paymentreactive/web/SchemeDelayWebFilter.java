package com.paylane.paymentreactive.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Non-blocking counterpart to the MVC SchemeDelayFilter: when {@code X-Scheme-Delay-Ms} is set,
 * inject the delay with {@code Mono.delay} — no thread is parked, the event loop stays free. This
 * is the reactive side of the slow-downstream benchmark comparison.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SchemeDelayWebFilter implements WebFilter {

    private static final long MAX_DELAY_MS = 5_000;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long delay = parse(exchange.getRequest().getHeaders().getFirst("X-Scheme-Delay-Ms"));
        if (delay > 0) {
            return Mono.delay(Duration.ofMillis(Math.min(delay, MAX_DELAY_MS)))
                    .then(Mono.defer(() -> chain.filter(exchange)));
        }
        return chain.filter(exchange);
    }

    private static long parse(String header) {
        if (header == null || header.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
