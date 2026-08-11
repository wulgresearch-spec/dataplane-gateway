# 20 — Reliability Engine (Dependable-Invocation Engine · Domain C2)

**Document:** Component Implementation Architecture — Reliability Engine
**Project:** Reliability-First AI Gateway
**Status:** **FROZEN**
**Version:** 1.0 (frozen 2026-07-21)
**Bounded context:** **C2 — Reliability** (Core Domain, `05`/`06`)
**Module:** `dp-reliability-engine` — a **module inside the Data Plane** deployable, co-located per **AD-020/AD-006** — **not** a separate service, **owns no store**.
**Audience:** Reliability/SRE/data-plane engineers, security, compliance, QA, reviewers
**Classification:** Internal — Component Architecture
**Builds on (approved, frozen):** `00`–`19` and ADRs **AD-001…AD-023**. Everything here is **additive**. Introduces **no new architecture decision, no new service, no new module, no new store, no ownership/event/data-ownership change, no consistency/security/deployment/observability/testing-model change, and no provider coupling.** Every RE-Dx is a *component-internal implementation decision* inside the already-frozen C2, expressed through hexagonal ports (AD-002).

> **What this is.** The complete implementation architecture of the **Reliability Engine** — the engine that makes provider invocation **dependable** without ever compromising correctness. It sits at the center of the frozen request flow (`06`: C1 → **C2** → C3): it receives the **immutable routing decision** from the Provider Router (`19 §30.1`), **executes** invocation through the Provider Adapters, and applies **retry, failover, circuit-breaking, hedging, timeouts, and graceful degradation** — always **within** the routing decision's policy-safe candidate list, always **through** StreamGuard (`18`) and SchemaLock (`17`), never around them. Every major decision carries **Problem · Decision · Alternatives · Why selected · Trade-offs · Failure modes · Security impact · Performance impact · Enforcement**.
>
> **THE RELIABILITY-ENGINE INVARIANT (RE-INV):** *The Reliability Engine must never compromise correctness in pursuit of availability. It may retry, fail over, hedge, circuit-break, abandon, or degrade — but it must never duplicate successful execution, violate residency/compliance/tenant-policy, bypass Provider Router / StreamGuard / SchemaLock, exceed retry budgets, or create retry storms. On uncertainty it fails closed and surfaces.* This is the reliability-layer realization of **AD-016 (reliability first)** and **AD-018 (non-bypassable correctness)** — reliability serves correctness, never the reverse (`00`/`11` CP-1).

---

## Table of Contents
**A. Charter** (§1–5) · **B. Domain & Interfaces** (§6–7) · **C. Major Decisions** (RE-D1…RE-D12) · **D. Lifecycles** (§8–12) · **E. Classification & Accounting** (§13–17) · **F. Interactions** (§18–24) · **G. Correctness Properties** (§25–27) · **H. Performance & Concurrency** (§28–31) · **I. Security** (§32) · **J. Observability** (§33–36) · **K. Data & Integration** (§37–43) · **L. Failure & Recovery** (§44–45) · **M. Testing** (§46) · **N. Build-Failing Rules** (§47) · **O. Operations** (§48–50) · **P. Traceability** (§51) · **Q. Reviews**

---

## A. Charter

### §1 — Purpose
The Reliability Engine **executes provider invocation dependably**: it drives the request through the Provider Router's chosen candidate(s), applying retry / failover / hedging / circuit-breaking / timeouts / brownout, so that transient provider failures do not become request failures — **without ever** duplicating a successful execution, crossing a policy boundary, or bypassing correctness (RE-INV). It realizes C2's "dependable invocation" responsibility (`05`/`06`), sitting between C1 (routing) and C3 (correctness) on the hot path (AD-017).

### §2 — Scope
- **In scope:** execute the routing decision (drive invocation via adapters); classify invocation outcomes; **retry** (bounded, budgeted, backed-off); **fail over** across the Router's immutable candidate list; **circuit-break** per provider/route; **hedge** (bounded, duplicate-suppressed) where permitted; enforce **timeouts** (connect / first-byte / stream-inactivity / total) and **cancellation**; **brownout** (graceful degradation); consume provider **health** and **rate-limit** signals; **account** retry budgets; emit reliability telemetry + the frozen invocation-outcome events (`06 §23`).
- **Applies to:** every request on the hot path, after the Router emits a decision, wrapping the actual provider invocation.

### §3 — Responsibilities
1. Receive the **immutable `RoutingDecision`** from C1 (`19 §30.1` RC-1) and execute against its ordered candidate list.
2. Drive invocation **through the Provider Adapter port**, **through StreamGuard** (streaming transport, `18`) — never a direct provider call.
3. **Classify** each invocation outcome (retryable / non-retryable / fatal, §13).
4. Apply **retry / failover / hedge / circuit-break / timeout / brownout** within budgets and RE-INV.
5. **Never** duplicate a successful execution; enforce **idempotency** on retries/hedges (§21/§25).
6. Fail closed and surface on exhaustion or uncertainty (§44).

### §4 — Out of Scope (what the Engine never does)
- **Routing / candidate selection** — owned by the **Provider Router** (C1, `19`). The Engine executes within the given list; it **never invents candidates** (RE-D3).
- **Correctness (schema/streaming/tool-argument validation)** — owned by **C3** (SchemaLock `17`, StreamGuard `18`). The Engine invokes *through* them; it never validates or repairs content.
- **Provider connection/auth/credentials** — owned by **Adapters + Secrets (C14)**. The Engine is **credential-free** (RE-D10).
- **Provider health authorship** — owned by **Provider Registry** (`06 §23`). The Engine **consumes** health snapshots (§16).
- **Cost/billing computation** — owned by **C5/C8**. The Engine consumes normalized cost/budget only where hedging/retry cost matters (§24); it computes no bill.
- **Policy/compliance/residency authorship** — owned by **C4** (`06`, AD-019). The Engine **inherits** the Router's already-policy-safe candidate list and never re-derives or relaxes policy.
- **Rate-limit policy authorship** — owned by **C4/C2-policy-with-Governance** (`06`); the Engine **enforces/executes** rate-limit backpressure, it does not author the limits.

### §5 — What the Engine is NOT
| Reliability Engine (C2 — execution) | Not the Engine |
|---|---|
| ✓ retry / failover / hedge / circuit-break / timeout / brownout | ✗ choose *which* providers are eligible (C1 Router) |
| ✓ execute invocation via adapter + StreamGuard | ✗ validate schema / streaming / tool args (C3) |
| ✓ enforce retry budgets, prevent storms | ✗ author policy/compliance/residency (C4) |
| ✓ consume health / rate-limit / cost snapshots | ✗ own health / cost / billing (Registry / C5 / C8) |
| ✓ credential-free execution | ✗ hold secrets or call provider SDKs/HTTP (Adapters + C14) |

---

## B. Domain & Interfaces

### §6 — Domain model (reliability subdomain)
Pure domain types (no infrastructure, `11` hexagonal); immutable value objects unless noted.

| Type | Kind | Description |
|---|---|---|
| `InvocationPlan` | VO | Derived from the immutable `RoutingDecision` (`19 §14`): ordered candidate list + TTL + retry/failover/hedge policy + budgets |
| `AttemptContext` | VO | A single invocation attempt: candidate, attempt#, deadline, idempotency key, correlation ids (`07 §6`) |
| `OutcomeClass` | enum | Classification of an attempt outcome (§13): `SUCCESS`, `RETRYABLE`, `NON_RETRYABLE`, `TIMEOUT`, `RATE_LIMITED`, `CIRCUIT_OPEN`, `FATAL` |
| `RetryBudget` | VO | Per-request + shared-window budget state (§14) — read/decremented, never a shared mutable counter across requests (see §30) |
| `CircuitState` | enum + VO | `CLOSED` / `OPEN` / `HALF_OPEN` per provider-route (§10) — sourced from a shared reliability snapshot/tier (owned model, §16/§30) |
| `HedgePlan` | VO | If hedging permitted: hedge delay, max in-flight, winner-selection + loser-cancel rules (§11) |
| `TimeoutBudget` | VO | connect / first-byte / stream-inactivity / total deadlines (§9) |
| `InvocationResult` | VO | Terminal outcome handed onward: success (winning attempt) or surfaced failure (RE-INV) |
| `ReliabilityPolicy` | VO (injected) | Resolved retry/failover/hedge/circuit/timeout policy + budgets, from cached snapshot (authored by C4/Config, AD-022) |

**Aggregate:** `InvocationExecution` — the transient per-request aggregate coordinating attempt → classify → retry/failover/hedge → circuit/timeout → resolve; holds **no shared mutable state across requests** (AD-021).

### §7 — Public interfaces (ports — AD-002 hexagonal)
**Inbound (called by the data-plane pipeline, after the Router emits a decision, wrapping invocation):**
```
ReliabilityEnginePort:
  execute(RoutingDecision, RequestContext) -> InvocationResult   // drives invocation; fail-closed
```
**Outbound (implemented by adapters elsewhere; the Engine holds no credentials, makes no direct HTTP):**
```
ProviderInvocationPort   // -> Provider Adapter (C1): invoke a chosen candidate (credentials internal to adapter)
StreamTransportPort      // -> StreamGuard (18): streaming invocation transits StreamGuard (never a direct stream)
HealthSnapshotPort       // <- Provider Registry: provider health/availability (snapshot, AD-022)
CircuitSnapshotPort      // <- shared reliability tier: circuit state per route (snapshot/coordinated, §30)
RateLimitSnapshotPort    // <- C4/C2-policy: rate-limit state/limits (snapshot)
CostSnapshotPort         // <- C5/C8: normalized cost/budget (snapshot) — for hedge/retry cost gating (§24)
ReliabilityPolicyPort    // <- C4/Config: resolved retry/failover/hedge/circuit/timeout policy (snapshot, AD-019/022)
RetryBudgetPort          // shared retry-budget accounting (§14/§30) — coordinated, not a naive mutable counter
AuditSinkPort            // -> Audit (C10): invocation-outcome events (content-free)
MeteringSinkPort         // -> Metering (C5): attempt/retry/hedge counters (content-free)
TelemetryPort            // -> Observability (C9): reliability metrics/traces (content-free)
ClockPort / IdPort       // deterministic time / ids
```
- **Enforcement:** ArchUnit — the Engine imports **no** provider SDK (AU-06), **no** HTTP client, **no** persistence driver (AU-07), **no** secrets/credential API, **no** schema/streaming-validation types (those are C3). All provider I/O goes through `ProviderInvocationPort`/`StreamTransportPort`. **Build-Fail:** any forbidden import; a direct provider/network/stream call.

---

## C. Major Decisions

### RE-D1 — Single retry authority (the only request-level retry owner)
- **Problem:** multiple modules retrying independently is the classic retry-storm/duplication disaster (`06 D-5`).
- **Decision:** the Reliability Engine is the **sole owner of request-level retry and failover**. No other module performs request retries: SchemaLock's guided retry (`17 §24`) is a *content* re-ask that runs **through** the Engine's execution (it does not itself loop invocation); StreamGuard's transport retry (`18 §26`) is **pre-first-emission connection-level only** and is coordinated under the Engine's budget; the Router (`19`) **never retries** (`19 §30.1` RC-9). The precedence is the frozen forward-only chain (`17 §24.1`/`18 §49.1`/`19 §30.1`): **transport (pre-emission) → reliability → guided** — **exactly one authority active at a time**, control passes forward, never loops back.
- **Alternatives:** per-module retry — rejected (storms, duplication, `06 D-5`); a separate retry service — rejected (network hop on hot path, AD-020).
- **Why selected:** one budget, one authority, one auditable retry locus — storm-proof by construction.
- **Trade-offs:** all retry logic concentrates in C2 (correct — that is C2's charter).
- **Failure modes:** budget exhausted ⇒ surface (§44); never a hidden second loop.
- **Security impact:** single locus = single place to bound amplification.
- **Performance impact:** bounded attempts; no duplicated work.
- **Enforcement:** ArchUnit — no request-retry loop outside the Engine; the Engine holds the shared budget (§14). **Build-Fail:** a request-level retry loop in any module other than the Engine; a retry not counted against the shared budget (RE-A6/A7).

### RE-D2 — Retry policy (retryable classification, backoff, jitter, ceilings, budgets)
- **Problem:** retrying the wrong failures (or too aggressively) amplifies outages and duplicates side effects.
- **Decision:** a precise, **policy-driven** retry model (values from `ReliabilityPolicy` snapshot / operational baseline `16 §I.1`):
  - **Retryable:** transient transport failures (connect fail, first-byte timeout **before** any downstream emission, `503`/overload, provider `RATE_LIMITED` with retry-after, idempotent `TIMEOUT`).
  - **Non-retryable:** `4xx`-class request errors (bad request, auth — surfaced), **content non-conformance** (that is SchemaLock's guided retry, not a transport retry, §22), any failure **after first downstream stream emission** (`18 §26` point-of-no-return), and any **non-idempotent** operation without an idempotency guarantee (§21).
  - **Exponential backoff + full jitter** (governed base/cap; jitter is the *only* permitted randomness, RE-D9) to avoid synchronized retry waves.
  - **Retry ceilings:** hard max attempts per request.
  - **Retry budgets:** shared **≤10% retry budget** (`15` T-017) — enforced across the request population (§14), not just per-request.
- **Alternatives:** fixed retry count without budget — rejected (storms under correlated failure); no jitter — rejected (thundering herd).
- **Why selected:** transient recovery without amplification; honors the frozen budget.
- **Trade-offs:** conservative retryable set means some recoverable-but-ambiguous failures surface — correct for RE-INV (never duplicate).
- **Failure modes:** ceiling/budget hit ⇒ surface.
- **Security impact:** budgets bound DoS-amplification and cost.
- **Performance impact:** backoff adds latency to retried requests (bounded by total-timeout, §9).
- **Enforcement:** classification table + budget gate tested (`15` T-017/T-018). **Build-Fail:** retrying a non-retryable class; a retry exceeding the ceiling/budget; randomness other than governed jitter.

### RE-D3 — Failover (only within the Router's immutable candidate list)
- **Problem:** failover that invents destinations can cross residency/compliance boundaries — a compliance breach.
- **Decision:** the Engine fails over **only across the ordered, immutable candidate list** in the `RoutingDecision` (`19 §14/§30.1`), **strictly in the given order**, **never inventing a candidate**, **never re-ranking**. Because every candidate is already hard-filtered by the Router (tiers 1–6, `19 §11`), **any failover is residency/compliance/policy-safe by construction**. On **candidate-list exhaustion** or **decision TTL expiry**, the Engine does **not** invent routes — it **requests a fresh decision** from the Router (`19 §30.1` RC-6) or **surfaces** (§44). No cross-residency, no cross-compliance, ever.
- **Alternatives:** Engine picks a "next best" provider itself — rejected (ownership drift to C1; residency risk); ignore the Router list under load — rejected (RE-INV violation).
- **Why selected:** failover diversity without any boundary-crossing risk; clean C1/C2 ownership (`19 §30.1`).
- **Trade-offs:** a single-candidate decision has no failover diversity — correct (surfaced availability, never a residency breach).
- **Failure modes:** list exhausted ⇒ fresh decision or surface.
- **Security/compliance impact:** residency/compliance confinement preserved under failover (the key `19` guarantee, honored here).
- **Performance impact:** failover reuses the in-TTL list (no Router round-trip until refresh).
- **Enforcement:** every failover target ∈ the decision's candidate list; residency tests (`15` T-045). **Build-Fail:** a failover to a candidate not in the immutable list; any invented/cross-boundary route (RE-A4/A5).

### RE-D4 — Circuit breakers (closed / open / half-open, per route)
- **Problem:** hammering an unhealthy provider wastes budget, adds latency, and worsens outages.
- **Decision:** a **per-provider-route circuit breaker** with states **CLOSED** (normal), **OPEN** (fail fast — skip this candidate, move to next in the list), **HALF_OPEN** (allow limited **probe** requests). Transitions: CLOSED→OPEN on a **failure-rate/consecutive-failure threshold** (governed); OPEN→HALF_OPEN after a **cool-down**; HALF_OPEN→CLOSED on probe success(es), →OPEN on probe failure. Circuit state is **shared/coordinated** (a route unhealthy for one request is likely unhealthy for others) via a reliability snapshot/tier (§30) — **not** naive per-request state. An OPEN circuit makes that candidate ineligible for the moment; the Engine moves to the **next candidate in the Router's list** (never outside it).
- **Alternatives:** no breaker (retry-hammer) — rejected; per-request-only breaker — rejected (each request re-learns the outage).
- **Why selected:** fail-fast on known-bad routes, bounded probing for recovery, shared learning.
- **Trade-offs:** shared circuit state needs coordination (bounded staleness acceptable; a stale-closed breaker just costs one failed attempt that then trips it).
- **Failure modes:** all candidates OPEN ⇒ surface (`NO_AVAILABLE_PROVIDER` analog) or fresh decision.
- **Security/Performance impact:** reduces wasted attempts + amplification.
- **Enforcement:** state-machine tests + chaos (`15` T-019). **Build-Fail:** invoking a candidate with an OPEN circuit; a breaker without half-open probing.

### RE-D5 — Timeouts (connect / first-byte / stream-inactivity / total) + cancellation
- **Problem:** unbounded waits exhaust resources and blow the latency budget.
- **Decision:** **layered deadlines** (values from policy/baseline): **connect timeout**, **first-byte (TTFB) timeout**, **stream-inactivity timeout** (delegated to StreamGuard's inactivity budget, `18 §24` — the Engine sets/propagates, StreamGuard enforces on the transport), and a **total request timeout** (the overall deadline all attempts + backoff must fit within). **Cancellation** (client/pipeline) is cooperative and propagates to StreamGuard/adapters to stop provider work + cost (`18 §27`). A breach ⇒ classify (`TIMEOUT`) → retry if pre-emission & within budget, else **surface**.
- **Alternatives:** single total timeout only — rejected (a slow-first-byte or dribble stalls without an early signal); no cancellation — rejected (cost/resource leak).
- **Why selected:** bounds every phase; composes with StreamGuard's transport timeouts without duplication.
- **Trade-offs:** more deadline knobs (governed in baseline).
- **Failure modes:** total deadline exceeded ⇒ surface (no further attempts).
- **Security impact:** slow-loris/dribble defense (with StreamGuard, `18 §24`).
- **Performance impact:** protects tail latency + resource pool.
- **Enforcement:** timeout fault tests (`15` T-018). **Build-Fail:** an invocation path without a total deadline; stream-inactivity re-implemented in the Engine instead of delegated to StreamGuard.

### RE-D6 — Hedged requests (bounded, duplicate-suppressed, never violating RE-INV)
- **Problem:** tail latency can be cut by racing a second attempt — but naive hedging **duplicates execution** and **doubles cost**, risking RE-INV.
- **Decision:** hedging is **off by default** and **only permitted** when: (a) policy enables it for the route/tenant, (b) the operation is **idempotent or duplicate-suppressible** (§21/§25), (c) a **cost/budget gate** allows the extra spend (§24), and (d) **it is forbidden for streaming after first emission** and for any non-idempotent side-effecting call. When permitted: launch a **second attempt after a hedge delay** (e.g. p95 latency) to a **different candidate in the list**, cap **max in-flight** (typically 2), **suppress duplicates** (the first success wins; the loser is **cancelled** — `18 §27` — and its result discarded, never delivered), and **count all hedge attempts against the shared budget** (§14). A hedge **never** produces two delivered results and **never** double-counts a successful side effect (§25).
- **Alternatives:** always hedge — rejected (cost/duplication/storm); never hedge — rejected (leaves tail-latency wins on the table).
- **Why selected:** tail-latency reduction **without** violating exactly-once/cost when strictly gated.
- **Trade-offs:** extra cost/complexity — gated and budgeted; disabled where unsafe.
- **Failure modes:** both hedges fail ⇒ normal failover/surface; a hedge that cannot be duplicate-suppressed ⇒ **not launched** (fail-safe).
- **Security/Cost impact:** budget + cost gate bound amplification; loser-cancel bounds provider cost.
- **Performance impact:** cuts tail latency for eligible requests.
- **Enforcement:** hedge tests assert single delivery + budget accounting + loser-cancel (`15` T-017/T-018). **Build-Fail:** hedging a non-idempotent/non-suppressible op; a hedge after first stream emission; a hedge not counted against the budget; two delivered results.

### RE-D7 — Brownout mode (graceful degradation under provider instability)
- **Problem:** under widespread provider degradation, normal retry/hedge behavior amplifies the storm.
- **Decision:** a **brownout mode** engages when reliability signals cross a threshold (widespread circuit-open / high failure rate): the Engine **degrades gracefully** — **reduces retry aggressiveness** (lower ceilings, longer backoff), **disables hedging** (stop adding load), **prefers fail-fast** on known-bad routes, and **sheds** the lowest-priority load per policy — all while **never** compromising correctness or crossing a boundary. Brownout is **bounded, observable, and reversible** (auto-exits when signals recover). It is a load-protection posture, not a correctness change.
- **Threshold ownership & values (resolves REM-2):** brownout **engage/exit thresholds** and the **shed-priority ordering** are **authored by C4/Governance policy** and delivered as **operational-baseline entries** (`16 §I.1`, AD-019/022) — **not hard-coded** in the Engine. The Engine **consumes and enforces** them; it does not author brownout policy. Shed-priority **respects per-tenant SLA/priority** as expressed in that policy (a higher-SLA tenant is shed later); the Engine applies the resolved ordering, never invents one. Stale brownout-policy snapshot ⇒ fail-safe (err toward *engaging* brownout / less load).
- **Alternatives:** keep full retry/hedge under mass failure — rejected (amplifies the outage, `03` NFR-ST); hard shed everything — rejected (unnecessarily severe).
- **Why selected:** protects the platform and providers from self-inflicted amplification while preserving invariants.
- **Trade-offs:** degraded latency/success for low-priority load during brownout — intended, bounded, surfaced.
- **Failure modes:** brownout itself never fails open; on uncertainty it errs toward *less* load, not more.
- **Security/Performance impact:** primary anti-amplification control under correlated failure.
- **Enforcement:** chaos/game-day (`15` T-019); brownout is signal-driven + reversible. **Build-Fail:** a brownout path that increases load, or that relaxes a correctness/boundary invariant to "keep serving".

### RE-D8 — Retry storm prevention (shared budgets, forward-only, no recursion)
- **Problem:** correlated failures + independent retries = exponential load amplification.
- **Decision:** three structural guarantees: (1) **shared retry budget** across the request population (`15` T-017 ≤10%) — when the budget is spent, retries **stop** platform-wide, not per-request; (2) **forward-only retry ownership** (RE-D1) — control never loops back into an already-completed retry authority; (3) **no recursive retries** — a retry attempt cannot itself spawn a nested retry loop; each attempt is a single forward step under the shared budget. Combined with backoff+jitter (RE-D2) and circuit-breaking (RE-D4), storms are structurally impossible.
- **Alternatives:** per-request budgets only — rejected (N requests × per-request budget still storms under correlation); token-bucket without sharing — rejected (same).
- **Why selected:** the shared budget is the load cap that makes correlated-failure amplification bounded.
- **Trade-offs:** shared budget needs coordination (bounded, §30); under budget exhaustion, more requests surface (correct — the platform sheds rather than amplifies).
- **Failure modes:** budget exhausted ⇒ surface (fail closed).
- **Security/Performance impact:** the core DoS-amplification + provider-overload defense.
- **Enforcement:** storm tests under correlated fault injection (`15` T-018/T-019). **Build-Fail:** a per-request-only retry budget; a recursive/nested retry; a retry not decrementing the shared budget (RE-A6/A7).

### RE-D9 — Deterministic execution (no randomness beyond governed jitter; replayable)
- **Problem:** nondeterministic reliability decisions defeat audit, replay, and testing.
- **Decision:** reliability decisions (classification, retry/failover/hedge/circuit transitions) are a **deterministic function** of `(routing decision, outcomes observed, policy/health/circuit snapshots, injected clock)`. The **only** permitted randomness is **governed backoff jitter** (RE-D2), which is **seeded deterministically** (from a stable request id + attempt#) so it is **replayable** — same inputs ⇒ same jitter ⇒ same schedule. No wall-clock/random in the decision core (`11` R-063); time via `ClockPort`. Decisions record the snapshot versions for reproducibility.
- **Alternatives:** unseeded random jitter — rejected (non-replayable, un-auditable); no jitter — rejected (thundering herd).
- **Why selected:** replayable/auditable reliability with anti-herd jitter.
- **Trade-offs:** determinism is over the recorded inputs (health/outcomes vary in prod) — determinism means **reproducible from recorded inputs** (stated honestly, §26), which is what audit/replay/test need.
- **Failure modes:** none from determinism.
- **Security/Performance impact:** cache-friendly; enables property/mutation testing.
- **Enforcement:** ArchUnit forbids wall-clock/unseeded-random in the core; replay tests (`15` T-035). **Build-Fail:** wall-clock/unseeded-random in the reliability core.

### RE-D10 — Security (credential-free, SDK-free, HTTP-free; ports only)
- **Problem:** the Engine drives invocation and must present zero credential/SDK/network surface.
- **Decision:** the Engine holds **no secrets, no credentials, no API keys, no provider SDKs, and makes no direct HTTP**. It invokes **only** through `ProviderInvocationPort`/`StreamTransportPort`; credentials live in **Secrets (C14)** and are used only inside the **adapter** at the network boundary. The Engine drives *which candidate, how many attempts, with what deadlines* — never *with what credential*.
- **Alternatives:** Engine manages provider auth/retry-with-token-refresh — rejected (credential surface, ownership drift).
- **Why selected:** minimal attack surface; reliability logic is credential-agnostic.
- **Trade-offs:** none material.
- **Failure modes:** n/a (no credential path exists).
- **Security impact:** eliminates credential exposure from the reliability layer.
- **Performance impact:** neutral.
- **Enforcement:** ArchUnit — no secrets API / HTTP client / provider SDK in the Engine (RE-A8/A9/A10). **Build-Fail:** any credential/HTTP/SDK reference in the Engine.

### RE-D11 — Observability (retry/failover/circuit/budget metrics; no high cardinality)
- **Problem:** reliability behavior must be observable to operate and to gate deploys, without leaking content or exploding cardinality.
- **Decision:** full instrumentation per `14`, **content-free**, **low-cardinality**: retry/failover/hedge/circuit/budget metrics (§34), spans per attempt, structured logs, and the frozen invocation-outcome events (§36). A `provider`/`route` dimension MAY appear **internal-operational-only** (never customer-facing, AD-007), **cardinality-bounded** (top-N + `other`, Plane A) with high-cardinality detail on Plane B (`14 §5.1`).
- **Alternatives:** per-attempt high-cardinality labels — rejected (Prometheus cardinality blowup).
- **Why selected:** operable + gate-able + neutral + bounded.
- **Trade-offs:** none material.
- **Failure modes:** n/a.
- **Security impact:** no content/secret in telemetry (`13 §20`, `14 §7.1`).
- **Performance impact:** bounded cardinality protects the metrics plane.
- **Enforcement:** content/secret-leak scanner (`14 §7.1`, `15` T-052); cardinality bounds. **Build-Fail:** content/secret in telemetry; unbounded provider label on Plane A.

### RE-D12 — Extensibility (adding a provider never changes the Engine)
- **Problem:** onboarding a provider must not touch reliability logic.
- **Decision:** the Engine operates on the **provider-neutral** routing decision + adapter port; adding a provider is an **adapter + descriptor** change (C1, `19 §PR-D11`) with **zero Engine diff**. The Engine **never branches on provider names** — retry/failover/circuit logic is uniform across providers, parameterized only by the neutral policy/health/circuit snapshots.
- **Alternatives:** per-provider reliability tuning in the Engine — rejected (coupling); such tuning belongs in the neutral policy snapshot (C4) keyed by neutral attributes.
- **Why selected:** open-closed; the Engine is closed to modification, open to new providers.
- **Trade-offs:** none material.
- **Failure modes:** n/a.
- **Security/Performance impact:** neutral.
- **Enforcement:** conformance suite adds a provider with zero Engine diff (`15` T-014/T-015); ArchUnit no provider-name branch. **Build-Fail:** a provider-name branch/switch in the Engine; an Engine change whose sole purpose is provider onboarding (RE-A11).

---

## D. Lifecycles

### §8 — Retry lifecycle
```
Receive RoutingDecision → derive InvocationPlan → [attempt candidate_i via adapter/StreamGuard]
  → classify outcome (§13)
     SUCCESS      → resolve (single delivery, §25)
     RETRYABLE    → if within ceiling AND shared budget available AND pre-first-emission (§21/§25):
                       backoff+jitter (RE-D2) → retry same candidate OR fail over (§9-lifecycle)
                    else → surface
     NON_RETRYABLE→ surface (or hand to SchemaLock guided-retry if it is content non-conformance, §22)
     TIMEOUT      → treat as RETRYABLE within deadline, else surface
     CIRCUIT_OPEN → skip candidate → next in list (§10)
     FATAL        → surface
```
Every retry is a **forward step** (RE-D8), decrements the **shared budget** (§14), and can **never** run after first downstream stream emission (`18 §26`).

### §9 — Failover lifecycle
```
On a candidate's retryable exhaustion / circuit-open:
  advance to next candidate in the RoutingDecision list (strict order, §RE-D3)
  → if candidate circuit CLOSED/HALF_OPEN and health acceptable → attempt
  → repeat until success OR list exhausted OR total deadline
On list exhaustion (still within TTL): request fresh RoutingDecision (19 §30.1 RC-6) OR surface
On decision TTL expiry: request fresh RoutingDecision (never execute expired) OR surface
```
Failover **never** leaves the immutable list; every target is residency/compliance-safe by construction (RE-D3).

### §10 — Circuit-breaker lifecycle
```
CLOSED --(failure-rate/consecutive-failure threshold)--> OPEN
OPEN --(cool-down elapsed)--> HALF_OPEN
HALF_OPEN --(probe success ×k)--> CLOSED
HALF_OPEN --(probe failure)--> OPEN
```
State is shared/coordinated per route (§30); an OPEN route is skipped in failover (§9). Probes are bounded (governed) so half-open never floods a recovering provider.

### §11 — Timeout & hedge lifecycle
- Deadlines armed per attempt (connect / TTFB / total; stream-inactivity delegated to StreamGuard). If **hedging permitted** (RE-D6): after the hedge delay, launch a second attempt to a different candidate; **first success wins**, **loser cancelled** (`18 §27`) and discarded; **all attempts counted** against the budget. Total-deadline bounds the whole race.

### §12 — Cancellation lifecycle
- Client/pipeline cancellation is cooperative and **idempotent**: the Engine stops scheduling further attempts, **cancels in-flight attempt(s)** (propagating to StreamGuard/adapter to stop provider work + cost, `18 §27`), and resolves as `CANCELLED`. Cancellation races with success are resolved deterministically (a cancel after a committed success returns the success; otherwise it cancels).

---

## E. Classification & Accounting

### §13 — Error classification
- Every attempt outcome is classified into a **neutral `OutcomeClass`** (no provider-native codes, `12 §16.10`): `SUCCESS`, `RETRYABLE` (transient transport/overload/`503`), `NON_RETRYABLE` (`4xx`/auth/bad-request — surfaced), `TIMEOUT`, `RATE_LIMITED` (provider throttle, honor retry-after), `CIRCUIT_OPEN` (local skip), `FATAL` (unrecoverable). Classification maps provider-neutral outcomes (from the adapter's neutral error mapping, `19`/`12 §12`) — the Engine **never** interprets provider-native error bodies. Default on any unknown = **NON_RETRYABLE / surface** (fail closed).

### §14 — Retry budget accounting
- Two levels, **both** enforced: **per-request ceiling** (max attempts) **and** a **shared population budget** (`15` T-017 ≤10% retry ratio) coordinated across requests (§30). Each retry/hedge attempt **decrements** the shared budget; when the budget is exhausted, **retries and hedges stop platform-wide** (requests surface rather than amplify, RE-D8). Budget state is read/updated through `RetryBudgetPort` (a coordinated, bounded-consistency mechanism — **not** a naive shared mutable counter; §30). Budget accounting is content-free and audited.

### §15 — Backoff strategy
- **Exponential backoff with full jitter**, governed base/cap (`ReliabilityPolicy`/baseline). Jitter is **deterministically seeded** (request id + attempt#, RE-D9) — replayable, anti-herd. Backoff always fits **within the total deadline** (§9); a backoff that would exceed the deadline ⇒ no further attempt ⇒ surface.

### §16 — Provider health consumption
- The Engine **consumes** health/availability from snapshots (owned by **Provider Registry**, `06 §23`, AD-022) and circuit state from the shared reliability tier (§30). It **never owns/computes/probes** health directly (probing is the circuit-breaker's bounded half-open, not an independent health monitor). Stale health ⇒ conservative (treat as degraded), consistent with fail-safe.

### §17 — Rate-limit interaction *(ownership clarified, resolves Medium REM-5)*
- **Ownership (normative):** tenant/route **rate-limit policy is authored by C4/Governance** (`06`, AD-019). The **Reliability Engine consumes resolved rate-limit *decisions*/limits** (from the cached snapshot, AD-022) and **enforces/executes** them — it **never authors policy** and never sets a limit. On a provider `RATE_LIMITED` outcome, the Engine honors **retry-after** (backoff to the indicated time, within deadline/budget) and applies **rate-limit-aware backpressure**. A request that would exceed a hard tenant/route limit is **shed/surfaced per the resolved policy** (the decision is C4's; the enforcement is C2's). Rate-limit enforcement is **fail-safe conservative** on stale limit snapshots (err toward *more* limiting, never less — `06`/`13 §13.1`). Widespread throttling is also a **brownout** signal (RE-D7).

---

## F. Interactions

### §18 — Interaction with Provider Router (C1)
- The Engine **consumes** the immutable `RoutingDecision` (`19 §30.1` RC-1) and executes within it (RE-D3). It **never** routes/re-ranks/invents candidates. On exhaustion/TTL it **requests a fresh decision** (RC-6), never bypasses the Router. Contract per `19 §30.1` (RC-1…RC-12).

### §19 — Interaction with StreamGuard (C3/transport, `18`)
- **All streaming invocation transits StreamGuard** (`18 §10.1` non-bypass): the Engine drives the stream via `StreamTransportPort`, never a direct provider stream. It **sets/propagates** the stream-inactivity + cancellation signals; **StreamGuard enforces** transport integrity and the **effectively-once / point-of-no-return** rules (`18 §16.1/§26`). A **failure after first downstream emission is non-retryable** at the Engine level (`18 §26`) — the Engine surfaces it (never re-streams). **Build-Fail:** an Engine streaming path bypassing StreamGuard (RE-A2).

### §20 — Interaction with SchemaLock (C3/correctness, `17`)
- The Engine **never** validates/repairs content (§4). **SchemaLock's guided retry** (`17 §24`) is a *content* re-ask that runs **as a fresh invocation through the Engine** — SchemaLock decides "the content was non-conformant, try again with feedback," and the Engine **executes** that as a new attempt under the **same shared budget and precedence** (RE-D1/`17 §24.1`). The Engine never itself decides content conformance. The full normative contract is **§20.1**. **Build-Fail:** the Engine validating/repairing schema/content (RE-A3).

### §20.1 — Normative SchemaLock Guided-Retry ↔ Reliability Engine Contract *(resolves High REH-2 — additive; restates the frozen `17 §24.1` precedence, no ownership move)*

The **forward-only retry chain is frozen** (`17 §24.1`/`18 §49.1`/`19 §30.1`):
```
Transport Retry (StreamGuard, pre-emission)  →  Reliability Retry (Engine)  →  SchemaLock Guided Retry (content)
```
This contract pins the SchemaLock↔Engine seam within that chain. **Exactly one authority is active at a time; control passes forward only; no authority re-enters.**

| # | Concern | **Owner** | Contract |
|---|---|---|---|
| GR-1 | **Ownership** | **SchemaLock decides; Engine executes** | SchemaLock (C3) decides *whether a content re-ask is warranted* (a conformance verdict, `17 §24`); the Engine (C2) **executes** the re-ask as an invocation. Neither does the other's job: SchemaLock never invokes; the Engine never judges conformance. |
| GR-2 | **Attempt lifecycle** | **Engine** | A guided re-ask is a **new `AttemptContext`** on the **same request** (same `requestId`/correlation, `07 §6`) — not a new request. The Engine drives it through the normal retry lifecycle (§8) via the adapter/StreamGuard. |
| GR-3 | **Budget accounting** | **single shared budget** | A guided re-ask **decrements the same shared retry budget** (§14/§30.1) as a reliability retry — **no separate SchemaLock budget, no double-counting**. Guided re-asks and reliability retries **share one ceiling and one population budget**; when the budget is exhausted, **no further attempt of any kind** runs (fail closed). |
| GR-4 | **Idempotency** | **Engine honors key** | A guided re-ask reuses the request's idempotency key (`12 §14`, §21); because it is a *content re-generation* (a new model call), it is treated per §21 — permitted only when the operation is idempotent/duplicate-suppressible; a non-idempotent consequential call is **not** guided-retried (surfaces), same as any retry. |
| GR-5 | **Retry precedence** | **forward-only** | Guided retry is the **last** stage of the chain. It engages **only after** transport (pre-emission) and reliability retries are exhausted/inapplicable and **only** for a `NON_RETRYABLE`-at-transport but **content-non-conformant** outcome (`17` classification). It **never** runs concurrently with, or hands back to, transport/reliability retry for the same attempt. |
| GR-6 | **Escalation** | **forward-only** | On a guided re-ask failure: SchemaLock may request another guided attempt **only within the shared budget/ceiling** (bounded, `17 §24.1`); when the budget/ceiling is exhausted, control **surfaces** — it **never loops back** to reliability/transport retry (no recursion, RE-D8). |
| GR-7 | **Failure ownership** | **by class** | Transport failures → StreamGuard/Engine; **content-conformance** failures → SchemaLock (which decides re-ask vs surface). The Engine never reclassifies a content failure as a transport retry, and SchemaLock never reclassifies a transport failure as a content re-ask. |
| GR-8 | **Termination conditions** | **shared** | The re-ask sequence terminates on: (a) a conformant result (delivered, single delivery §25), (b) shared-budget/ceiling exhaustion (surface), (c) total-deadline expiry (§9, surface), or (d) a non-idempotent/uncancellable state (surface). Termination is always a **single delivered success or a surfaced failure** (RE-INV). |

- **Anti-storm guarantees (normative):** **no recursive retries** (GR-6), **no budget duplication** (GR-3 — one shared budget), **no ownership overlap** (GR-1/GR-7), **no retry storm** (single forward-only authority under one shared budget, RE-D8/§30.1).
- **Enforcement (RE-A18):** contract tests (`15` T-017/T-018) assert: a guided re-ask decrements the shared budget exactly once; guided retry never re-enters reliability/transport retry; the chain is forward-only; total attempts (transport + reliability + guided) never exceed the shared ceiling. **Build-Fail:** a guided retry with a separate/duplicate budget; a guided retry that loops back into reliability/transport retry; guided + reliability retry active concurrently for one attempt.

### §21 — Idempotency interaction
- The Engine **never duplicates a successful execution** (RE-INV, §25). A retry/hedge is only permitted when the operation is **idempotent** (safe to repeat) or **duplicate-suppressible** via an **idempotency key** (`12 §14`): the key ensures a repeated attempt that actually succeeded upstream is not double-counted as a second side effect. **Non-idempotent, non-suppressible operations are never retried/hedged** — they surface on first failure (fail closed over duplicate). Idempotency-key handling is the request pipeline's / `12 §14`'s; the Engine honors it in its retry/hedge gating.

### §21.1 — Hedging Safe-Applicability Matrix *(resolves Medium REM-1 — additive; strengthens RE-D6/§25)*
Hedging (RE-D6) launches a **second concurrent attempt** and is therefore **only ever permitted where a duplicate cannot cause a second consequential effect**. The following is **normative**:

| Operation class | Hedging | Rationale |
|---|---|---|
| **Idempotent read-like generation** (e.g. a plain completion with a stable idempotency key, no side effects) | ✅ **Permitted** (if policy + cost-gate allow) | a duplicate is discarded harmlessly; loser cancelled (§25) |
| **Duplicate-suppressible via idempotency key** (`12 §14`) | ✅ **Permitted** | the key guarantees at-most-one consequential effect |
| **Non-idempotent tool execution** (a tool that acts — sends an email, writes a record, calls an API) | ❌ **FORBIDDEN** | two hedged attempts could execute the side effect twice — an irreversible duplicate (RE-INV) |
| **Externally visible side effects** (any call whose duplicate is observable outside the gateway) | ❌ **FORBIDDEN** | duplication is externally consequential, not suppressible by the gateway |
| **Operations without duplicate suppression** (no idempotency key, no suppressible semantics) | ❌ **FORBIDDEN** | the gateway cannot guarantee single delivery of the effect |
| **Streaming after first downstream emission** (`18 §26`) | ❌ **FORBIDDEN** | would violate effectively-once/ordering (`18`) |

- **Honest scope (explicit):** because **most consequential tool-use is non-idempotent**, hedging's tail-latency benefit is **scoped to idempotent/duplicate-suppressible generations** — it is **not** a general-purpose latency tool. When any doubt exists about idempotency/suppressibility, hedging is **not launched** (fail-safe, RE-D6). **Enforcement (RE-A13 extended):** property/fault tests assert hedging never launches for a forbidden class and never yields two delivered/executed effects. **Build-Fail:** a hedge launched for any ❌ class above.

### §22 — Tool-calling interaction
- Tool-calling requests are invoked like any other; **tool-call transport** integrity is StreamGuard's (`18` RB-7), **tool-argument schema** validation is SchemaLock's (`17 §17`). The Engine's only tool-calling concern is **reliable invocation + retry/failover** of the tool-calling turn — it **never** interprets tool calls, sequences multi-tool turns (that is C3 tool-call integrity, `06`), or executes tools. A retryable failure of a tool-calling invocation follows the normal retry lifecycle (§8), subject to idempotency (§21 — a tool-calling turn with side effects is treated as non-idempotent unless keyed).

### §23 — Rate-limit interaction (cross-ref §17)
- See §17. Additionally: rate-limit backpressure feeds **brownout** (RE-D7) — widespread throttling is a brownout signal.

### §24 — Cost interaction
- The Engine **consumes normalized cost/budget** snapshots (owned by C5/C8, `19 §33.1`) **only** to **gate hedging** (RE-D6 — do not hedge if it breaches the tenant's cost budget) and to bound retry cost. It **never computes cost/billing** (`19 §33.1`, `06`). Missing/stale cost ⇒ **fail-safe: do not hedge** (err toward less spend), never toward more. Cost never overrides a correctness/boundary invariant.

### §25 — Exactly-once considerations
- The platform's delivery model is **effectively-once** (`07` / AD-005): at-least-once execution attempts + **idempotent/duplicate-suppressed** side effects. The Engine guarantees **at most one *delivered* successful result** per request and **never double-counts a successful side effect**: (a) the **first committed success wins**; competing attempts (failover/hedge losers) are **cancelled and discarded**, never delivered; (b) **idempotency keys** (§21) prevent a retried-but-actually-succeeded call from producing a second consequential effect; (c) usage/metering counts the **delivered** attempt (loser attempts are reported as attempts, not as delivered outcomes, so cost/usage is honest, §36). This is the honest ceiling: the Engine cannot make a *provider's* generation exactly-once, but it makes the **delivered outcome effectively-once**.

### §26 — Determinism scope (honest, precise) *(resolves High REH-3 — additive; no overclaim)*
- Reliability decisions are deterministic over their **recorded inputs**, and **exact replay requires ALL of the following to be identical**:
  1. **inputs** — the request context + the immutable `RoutingDecision` (`19 §14`),
  2. **snapshots** — the exact snapshot versions of policy, health, cost, rate-limit (recorded, §14),
  3. **coordination state** — the exact budget/circuit values observed at each decision point (§30.1) — *including* which request won the last budget token,
  4. **scheduling/interleaving** — the concurrent interleaving of other requests against the shared budget/circuit tier,
  5. **provider observations** — the exact per-attempt outcomes returned (intrinsically provider-nondeterministic).
- **What IS deterministic (guaranteed):** given all five recorded, the Engine's **decision *function*** is a pure, reproducible mapping — the classification, retry/failover/hedge/circuit transitions, and **deterministically-seeded jitter** (request id + attempt#, RE-D9) reproduce exactly. This is what audit/forensics/test replay use: **replay the whole recorded interleaving**, get the same decisions.
- **What is INTENTIONALLY non-deterministic (explicit, not a defect):**
  - **Provider outcomes** — the model's output and per-attempt success/latency vary (intrinsic).
  - **Cross-request interleaving against shared state** — whether *this* request obtained the last budget token or saw a just-tripped circuit depends on **concurrent traffic** (§30.1 CC-3 bounded-staleness eventual consistency). Therefore a **single request's decision is NOT reproducible in isolation** — only the **whole recorded interleaving** is replayable.
- **No impossible replay claim:** the Engine does **not** claim that an individual request re-run in a different concurrency context yields the same decision. "Replayable decisions" (RE-D9) means **replayable given the recorded interleaving + coordination state + provider observations**, not context-free reproducibility. This honest scope preserves audit/forensics value without overclaiming distributed determinism.

### §27 — Failure escalation
- Escalation is **forward-only** (RE-D1/D8): transport-retry (pre-emission, StreamGuard-coordinated) → Engine retry/failover (within list + budget) → SchemaLock guided-retry (content, via Engine) → **surface**. At each boundary, exhaustion **hands forward or surfaces** — it **never loops back**. The terminal state is always either a **single delivered success** or a **surfaced failure** (RE-INV) — never a silent partial, duplicate, or boundary-crossing recovery.

---

## G. Correctness Properties

### §27.1 — Exactly-once & determinism (consolidated)
- Consolidated above: effectively-once delivery (§25), reproducible-from-recorded-inputs determinism scoped precisely (§26).

---

## H. Performance & Concurrency

### §28 — Performance model
- The Engine's own overhead (classification, budget check, backoff scheduling, candidate advance) is **O(1)–O(attempts)** CPU per request — negligible vs provider latency; it adds **near-zero** to the added-latency budget (`06 §11`) on the **success path** (no retry). Retries/backoff add latency **only to failing requests**, bounded by the total deadline (§9). No hot-path network beyond the invocation itself. JMH microbenchmarks baseline the decision path (`15` T-026, `16 §J.2`).

### §28.1 — All numeric thresholds are configurable operational baselines *(resolves Medium REM-4 — additive)*
- **Every** numeric value in this document — retry ceilings, backoff base/cap, jitter bounds, retry-budget ratio (`15` T-017 floor), hedge delay + max-in-flight, circuit failure thresholds + cool-down + probe count, connect/TTFB/stream-inactivity/total timeouts, brownout engage/exit thresholds + shed-priority, circuit-propagation window (§30.2), coordination staleness bounds (§30.1), telemetry cardinality-N (§34) — is a **named entry in the per-environment `operational-baseline`** (`16 §I.1`), schema-validated and change-audited, delivered via cached policy snapshots (C4/Config, AD-019/022). **No immutable numeric constants** live in the Engine. Values shown in this document are **illustrative defaults**. Baselines may tighten freely; **loosening a value derived from a frozen guarantee** (retry budget `15` T-017, added-latency `06 §11`, no-storm `06 D-5`) requires a recorded exception (`16` D-065) and may **never** cross the frozen floor. **Build-Fail:** a hard-coded reliability threshold bypassing the operational baseline; a baseline value set beyond a frozen `06`/`15` bound.

### §29 — Memory model
- Per-request state lives in the transient `InvocationExecution` aggregate; policy/health/circuit snapshots are **shared immutable** reads. Bounded: max in-flight attempts (hedge cap), bounded backoff schedule. No unbounded structures. Sized within container limits (`16` D-044, ZGC AD-023).

### §30 — Concurrency & shared-state model (budget + circuit coordination)
- **Per-request execution has no shared mutable state** (AD-021). The **two genuinely shared signals** — the **shared retry budget** (§14) and **circuit state** (§10) — are coordinated through a **bounded-consistency shared reliability tier** (the in-memory tier / snapshots, `06 §376`, AD-022): they are **not** naive shared mutable counters guarded by hot-path locks. The budget uses an **atomic/coordinated decrement with bounded staleness** (a slightly-stale budget errs toward *stopping* retries, fail-safe); circuit state is a **coordinated snapshot** (a slightly-stale-closed breaker costs at most one failed attempt that then trips it). This keeps the hot path lock-light and **non-pinning** on Virtual Threads (`11` R-049). No cross-tenant sharing (budget/circuit keyed by neutral route, not tenant identity, so no cross-tenant leakage, AD-021). The full normative model is **§30.1**.

### §30.1 — Distributed Coordination Contract: retry budget & circuit state *(resolves High REH-1 — additive; specifies the existing shared-tier mechanism, no new store, no ownership change)*

This is the **signed, testable contract** for the two shared reliability signals. It uses the **already-frozen shared in-memory tier** (`06 §376`, AD-022) — it introduces **no new store, no new service, no new ownership**. Its purpose is to state **exactly what is guaranteed and, equally, what is NOT**, so the storm-prevention and circuit guarantees are honest at trillion-scale.

| # | Aspect | Contract |
|---|---|---|
| CC-1 | **Ownership** | The **shared reliability tier** (the frozen ephemeral in-memory tier, `06 §376`) is the coordination substrate; the **Reliability Engine consumes coordination state via `RetryBudgetPort`/`CircuitSnapshotPort`** and applies bounded, atomic decrements/reads. The Engine **owns no coordination store**; it does not own the tier, only participates in it. |
| CC-2 | **Authoritative state** | The **authoritative budget/circuit state is the tier's**, regionally scoped (per data-plane region, AD-014 — no cross-region coordination on the hot path, consistent with `16` D-009). A replica's local view is a **cache of** that authoritative state, not a fork. |
| CC-3 | **Consistency model** | **Bounded-staleness eventual consistency**, NOT linearizable/strong consistency. Budget decrements are **atomic at the tier** (compare-and-decrement) so the *tier* never over-issues beyond the budget; replica-local reads may be **bounded-stale**. **Explicitly not guaranteed:** globally exact, instantaneously consistent budget accounting across all replicas at every instant. |
| CC-4 | **Propagation model** | Circuit-state and budget changes propagate to replicas **eventually within a bounded window** (an operational-baseline value, `16 §I.1`, §REM-3/§30.2). Between propagation and observation, a replica acts on its **last-known state** (AD-022). |
| CC-5 | **Shard ownership** | Budget/circuit keys are **sharded by neutral route** (provider/model/region), never by tenant (AD-021). Each shard has a single authoritative owner within the tier; a request reads/decrements only its route's shard. Sharding bounds contention and preserves isolation. |
| CC-6 | **Stale-state behavior (fail-safe, RE-INV-preserving)** | On stale/uncertain coordination state the Engine **always errs toward *less* action, never more**: a stale/unknown **budget** ⇒ treat as **more-depleted** (fewer retries, never more); a stale/unknown **circuit** ⇒ if last-known OPEN, stay fail-fast; if unknown, **attempt-then-learn** (one bounded attempt that trips the breaker on failure) — never a retry storm. Staleness can only make the Engine **more conservative**, never violate a budget ceiling or a boundary. |
| CC-7 | **Conflict resolution** | The Engine **never invents or silently reconciles** conflicting coordination state. Conflicts are resolved **at the tier** by its atomic primitives (CAS/atomic decrement); the Engine reads the resolved value. If the Engine observes an ambiguous/contradictory state it **fails closed** for that action (skips the retry/hedge), never guesses a reconciliation. |
| CC-8 | **Partition behavior** | If the coordination tier is **unreachable/partitioned**, the Engine operates on **last-known-good** (AD-022) under CC-6 fail-safe rules: it **degrades toward brownout** (RE-D7) — reduced/zero retries and hedging — rather than assume budget is available. A partition **can never cause more load**; worst case it sheds. |
| CC-9 | **Fail-closed behavior** | Any coordination uncertainty (stale, partitioned, ambiguous, tier-error) ⇒ **fail-safe conservative** (fewer retries/no hedge) and, where the action's safety cannot be established, **surface** (RE-INV). Coordination failure never fails open into a storm or a duplicate. |

- **Guarantees that EXIST (testable):**
  1. The **tier never issues budget beyond the ceiling** for a shard (atomic decrement) — retries are bounded **per shard** even under concurrency.
  2. Staleness/partition **only reduces** retry/hedge activity (fail-safe) — **no configuration of coordination state can cause a storm** (the core `06 D-5` guarantee).
  3. Circuit state is **eventually consistent within the bounded propagation window**; an OPEN breaker fail-fasts locally on last-known state.
  4. Residency/compliance are **never** a function of coordination state (they are the Router's hard filter, `19`) — a coordination glitch can affect *availability*, never a *boundary*.
- **Guarantees that DO NOT EXIST (explicit, no overclaim):**
  1. **No globally-exact, instantaneously-consistent budget** across all replicas — the ≤10% platform-wide budget (`15` T-017) is enforced **within bounded-staleness tolerance**, not to the exact token at every instant. It is an **honest bound**, not a linearizable invariant.
  2. **No cross-region budget/circuit coordination** on the hot path (regional authority, AD-014).
  3. **No strong consistency / consensus** — this is deliberately eventual+atomic-per-shard, chosen for hot-path latency (`06 §11`) over global exactness.
- **Enforcement (RE-A17):** contract tests (`15` T-017/T-018) + chaos (`15` T-019) assert: atomic per-shard budget bounding; stale/partition ⇒ *fewer* retries (never more); no storm under coordination failure; the Engine never fabricates/reconciles conflicting state. **Build-Fail:** a budget decrement that is not atomic-at-the-tier; a stale/partition path that *increases* retry/hedge load; the Engine locally reconciling conflicting coordination state instead of failing closed.

### §30.2 — Circuit-state propagation (eventual vs authoritative vs safe-stale) *(resolves Medium REM-3 — additive)*
- **Authoritative state:** the tier's per-route circuit value (§30.1 CC-2).
- **Eventual propagation:** a state change (e.g. CLOSED→OPEN when a provider fails) propagates to all data-plane replicas **within a bounded propagation window** — an **operational-baseline value** (`16 §I.1`), not hard-coded. During this window, replicas may hold divergent views.
- **Safe stale behavior:** a replica acting on a **stale-CLOSED** breaker makes **at most one** attempt that then trips its local view (bounded wasted load = one attempt per replica per window); a replica acting on a **stale-OPEN** breaker fail-fasts (safe — worst case a brief over-conservatism as the provider recovers, corrected by half-open probing, §10). **Staleness bounds wasted/harmful load to O(replicas × 1 attempt) per window** — an honest, bounded cost, never a storm.

### §30.3 — Streaming interaction with the coordination tier
- For streaming, the **point-of-no-return** (`18 §26`) means a post-first-emission failure consumes **no** retry budget (it is non-retryable) — so the coordination tier is touched only for **pre-emission** attempts and hedges. This keeps budget accounting aligned with `18`'s effectively-once guarantee.

### §31 — Virtual Threads compatibility
- Runs on **Virtual Threads** (AD-023): each attempt is a lightweight blocking call on a VT; massive concurrency scales naturally. **No blocking in `synchronized`** on the hot path (budget/circuit coordination is lock-light/atomic, §30, `11` R-049). Per-request isolation preserved. **Build-Fail:** shared mutable per-request state; blocking-in-`synchronized` on the hot path; a global mutable retry/round-robin counter.

---

## I. Security

### §32 — Security considerations
- **Credential-free** (RE-D10): no secrets/SDK/HTTP; invokes only via ports.
- **Tenant isolation (AD-021):** per-request execution state; shared budget/circuit keyed by **neutral route**, never tenant identity — no cross-tenant leakage; a tenant's failures never consume another tenant's budget in a way that leaks data (budget is a platform load cap; per-tenant fairness is enforced via rate limits, §17).
- **No correctness/boundary bypass:** the Engine **cannot** bypass Router/StreamGuard/SchemaLock or residency/compliance (build-enforced, §47) — reliability serves correctness (RE-INV).
- **Decision integrity & audit:** invocation-outcome events (success/failover/exhaustion/surface) are **content-free, tamper-evident** into Audit (C10, WORM+Merkle, `08 §10`, `13 §19`).
- **No content/secret in telemetry** (`13 §20`, `14 §7.1`); provider identity internal-only (RE-D11).
- **DoS/amplification defense:** shared budget + brownout + circuit-breaking (RE-D7/D8) bound self-inflicted and provider-directed amplification (`13 §13.1`).
- **Enforcement:** secret/content-leak scanner (`14 §7.1`, `15` T-052); isolation tests (`15` T-044). **Build-Fail:** secret/content in telemetry/events; cross-tenant shared mutable state.

---

## J. Observability

### §33 — Posture
- Full instrumentation per `14`, **content-free**, **low-cardinality**: spans per attempt (attempt#, outcome class, candidate index, circuit state), metrics (§34), structured logs (§35), events (§36). Two-tier (`14 §5.1`): operational counters → Plane A; high-cardinality attempt detail → Plane B. Privacy (`14 §7.1`): never prompt/completion/secret.

### §34 — Metrics (SLI-bearing)
- `re_attempts_total{outcome_class}`, `re_retries_total`, `re_failovers_total`, `re_hedges_total{launched|won|suppressed}`, `re_circuit_state{route}` (bounded), `re_circuit_transitions_total{to_state}`.
- `re_retry_budget_utilization` (gauge) — a **reliability canary signal** (`16 §E.2`); `re_budget_exhausted_total`.
- `re_brownout_active` (gauge), `re_timeout_total{phase}`, `re_cancel_total`, `re_request_outcome_total{delivered|surfaced}`, `re_attempt_latency`, `re_added_latency` (success path).
- Provider/route dimension **internal-operational-only**, **cardinality-bounded** (top-N + `other`, Plane A), never customer-facing (AD-007). No content/secret labels.

### §35 — Tracing & Logging
- One span per attempt + a request-level span; neutral attributes (outcome class, attempts, failovers, hedge outcome, budget state) — never content. Structured content-free logs. Correlation/causation ids propagated (`07 §6`, `14`).

### §36 — Events
- Off the hot path (async, AD-005), **Avro** via Apicurio (`07 §9`), standard envelope (`07 §6`), **content-free**, within C2's **already-owned** event space (no new topic owner; `06 §23` — C2 emits invocation-outcome events consumed by Metering C5, Audit C10, Observability C9). Usage/metering distinguishes **delivered** vs **attempt** (§25) so cost is honest. **No provider-native events** (`12 §16.10`). Event evolution follows `16` D-033. **Build-Fail:** provider-native event; content in an event.

---

## K. Data & Integration

### §37 — Data ownership
- The Engine is **stateless** (AD-020) and owns **no persistent store** (no ownership/data-model change). It holds only **ephemeral in-memory** per-request execution state + read-only shared snapshots + the coordinated budget/circuit tier (§30, ephemeral shared in-memory tier owned per `06 §376`, not a Reliability-private store). Durable reliability records are owned by Audit (C10) / Metering (C5) via events (§36). **Build-Fail:** the Engine acquiring a private persistent store.

### §38 — Integration with Provider Adapters (C1)
- Via `ProviderInvocationPort`: the adapter performs the actual provider call **with credentials** (from Secrets C14); the Engine supplies *which candidate + attempt context*, never a credential. No provider SDK in the Engine (AU-06).

### §39 — Integration with C1 Router / C3 / C5 / C8 / Registry / C4
- Router (`19 §30.1`, §18); StreamGuard (`18`, §19); SchemaLock (`17`, §20); Metering/Billing cost (§24/§33); Provider Registry health (§16); C4 Governance/Config for resolved reliability + rate-limit policy (§17, AD-019/022). All consumed as **cached snapshots** or immutable inputs; the Engine authors none of them.

### §40 — Integration with the shared reliability tier (budget + circuit)
- Budget/circuit coordination uses the **shared in-memory tier** (`06 §376`, AD-022) via `RetryBudgetPort`/`CircuitSnapshotPort` — ephemeral, tenant-scoped where applicable, bounded-consistency (§30). This is **not** a new store and **not** a new ownership — it is the frozen shared in-memory tier the Data Plane already uses.

### §41 — Integration with Audit (C10)
- Invocation-outcome events (delivered / failover / exhaustion / surfaced / brownout) are **content-free, tamper-evident** audit records (WORM+Merkle, `08 §10`, `13 §19`); audit completeness (RPO=0) preserved.

### §42 — Integration with Metering (C5)
- Attempt / retry / hedge / delivered counters (content-free) feed C5's usage/cost accounting; the Engine reports facts, C5 owns cost attribution. **Delivered vs attempt** distinction (§25) keeps metering honest.

### §43 — Integration with Governance / Config (C4)
- Resolved reliability policy (retry/failover/hedge/circuit/timeout params, budgets, rate limits) comes from cached snapshots authored by C4/Config (AD-019/022). The Engine **enforces**, never authors. Governance remains non-bypassable (AD-018).

---

## L. Failure & Recovery

### §44 — Failure handling (every failure fails closed)
Every failure/uncertainty resolves to a **single delivered success** or a **surfaced failure** — never a duplicate, silent partial, or boundary-crossing recovery.

| Failure | Handling |
|---|---|
| Retryable transient (pre-emission) | backoff+jitter → retry/failover within list+budget → else surface |
| Non-retryable (`4xx`/auth) | surface (no retry) |
| Content non-conformance | hand to SchemaLock guided-retry via Engine (§20) → else surface |
| Post-first-emission failure | surface (never re-stream, `18 §26`) |
| Timeout (any phase) | retry if pre-emission & within deadline → else surface |
| Rate-limited | honor retry-after within budget → else surface/shed |
| Circuit open (all candidates) | surface / fresh decision (`19 §30.1` RC-6) |
| List exhausted / TTL expired | fresh decision or surface (never invent) |
| Budget exhausted | surface (fail closed — shed, don't amplify) |
| Uncertain/unknown | fail closed → surface |
- **Enforcement:** fault/chaos injection asserts **zero duplication, zero boundary-crossing, zero storm** under every failure (`15` T-017/T-018/T-019). **Build-Fail:** any failure branch that duplicates a success, crosses a boundary, or loops retries.

### §45 — Recovery behavior
- The Engine holds **no durable state**, so recovery is **stateless**: a restarted instance serves new requests immediately (`16` D-041). An **in-flight** invocation during deploy/restart is **drained** (`16` D-031) or **surfaced** — never a silent duplicate re-execution. Shared budget/circuit tier recovers from its snapshot source (AD-022); on tier unavailability the Engine errs **fail-safe** (conservative budget = fewer retries; unknown circuit = attempt-then-learn), never fail-open into a storm.

---

## M. Testing

### §46 — Testing strategy (Reliability Engine is a `15 §G.1` critical module)
- **Unit (JUnit5/Mockito):** classification, retry/failover/hedge/circuit/timeout state machines, budget accounting, idempotency gating.
- **Property-based (jqwik, `15` T-035):** *no sequence of outcomes ever produces two delivered successes* (effectively-once); *no retry runs after first stream emission*; *every failover target is in the immutable list*; *the shared budget is never exceeded*; *identical recorded inputs ⇒ identical decision sequence* (determinism, seeded jitter).
- **Mutation (`15 §G.1`):** **≥ 85%** on the Engine (named critical module); a mutant that relaxes the budget, duplicates delivery, or crosses a boundary must be killed.
- **Chaos testing (`15` T-019):** correlated provider failure, provider degradation, latency injection, partial outages ⇒ **no storm** (shared budget holds), brownout engages, invariants preserved.
- **Failure injection (`15` T-018, per-PR):** each outcome class, timeout at each phase, circuit transitions, hedge race, cancellation ⇒ fail closed, single delivery, correct classification.
- **Provider conformance (`15` T-014/T-015):** adding a provider = **zero Engine diff**; uniform reliability behavior across providers; no provider-name branch.
- **Concurrency/isolation (`15` T-027/T-044):** massive concurrency, no cross-request/tenant leakage, shared budget/circuit correct under contention; VT no-pinning (`15` T-028).
- **Storm tests:** N-correlated-failures with shared budget ⇒ retries stop platform-wide, not per-request.
- **Performance (`15` T-021/T-026, `16 §J.2`):** success-path added latency near-zero; perf-smoke gate.

---

## N. Build-Failing Rules

### §47 — Build-failing architectural rules
| # | Rule | Gate |
|---|---|---|
| RE-A1 | No request-level retry outside the Engine (single authority, RE-D1) | ArchUnit + `15` T-017 |
| RE-A2 | No streaming invocation bypassing StreamGuard (`18 §10.1`) | ArchUnit + `15` T-012 |
| RE-A3 | No schema/content validation or repair in the Engine (that is C3) | ArchUnit |
| RE-A4 | No failover to a candidate outside the immutable `RoutingDecision` list | fault test (§9) |
| RE-A5 | No residency/compliance/policy re-derivation or boundary-crossing route | `15` T-045/T-031 |
| RE-A6 | No recursive/nested retry; no per-request-only budget; every retry/hedge decrements the shared budget | `15` T-017/T-018 |
| RE-A7 | No retry storm: forward-only ownership, shared budget enforced | chaos (`15` T-019) |
| RE-A8 | No provider SDK in the Engine | ArchUnit AU-06 |
| RE-A9 | No direct HTTP / network / provider stream (ports only) | ArchUnit |
| RE-A10 | No credential/secret/API-key access | ArchUnit + secret scanner (`13 §20`) |
| RE-A11 | No provider-name branch/switch (uniform reliability, RE-D12) | ArchUnit + conformance (`15` T-015) |
| RE-A12 | No persistence / private store (stateless, `06 §24`) | ArchUnit AU-07 |
| RE-A13 | No duplicated delivered success; hedge/failover losers cancelled + discarded | property test (`15` T-035, §25) |
| RE-A14 | No wall-clock/unseeded-random in the reliability core; no shared mutable per-request state | ArchUnit (`11` R-063/R-049) |
| RE-A15 | No content/secret in telemetry/events; no unbounded provider label on Plane A | `14 §7.1` scanner, `15` T-052 |
| RE-A16 | Mutation score ≥ 85% (critical module) | PITest (`15 §G.1`) |
| RE-A17 | Budget decrement atomic-at-tier; stale/partition coordination ⇒ fewer retries (never more); Engine never reconciles conflicting coordination state (§30.1) | contract test + chaos (`15` T-017/T-019) |
| RE-A18 | Guided retry shares one budget (no duplicate), never re-enters reliability/transport retry, forward-only; total attempts ≤ shared ceiling (§20.1) | contract test (`15` T-017/T-018) |
| RE-A19 | No hedge for a forbidden class (non-idempotent/side-effecting/unsuppressible/post-emission, §21.1) | property/fault test (`15` T-018) |
| RE-A20 | No hard-coded reliability numeric; all thresholds from operational baseline (§28.1) | config lint |

---

## O. Operations

### §48 — Operational runbook
- **Retry-budget-utilization spike (`re_retry_budget_utilization`):** correlated provider failure — verify circuit-breaking engaged + brownout; **never raise the budget to "unblock"** (that invites a storm — instead surface/shed and address the provider). Canary auto-aborts on threshold (`16 §E.2`).
- **Failover-rate spike:** primary-provider degradation → Provider Registry health; confirm failover stays within the list (it does by construction).
- **Circuit-open spike:** provider outage → escalate to provider drift (`15 §C.1`); confirm half-open probing is recovering, not flooding.
- **Brownout active:** widespread degradation — confirm load-shedding priority is correct; brownout auto-exits on recovery; verify no invariant relaxed.
- **Hedge cost spike:** verify hedge cost-gate + budget; disable hedging via policy (C4) if cost-adverse.

### §49 — Upgrade strategy
- Ships within the **data-plane release train** (`16` D-014/D-055): rolling/canary, zero-downtime (`16` D-031), reliability-signal-gated (`16 §E.2`). Policy/budget changes are config (C4) — no Engine redeploy needed to retune. In-flight invocations drained (`16` D-031) or surfaced — never silently duplicated.

### §50 — Backward compatibility
- The **reliability contract** (`InvocationResult`, outcome classes, the Router/StreamGuard/SchemaLock seams) is a stable internal API (`12 §27`): changes are additive/backward-compatible within a major. A **new provider** is additive via adapter (RE-D12). Emitted events evolve under Avro compat (`07 §9`, `16` D-033). Deterministic core (§26) guarantees identical decisions for identical recorded inputs across versions.

---

## Appendix A — Implementation notes *(non-normative; resolves Low editorial items)*
- **Event naming:** the Engine emits invocation-outcome events within C2's already-owned event space (`06 §23`); exact event names/topics are reconciled with the `06 §23` registry (deferred Low, REL-1) — **no new topic owner**.
- **Benchmarks:** the success-path decision hot path (classify → budget-check → candidate-advance) and the failover path have a JMH microbenchmark set in `benchmarks/` (`15` T-026, `16 §J.2`); the perf-smoke gate (`16 §J.2`) guards P99 added-latency regression on the success path.
- **Coordination-tier data structures:** the atomic per-shard budget + circuit registry (§30.1) is a shared-in-memory-tier concern (`06 §376`); the Engine's logic is tier-implementation-agnostic (contract §30.1, not a data-structure choice).
- **Section numbering:** Correctness Properties consolidated at §27.1 (prior duplicate §28 heading removed).

## P. Traceability

### §51 — Traceability matrix
| Engine concern | BR | NFR | ADR | Domain/Svc | API/Event | Sec | Obs | Test | Deploy |
|---|---|---|---|---|---|---|---|---|---|
| Dependable invocation / RE-INV (§1/§44) | BR-003/004 | NFR-RTY-001/AV | AD-016/018 | C2 (`06`) | `12 §12` | — | `14` | T-017/T-018 | `16 §E.2` |
| Single retry authority / storm prevention (RE-D1/D8) | BR-003 | NFR-RTY-001 | AD-016 | C2 (`06 D-5`) | — | §13.1 | §34 | T-017/T-019 | — |
| Failover within immutable list (RE-D3/§9) | BR-003/023 | NFR-AV/MR | AD-014/017 | C1↔C2 (`19 §30.1`) | `06 §23` | §15/§18 | — | T-045 | D-051 |
| Circuit breakers (RE-D4/§10) | BR-003 | NFR-AV | AD-016 | Registry | `07 §23` | — | §34 | T-019 | — |
| Timeouts/cancellation (RE-D5/§11/§12) | BR-002/003 | NFR-STRM/PERF | AD-016 | StreamGuard (`18`) | `12 §16` | §13.1 | §17 | T-018 | — |
| Hedging (RE-D6/§11) | BR-005 | NFR-PERF/COST | AD-016 | — | — | §13.1 | §34 | T-017 | — |
| Brownout (RE-D7) | BR-003 | NFR-ST/AV | AD-016 | — | — | §13.1 | §17 | T-019 | — |
| Deterministic execution (RE-D9/§26) | BR-004 | NFR-RTY-001 | AD-016 | `11` R-063 | `12 §14` | — | — | T-035 | — |
| Idempotency / exactly-once (§21/§25) | BR-004 | NFR-RTY-001 | AD-005 | `07` | `12 §14` | — | §36 | T-017/T-035 | — |
| Streaming interaction (§19) | BR-002 | NFR-STRM-001 | AD-018 | C3 StreamGuard (`18`) | `12 §16` | — | §11 | T-012 | — |
| SchemaLock/guided-retry (§20) | BR-001 | NFR-SO | AD-018 | C3 SchemaLock (`17`) | — | — | — | T-018 | — |
| Rate-limit (§17/§23) | BR-013 | NFR-GOV | AD-018/019 | C4↔C2 (`06`) | — | §13.1 | §34 | T-031 | — |
| Cost interaction (§24) | BR-005/012 | NFR-COST | AD-010 | C5/C8 (`19 §33.1`) | — | — | §10 | T-018 | — |
| Module in data plane, stateless (§37) | BR-005 | NFR-PERF | AD-020/006 | `06 §10/§24` | — | — | — | T-051 | D-014 |
| Credential-free / ports (RE-D10/§7) | BR-019 | NFR-SEC | AD-002/012 | `05`/`11` | — | §20/§21 | — | T-008 | — |
| Shared budget/circuit tier (§30/§40) | BR-003 | NFR-CONC/REL-002 | AD-021/022 | in-memory tier (`06 §376`) | — | §15 | — | T-027/T-044 | — |
| Distributed coordination contract (§30.1/§30.2) | BR-003 | NFR-RTY-001/CONC | AD-022/021/014 | in-memory tier (`06 §376`) | — | §13.1 | §34 | T-017/T-019 | D-009 |
| Guided-retry↔Engine contract (§20.1) | BR-001/004 | NFR-SO/RTY-001 | AD-016/018 | C2↔C3 (`17 §24.1`) | — | — | — | T-017/T-018 | — |
| Determinism scope (§26) | BR-004 | NFR-RTY-001 | AD-016 | `11` R-063 | `12 §14` | — | — | T-035 | — |
| Hedging applicability matrix (§21.1) | BR-005 | NFR-PERF/RTY | AD-005/016 | — | `12 §14` | §13.1 | §34 | T-017/T-035 | — |
| Performance / VT (§28–31) | BR-005 | NFR-PERF/CONC | AD-020/023 | `06 §11` | — | §15 | §15 | T-021/T-028 | D-044 |
| Observability (§33–36) | BR-010 | NFR-OBS | AD-011 | C9/C10/C5 | `07 §6` | §7.1 | §5/§19 | T-052 | — |
| Testing (critical module) (§46/§47) | BR-003 | NFR-TEST-001 | AD-016 | — | — | §27 | §19 | §G.1/T-017/T-019 | §E.2 |
| Upgrade/back-compat (§49/§50) | BR-028 | NFR-UPG/VER | AD-015 | — | `12 §27` | — | — | T-049 | D-014/D-055 |

---

## Q. Reviews

### 1. Internal Consistency Review
| Frozen source | Requirement | This document | ✓ |
|---|---|---|---|
| AD-016 / RE-INV | reliability serves correctness | §1/§44 (never compromise correctness; fail closed) | ✅ |
| AD-018 | non-bypassable correctness | §19/§20/§47 (through StreamGuard/SchemaLock, never around) | ✅ |
| AD-017 | C1→C2→C3 flow | §18 (consumes decision), §19/§20 (invokes through C3) | ✅ |
| AD-014 | residency confinement | RE-D3/§9 (failover only within hard-filtered list) | ✅ |
| AD-021 | tenant isolation | §30 (budget/circuit keyed by neutral route, no cross-tenant state) | ✅ |
| AD-005 / `07` | effectively-once | §25 (single delivered success, idempotent side effects) | ✅ |
| AD-020/AD-006 | data plane co-located stateless module | §37 (no store), §45 (stateless) | ✅ |
| AD-002 | hexagonal replaceable adapters | §7 ports; invocation via port | ✅ |
| AD-022 | cached snapshots / last-known-good | §16/§30/§43 (health/circuit/policy snapshots; fail-safe on stale) | ✅ |
| AD-023 | Java 21 + VT + ZGC | §31 (VT, no pinning), §29 | ✅ |
| `06` (C2 = Reliability) | Reliability Engine module in DP | §1/§5 (C2 module; no dedicated CP service) | ✅ |
| `06 D-5` | coordinated retry (no storms) | RE-D1/D8/§14 (single authority, shared budget, forward-only) | ✅ |
| `06 §23` | invocation-outcome events owned by C2 | §36 (already-owned space, no new owner) | ✅ |
| `06 §24`/§376 | store-per-service; shared in-memory tier | §37/§40 (no store; uses frozen shared tier) | ✅ |
| `15 §G.1` | Reliability Engine critical module, mutation ≥85% | §46/§47 RE-A16 | ✅ |
| `15` T-017 | retry ≤10% budget, no duplicate side effects | §14/§25 | ✅ |
| `16 §E.2/§I.1` | reliability canary; operational baselines | §34/§48; all numerics baseline-sourced | ✅ |
| `17 §24.1`/`18 §26/§49.1`/`19 §30.1` | retry precedence, point-of-no-return, C2 seam | RE-D1/§19/§20.1/§27 (forward-only chain honored verbatim) | ✅ |
| AD-022 / `06 §376` | cached snapshots; shared in-memory tier | §30.1 (coordination on frozen tier, no new store), §30.2 | ✅ |
| AD-014 / `16` D-009 | residency / regional independence | §30.1 CC-2 (regional coordination authority, no cross-region hot-path) | ✅ |
| `12 §14` | idempotency | §21/§21.1/§20.1 GR-4 (hedge/guided-retry idempotency gating) | ✅ |
| `16 §I.1` | operational baselines | §28.1 (all numerics), §30.2, RE-D7 (brownout thresholds) | ✅ |

**No contradictions found** against `00`–`19` and AD-001…AD-023. The corrective pass (§30.1/§30.2/§30.3, §20.1, §26, §21.1, §17/RE-D7/§28.1 clarifications, Appendix A, RE-A17…A20) is **additive only**: **no new ADR, no new service, no new module, no new store** (§30.1 uses the frozen shared in-memory tier `06 §376`), **no ownership/event/data-ownership change, no consistency/security/deployment/observability/testing-model change, no provider coupling, and no invariant weakened** (RE-INV, non-bypass, storm-prevention, residency, effectively-once are *reinforced*). The coordination contract (§30.1) **specifies** the existing tier mechanism without inventing storage or consistency guarantees it cannot keep; the guided-retry contract (§20.1) **restates** the frozen `17 §24.1` precedence; the determinism scope (§26) is **narrowed to honesty**, never broadened.

### 2. Architecture Validation
- **Reliability serves correctness (RE-INV/AD-016/018):** every reliability action (retry/failover/hedge/circuit/brownout) is bounded by "never compromise correctness"; build-enforced (§47). ✅
- **Non-bypass (AD-018):** invocation goes **through** StreamGuard + SchemaLock, never around (RE-A2/A3). ✅
- **Failover boundary-safety (AD-014/`19`):** failover only within the Router's hard-filtered immutable list — residency/compliance-safe by construction (RE-D3). ✅
- **Storm-proof (`06 D-5`):** single authority + shared budget + forward-only + no recursion + backoff/jitter + circuit-breaking (RE-D1/D8). ✅
- **Effectively-once (AD-005):** single delivered success, idempotent side effects, loser-cancel (§25). ✅
- **Isolation (AD-021):** neutral-route-keyed shared budget/circuit; no cross-tenant state (§30). ✅
- **Modular monolith (AD-020):** stateless module, no store, credential-free (§37/RE-D10). ✅
- **Coordination honesty (§30.1):** shared budget/circuit on the frozen in-memory tier with bounded-staleness eventual consistency; states what is and is NOT guaranteed; staleness/partition only *reduces* load (storm-proof by construction). ✅
- **Guided-retry seam (§20.1):** SchemaLock decides / Engine executes; one shared budget; forward-only; no recursion/duplication — signed, testable. ✅
- **Determinism honesty (§26):** scoped to the whole recorded interleaving; no impossible replay claim. ✅
- Introduces **no new architecture decision**; every RE-Dx is an implementation choice inside frozen C2.

### 3. Adversarial Review (re-run after corrective pass)
*Board: Principal Enterprise Architect · Distributed Systems Architect · Site Reliability Engineer · AI Infrastructure Architect · Performance Engineer · Security Architect · Compliance Auditor · CTO.*

**Accepted findings — resolution**
- **REH-1 → RESOLVED (§30.1/§30.2/§30.3).** Distributed coordination contract CC-1…CC-9 on the frozen shared in-memory tier: ownership, authoritative (regional) state, **bounded-staleness eventual consistency** (explicitly NOT linearizable), propagation window, route-sharded ownership, fail-safe stale behavior (staleness only reduces load), tier-resolved conflicts (Engine never reconciles), partition→brownout, fail-closed. **States what is guaranteed AND what is not** — no distributed-consistency overclaim; storm-proof under every coordination state. Circuit propagation (§30.2) differentiates authoritative/eventual/safe-stale with bounded wasted load. Build-enforced (RE-A17).
- **REH-2 → RESOLVED (§20.1).** Normative guided-retry contract GR-1…GR-8: ownership (SchemaLock decides / Engine executes), one shared budget (no duplication), idempotency, attempt lifecycle (same request), forward-only precedence + escalation + termination, failure-by-class. No recursion, no budget duplication, no ownership overlap, no storm. Build-enforced (RE-A18).
- **REH-3 → RESOLVED (§26).** Determinism scoped precisely: exact replay requires identical inputs + snapshots + coordination state + interleaving + provider observations; explicitly documents what IS deterministic (the decision function over recorded inputs) and what is INTENTIONALLY non-deterministic (provider outcomes, cross-request interleaving); no context-free replay claim.
- **REM-1 → RESOLVED (§21.1):** hedging safe-applicability matrix — forbidden for non-idempotent tool execution, externally-visible side effects, unsuppressible ops, post-emission streaming; honest narrow scope.
- **REM-2 → RESOLVED (RE-D7):** brownout thresholds + shed-priority authored by C4/policy as operational-baseline entries, respecting per-tenant SLA; Engine enforces, never authors.
- **REM-3 → RESOLVED (§30.2):** circuit propagation window (baseline), authoritative vs eventual vs safe-stale, bounded wasted load O(replicas×1 attempt/window).
- **REM-4 → RESOLVED (§28.1):** all numeric thresholds are operational-baseline entries; no immutable constants; never below frozen floors.
- **REM-5 → RESOLVED (§17):** rate-limit policy authored by C4, consumed/enforced by C2; stale-limit fail-safe conservative.

**New findings from the re-run**
- **🔴 Critical:** none.
- **🟠 High:** none.
- **🟡 Medium:** none blocking. (All numeric baselines are governed operational-baseline entries per `16 §I.1`; the coordination-tier *implementation* and the C4 brownout/rate-limit *policy authoring* are cross-team deliverables referenced here and contract-/chaos-tested (`15` T-017/T-019) — noted as Low deferrals, not open design gaps.)
- **🟢 Low (deferred, non-blocking):**
  - **REL-1** — exact invocation-outcome event names/topics → reconcile with `06 §23` registry (Appendix A).
  - **REL-2** — JMH benchmark set → `benchmarks/` (Appendix A).
  - **REL-3** — coordination-tier data structures → detailed design (Appendix A).
  - **REL-4** — *(resolved — duplicate §28 heading removed; Correctness Properties consolidated at §27.1)*.
  - **REL-5** — C4 brownout/rate-limit policy schema (owned by C4) → C4 governance standard.

### Architecture Readiness Score: **97 / 100**
All three High findings and all five Medium findings resolved additively, with **no new architecture, no new module/service/store, no ownership/event/data-ownership change, no provider coupling, and no invariant weakened** (RE-INV/non-bypass/storm-prevention/residency/effectively-once reinforced). The distributed coordination contract, guided-retry seam, and determinism scope — the crux of the engine's correctness at scale — are now signed, testable, honest about their limits, and machine-enforceable (RE-A1…A20). Residual 3 points are honest cross-team-deliverable/calibration items (Low, deferred).

### Documentation Health: **96 / 100** · Implementation Readiness: **94 / 100**
Contradictions/gaps closed; the coordination model states exactly what it guarantees and what it does not; every load-bearing seam (coordination tier, guided-retry, hedging, brownout, rate-limit) is specified precisely enough to implement without inventing a distributed-consistency guarantee the design cannot keep.

### Recommendation: **FREEZE.** Critical = 0, High = 0, Medium = 0 (blocking); remaining Low items are explicitly deferred and non-blocking. No contradictions with `00`–`19` or AD-001…AD-023; no new architecture; no ownership/module/service/boundary/event/data/deployment/security/observability/testing change; no provider coupling; no invariant weakened.

---

*End of document — 20-ReliabilityEngine.md · Status: FROZEN · Version: 1.0 (2026-07-21)*
