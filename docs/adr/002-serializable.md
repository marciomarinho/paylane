# ADR 002 — SERIALIZABLE + retry over explicit row locks (ledger)

**Status:** accepted

## Context

The ledger must never let the books drift: every journal entry balances to zero, and concurrent
postings must not interleave into an inconsistent balance. The classic approaches are pessimistic
(`SELECT ... FOR UPDATE` on the accounts involved) or optimistic (version columns, compare-and-set).

## Decision

Run ledger write transactions at `SERIALIZABLE` isolation and **retry on serialization failure**
(Postgres SQLSTATE 40001). Postgres' SSI gives us a simple mental model — transactions behave as if
they ran one at a time — without hand-maintained lock ordering across a growing set of accounts.

The balance invariant is enforced in two independent places:

1. **Domain** — `JournalEntry` refuses to construct unless Σ postings = 0.
2. **Database** — a deferred constraint trigger re-checks the sum at commit, so no code path (not
   even a raw SQL bug) can persist an unbalanced entry.

The ledger is also append-only: `UPDATE`/`DELETE` on journal tables are rejected by trigger;
corrections are reversing entries. Balances are a view over the journal, rebuildable at any time.

## Consequences

- Under contention some transactions abort and retry (bounded loop, small backoff). Throughput is
  fine for settlement-scale write rates; a hot single account would be the thing to watch.
- No lock-ordering discipline to get wrong as the chart of accounts grows.
- Retries must be **safe**, which they are: entries are idempotent on `external_ref`, so a retried
  post cannot double-apply.
- Row locks (`FOR UPDATE`) remain the better tool if we ever need a single, provably hot account to
  serialize with minimal aborts. We would revisit then, with numbers.
