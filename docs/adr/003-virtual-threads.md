# ADR 003 — Virtual threads as the default request model

**Status:** accepted

## Context

These services are IO-bound request/response systems: a request spends almost all its time waiting
on Postgres or an HTTP call to another service, not on CPU. The two mainstream ways to keep such a
service from tying up an OS thread per in-flight request are (a) reactive/non-blocking stacks
(WebFlux + R2DBC) and (b) Java 21 virtual threads under a normal blocking (MVC + JDBC) programming
model.

## Decision

Default to **Spring MVC on virtual threads** (`spring.threads.virtual.enabled=true`). Application
code stays straight-line and blocking — plain JDBC, plain `try`/`catch`, readable stack traces —
while the runtime multiplexes thousands of in-flight requests onto a handful of carrier threads.

The reactive stack is not dismissed; it is measured. `payment-api-reactive` is a byte-for-byte
behavioural twin on WebFlux + R2DBC, and `bench/` runs the same k6 scenario against both. The claim
we wanted to *test*, not assert: for IO-bound request/response, virtual threads erase most of
reactive's throughput edge while keeping the simpler model; reactive keeps winning where it
genuinely fits — streaming and end-to-end backpressure.

> Result (see `bench/results.md`): throughput within ~2% across three concurrency tiers and a
> 200ms-slow-downstream variant, 0% errors. Reactive held a slightly tighter p99 at high concurrency;
> virtual-threads MVC used less memory and was actually faster at low concurrency. The hypothesis
> holds — the throughput edge is gone for this workload — so the decision stands on the evidence,
> not just the argument.

## Consequences

- Blocking JDBC is fine again — no need for R2DBC or to colour every method `Mono`/`Flux`.
- One caveat honoured in code: avoid pinning carrier threads inside `synchronized` blocks over IO.
- We keep the ability to reach for reactive per-service if the benchmark says a given workload
  needs it, rather than adopting it everywhere by default.
