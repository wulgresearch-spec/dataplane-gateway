# 27 — Observability Telemetry (Runtime Telemetry-Emission Layer · Domain C9)

**Document:** Component Implementation Architecture — Observability Telemetry (runtime emission)
**Project:** Reliability-First AI Gateway
**Status:** **FROZEN**
**Bounded context:** **C9 — Observability** (Supporting, `05`/`06`)
**Module:** *(no new module)* — this document specifies **only the runtime telemetry-emission behavior of the frozen "Telemetry/Audit Emitter (C9/C10)" node** (`06 §8` EM), the data-plane presence of C9, co-located per **AD-020/AD-006**. It is **not** a monitoring platform, **not** an analytics system, **not** a logging framework, **not** a security engine, **not** a new bounded context/service/module/store/event-authority/pipeline stage/deployment topology, and it **owns no store**. It **emits** the telemetry that runtime components (`17`–`26`) produce, **strictly per the frozen `14-Observability-Standards`** and **AD-011**.
**Frozen name:** Telemetry/Audit Emitter (C9/C10) — data-plane presence (telemetry facet)
**Owner:** SRE/Platform — the frozen C9 owner (`06 §9.10`) — **unchanged**
**Plane:** Tier-0 Runtime (emission); backends are Tier-2 control-plane (C9 Observability Service, `06 §9.10`)
**Audience:** SRE/observability/platform/data-plane engineers, privacy, security, compliance, QA, reviewers
**Classification:** Internal — Component Architecture
**Builds on (approved, frozen):** `00`–`26`, `24A` (informational), and ADRs **AD-001…AD-023** — **especially** `07` Event Architecture, `14` Observability Standards, `17`–`26`. Everything here is **additive**. Introduces **no new architecture decision, no new bounded context, no new service, no new module, no new store, no new event authority, no new pipeline stage, no new deployment topology, no provider coupling, and no responsibility movement.** Every OT-Dx is a *component-internal implementation decision* inside the already-frozen C9 data-plane presence, expressed through hexagonal ports (AD-002), **strictly subordinate to `14`** (which owns the observability standards, policy, schema, and metric names this document emits against — see **§RE Runtime Emission Authority Contract**).

> **What this is.** The implementation architecture of the **runtime telemetry-emission layer** — the passive, side-effect-free component that takes the metrics/traces/logs/telemetry-observations that the frozen runtime components already produce and **emits** them per **`14`** (two-tier Plane A/Plane B) and **AD-011** (OpenTelemetry). It **observes**; it never acts, never authors policy/schema/metric-names (that is `14`, frozen), and never owns a business event.
>
> **THE OBSERVABILITY INVARIANT (OT-INV):** *Observability must never change runtime behavior.* It must never modify requests/responses, change routing/retries/governance/provider decisions, affect billing/metering, or expose secrets/PII/tenant data. On any uncertainty it **fails closed by dropping telemetry — never runtime correctness**. This is the observability-layer realization of **AD-018 (non-bypassable pipeline — observability is additive and removable)**, **AD-021 (tenant isolation)**, and **AD-011/`14` (governed, privacy-preserving, vendor-neutral telemetry)**.

---

## ✅ Resolution Notice (Independent Architecture Resolution Pass · 2026-07-22)

This document was frozen **after** the Independent Review Board's adversarial attack. All **Critical (OT-C1, OT-C2)**, **High (OT-H1…OT-H4)**, and **Medium** findings are resolved **additively** through signed normative contracts, each with build-failing enforcement:

- **§RE Runtime Emission Authority Contract (REA-1…REA-8)** — OT-C1 (subordination to `14`; never authors policy/schema/metric-names). Build rule **OT-A16**.
- **§EO Telemetry Event Ownership Contract (EO-1…EO-8)** — OT-C2 (operational telemetry ≠ business-event ownership). Build rule **OT-A17**.
- **§13.1 Streaming Aggregation Contract (SA-1…SA-8)** — OT-H1 (no per-token/per-chunk storms; bounded memory/CPU/cardinality). Build rule **OT-A18**.
- **§19.1 Replay & Sampling Determinism Contract (RSD-1…RSD-6)** — OT-H2 (replay reproduces decisions, never wall-clock; deterministic sampling from recorded inputs).
- **§18.1 Retry Deduplication Contract (RDD-1…RDD-7)** — OT-H3 (no retry double-count; immutable execution identity; ownership pinned). Build rule **OT-A19**.
- **§15.1 Provider Metadata Redaction Contract (PMR-1…PMR-7)** — OT-H4 (canonical metadata only; provider headers redacted; credential references prohibited; no tenant leakage). Build rule **OT-A20**.
- **Medium clarifications** — fail-closed-drop vs decision metrics (§18.2), cross-region trace residency (§10.1), head/tail sampling ownership (§13.2), telemetry cost budget (§17.1), missing correlation-id handling (§9.1), operational numeric baselines (§17.1/§ODN).

**Low** items remain **deferred** (Appendix B). **No new architecture, service, module, bounded context, ownership, store, event authority, pipeline stage, or deployment topology is introduced; AD-002/AD-005/AD-011/AD-014/AD-018/AD-020/AD-021 are preserved exactly; no invariant is weakened; complete consistency with `06`/`07`/`14`/`17`–`26` is maintained.** No document `00`–`26` and no ADR is modified.

---

## Table of Contents
**1. Charter** · **2. Scope** · **3. Runtime Ownership** (§RE) · **4. Interfaces** · **5. Canonical Telemetry Model** · **C. Major Decisions** (OT-D1…OT-D12) · **6. Metrics** · **7. Traces** · **8. Structured Logs** · **9. Correlation IDs** (§9.1) · **10. Context Propagation** (§10.1) · **11. OpenTelemetry Mapping** · **12. Event Emission** (§EO) · **13. Sampling** (§13.1 Streaming · §13.2 Head/Tail) · **14. Cardinality Control** · **15. Privacy & Redaction** (§15.1 Provider Metadata) · **16. Security** · **17. Performance** (§17.1 Cost/Baselines) · **18. Failure Handling** (§18.1 Retry Dedup · §18.2 Drop Order) · **19. Replay** (§19.1 Determinism) · **20. Determinism** · **21. Testing** · **22. Operations** · **23. Build-Failing Rules** (OT-A1…OT-A20) · **24. Traceability** · **25. Independent Review Board** · **Appendix A/B**

---

## 1. Charter

### §1 — Purpose
The Observability Telemetry layer **emits standardized, correlated, privacy-safe telemetry** for every runtime component, **strictly per `14`** and **AD-011**. For each request it emits the **metrics/traces/logs/telemetry-observations** the runtime already produces, threads a **correlation id** across all signals, applies the **frozen `14` redaction** before emission, respects the **frozen `14` cardinality/sampling rules**, and exports to the C9 Observability backend and (via the event fabric) Plane B. It is a **passive, side-effect-free** layer: removing it changes **no** runtime behavior (OT-INV).

### §2 — Scope
- **In scope (emit only, per frozen policy):** metric emission (Plane A, per `14 §5`); trace emission (OTel/W3C, per `14 §6`); structured-log emission (redacted, per `14 §7`); correlation-id threading; context propagation; OTel mapping; **operational-telemetry observation emission within C9's already-owned space** (`07`, `14 §5.1`); applying the frozen sampling/cardinality/redaction rules; content-free provider-neutral telemetry.
- **Applies to:** every runtime component (`17`–`26`) on the hot path, off the response-latency critical path where possible (§17).

### §3 — Runtime Ownership (see §RE)
This layer is the **telemetry-emission facet of the frozen "Telemetry/Audit Emitter (C9/C10)" node** (`06 §8` EM), owned by **SRE/Platform** (C9, `06 §9.10`). It **implements `14`**; it **does not author** observability standards/policy/schema/metric-names (that is `14`, frozen) and **does not own** the observability backend/store (C9 Observability Service, `06 §9.10`). **Audit records are C10's** (out of scope). **No ownership moves** — the normative statement is the **Runtime Emission Authority Contract (§RE)**.

### §RE — Runtime Emission Authority Contract (REA-1…REA-8) *(resolves Critical OT-C1 — additive)*
| # | Aspect | Contract |
|---|---|---|
| REA-1 | **Emission-only** | This document specifies **only the runtime emission implementation** of the frozen **Telemetry/Audit Emitter (EM)** node (`06 §8`), governed by **`14`**. |
| REA-2 | **C9 owns standards** | **C9 owns the observability standards**; ownership is unchanged (`06 §9.10`). |
| REA-3 | **`14` is the single source of truth** | **`14-Observability-Standards` remains the single source of truth** for observability policy, schema, labels, cardinality, sampling, and redaction. |
| REA-4 | **Never authors policy** | This document **never authors telemetry policy**. |
| REA-5 | **Never defines schema** | This document **never defines telemetry schema**. |
| REA-6 | **Never owns metric names** | This document **never owns metric names** (the metric-name registry is `14`'s, Appendix B OT-L1). |
| REA-7 | **Emits per frozen policy** | This document **only emits telemetry according to the frozen `14` policy**; where this document restates a `14` rule it is a **non-normative pointer**, and **`14` prevails** on any difference. |
| REA-8 | **Supersession** | Any wording in this document that could appear to **replace, re-author, or compete with `14`** is **superseded** by REA-1…REA-7. |
- **Enforcement (OT-A16):** cross-check `06 §8/§9.10` + `14`. **Build-Fail:** this component authoring a telemetry policy/schema/metric-name, or acting as a second observability authority.

### §4 — Interfaces (ports — AD-002 hexagonal)
**Inbound (called by runtime components as they execute — passive collection):**
```
TelemetryEmitPort:
  metric(CanonicalMetric)                   // Plane A, per 14 §5 (never sampled)
  span(CanonicalSpan)                       // OTel trace, sampled per 14 §6 / §13
  log(CanonicalLogRecord)                   // structured, redacted-before-emit per 14 §7
  observation(CanonicalTelemetryObservation)// operational observation within C9-owned space (07), content-free (§EO)
```
**Outbound (no store owned; export via the frozen mechanisms):**
```
MetricSinkPort     // -> Plane A TSD (Prometheus, 14 §5.1)
TraceSinkPort      // -> OTel collector / trace backend (AD-011, 14 §6)
LogSinkPort        // -> structured-log backend (14 §7)
EventFabricPort    // -> event backbone (07) for Plane B analytics (14 §5.1) — content-free observations only (§EO)
RedactionPort      // <- frozen classification/redaction policy (13 §20, 14 §7.1) — allow-list + scan + redact + reject
ClockPort          // time source (telemetry timestamps — nondeterministic, §20)
```
- **Enforcement:** ArchUnit — the emit path is **side-effect-free** toward runtime state; it imports **no** routing/retry/governance/pricing/metering mutation types; it **cannot** block or fail a request (§18). All emission is **post-decision, additive, removable**.

---

## 5. Canonical Telemetry Model
Immutable value objects; content-free by construction; **schema owned by `14`** (this doc references, never defines — REA-5).

| Type | Kind | Description |
|---|---|---|
| `CanonicalMetric` | VO | `{name(from 14 registry), type, value, labels(14 allow-list, §14), plane:A}` — never unbounded/identifying/content |
| `CanonicalSpan` | VO | OTel span `{name(<area>.<operation>), attributes(14-neutral, bounded), traceparent, correlationId, tenantScope}` (`14 §6`) |
| `CanonicalLogRecord` | VO | Structured `{...14 §7 required fields..., message(redacted), error.class?}` |
| `CanonicalTelemetryObservation` | VO | Content-free **operational observation** within C9's owned space (`07 §6` envelope) — an **observation about execution, NOT a business event** (§EO); no new topic owner |
| `ExecutionIdentity` | VO | Immutable per-attempt/per-request identity `{requestId, attemptId, correlationId}` used to **dedup** telemetry under retry (§18.1) |
| `CorrelationContext` | VO | `{correlationId, causationId, tenantScope, traceparent, tracestate}` — baggage = correlation id + tenant scope only (`14 §6`) |
| `RedactionVerdict` | VO | `emit` \| `redacted` \| `rejected(drop)` (`14 §7.1`) |

**Aggregate:** `TelemetryEmission` — transient per-signal emission; **no shared mutable state across requests** (AD-021); no request/tenant content retained.

---

## C. Major Decisions (OT-D1 … OT-D12)
*9-field template: Problem · Decision · Alternatives · Why selected · Trade-offs · Failure Modes · Security Impact · Performance Impact · Enforcement.*

### OT-D1 — Passive, side-effect-free observability (OT-INV)
- **Problem:** observability must never alter runtime behavior or correctness.
- **Decision:** the layer is **passive and side-effect-free**: it observes and emits; it **never** modifies a request/response/route/retry/governance/provider decision, and **never** affects billing/metering. **Removable** with zero behavioral change (AD-018 additive).
- **Alternatives:** observability that gates/annotates the request — rejected (OT-INV, AD-018).
- **Why selected:** the core OT-INV guarantee.
- **Trade-offs:** telemetry can be dropped (§18); correctness never.
- **Failure Modes:** any emit failure ⇒ drop telemetry, never fail the request.
- **Security Impact:** no runtime attack surface via telemetry.
- **Performance Impact:** off the critical path where possible (§17).
- **Enforcement:** ArchUnit — emit returns no request-affecting value; OT-A1. **Build-Fail:** telemetry that can alter/block a request/decision.

### OT-D2 — Runtime identity: implements the frozen C9 emitter node, subordinate to `14` (see §RE)
- **Problem:** observability standards are frozen (`14`) and owned by C9; this layer must not re-own or re-author them.
- **Decision:** this document specifies **only the emission implementation** of the frozen EM node; it is **strictly subordinate to `14`** (§RE) and to the C9 Observability Service. **No new module/service/context/store/stage/authority.**
- **Alternatives:** a new observability module/authority — rejected (no-new-module; `14`/C9 own it).
- **Why selected:** preserves `14`/C9 ownership.
- **Trade-offs:** none — subordination is explicit (§RE).
- **Failure Modes:** n/a.
- **Security Impact:** no ownership drift.
- **Performance Impact:** neutral.
- **Enforcement:** §RE + cross-check `14`/`06 §8/§9.10`; OT-A16. **Build-Fail:** a new observability module/service/store/topic-owner/authority.

### OT-D3 — OpenTelemetry + W3C Trace Context (AD-011)
- **Problem:** telemetry must be vendor-neutral and correlated (AD-011).
- **Decision:** all traces use **OpenTelemetry**; propagation via **W3C Trace Context** (`12 §8/§24`); metrics/logs per `14`. No bespoke format; no vendor agent.
- **Alternatives:** proprietary/bespoke — rejected (AD-011).
- **Why selected:** AD-011; portable, exportable.
- **Trade-offs:** OTel maturity variance (AD-011 accepted).
- **Failure Modes:** collector down ⇒ drop/buffer telemetry (§18), never fail the request.
- **Security Impact:** governed export (`14 §7.1`).
- **Performance Impact:** sampling within budget (§13/§17).
- **Enforcement:** OTel conformance; OT-A3. **Build-Fail:** a non-OTel/bespoke trace format; a non-W3C propagation.

### OT-D4 — Two-tier emission (Plane A / Plane B), never conflated (`14 §5.1`)
- **Problem:** low-cardinality operational metrics and high-cardinality analytics must never be conflated.
- **Decision:** **Plane A** = low-cardinality operational metrics (Prometheus); **Plane B** = high-cardinality analytics fed by the **event fabric (`07`) + authoritative metering (C5)** — the layer emits **content-free observations** (§EO) to the correct plane; **never** high-cardinality on Plane A.
- **Alternatives:** single-tier — rejected (`14 §5.1`).
- **Why selected:** frozen `14 §5.1`.
- **Trade-offs:** two emission paths (correct separation).
- **Failure Modes:** wrong-plane emission ⇒ build-fail (§14).
- **Security Impact:** neither plane carries secrets/PII/content (`14 §7.1`).
- **Performance Impact:** Plane A fast; Plane B absorbs cardinality.
- **Enforcement:** `14 §5.1`/§19; OT-A4. **Build-Fail:** high-cardinality dimension on Plane A; analytics on Prometheus.

### OT-D5 — Metrics never sampled; decision/accounting metrics complete (NFR-MET-001)
- **Problem:** aggregates must be complete; only traces/high-volume telemetry may sample.
- **Decision:** **metrics are NOT sampled** (`14 §5`); only **traces + best-effort high-volume telemetry** sample (§13). **Decision/accounting metrics are never dropped** (NFR-MET-001) — protected buffer (§18.2). Retry double-counting is prevented by immutable execution identity (§18.1).
- **Alternatives:** sample metrics — rejected (`14 §5`).
- **Why selected:** complete aggregates; frozen `14`.
- **Trade-offs:** metric volume bounded by cardinality (not sampling) + streaming aggregation (§13.1).
- **Failure Modes:** extreme pressure ⇒ drop best-effort telemetry (§18.2), never decision/accounting metrics.
- **Security Impact:** none.
- **Performance Impact:** metrics cheap.
- **Enforcement:** `14 §5`; §18.1/§18.2; OT-A5/A19. **Build-Fail:** a sampled/double-counted decision-or-accounting metric.

### OT-D6 — Cardinality control (build-failing label allow-list)
- **Problem:** unbounded labels blow up the TSD (`08 §24.9`).
- **Decision:** only the **bounded Plane-A allow-list** (`14 §5/§5.1`); **NEVER** `request_id`/`correlation_id`/`user_id`/`api_key`/`session_id`/raw-URL/`email`/free-text as a metric label. Budgets enforced; exceeding **fails the build** (`14 §19`).
- **Alternatives:** free labeling — rejected.
- **Why selected:** frozen `14 §5`.
- **Trade-offs:** high-cardinality → traces/logs/exemplars/Plane B.
- **Failure Modes:** budget exceeded ⇒ build-fail + alert.
- **Security Impact:** no identifying labels.
- **Performance Impact:** protects the TSD.
- **Enforcement:** `14 §19`; OT-A6. **Build-Fail:** a forbidden/unbounded metric label.

### OT-D7 — Privacy & redaction (allow-list + scan + redact + reject, before emission)
- **Problem:** telemetry is the top real-world leakage vector.
- **Decision:** **allow-list + scan + redact + reject**, at **CI and runtime**, on **both planes** (`14 §7.1`): no secrets/PII/PHI/prompt/completion/regulated data; redaction **before** emission; residual ⇒ **reject/drop + alert**. Provider-native metadata redacted (§15.1). Never cross-tenant (AD-021).
- **Alternatives:** policy-only — rejected (`14 §7.1`).
- **Why selected:** frozen `14 §7.1`.
- **Trade-offs:** redaction cost bounded (§17).
- **Failure Modes:** unredactable ⇒ drop (fail-secure).
- **Security Impact:** the core privacy guarantee.
- **Performance Impact:** scan bounded.
- **Enforcement:** scanner (`13 §20`, `14 §7.1`, `15` T-052); OT-A7/A20. **Build-Fail:** a secret/PII/content/provider-native value in any telemetry.

### OT-D8 — Correlation & causation (one id threads all signals)
- **Problem:** signals must be correlatable end-to-end.
- **Decision:** one **correlation id** threads metrics↔traces↔logs↔events↔audit (`11` R-056, `12 §24`); every event carries **correlation + causation** (`07 §6`). Baggage = correlation id + tenant scope only (`14 §6`). Missing-id handling: §9.1.
- **Alternatives:** per-signal ids — rejected.
- **Why selected:** frozen `14`/`07 §6`.
- **Trade-offs:** disciplined propagation (§10).
- **Failure Modes:** missing correlation id ⇒ §9.1 (mint deterministic root, flag; never drop correctness).
- **Security Impact:** ids non-identifying/scoped.
- **Performance Impact:** O(1).
- **Enforcement:** correlation-coverage tests; OT-A8. **Build-Fail:** a signal without a correlation id where one exists.

### OT-D9 — Context propagation (in-process + across the async event boundary)
- **Problem:** the trace must reconstruct across hops and the async boundary.
- **Decision:** context propagates in-process (VT-safe, AD-023) and across the async event boundary via correlation/causation in the envelope (`07 §6`); cross-service hops mTLS-authenticated (`14 §6`). **Multi-region is id-only, residency-confined** (§10.1).
- **Alternatives:** no async propagation — rejected.
- **Why selected:** frozen `14 §6`/`07 §6`.
- **Trade-offs:** cross-region id-only (residency).
- **Failure Modes:** propagation gap ⇒ partial trace, never a runtime effect.
- **Security Impact:** no PII/content in baggage/envelope (§10.1).
- **Performance Impact:** O(1).
- **Enforcement:** propagation tests; OT-A9. **Build-Fail:** PII/content in baggage; cross-region propagation beyond the §10.1 allow-set.

### OT-D10 — Fail-closed by dropping telemetry (never runtime correctness)
- **Problem:** telemetry backpressure/failure must never harm the request.
- **Decision:** on any telemetry failure/backpressure/uncertainty the layer **drops telemetry** (best-effort) — **never** blocks/delays/fails a request (OT-INV). Bounded buffering; on saturation, shed per the **drop-order contract (§18.2)** which protects decision/accounting metrics.
- **Alternatives:** block on telemetry — rejected (OT-INV).
- **Why selected:** correctness > observability.
- **Trade-offs:** telemetry loss under pressure (observed via drop metrics).
- **Failure Modes:** all ⇒ drop telemetry (§18.2 order).
- **Security Impact:** no leak on drop.
- **Performance Impact:** bounded buffers (§17).
- **Enforcement:** load/backpressure tests; OT-A10. **Build-Fail:** a telemetry path that can block/fail a request.

### OT-D11 — Determinism/replay: telemetry decisions reproducible; timing not (see §19.1)
- **Problem:** telemetry carries wall-clock and sampling decisions; the platform supports replay (`25 §23.1`).
- **Decision:** **replay reproduces telemetry decisions** (which spans/samples/redactions) from recorded inputs; **replay never reproduces wall-clock timing**; **sampling decisions are deterministic from recorded inputs** (§19.1). Telemetry is **non-authoritative** (truth is C5, `23`) — **no overclaim**.
- **Alternatives:** authoritative/replayable-as-truth telemetry — rejected.
- **Why selected:** honest scope (§19.1).
- **Trade-offs:** timing differs on replay (disclosed, RSD-3).
- **Failure Modes:** none on correctness.
- **Security Impact:** none.
- **Performance Impact:** neutral.
- **Enforcement:** §19.1; OT-A11. **Build-Fail:** telemetry treated as authoritative usage/accounting; a nondeterministic sampling decision over recorded inputs.

### OT-D12 — Cost & performance bounded; streaming aggregated (see §13.1/§17.1)
- **Problem:** telemetry is a significant cost/throughput load (`14 §OM-2`); streaming can storm.
- **Decision:** telemetry cost is a **bounded % of infra cost** (`14 §OM-2`, §17.1); runtime overhead budgeted (NFR-TRC-001), off the critical path where possible; **high-volume streaming telemetry is aggregated/batched** (§13.1) — no per-token/per-chunk storms; bounded memory/CPU/cardinality.
- **Alternatives:** unbounded telemetry — rejected (`14 §OM-2`).
- **Why selected:** frozen cost discipline + §13.1.
- **Trade-offs:** aggregated streaming telemetry (disclosed).
- **Failure Modes:** budget/pressure ⇒ shed best-effort (§18.2).
- **Security Impact:** none.
- **Performance Impact:** bounded (§13.1/§17.1).
- **Enforcement:** cost budget (Plane B, `14 §OM-2`); §13.1; OT-A12/A18. **Build-Fail:** an unbounded/unaggregated high-volume (streaming) telemetry path.

---

## 6. Metrics
Plane A only, low-cardinality (`14 §5/§5.1`): counters/gauges/histograms; **bounded `14` label allow-list**. **Metrics never sampled** (OT-D5); no retry double-count (§18.1). Aggregation server-side. High-cardinality → Plane B/exemplars. **Metric names are `14`'s** (REA-6).

## 7. Traces
OpenTelemetry (AD-011); `<area>.<operation>` (no ids in the name); neutral bounded attributes (`14 §6`) — no content/secret/PII/provider-native (§15.1). **Sampled** deterministically (§13/§19.1); tail-sampling retains error/outlier traces (§13.2). Cross-service + async reconstruction (`07 §6`).

## 8. Structured Logs
Required fields (`14 §7`); **redaction before emission** (OT-D7/§15.1); log-field allow-list; free-text `message` redaction-scanned; **no content fields** (`14 §7.1`).

## 9. Correlation IDs
One correlation id threads metrics↔traces↔logs↔events↔audit (`11` R-056, `12 §24`); causation on every event (`07 §6`). Correlation ids are **never** metric labels (`14 §5`).

### §9.1 — Missing correlation-id handling *(resolves Medium — additive)*
If a signal arises where **no** correlation id exists yet (e.g., pre-auth ingress before an id is assigned), the layer **deterministically mints a root correlation id** from the already-available request context (transport/connection identity), **flags it** `correlation.origin=minted`, and threads it forward — it **never drops correctness** and **never emits an uncorrelatable orphan silently**. Once the pipeline assigns the canonical id, the minted root is linked as the causation ancestor. **Build-Fail (OT-A8):** a silently uncorrelated signal where context to mint/thread exists.

## 10. Context Propagation
In-process (VT-safe, AD-023) + across the async event boundary (`07 §6`) + across service hops (mTLS, `14 §6`). Baggage = correlation id + tenant scope only (`14 §6`).

### §10.1 — Cross-region trace residency *(resolves Medium — additive; pinned to `14 §18.1`/AD-014/`23 UME-D11`)*
Multi-region trace propagation is **id-only and residency-confined**: **only** the `{correlationId, causationId, traceparent, tracestate}` and a **residency-safe tenant scope token** may cross a region boundary — **never** prompt/completion/content, secrets, raw tenant identifiers, or provider-native data (`14 §18.1`, AD-014). Cross-region trace stitching uses **id correlation only** (matching `23 UME-D11` id-only cross-region rule). **Build-Fail (OT-A9):** any content/secret/raw-tenant value crossing a region boundary in a trace/baggage/envelope.

## 11. OpenTelemetry Mapping
Metrics → OTel/Prometheus (Plane A); traces → OTel spans + W3C; logs → OTel/structured backend. Semantic conventions where applicable (`14 §6`). Backend/store owned by the **C9 Observability Service** (`06 §9.10`), **not** this layer (REA-2).

## 12. Event Emission (see §EO)
The layer emits **content-free operational observations** within C9's already-owned space (`07 §6` envelope); Plane B is **fed by the event fabric (`07`) + C5 metering** (`14 §5.1`). It **produces observations about execution**; it **never** owns or duplicates a business-event topic (§EO).

### §EO — Telemetry Event Ownership Contract (EO-1…EO-8) *(resolves Critical OT-C2 — additive)*
| # | Aspect | Contract |
|---|---|---|
| EO-1 | **Operational ≠ business** | **Operational telemetry emission ≠ business-event ownership.** They are different concerns. |
| EO-2 | **Producers unchanged** | **Business events remain owned by their frozen producers** — no ownership moves. |
| EO-3 | **Observations only** | This layer may emit **only telemetry observations about runtime execution** — never a business event. |
| EO-4 | **Never owns** | This layer **never owns**: **Request events** (`06 §23.11`), **Cost events** (`22`), **Metering/Usage events** (`23`), **Governance events** (`21`), **Reliability events** (`20`) — nor any other frozen domain topic. |
| EO-5 | **No duplication** | The layer **never re-emits, duplicates, or shadow-publishes** a business event; it observes them (as they flow on `07`) and emits **separate, clearly-typed observations**. |
| EO-6 | **Content-free** | Observations are **content-free** (`14 §7.1`) and carry only ids/measurements. |
| EO-7 | **No topic authority** | The layer holds **no event-topic authority**; observation streams live within C9's already-owned observability space (`06 §23`), not a new topic owner. |
| EO-8 | **Non-authoritative** | Observations are **non-authoritative** for accounting/usage/cost/governance/reliability — the frozen producers remain the sources of truth. |
- **Enforcement (OT-A17):** cross-check `06 §23`/`07`/`20`/`21`/`22`/`23`. **Build-Fail:** this layer producing/owning/duplicating a business event (Request/Cost/Metering/Governance/Reliability or any frozen topic).

## 13. Sampling
**Metrics: never sampled** (OT-D5). **Traces + best-effort high-volume telemetry: sampled**, **deterministically from recorded inputs** (§19.1). Streaming aggregation: §13.1. Head/tail ownership: §13.2. Decision/accounting metrics never sampled (NFR-MET-001).

### §13.1 — Streaming Aggregation Contract (SA-1…SA-8) *(resolves High OT-H1 — additive)*
| # | Aspect | Contract |
|---|---|---|
| SA-1 | **No per-token/per-chunk emission** | Streaming telemetry (`18`, `25 §35`) is **never** emitted per token/per chunk as an individual signal. |
| SA-2 | **Aggregate** | Streaming metrics are **aggregated** over a stream (counts, sizes, durations as histograms/counters) and emitted at bounded intervals / at terminal verdict (`18 §30`). |
| SA-3 | **Bounded memory** | Per-stream aggregation state is **bounded** (fixed-size counters/histograms, operational baseline `16 §I.1`) — **no unbounded per-chunk buffering** (no memory amplification). |
| SA-4 | **Bounded CPU** | Aggregation is O(1) per chunk (increment/observe), not O(chunk-size) emission. |
| SA-5 | **Bounded cardinality** | No per-chunk/per-token dimension enters a label (`14 §5`); stream identity is a trace/exemplar concern, not a metric label. |
| SA-6 | **Consumer-paced** | Aggregation participates in the frozen **consumer-paced backpressure** (`25 §31.1 PL-7`) — telemetry never forces the provider read faster and never buffers ahead of the consumer. |
| SA-7 | **Sampled traces** | Per-stream trace detail is **sampled** (§13.2), not per-chunk-spanned; error/outlier streams retained by tail sampling. |
| SA-8 | **Drop under pressure** | Under pressure, streaming telemetry is **best-effort and shed first** (§18.2) — never the request, never decision/accounting metrics. |
- **Enforcement (OT-A18):** load/streaming tests (memory/CPU/cardinality bounded); cross-check `18`/`25 §31.1/§35`. **Build-Fail:** per-token/per-chunk individual telemetry emission; unbounded per-stream aggregation state; a per-chunk metric label.

### §13.2 — Head/tail sampling ownership *(resolves Medium — additive)*
Sampling **policy and rate are owned by `14`** (REA-3); this layer **applies** them. The applied model is **head sampling for the common path + tail sampling to retain error/outlier traces** (`14 §6`), with rate as an **operational baseline** (`16 §I.1`, §17.1). Decision/accounting **metrics are never sampled** regardless. **Build-Fail:** this layer authoring a sampling policy/rate (that is `14`'s).

## 14. Cardinality Control
Build-failing label allow-list (`14 §5/§19`); per-metric budgets; never unbounded/identifying/content/per-chunk labels (OT-D6/SA-5). High-cardinality → Plane B/exemplars. CI **and** runtime.

## 15. Privacy & Redaction
Allow-list + scan + redact + reject, before emission, both planes (`14 §7.1`, OT-D7). No secrets (incl. `26` credentials), no PII/PHI, no prompt/completion, no regulated data, no cross-tenant data. Provider metadata: §15.1.

### §15.1 — Provider Metadata Redaction Contract (PMR-1…PMR-7) *(resolves High OT-H4 — additive)*
| # | Aspect | Contract |
|---|---|---|
| PMR-1 | **Canonical only** | Only **canonical, provider-neutral metadata** may appear in telemetry (`25` canonical model; AD-007). |
| PMR-2 | **Never provider-native** | Provider-native codes/metadata are **never** emitted externally; `providerCodeOpaque` (`25 §37`) stays opaque/internal-ops only (`14 §10`). |
| PMR-3 | **Provider headers redacted** | Provider request/response headers (incl. any `Authorization`/credential-bearing header) are **redacted** before any telemetry/trace/log — consistent with `26 §20.1`. |
| PMR-4 | **No credential references** | **Credential references are prohibited** in telemetry — no credential, no lease id that resolves to material, no secret-derived value (`26`). |
| PMR-5 | **No tenant leakage** | No raw tenant identifier or cross-tenant value (AD-021); tenant scope is bounded/scoped (`14 §16`). |
| PMR-6 | **Provider label internal-only** | The `provider` label is **internal-ops only** (`14 §10`), never externally exposed/customer-facing. |
| PMR-7 | **Reject residual** | Any residual provider-native/credential/tenant pattern after redaction ⇒ **reject/drop + alert** (fail-secure). |
- **Enforcement (OT-A20):** leak scanner (`13 §20`, `14 §7.1`, `15` T-052); cross-check `25 §37`/`26 §20.1`/AD-007. **Build-Fail:** a provider-native code/header/credential-reference/raw-tenant value in any telemetry.

## 16. Security
- **OT-INV:** no runtime effect; telemetry cannot alter/block a request.
- **Secret safety:** never emit a credential (`26`); redact + reject (OT-D7/§15.1).
- **Tenant isolation (AD-021):** telemetry never crosses tenants; per-tenant signals access-scoped + cardinality-bounded (`14 §16`, `08 §24.9`).
- **No provider leak:** provider label internal-ops only; provider-native codes opaque (§15.1).
- **Residency (AD-014):** multi-region telemetry id-only, residency-confined (§10.1).
- **Enforcement:** scanner (`13 §20`, `14 §7.1`); isolation tests (`15` T-044). **Build-Fail:** secret/PII/content/cross-tenant/provider-native value in telemetry.

## 17. Performance
Emit path **off the response-latency critical path where possible**; **bounded buffers**; **runtime overhead budgeted** (NFR-TRC-001); streaming aggregated (§13.1). Numerics are operational-baseline (§17.1).

### §17.1 — Telemetry cost budget & operational numeric baselines *(resolves Medium — additive)*
Telemetry cost is a **bounded % of total infra cost** (`14 §OM-2`), enforced via sampling (§13), cardinality (§14), streaming aggregation (§13.1), retention/downsampling (`14 §5/§7`), and Plane A/B separation (`14 §5.1`); monitored on Plane B with overage alerts. **All numerics** — sampling rates, per-stream aggregation bounds, buffer sizes, budget %, cardinality-N, drop-order thresholds — are **operational-baseline entries** (`16 §I.1`, `§ODN`), never hard-coded (OT-A14). Under budget pressure the **drop order (§18.2)** applies.

## 18. Failure Handling (fail closed by dropping telemetry)
| Failure | Handling |
|---|---|
| Sink/collector down or slow | **drop/bounded-buffer**; never block/fail the request (OT-D10) |
| Backpressure/saturation | **shed per §18.2**; decision/accounting metrics protected (NFR-MET-001) |
| Redaction cannot sanitize | **reject/drop + alert** (fail-secure, `14 §7.1`/§15.1) |
| Retry/replay of the same attempt | **dedup by execution identity** (§18.1) — no double-count |
| Missing correlation id | mint deterministic root + flag (§9.1); never drop correctness |
| Wrong-plane/over-cardinality | build-fail (CI) / drop + alert (runtime) |
| Any unknown/internal error | **drop telemetry**, never affect the request |

### §18.1 — Retry Deduplication Contract (RDD-1…RDD-7) *(resolves High OT-H3 — additive)*
| # | Aspect | Contract |
|---|---|---|
| RDD-1 | **Immutable execution identity** | Telemetry is keyed by **`ExecutionIdentity{requestId, attemptId, correlationId}`** — the frozen per-attempt identity from Reliability (`20`) and the request idempotency key (`12 §14`). |
| RDD-2 | **No metric double-count** | Metrics are incremented **once per execution identity**; a retried/replayed emission with the same identity is **deduped** — **no double-count** of metrics. |
| RDD-3 | **No accounting double-count** | Accounting/decision telemetry is **non-authoritative** (EO-8) and additionally deduped; the **authoritative accounting truth is C5/`23`** (which is itself exactly-once, `23 UME-D3`) — telemetry never inflates it. |
| RDD-4 | **Latency** | Latency is recorded **per attempt** (distinctly, by `attemptId`) and **per request** (once, by `requestId`) — retries add attempts, never re-count the request. |
| RDD-5 | **Success** | "Success" is counted **once per request** (the delivered outcome, `20 §25`), never once per successful attempt. |
| RDD-6 | **Ownership** | **Execution identity is owned by Reliability (`20`)/the pipeline**, not this layer; the layer **consumes** it for dedup — no new identity authority. |
| RDD-7 | **At-least-once safe** | Under the frozen at-least-once/effectively-once event model (`07`), replayed telemetry with the same identity is idempotent at the consumer/aggregator — effectively-once counting. |
- **Enforcement (OT-A19):** dedup/property tests under `20` retry/hedge/replay. **Build-Fail:** a metric/accounting/success/latency counter incremented more than once per execution identity.

### §18.2 — Fail-closed drop order *(resolves Medium — additive)*
Under saturation the shed order is: **(1)** best-effort high-volume/streaming telemetry (§13.1) → **(2)** sampled traces → **(3)** verbose logs → **never (4)** decision/accounting metrics (NFR-MET-001, a **protected bounded buffer** sized as an operational baseline, §17.1). If even the protected buffer saturates, the layer **drops telemetry and alerts** — it **never** blocks or fails the request (OT-INV). Fail-closed-drop and never-drop-decision-metrics coexist because decision/accounting metrics have a **reserved, prioritized path**; only best-effort telemetry is shed.

## 19. Replay
Telemetry is **non-authoritative** and **not replayed as truth** (EO-8/OT-D11); usage/accounting truth is C5 (`23`). A replay (`25 §23.1`) reproduces **decisions**, not wall-clock timing (§19.1).

### §19.1 — Replay & Sampling Determinism Contract (RSD-1…RSD-6) *(resolves High OT-H2 — additive)*
| # | Aspect | Contract |
|---|---|---|
| RSD-1 | **Decisions reproduced** | **Replay reproduces telemetry decisions** — which spans are created, which samples are kept, which redactions apply — from recorded inputs. |
| RSD-2 | **Deterministic sampling** | **Sampling decisions are deterministic from recorded inputs**: the keep/drop decision is a pure function of a **correlation-id-seeded** hash + the `14`-owned rate (no `Math.random`); given the same correlation id + rate, the same decision results. |
| RSD-3 | **Timing NOT reproduced** | **Replay never reproduces wall-clock timing** — timestamps/latencies are real-time and differ on replay; this is disclosed, **not overclaimed**. |
| RSD-4 | **Non-authoritative** | Telemetry is **non-authoritative** (EO-8); a replay uses it for correlation, never as a source of truth. |
| RSD-5 | **Pure decision core** | The emit-decision logic (map → redact → cardinality-check → sample-decision) is a **pure function** of recorded inputs (`11` R-063); only the sink I/O and timestamps are nondeterministic. |
| RSD-6 | **No overclaim** | The layer is **not** claimed deterministic end-to-end — only its **decisions** are reproducible; timing/delivery are best-effort. |
- **Enforcement (OT-A11):** replay/determinism tests (`15` T-035). **Build-Fail:** a nondeterministic (non-seeded) sampling decision; telemetry treated as an authoritative/replayable source of truth.

## 20. Determinism
The emit **decision logic** is deterministic over recorded inputs (§19.1 RSD-5); **timestamps and delivery are nondeterministic**; sampling is **correlation-id-seeded** (RSD-2). Honestly **not** a deterministic authoritative artifact (RSD-6).

## 21. Testing
- **Unit/property (jqwik, `15` T-035):** *no secret/PII/content/provider-native ever emitted* (both planes); *no unbounded/per-chunk label*; *telemetry never alters a request/decision*; *drop-on-failure never blocks a request*; *correlation id threads all signals*; *deterministic sampling from recorded inputs*; *no retry double-count (execution identity)*.
- **Privacy/leak (`15` T-052):** allow-list + scan + redact + reject; credential (`26`)/PII/content/provider-native never in any signal (§15.1).
- **Cardinality (`14 §19`):** label allow-list build-fail; per-metric budget; no per-chunk label (SA-5).
- **Streaming (§13.1):** aggregation bounded memory/CPU/cardinality; consumer-paced (`25 §31.1`); no per-chunk storm.
- **Retry dedup (§18.1):** no metric/accounting/latency/success double-count under `20` retry/hedge/replay.
- **Replay determinism (§19.1):** decisions reproduced; timing not.
- **Isolation/residency (`15` T-044/T-045):** no cross-tenant; cross-region id-only (§10.1).
- **Backpressure/drop order (§18.2):** decision/accounting metrics protected; request unaffected.
- **Mutation (`15 §G.1`):** **≥ 85%** — a mutant that leaks, alters a request, blows cardinality, blocks a request, or double-counts must be killed.
- **Follows:** `14`/`15`/`16`/`07` and `17`–`26`.

## 22. Operations
- **`otel_dropped_total` spike:** telemetry backpressure — expected (OT-D10/§18.2); investigate sink; **never make telemetry blocking**.
- **Secret/PII/provider-native-in-telemetry alarm (`14 §7.1`/§15.1, Sev1):** redaction gate breached — immediate.
- **Cardinality-budget breach:** build-fail (CI)/drop+alert (runtime).
- **Retry-double-count anomaly:** execution-identity dedup breach (§18.1) — investigate; accounting truth remains C5/`23`.
- **Telemetry-cost overage (`14 §OM-2`/§17.1):** tune sampling/aggregation/cardinality; observability must not cost more than the reliability it buys.

## 23. Build-Failing Rules (OT-A1 … OT-A20)

| # | Rule | Gate |
|---|---|---|
| OT-A1 | Telemetry never alters/blocks/fails a request or any runtime decision (side-effect-free) | ArchUnit + property test |
| OT-A2 | No new observability module/service/store/topic-owner; implements the frozen C9 emitter node only | cross-check `06 §8/§9.10`, `14` |
| OT-A3 | OpenTelemetry + W3C Trace Context only; no bespoke format / vendor agent | OTel conformance (AD-011) |
| OT-A4 | Two-tier: no high-cardinality dimension on Plane A; no analytics on Prometheus | `14 §5.1/§19` |
| OT-A5 | Metrics never sampled; decision/accounting metrics never dropped/double-counted | `14 §5`, NFR-MET-001, §18.1 |
| OT-A6 | No unbounded/identifying/content/per-chunk metric label (allow-list; per-metric budget) | `14 §19` label lint |
| OT-A7 | No secret/PII/PHI/prompt/completion/regulated value in any telemetry (redact-before-emit; reject residual) | scanner (`13 §20`, `14 §7.1`, `15` T-052) |
| OT-A8 | Every signal carries the correlation id (mint+thread if absent, §9.1); causation on events | correlation-coverage test |
| OT-A9 | No PII/content in baggage/envelope; cross-region propagation id-only, residency-confined (§10.1) | `15` T-044/T-045 |
| OT-A10 | No telemetry path that can block/fail/delay a request; bounded buffers; drop on saturation (§18.2) | load/backpressure test |
| OT-A11 | Telemetry never authoritative; sampling deterministic from recorded inputs (§19.1) | replay test (`15` T-035) |
| OT-A12 | No unbounded/unsampled high-volume telemetry path; cost budget enforced | `14 §OM-2` |
| OT-A13 | No provider-native code/metadata exposed externally (opaque; internal-ops only) | `14 §10`, `25 §37`, AD-007 |
| OT-A14 | No hard-coded telemetry numeric; sampling/cardinality/budget/aggregation from operational baseline | config lint (`16 §I.1`) |
| OT-A15 | Mutation score ≥ 85% | PITest (`15 §G.1`) |
| OT-A16 | **Runtime Emission Authority (§RE):** never authors telemetry policy/schema/metric-names; subordinate to `14`; not a second authority | cross-check `14`/`06 §8/§9.10` |
| OT-A17 | **Telemetry Event Ownership (§EO):** never owns/produces/duplicates a business event (Request/Cost/Metering/Governance/Reliability or any frozen topic); observations only | cross-check `06 §23`/`07`/`20`–`23` |
| OT-A18 | **Streaming Aggregation (§13.1):** no per-token/per-chunk emission; bounded memory/CPU/cardinality; consumer-paced | streaming/load test (`18`/`25 §31.1`) |
| OT-A19 | **Retry Deduplication (§18.1):** no metric/accounting/latency/success double-count; dedup by execution identity (owned by `20`) | dedup/property test |
| OT-A20 | **Provider Metadata Redaction (§15.1):** canonical only; provider headers redacted; no credential references; no tenant leakage | leak scanner (`15` T-052) |

## 24. Traceability
| Concern | BR | NFR | ADR | Domain/Svc | Sec | Test | Deploy |
|---|---|---|---|---|---|---|---|
| Passive / OT-INV (OT-D1/§16) | BR-010 | NFR-OBS-001 | AD-018/011 | C9 (`06 §8` EM) | §16 | T-052 | `16` |
| Emission authority (OT-D2/§RE) | BR-005 | NFR-OBS | AD-020/006 | C9 (`06 §9.10`)/`14` | — | T-051 | D-014 |
| OTel/W3C (OT-D3/§11) | BR-010 | NFR-TRC-001 | AD-011 | C9 | — | conformance | — |
| Two-tier (OT-D4/§6/§12) | BR-010 | NFR-MET-001 | AD-011/005 | `14 §5.1` | — | `14 §19` | — |
| Cardinality (OT-D6/§14) | BR-010 | NFR-MET-001 | AD-011 | `08 §24.9` | §16 | `14 §19` | — |
| Privacy/redaction (OT-D7/§15/§15.1) | BR-019/010 | NFR-LOG-001 | AD-012 | `13 §20`/`14 §7.1` | §16 | T-052 | — |
| Correlation/context (OT-D8/D9/§9.1/§10.1) | BR-010 | NFR-OBS/TRC | AD-011/005/014 | `07 §6`/`14 §6/§18.1` | §16 | coverage/T-045 | — |
| Event ownership (OT-D2/§EO) | BR-010 | NFR-OBS | AD-005 | `07`/`20`–`23`/C10 | — | T-008 | — |
| Streaming aggregation (OT-D12/§13.1) | BR-010 | NFR-TRC-001/PERF | AD-011/023 | `18`/`25 §31.1/§35` | §16 | load | — |
| Sampling (OT-D5/§13/§13.2) | BR-010 | NFR-TRC-001 | AD-011 | `14 §6` | — | sampling | — |
| Fail-closed drop (OT-D10/§18.2) | BR-001/010 | NFR-REL/OBS | AD-016/018 | C9 | §16 | load | — |
| Retry dedup (OT-D5/§18.1) | BR-012 | NFR-MET-001 | AD-005 | `20`/`23` | — | dedup | — |
| Replay/determinism (OT-D11/§19.1) | BR-004 | NFR-OBS | AD-016 | `25 §23.1`/`23` | — | T-035 | — |
| Cost/baselines (OT-D12/§17.1) | BR-010 | NFR-TRC-001/PERF | AD-011 | `14 §OM-2` | — | load | — |

---

## 25. Independent Review Board — Adversarial Review (re-run after resolution)

### Consistency Review (post-resolution)
| Frozen source | Requirement | This document | ✓ |
|---|---|---|---|
| `14` (frozen standards owner) | C9 owns observability standards; `14` is SoT | §RE REA-1…REA-8 (emission-only; `14` prevails) | ✅ |
| `06 §8` EM / `06 §9.10` | frozen emitter node; C9 backend | §3/§RE (pinned) | ✅ |
| AD-011 | OpenTelemetry + W3C | OT-D3/§11 | ✅ |
| `14 §5.1` | two-tier, never conflated | OT-D4/§6/§12 | ✅ |
| `14 §5/§19` | metrics not sampled; cardinality build-fail | OT-D5/D6/§13/§14 | ✅ |
| `14 §7.1` | allow-list + scan + redact + reject | OT-D7/§15/§15.1 | ✅ |
| `07 §6` | correlation+causation; async propagation | OT-D8/D9/§9.1/§10 | ✅ |
| `07`/`20`–`23`/C10 | business-event ownership | §EO EO-1…EO-8 (observations only) | ✅ |
| `25 §23.1`/`23` | replay; authoritative usage | §19.1 RSD-1…RSD-6 (decisions reproduced, non-authoritative) | ✅ |
| `18`/`25 §31.1/§35` | streaming volume; consumer-paced | §13.1 SA-1…SA-8 (aggregated, bounded, consumer-paced) | ✅ |
| `25 §37`/`26 §20.1`/`14 §10` | provider opacity; credential redaction | §15.1 PMR-1…PMR-7 | ✅ |
| `20`/`12 §14`/`23 UME-D3` | attempt identity; exactly-once accounting | §18.1 RDD-1…RDD-7 (dedup; non-authoritative) | ✅ |
| AD-021/AD-014/`14 §18.1` | isolation; residency | §16/§10.1 | ✅ |
| AD-018 | non-bypassable; observability additive | OT-D1 | ✅ |

**No contradictions remain.** The Resolution Pass (§RE/§EO/§9.1/§10.1/§13.1/§13.2/§15.1/§17.1/§18.1/§18.2/§19.1, OT-A16…A20) is **additive only**: no new architecture/service/module/context/store/event-authority/pipeline-stage/topology; AD-002/005/011/014/018/020/021 preserved exactly; OT-INV reinforced; full consistency with `06`/`07`/`14`/`17`–`26`.

### Architecture Validation (post-resolution)
- **Ownership:** ✅ pinned to the frozen EM node and strictly subordinate to `14`/C9 (§RE); business events stay with frozen producers (§EO) — **nothing moved**.
- **Boundaries:** ✅ passive/emit-only; audit (C10) excluded; observations ≠ business events.
- **Provider neutrality:** ✅ canonical-only, provider-native redacted (§15.1, AD-007).
- **Privacy/security:** ✅ `14 §7.1` redaction + provider/credential/tenant redaction (§15.1); streaming leak surface bounded (§13.1).
- **Performance:** ✅ streaming aggregated/bounded (§13.1); off-critical-path; cost-budgeted (§17.1); drop order protects decision metrics (§18.2).
- **Determinism/replay:** ✅ decisions reproduced, timing not (§19.1); no retry double-count (§18.1).

### Independent Review Board (re-run)
*Board: Principal Enterprise Architect · Observability/SRE Architect · Distributed Tracing Expert · Data Privacy Officer · Security Architect · Streaming Systems Expert · Performance Engineer · FinOps Architect · Compliance Auditor · CTO.*

#### Accepted findings — resolution
- **OT-C1 → RESOLVED (§RE).** Runtime Emission Authority Contract: emission-only; C9 owns standards; `14` is the single source of truth; never authors policy/schema/metric-names; competing wording superseded. Build-enforced (OT-A16).
- **OT-C2 → RESOLVED (§EO).** Telemetry Event Ownership Contract: operational telemetry ≠ business-event ownership; never owns Request/Cost/Metering/Governance/Reliability (or any frozen) events; observations only; no duplication. Build-enforced (OT-A17).
- **OT-H1 → RESOLVED (§13.1).** Streaming Aggregation Contract: no per-token/per-chunk emission; bounded memory/CPU/cardinality; consumer-paced (`25 §31.1`). Build-enforced (OT-A18).
- **OT-H2 → RESOLVED (§19.1).** Replay & Sampling Determinism Contract: decisions reproduced; wall-clock never reproduced; sampling deterministic from recorded (correlation-id-seeded) inputs; no overclaim.
- **OT-H3 → RESOLVED (§18.1).** Retry Deduplication Contract: no metric/accounting/latency/success double-count; immutable execution identity owned by `20`; effectively-once. Build-enforced (OT-A19).
- **OT-H4 → RESOLVED (§15.1).** Provider Metadata Redaction Contract: canonical only; provider headers redacted; credential references prohibited; no tenant leakage. Build-enforced (OT-A20).
- **Medium → RESOLVED:** drop order vs decision metrics (§18.2); cross-region trace residency (§10.1); head/tail sampling ownership (§13.2); telemetry cost budget (§17.1); missing correlation-id handling (§9.1); operational numeric baselines (§17.1).

#### New findings from the re-run
- **🔴 Critical:** none. **🟠 High:** none. **🟡 Medium:** none blocking. **🟢 Low (deferred):** Appendix B (metric-name registry pointer, exemplar wiring, OTel semantic-convention version pin, dashboard/alert catalog) — detail deliverables, non-blocking.

#### Internal Contradictions
- **None.** Fail-closed-drop and never-drop-decision-metrics reconciled via the reserved protected path (§18.2); authority/ownership pinned (§RE/§EO); determinism claimed only for decisions (§19.1).

#### Cross-document Contradictions
- **None.** Consistent with `14` (SoT), `07`/`20`–`23`/C10 (event ownership), `18`/`25 §31.1/§35` (streaming), `25 §37`/`26 §20.1` (redaction), `20`/`23` (dedup/exactly-once), AD-005/011/014/018/021.

#### Scores
- **Architecture Readiness: 96 / 100** — all Critical/High/Medium resolved additively; authority/ownership pinned; streaming/replay/dedup/provider-metadata/residency contracted; no architecture/ownership/topology change. Residual 4 pts are Low detail deliverables.
- **Documentation Health: 96 / 100** — contradictions closed; `14` supremacy explicit; honest determinism/cost scoping.
- **Implementation Readiness: 94 / 100** — every load-bearing seam (emission authority, event ownership, streaming aggregation, replay/sampling determinism, retry dedup, provider redaction, drop order, residency) specified precisely enough to implement.

#### Recommendation: **FREEZE.** Critical = 0, High = 0, Medium = 0 (blocking); Low deferred (Appendix B). No contradictions with `00`–`26` or AD-001…AD-023; no new architecture/service/module/context/store/event-authority/pipeline-stage/topology; AD-002/005/011/014/018/020/021 preserved; OT-INV reinforced.

---

## Appendix A — Sampling determinism (informative)
The keep/drop decision is `keep = hash(correlationId) < rate`, where `rate` is `14`-owned (§13.2) — a pure function of recorded inputs (no RNG), so a replay reproduces the identical decision (§19.1 RSD-2/RSD-5) while timestamps/latencies remain real-time (RSD-3).

## Appendix B — Deferred Low Items (non-blocking)
- **OT-L1** — metric-name registry pointer → owned by `14` (this doc references only, REA-6).
- **OT-L2** — exemplar wiring (Plane A → Plane B trace links) → detailed design.
- **OT-L3** — OTel semantic-convention version pinning → observability detailed design.
- **OT-L4** — dashboard/alert catalog → SRE runbook (`14 §…`).

These are documentation/detail deliverables; none affects ownership, neutrality, correctness, privacy, or any invariant, and each is contract-/cross-team-testable.

---

*End of document — 27-ObservabilityTelemetry.md · Status: **FROZEN** · Version: 1.0 (2026-07-22)*
