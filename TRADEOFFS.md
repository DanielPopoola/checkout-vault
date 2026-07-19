# TRADEOFFS.md

## Isolation Mechanism

I chose semaphore + timeout per dependency to control the number of in-flight requests in a
downsteam service. This keeps pending requests from being queued indefinetly which can lead to out-of-memory error, as requests pile up. I used virtual threads instead as they are much lighter to spin up therefore moving scarcity to something else: the httpclient's workers. So the core isolation mechanism is a semaphore to cap concurrency and separate `HttpClient` per 
depedency.

## Pool Sizing

Permits (5 per dependency) came from a stated assumption, not a
production measurement — there's no real traffic to measure against a
mock system. I assumed 10 req/s baseline load and 50ms baseline latency
per dependency (both my own choices as test designer). Using Little's
Law (`L = λW`): 10 × 0.05 = 0.5 average concurrent calls in flight per
dependency at steady state. I padded roughly 10x that average (5
permits) for burst headroom, landing in the same range as Hystrix's own
wiki guidance (pools sized ~10, typically 1-2 threads active for ~40ms
calls at steady state).

I sized all three dependencies equally at the start and did not run an
experiment to test whether asymmetric sizing (e.g., larger permits for
required dependencies) would change behavior — this is a real gap,
noted honestly rather than implied as tested. See "What You'd Do
Differently."

## Bulkhead vs. Circuit Breaker

Ran `slow` mode specifically (10s delay, not `hang`) at 5x and 10x
baseline load:

- 50 req/s: baseline p99 141ms/100% success; fault-run p99 120ms/99.33%
  success. PASS.
- 100 req/s: baseline p99 725ms/97.04% success (already degraded before
  any fault injection — see "What Breaks First"); fault-run p99
  686ms/97.93% success. PASS.

This is the concrete case a failure-rate circuit breaker would miss
entirely: every `slow` call still returns 200 eventually — nothing ever
"fails" in a way a breaker counts, so it would never trip. The bulkhead
doesn't need a failure signal to act; it constrains concurrency
regardless of whether calls are failing or just slow. Earlier `hang`
mode data makes the mechanism visible directly: per-dependency
Prometheus metrics showed Fraud/Payment latency untouched (p50/p99
51-60ms) while Inventory's own `bulkhead_full` + `timeout` outcomes
covered roughly half its traffic. `bulkhead_full` specifically is a
fast, local rejection — no network call attempted at all — categorically
different from a circuit breaker, which requires calls to have already
failed before it acts.

## What Breaks First

At 10x baseline load (100 req/s), Fraud/Payment-path stats still passed
verification, but the **baseline itself degraded** — p99 jumped to
725ms and success rate dropped to 97.04% with nothing faulted at all.
The fault-run wasn't meaningfully worse than this already-strained
baseline. This means at 10x load, something un-isolated is the new
shared bottleneck, independent of any single dependency's health — not
Inventory's semaphore, since Inventory wasn't even faulted yet in the
baseline phase. The most likely candidates, based on earlier
diagnosis at lower load (virtual thread scheduling, the shared
`checkoutExecutor`, or JVM-level pressure) weren't conclusively
isolated at 10x — this is an honest open question rather than a solved
one, and matches the guideline's expectation that this section is often
where the real lesson shows up.

## What I'd Do Differently

- Test asymmetric permit sizing (smaller Inventory, larger Fraud/
  Payment) with real load-test evidence, instead of leaving pool sizing
  and required/optional status as an untested assumption.
- Separate the permit-wait timeout from the call timeout — currently
  they reuse the same duration, so worst-case latency for a single call
  can approach 2x the configured timeout.
- Diagnose the 10x-load baseline degradation directly (thread dump at
  100 req/s, same method used to find the original shared-`HttpClient`
  bug) rather than leaving it as an open question.
- At much larger scale (e.g. 100+ dependencies), separate
  platform-thread `HttpClient`s per dependency stops being viable
  (thread-stack memory cost scales linearly) — a shared `HttpClient`
  with a virtual-thread executor, relying on the semaphore as the sole
  isolation mechanism, would be worth testing, with the tradeoff that
  connection-level isolation under a shared virtual-thread executor is
  unverified.
- Add a real circuit breaker (e.g. resilience4j) alongside the bulkhead
  for the `error` case specifically, to distinguish "temporarily
  overloaded" from "actually down" — bulkhead alone doesn't make that
  distinction.
