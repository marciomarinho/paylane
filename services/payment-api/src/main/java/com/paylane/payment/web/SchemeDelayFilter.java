package com.paylane.payment.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Simulates a slow downstream (a ~200ms card-scheme call) when the request carries
 * {@code X-Scheme-Delay-Ms}. On virtual threads this parked wait costs a virtual thread, not a
 * carrier thread — which is exactly what the benchmark's slow-downstream variant probes. The
 * WebFlux twin honours the same header with a non-blocking delay, so the comparison is fair.
 */
@Component
@Order(1)
public class SchemeDelayFilter extends OncePerRequestFilter {

    private static final long MAX_DELAY_MS = 5_000;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long delay = parse(request.getHeader("X-Scheme-Delay-Ms"));
        if (delay > 0) {
            try {
                Thread.sleep(Math.min(delay, MAX_DELAY_MS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
        }
        chain.doFilter(request, response);
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
