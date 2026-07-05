# ADR 001 — Transactional outbox over direct publish / CDC

**Status:** accepted

## Context

When a payment is captured, two things must happen: the payment row moves to `CAPTURED`, and a
`payment.captured` event reaches the settlement worker. If we publish to SNS directly from the
request handler, we have a dual write — a DB commit and a broker publish with no shared
transaction. Any crash between them either loses the event (settlement never happens) or emits an
event for a capture that rolled back (settlement for money we never took).

## Decision

Write the event to an `outbox` table **in the same transaction** as the state change. A separate
poller reads unpublished rows, publishes them to SNS, and stamps `published_at`. The domain change
and the intent-to-publish commit atomically; publication is decoupled and retryable.

We chose the application-level outbox over log-based CDC (e.g. Debezium) because:

- It is self-contained — no Kafka Connect, no Postgres logical replication slot to operate.
- The event payload is shaped by the domain, not reverse-engineered from a row diff.
- For an interview-scale demo it shows the *pattern* clearly; the CDC variant is an ops upgrade,
  not a different idea.

## Consequences

- Delivery is **at-least-once**: a crash after publish but before `published_at` is stamped
  republishes on the next poll. Consumers must be idempotent — see ADR 002 and the settlement
  worker's `processed_message` dedupe.
- A poll loop adds latency (default 1s) between capture and publish. Acceptable here; tunable, and
  replaceable with `LISTEN/NOTIFY` if sub-second matters.
- The `outbox` table needs periodic pruning of published rows in production.
