-- Identical schema to payment-api. The reactive twin runs against its own database
-- (payment_reactive) so benchmarks don't interfere with the MVC service's data.

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

CREATE TABLE idempotency_key (
    key             TEXT        PRIMARY KEY,
    fingerprint     TEXT        NOT NULL,
    response_status INT,
    response_body   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type TEXT        NOT NULL,
    aggregate_id   TEXT        NOT NULL,
    type           TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
