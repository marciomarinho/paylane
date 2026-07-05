-- The settlement-worker's own state, isolated in the `settlement` schema (Flyway creates it).
-- It shares the ledger's Postgres instance but never touches ledger tables directly; it posts
-- to the ledger over HTTP. Its job: turn a stream of captures into reconciled daily payouts.

-- Effectively-once: at-least-once SQS delivery + this dedupe table = each capture handled once.
CREATE TABLE processed_message (
    message_key  TEXT        PRIMARY KEY,   -- the payment id carried by the event
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE settlement_batch (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    merchant_id  UUID        NOT NULL,
    gross_minor  BIGINT      NOT NULL,
    fee_minor    BIGINT      NOT NULL,
    payout_minor BIGINT      NOT NULL,
    status       TEXT        NOT NULL CHECK (status IN ('SETTLED', 'SUSPENDED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per captured payment awaiting/assigned to a payout.
CREATE TABLE settlement_item (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id   UUID        NOT NULL UNIQUE,   -- natural idempotency key for a capture
    merchant_id  UUID        NOT NULL,
    amount_minor BIGINT      NOT NULL,
    fee_minor    BIGINT      NOT NULL,
    batch_id     BIGINT      REFERENCES settlement_batch (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The batching query scans unbatched items by merchant.
CREATE INDEX idx_item_unbatched ON settlement_item (merchant_id) WHERE batch_id IS NULL;
