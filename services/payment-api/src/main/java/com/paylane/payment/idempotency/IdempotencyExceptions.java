package com.paylane.payment.idempotency;

public final class IdempotencyExceptions {
    private IdempotencyExceptions() {}

    /** No Idempotency-Key header on a write. */
    public static class MissingKey extends RuntimeException {
        public MissingKey() {
            super("Idempotency-Key header is required on this request");
        }
    }

    /** Key reused with a different request body — a client bug we refuse to paper over. */
    public static class Conflict extends RuntimeException {
        public Conflict(String key) {
            super("Idempotency-Key '" + key + "' was already used with a different request");
        }
    }

    /** Key seen, but the original request has not committed its response yet. */
    public static class InProgress extends RuntimeException {
        public InProgress(String key) {
            super("a request with Idempotency-Key '" + key + "' is still in progress");
        }
    }
}
