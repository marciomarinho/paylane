-- payment-api owns merchants and the payment lifecycle, plus two supporting tables that
-- carry the payments-domain weight of this service:
--   idempotency_key  -> replay safety on every write (the #1 payments interview topic)
--   outbox           -> the transactional outbox: state change and "event emitted" commit atomically

CREATE TABLE merchant (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name               TEXT        NOT NULL,
    settlement_account TEXT        NOT NULL,
    status             TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE payment (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id  UUID        NOT NULL REFERENCES merchant (id),
    amount_minor BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency     CHAR(3)     NOT NULL DEFAULT 'AUD',
    status       TEXT        NOT NULL CHECK (status IN ('CREATED', 'AUTHORIZED', 'CAPTURED', 'SETTLED', 'FAILED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_merchant ON payment (merchant_id);

-- Idempotency-Key store. The fingerprint pins a key to one request shape; reusing a key with a
-- different body is a client error, not a silent overwrite. response_body is filled in the same
-- transaction as the effect, so a committed key always has its original response to replay.
CREATE TABLE idempotency_key (
    key             TEXT        PRIMARY KEY,
    fingerprint     TEXT        NOT NULL,
    response_status INT,
    response_body   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Transactional outbox. Rows are written in the same transaction as the domain change; a poller
-- publishes them to SNS and stamps published_at. No dual write, so no lost or phantom events.
CREATE TABLE outbox (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type TEXT        NOT NULL,
    aggregate_id   TEXT        NOT NULL,
    type           TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

-- Partial index: the poller only ever scans unpublished rows.
CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
