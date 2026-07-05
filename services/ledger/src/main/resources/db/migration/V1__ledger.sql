-- Double-entry ledger core.
-- Invariants enforced at the database boundary, not just in the app:
--   1. Every journal entry balances: SUM(amount_minor) over its postings = 0.
--   2. The ledger is append-only: no UPDATE / DELETE. Corrections are reversing entries.
-- Money is stored as signed minor units (cents) in a bigint. No floats, no BigDecimal drift.

CREATE TABLE account (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        TEXT        NOT NULL UNIQUE,
    name        TEXT        NOT NULL,
    type        TEXT        NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'REVENUE', 'EQUITY')),
    currency    CHAR(3)     NOT NULL DEFAULT 'AUD'
);

CREATE TABLE journal_entry (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    external_ref  TEXT        NOT NULL UNIQUE,   -- idempotency key from the caller
    description   TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE posting (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    journal_entry_id  BIGINT      NOT NULL REFERENCES journal_entry (id),
    account_id        BIGINT      NOT NULL REFERENCES account (id),
    amount_minor      BIGINT      NOT NULL,       -- signed: debits positive, credits negative
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_posting_account ON posting (account_id);
CREATE INDEX idx_posting_entry   ON posting (journal_entry_id);

-- Balance is a projection over the journal, rebuildable at any time by re-summing postings.
-- Balance is not stored mutable state; it is always derived.
CREATE VIEW account_balance AS
SELECT a.id                                  AS account_id,
       a.code                                AS code,
       a.name                                AS name,
       a.type                                AS type,
       a.currency                            AS currency,
       COALESCE(SUM(p.amount_minor), 0)      AS balance_minor,
       COUNT(p.id)                           AS posting_count
FROM account a
LEFT JOIN posting p ON p.account_id = a.id
GROUP BY a.id, a.code, a.name, a.type, a.currency;

-- Invariant 1: entries must balance. Deferred so all postings of an entry can be
-- inserted in one transaction before the check runs at COMMIT.
CREATE OR REPLACE FUNCTION assert_entry_balanced() RETURNS trigger AS $$
DECLARE
    total BIGINT;
BEGIN
    SELECT COALESCE(SUM(amount_minor), 0) INTO total
    FROM posting WHERE journal_entry_id = NEW.journal_entry_id;
    IF total <> 0 THEN
        RAISE EXCEPTION 'journal entry % does not balance: sum(postings) = %', NEW.journal_entry_id, total;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER posting_balanced
    AFTER INSERT ON posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_entry_balanced();

-- Invariant 2: append-only.
CREATE OR REPLACE FUNCTION reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger is append-only: % on % is rejected (post a reversing entry instead)',
        TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER journal_entry_append_only
    BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER posting_append_only
    BEFORE UPDATE OR DELETE ON posting
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- Chart of accounts. Fixed, canonical accounts for the payments flow.
INSERT INTO account (code, name, type, currency) VALUES
    ('scheme_receivable', 'Card scheme receivable', 'ASSET',     'AUD'),
    ('merchant_payable',  'Merchant payable',       'LIABILITY', 'AUD'),
    ('platform_fees',     'Platform fee revenue',   'REVENUE',   'AUD'),
    ('cash',              'Settlement cash',         'ASSET',    'AUD');
