# paylane

[![ci](https://github.com/marciomarinho/paylane/actions/workflows/ci.yml/badge.svg)](https://github.com/marciomarinho/paylane/actions/workflows/ci.yml)

A small payments platform you can run end-to-end on a laptop. A card payment is taken, an event
flows over SNS/SQS to a back-office worker, the worker batches it into a merchant settlement, and a
strict double-entry ledger records every cent — with the books always netting to zero.

It exists to demonstrate payments-domain engineering on a modern Java 25 / Spring Boot stack:
**idempotency, a transactional outbox, effectively-once event processing, double-entry accounting,
and settlement reconciliation** — each one small, real, and tested against real infrastructure.

> **Status.** The core payment flow is complete and runnable — `payment-api → SNS/SQS →
> settlement-worker → ledger` — plus a **reactive twin + k6 benchmark** answering "virtual threads or
> reactive?" with real numbers ([Benchmark](#benchmark-virtual-threads-vs-reactive)), a **Next.js
> dashboard** with a typed client generated from the services' OpenAPI specs, and a **GitHub Actions
> CI pipeline** (build → test → scan → GHCR), and **OpenTelemetry tracing** into Grafana/Tempo with
> trace context propagated across the SNS/SQS boundary. One-command local stack, integration tests on
> Testcontainers. Everything from the original build spec is now in place — see [Roadmap](#roadmap).

![paylane merchant ops dashboard — money-flow rail, per-payment state-machine tracks, a live outbox→SNS→SQS→worker event feed, settlement reconciliation (with a parked rounding-bug batch), the double-entry trial balance, and the virtual-threads-vs-WebFlux benchmark](docs/dashboard.png)

---

## Architecture

```mermaid
flowchart TB
    client(["client / merchant app"]):::ext
    pa["<b>payment-api</b><br/>MVC + virtual threads<br/>CREATED → AUTHORIZED → CAPTURED"]:::svc
    pgp[("Postgres · payment<br/>payment · merchant<br/>idempotency_key · outbox")]:::infra
    sns(["SNS<br/>payment-events"]):::infra
    sqs(["SQS"]):::infra
    dlq(["DLQ<br/>poison msgs"]):::infra
    sw["<b>settlement-worker</b><br/>dedupe · batch/merchant · reconcile"]:::svc
    pgs[("Postgres<br/>settlement")]:::infra
    ledger["<b>ledger</b><br/>Postgres SERIALIZABLE<br/>double-entry · append-only"]:::svc
    pgl[("Postgres · ledger<br/>account · journal_entry · posting")]:::infra

    client -->|"Idempotency-Key on every write"| pa
    pa -->|"payment + outbox<br/>in one tx"| pgp
    pgp -.->|"outbox poller"| sns
    sns --> sqs
    sqs -->|"at-least-once"| sw
    sw -.->|"3 retries"| dlq
    sw --> pgs
    sw -->|"HTTP · post payout"| ledger
    ledger --> pgl

    classDef ext fill:#e8eef7,stroke:#4a6fa5,color:#1a2a3a;
    classDef svc fill:#eef4ec,stroke:#4a8a5a,color:#16301f;
    classDef infra fill:#f2ecdc,stroke:#a5894a,color:#3a2f1a;
```

Three services plus the event backbone. Everything runs locally: two Postgres instances and
LocalStack (SNS/SQS) in Docker, no cloud account required.

| Service | Stack | Responsibility |
|---|---|---|
| **payment-api** | Spring MVC · virtual threads | Merchants + payment lifecycle. Idempotency-Key on all writes; `CREATED→AUTHORIZED→CAPTURED` state machine enforced in the aggregate; emits `payment.captured` via the transactional outbox. |
| **settlement-worker** | Spring Boot · SQS consumer | Consumes captures at-least-once, dedupes to effectively-once, batches per merchant, reconciles `Σpayments − fees = payout`, posts to the ledger. Poison messages redrive to a DLQ. |
| **ledger** | Spring Boot · Postgres SERIALIZABLE | Double-entry core. Every entry balances (domain **and** DB trigger); money is `long` minor units; append-only; balances are a view over the journal. |

## Run it in ~90 seconds

Requires Docker (with Compose) and, for the demo script, `python3` + `curl`.

```bash
docker compose up --build          # Postgres x2, LocalStack, three services
./scripts/demo.sh                  # onboard a merchant, take 3 payments, settle, show the books
```

`demo.sh` walks the whole flow and finishes by printing every account balance and their sum, which
is `0` — the ledger balanced. Then open the **merchant-ops dashboard** at **http://localhost:3000**:
a money-flow rail, per-payment state-machine tracks, a live outbox→SNS→SQS→worker event feed,
settlement reconciliation, and the double-entry trial balance — reading the real services through a
typed client generated from their OpenAPI specs (and falling back to a seeded demo when idle). To see
the dead-letter path:

```bash
./scripts/poison.sh                # drops a malformed message; it lands in the DLQ after 3 retries
./scripts/backpressure.sh          # streams a 5M-row firehose slowly; watch reactive memory stay flat
```

Ports: dashboard `:3000`, payment-api `:8081`, ledger `:8082`, settlement-worker `:8083`,
payment-api-reactive `:8091`, LocalStack `:4566`. (If `:3000` is taken locally, set `DASHBOARD_PORT`.)

### Poke it by hand

```bash
# onboard
curl -s localhost:8081/merchants -H 'Idempotency-Key: m1' \
  -H 'Content-Type: application/json' \
  -d '{"name":"Acme","settlementAccount":"BSB-062-000-1234"}'

# take a payment (create+authorize), then capture
curl -s localhost:8081/payments -H 'Idempotency-Key: p1' \
  -H 'Content-Type: application/json' \
  -d '{"merchantId":"<id>","amountMinor":12000,"currency":"AUD"}'
curl -s -X POST localhost:8081/payments/<paymentId>/capture -H 'Idempotency-Key: c1'

# run settlement, inspect the books
curl -s -X POST localhost:8083/settlements/run
curl -s localhost:8082/accounts
```

## Benchmark: virtual threads vs reactive

`payment-api` (Spring MVC on **virtual threads**) and `payment-api-reactive` (**WebFlux + R2DBC**)
are byte-for-byte behavioural twins on separate databases. `bench/` runs the same mixed workload
(create+authorize → capture → read) against both, at three concurrency tiers plus a slow-downstream
variant that injects a 200ms simulated card-scheme call. Reproduce: `docker compose up --build`, then
`bench/run.sh`. Full numbers in [`bench/results.md`](bench/results.md); a representative slice:

| scenario | stack | req/s | p50 | p99 |
|---|---|---|---|---|
| baseline, 300 VUs | mvc (virtual threads) | 7507 | 38.1ms | 107.5ms |
| baseline, 300 VUs | reactive (WebFlux) | 7633 | 37.2ms | 97.7ms |
| slow downstream 200ms, 200 VUs | mvc (virtual threads) | 1431 | 203.2ms | 240.3ms |
| slow downstream 200ms, 200 VUs | reactive (WebFlux) | 1442 | 202.5ms | 214.7ms |

*(0% errors across all runs; laptop/Docker Desktop, directional not datacenter.)*

**Read:** throughput is within ~2% across the board — when the bottleneck is Postgres or a slow
downstream, the request model barely matters. Reactive holds a slightly tighter p99 at high
concurrency; virtual-threads MVC used less memory here (482MiB vs 712MiB RSS) and, notably, was
*faster* at low concurrency (50 VUs) where reactive's per-request overhead hasn't amortized. So:
**MVC on virtual threads is the right default** — you keep blocking JDBC and readable stack traces
for near-identical numbers. Reactive earns its complexity where it structurally wins — streaming and
end-to-end backpressure — which this request/response workload doesn't exercise.

To make that concrete, the twin ships a **streaming exhibit**: `GET /payments/stream` (and a synthetic
`GET /payments/firehose?count=N`) return a `Flux` as NDJSON with demand propagated end to end
(Netty → `Flux` → R2DBC cursor). `scripts/backpressure.sh` reads a 5,000,000-row firehose at 32 KB/s
and the service memory stays flat (~660 MB) while only a few thousand rows have been produced —
backpressure throttling the source, not buffering. That's the workload where WebFlux wins on purpose.
See ADR 003.

## Design decisions

Three ADRs carry the reasoning:

- [ADR 001 — Transactional outbox over direct publish / CDC](docs/adr/001-outbox.md)
- [ADR 002 — SERIALIZABLE + retry over explicit row locks](docs/adr/002-serializable.md)
- [ADR 003 — Virtual threads as the default request model](docs/adr/003-virtual-threads.md)

A few things worth calling out:

- **Idempotency is atomic with the effect.** The `Idempotency-Key` row and the payment state change
  commit in one transaction, so a committed key always carries the exact response the caller first
  received. A replay returns it verbatim; a key reused with a *different* body is a `409`, not a
  silent overwrite.
- **Effectively-once, not exactly-once.** Delivery is at-least-once (outbox → SNS → SQS). The worker
  dedupes on the payment id and every downstream write (settlement item, ledger post) is idempotent,
  so redelivery is a no-op. "Exactly-once delivery" is a myth; effectively-once *processing* is the
  real, achievable property.
- **Money is integer minor units, everywhere.** No `float`, no `BigDecimal` drift. The ledger's
  balance invariant is enforced by a deferred Postgres constraint trigger in addition to the domain,
  and a jqwik property test asserts random capture/payout sequences always balance.

## Testing

- **Testcontainers** integration tests against real Postgres for each service (idempotency replay,
  the DB balance trigger, append-only enforcement, dedupe, reconciliation → `SUSPENDED`).
- **jqwik** property test in the ledger: for any random sequence of captures and payouts, the books
  net to zero.

```bash
# per service (needs a Docker daemon for Testcontainers)
cd services/ledger && mvn test
```

## CI/CD

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every push and PR:

1. **build + test** — `mvn verify` per service (unit + Testcontainers integration) on a matrix.
2. **e2e smoke** — brings the whole stack up with `docker compose`, runs `demo.sh` (pay → settle →
   books balance), then a short k6 smoke.
3. **DevSecOps** — **Trivy** scans each image (results uploaded as SARIF to the Security tab) and
   **OWASP dependency-check** runs SCA on each service.
4. **publish** — on `main`, images are pushed to **GHCR** (`ghcr.io/<owner>/paylane/<service>`).

## Observability

Every JVM service runs the **OpenTelemetry Java agent** (auto-instrumentation, switched on via
`JAVA_TOOL_OPTIONS` so tests and plain `java -jar` are unaffected), exporting traces over OTLP to
**Tempo**. The interesting part is the async hop: the agent injects the W3C `traceparent` into the
**SNS/SQS message attributes** on publish and extracts it on the worker's receive (raw message
delivery preserves the attributes), so one payment's trace stays connected all the way across the
message boundary —

```mermaid
flowchart LR
    cap["payment-api<br/>capture"]:::svc
    ob["outbox"]:::infra
    sns["SNS"]:::infra
    sqs["SQS"]:::infra
    sw["settlement-worker"]:::svc
    lg["ledger"]:::svc
    pg[("Postgres")]:::infra

    cap --> ob --> sns -->|"traceparent in<br/>SNS/SQS message attributes"| sqs --> sw -->|"HTTP"| lg --> pg

    classDef svc fill:#eef4ec,stroke:#4a8a5a,color:#16301f;
    classDef infra fill:#f2ecdc,stroke:#a5894a,color:#3a2f1a;
```

<sub>One payment stays a single trace id, spans linked end-to-end — the W3C <code>traceparent</code> rides the SNS/SQS message attributes across the async hop.</sub>

**Prometheus** scrapes each service's `/actuator/prometheus`; **Grafana** (provisioned with both
datasources) shows traces and metrics. Bring the stack up, run `./scripts/demo.sh`, then open
**Grafana at http://localhost:3300** → Explore → Tempo → search recent traces. One payment's
end-to-end trace, spanning the SNS/SQS boundary:

![one payment's end-to-end distributed trace in Grafana Tempo — spans across payment-api, SNS, SQS, settlement-worker and ledger, connected by trace context propagated through the message attributes](docs/trace.png)

Observability ports: Grafana `:3300`, Tempo `:3200`, Prometheus `:9090`.

## Repo layout

```
paylane/
├── docker-compose.yml         one-command local stack
├── docs/adr/                  the three decisions above
├── services/
│   ├── payment-api/           idempotency · state machine · outbox (MVC + virtual threads)
│   ├── payment-api-reactive/  behavioural twin (WebFlux + R2DBC) — benchmark counterpart
│   ├── settlement-worker/     SQS consumer · dedupe · batch · reconcile · DLQ
│   └── ledger/                double-entry core (SERIALIZABLE, append-only)
├── dashboard/                 Next.js + TS · typed client generated from OpenAPI specs
├── bench/                     k6 mixed workload · run.sh · results.md
├── infra/                     localstack bootstrap · tempo · prometheus · grafana provisioning
└── scripts/                   demo.sh · poison.sh · backpressure.sh
```

## Roadmap

Deliberately staged; the core money-movement slice above is done first because it carries the
domain weight. Done since: the **reactive twin + benchmark**, the **backpressure exhibit**, the
**CI pipeline**, the **dashboard**, and **OpenTelemetry tracing** (all above). Everything from the
original build spec is now in place. What's left is genuinely production-scale work:

- **`infra/`** — Terraform (plan-only) for the AWS shape: SNS, SQS + DLQ, RDS.
- Multi-region / DR, a real card-scheme integration behind the authorize/capture boundary, and the
  PCI scope boundary (see [What I'd do next at production scale](#what-id-do-next-at-production-scale)).

## What I'd do next at production scale

- **Ledger posting at capture time, not settlement time** — today the worker posts both the capture
  and payout legs; at scale the capture leg belongs on the capture path (still via the outbox) so the
  ledger reflects reality the moment money moves.
- **Per-merchant payable sub-accounts** and a real chart of accounts, so a single `merchant_payable`
  isn't a contention and reporting bottleneck.
- **Scheme integration & PCI scope** — a real acquirer/card-scheme call behind the authorize/capture
  boundary, with the PCI-sensitive surface isolated to a tokenization edge the rest of the system
  never sees card data through.
- **Multi-region & DR** — active-passive Postgres with a defined RPO/RTO; the outbox and effectively-
  once consumer already make the event path safe to replay after failover.
- **Poller → LISTEN/NOTIFY or CDC** if outbox latency ever needs to be sub-second.
```
