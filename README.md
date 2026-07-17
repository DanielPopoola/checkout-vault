# Project: checkout-vault
 
## Project Overview
 
You are building the backend for a checkout endpoint. To approve or reject a
single incoming order, your service must call three downstream dependencies
over HTTP, synchronously, before it can respond: **Fraud** (risk decision —
required), **Payment** (charges the customer — required), and **Inventory**
(stock/shipping-eligibility check — optional for the response).
 
This mirrors a real, published checkout architecture: a Payment service that
calls a Fraud service to obtain a risk decision and score before proceeding,
and a downstream Shipping/Inventory check that happens after payment is
approved, on a separate path:
 
> "The *User* submits a payment request to the *Payment* service, which
> calls the *Fraud* service via gRPC to obtain a risk decision and score...
> For approved payments, *Order Confirmation* calls the *Shipping* service
> via gRPC to create a shipment."
> — [Fuzzing Microservices in Face of Intrinsic Uncertainties, arXiv](https://arxiv.org/pdf/2603.02551)
 
The three downstream dependencies are not real services — they're mock
servers you control (scope below). The scenario: Inventory degrades (goes
slow, not down) while Fraud and Payment stay healthy. In a naive
implementation, this can still degrade or break checkout entirely, including
requests that never needed Inventory's answer to respond. Your job is to
prove this failure is real, then fix it so a degraded Inventory service can
only ever affect its own slice of the system — without weakening the parts
of checkout that must genuinely fail closed.
 
> "Hystrix uses separate, per-dependency thread pools as a way of
> constraining any given dependency so latency on the underlying executions
> will saturate the available threads only in that pool."
> — [How it Works, Netflix/Hystrix Wiki](https://github.com/Netflix/Hystrix/wiki/How-it-Works)
 
## What You'll Learn
 
- Resource isolation under partial degradation (Bulkhead pattern)
- The distinction between resource exhaustion and failure detection (why
  Bulkhead is not a substitute for Circuit Breaker, and vice versa)
- Thread pool isolation vs. semaphore isolation, and when each is the right
  tool
- Distinguishing "required, must fail closed" dependencies from "optional,
  can degrade gracefully" ones, and designing isolation differently for each
- Load testing and verifying isolation empirically, not by inspection
- Pool sizing as a real engineering decision, not a guess
## Core Task
 
Build an HTTP API — `POST /checkout` — that, per request, calls three
downstream mock services synchronously and assembles a response:
 
1. **Fraud** — required, hard gate. If it fails or times out, the checkout
   must be rejected. You cannot approve an order without a fraud decision —
   this is a deliberate business rule to design around, not a shortcut.
2. **Payment** — required. If it fails or times out, the checkout must be
   rejected. No successful payment call, no order.
3. **Inventory** — optional for the response. If it fails or times out, the
   checkout can still succeed; the response should indicate stock/shipping
   status is "pending confirmation" rather than blocking the order.
Build the naive version first — one shared connection pool / HTTP client /
worker limit across all three outbound calls — and produce evidence (see
Deliverables) that a hung Inventory dependency degrades Fraud and Payment
calls too, even though nothing is wrong with either of them.
 
Then fix it: isolate each dependency's outbound calls into its own resource
pool, sized deliberately (not identically) for its role, with appropriate
timeouts and a defined fallback behavior for Inventory only — Fraud and
Payment do not get a "soft" fallback, since failing open on either is a
business risk, not just a UX inconvenience.
 
## Mock Service Scope
 
You are not building real fraud, payment, or inventory logic. Each mock is a
minimal HTTP server whose only real job is to be **controllable** — it needs
to simulate the failure conditions your gateway must survive, nothing more.
 
Each of the three mocks (Fraud, Payment, Inventory) must expose:
 
- **One "business" endpoint** the checkout service calls, e.g.
  `POST /fraud/score`, `POST /payment/charge`, `GET /inventory/:sku`.
  The response body can be trivial/hardcoded (e.g. Fraud always returns
  `{ "risk": "low" }` when healthy) — the content of the response is not
  the point, its *timing and availability* are.
- **One control endpoint** to change its current behavior at runtime, e.g.
  `POST /control` accepting a mode:
  - `normal` — responds immediately (or with a small fixed baseline latency).
  - `slow` — responds successfully, but only after a configurable delay
    (this is the important one — see below).
  - `hang` — never responds at all (holds the connection open indefinitely,
    simulating a dependency that isn't even timing out cleanly).
  - `error` — responds immediately with an HTTP 5xx.
- No persistence, no real business logic, no database. Each mock can be a
  single-file HTTP server.
**Prioritize the `slow` mode over `error` mode when designing your fault
injection scenarios.** A dependency that fails outright is comparatively
easy to reason about and is the case a naive circuit breaker already
handles. A dependency that is merely slow-but-eventually-successful is the
case that actually exhausts a shared pool while looking, request-by-request,
like nothing is wrong — that's the failure this project exists to make you
confront.
 
## Implementation Requirements
 
- Language: your choice (Go, Python, or Java, consistent with your other
  projects).
- The checkout service must make all three downstream calls per request; do
  not fake or skip any of them.
- Mocks must be separate processes/servers, not in-process stubs — the
  isolation you're building only matters if the calls are real network calls
  with real timeouts.
- The naive (shared-pool) version and the fixed (isolated-pool) version must
  both be runnable, ideally via a config flag or separate entrypoints, so the
  before/after can be demonstrated directly.
- Include a load-generation script or harness capable of firing concurrent
  requests at the checkout endpoint while independently switching each
  mock's mode mid-run (via its control endpoint).
- Include a verifier: an automated check (not a manual read of logs) that
  asserts, while Inventory is in `slow` or `hang` mode, Fraud- and
  Payment-dependent latency and success rate remain within an acceptable
  band of baseline.
Your approach to structuring the code, choice of isolation mechanism (thread
pool, semaphore, etc.), and pool sizing strategy are left to you — this is
part of what TRADEOFFS.md should explain and justify.
 
## Deliverables
 
1. **Source code** for the checkout service and the three mock dependencies.
2. **Load test / verifier harness** and its output, demonstrating:
   - The naive version failing: Fraud/Payment latency or error rate visibly
     degrading while only Inventory is in `slow` or `hang` mode.
   - The fixed version holding: Fraud/Payment latency and error rate staying
     flat under the same fault injection.
3. **TRADEOFFS.md** (see below).
4. **README** (this becomes your own project's README once built) covering
   how to run the naive version, the fixed version, and the verifier.
## Resources
 
Read these while you build, not before.
 
**Primary sources — read these first:**
- [Netflix/Hystrix Wiki — How it Works](https://github.com/Netflix/Hystrix/wiki/How-it-Works):
  the actual reasoning from the team that built the reference implementation
  of this pattern, including why they chose threads over semaphores.
- [Netflix/Hystrix — HystrixThreadPool.java](https://github.com/Netflix/Hystrix/blob/master/hystrix-core/src/main/java/com/netflix/hystrix/HystrixThreadPool.java):
  read the comments — real guidance on how to size a pool.
- [Fuzzing Microservices in Face of Intrinsic Uncertainties, arXiv](https://arxiv.org/pdf/2603.02551):
  the real checkout architecture (Payment → Fraud via gRPC, Order
  Confirmation → Shipping) this project is scoped from.
**Pattern reference:**
- [Bulkhead pattern — Azure Architecture Center](https://learn.microsoft.com/en-us/azure/architecture/patterns/bulkhead)
**Adjacent, don't conflate:**
- [Circuit Breaker pattern — Azure Architecture Center](https://learn.microsoft.com/en-us/azure/architecture/patterns/circuit-breaker):
  read this to understand what Bulkhead is *not*. Circuit Breaker stops
  sending traffic to something known-bad. Bulkhead contains the blast radius
  of something currently bad, detected or not. This project is deliberately
  scoped around the "slow but technically succeeding" case (the `slow` mock
  mode), where a naive circuit breaker may not even trip.
**If you get stuck on concurrency primitives:**
- Go: [sync package docs](https://pkg.go.dev/sync), `context` for deadlines/cancellation.
- Python: `asyncio.Semaphore`, `concurrent.futures.ThreadPoolExecutor`.
- Java: `java.util.concurrent` — `Semaphore`, `ExecutorService`.
