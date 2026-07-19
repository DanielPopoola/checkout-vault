# checkout-vault

A bulkhead isolation demo: a checkout service that calls three downstream
dependencies (Fraud, Payment, Inventory) over HTTP, and proves — with real
load tests and live metrics, not just theory — that a hung Inventory
dependency can silently degrade Fraud and Payment too, unless each
dependency's outbound calls are physically and logically isolated from
each other.

Built as a from-scratch learning project to understand the Bulkhead
pattern, the difference between resource isolation and failure detection
(Bulkhead vs. Circuit Breaker), and pool sizing as a real, evidence-backed
engineering decision rather than a guess.

## What's actually being proven

1. **Naive version**: Fraud, Payment, and Inventory share one
   `HttpClient` instance under the hood. When Inventory is set to `hang`
   mode, its stuck connections consume worker threads from the *same*
   shared pool Fraud and Payment need — even though Fraud and Payment are
   completely healthy. Confirmed via live thread dumps during load
   testing, not just inferred from latency numbers.
2. **Isolated version**: each dependency gets its own dedicated
   `HttpClient` (its own connection pool/executor) *and* its own
   `Semaphore` (bounding concurrent in-flight calls, with a timeout so a
   caller never waits unboundedly for a permit). A hung Inventory can
   now fail up to 100% of its own traffic without Fraud or Payment's
   latency or success rate moving at all — confirmed with real
   Prometheus metrics captured per dependency, not just end-to-end
   numbers.

## Architecture

```
mocks/            Go — one parameterized mock server, run 3x as
                   Fraud/Payment/Inventory. Each supports normal/slow/
                   hang/error modes, switchable at runtime via /control.

checkout-service/  Java 21 + Spring Boot (virtual threads enabled).
  config/          CheckoutVaultProperties — the only place
                   application.yml is read; validated at startup.
  client/          DependencyClient interface + HTTP implementation,
                   decorated with IsolatedDependencyClient (semaphore +
                   Micrometer instrumentation). HttpClientConfig is the
                   ONE place isolation.mode is consulted — naive mode
                   shares one HttpClient across all three dependencies,
                   isolated mode gives each its own.
  service/         CheckoutService — orchestrates Fraud (gate) →
                   Payment (gate), Inventory fired fire-and-forget,
                   concurrently, never blocking the response.
  handler/         POST /checkout controller (thin, transport only).

loadtest/          Go — fires concurrent requests at /checkout, injects
                   an Inventory fault mid-run via its /control endpoint,
                   compares before/after stats against fixed thresholds,
                   prints PASS/FAIL.

observability/     Prometheus scrape config + Grafana dashboard
                   (provisioned automatically) showing per-dependency
                   latency, call outcome rate, and live semaphore permit
                   availability — the last one is the bulkhead itself,
                   visualized.
```

## Call shape

- **Fraud** is called first. Fails or times out → checkout is rejected
  immediately, **Payment is never called** (charging an order already
  known to be rejected would be exactly the "failing open" risk this
  project is designed to avoid).
- **Payment** is only called if Fraud succeeds. Fails or times out →
  rejected.
- **Inventory** is fired at the very start of the request, concurrently,
  independent of Fraud/Payment's outcome — and its result is never
  awaited by the response. This matches the real checkout architecture
  the project is scoped from: Inventory/Shipping checks happen on a
  separate path, after payment, not synchronously in the checkout
  response. Every approved order reports `"pending confirmation"` for
  inventory status, regardless of whether Inventory succeeded, failed,
  or was mid-flight when the response went out.

## Isolation mechanism

**Semaphore + timeout per dependency, not a dedicated OS thread pool per
dependency.** With Java 21 virtual threads enabled, a raw thread-pool
bulkhead (Hystrix's original approach) doesn't map cleanly — virtual
threads are nearly free, so "thread pool size" stops being the scarce
resource. What's still finite: the underlying HTTP client's connection
handling. A live thread dump during load testing confirmed this
directly — a shared `HttpClient` has a real, bounded internal worker
pool (not the "practically unbounded" behavior the general docs
describe), and that pool is what naive mode leaves accidentally shared.

The fix pairs two independent mechanisms:
- **A dedicated `HttpClient` per dependency** (physical separation) —
  contains the blast radius, so Inventory's hung connections can never
  compete with Fraud/Payment's for the same worker.
- **A `Semaphore` per dependency** (concurrency + fail-fast) — bounds
  how many calls to that dependency can be in flight at once, and
  ensures a caller never queues unboundedly (which risks OOM under
  sustained load) — it fails fast once the bulkhead is full, rather than
  silently piling up.

See `TRADEOFFS.md` for the full reasoning, including where this design
was validated against Netflix/Hystrix's own documented tradeoffs and
resilience4j's real bulkhead implementations.

## Running it

### 1. Start the mocks

```bash
make mocks-up      # starts Fraud (:8080), Payment (:8081), Inventory (:8082)
make mocks-down     # stops them
```

### 2. Start checkout-service

Set `checkout-vault.isolation.mode` in
`checkout-service/src/main/resources/application.yml` to `naive` or
`isolated`, then:

```bash
make checkout-run
```

Runs in the foreground on `:8090`.

### 3. Run the load test / verifier

```bash
make loadtest                                            # defaults: 10 req/s, 15s, hang
make loadtest RATE=50 FAULT_MODE=hang DURATION=15s
make loadtest RATE=50 FAULT_MODE=slow FAULT_DELAY_MS=3000
```

Runs a baseline phase (all mocks `normal`), then injects the fault into
Inventory, then compares Fraud/Payment-path latency and success rate
between the two phases. Prints PASS/FAIL against fixed thresholds (see
`TRADEOFFS.md` for the exact numbers and reasoning).

### 4. Observability (Prometheus + Grafana)

```bash
make observability-up
```

- Prometheus: http://localhost:9090 (Status → Targets to confirm
  `checkout-service` is `UP`)
- Grafana: http://localhost:3000 (anonymous access enabled; dashboard
  "checkout-vault — Bulkhead Isolation" is auto-provisioned)

Run the load test while watching the dashboard live to see Inventory's
permit gauge pin at 0 while Fraud/Payment's stay near full capacity.

```bash
make observability-down
```

## What's NOT built (by design — see PLAN.md non-goals)

- No real fraud/payment/inventory business logic — mocks return
  hardcoded payloads; only their timing/availability is simulated.
- No persistence or database.
- No actual circuit breaker implementation — discussed conceptually in
  `TRADEOFFS.md` as a contrast to Bulkhead, not built alongside it.
- No per-tenant isolation.

## Further reading

- `TRADEOFFS.md` — the project's actual deliverable: isolation mechanism
  justification, pool sizing derivation, bulkhead-vs-circuit-breaker
  gap demonstrated with real fault injection, what breaks first under
  10x load, and what would change with more time.