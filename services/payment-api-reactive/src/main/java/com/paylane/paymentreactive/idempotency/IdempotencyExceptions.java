package com.paylane.paymentreactive.idempotency;

public final class IdempotencyExceptions {
    private IdempotencyExceptions() {}

    public static class MissingKey extends RuntimeException {
        public MissingKey() {
            super("Idempotency-Key header is required on this request");
        }
    }

    public static class Conflict extends RuntimeException {
        public Conflict(String key) {
            super("Idempotency-Key '" + key + "' was already used with a different request");
        }
    }

    public static class InProgress extends RuntimeException {
        public InProgress(String key) {
            super("a request with Idempotency-Key '" + key + "' is still in progress");
        }
    }
}
