# 25 — Provider Adapter (Runtime Anti-Corruption Layer · Domain C1)

**Document:** Component Implementation Architecture — Provider Adapter
**Project:** Reliability-First AI Gateway
**Status:** **FROZEN**
**Bounded context:** **C1 — Provider & Routing** (Core Domain, `05`/`06`)
**Module:** `dp-provider-adapter` — the **runtime Anti-Corruption Layer (ACL)** of C1's data-plane presence (the frozen "Provider Adapters" node, `06 §8` PA), co-located per **AD-020/AD-006** — **not** a new service, **not** a new bounded context, **owns no store**. **One adapter implementation per provider.**
**Frozen name:** Provider Adapters (PA)
**Owner:** Provider & Routing (stream-aligned, Core)
**Plane:** Tier-0 Runtime
**Audience:** Provider/AI-infrastructure/platform/data-plane engineers, SRE, streaming/security engineers, QA, reviewers
**Classification:** Internal — Component Architecture
**Builds on (approved, frozen):** `00`–`23`, `24A-Architecture-Reconciliation`, and ADRs **AD-001…AD-023**. Everything here is **additive**. Introduces **no new architecture decision, no new bounded context, no new service, no new store, no ownership move, and no provider coupling** (the adapter is the *sole sanctioned locus* of provider specificity, AD-007). Every PA-Dx is a *component-internal implementation decision* inside the already-frozen C1, expressed through hexagonal ports (AD-002).

> **What this is.** The complete implementation architecture of the **Provider Adapter** — the **Anti-Corruption Layer (AD-007/AD-002)** that converts provider-native protocols, SDKs, authentication, request/response/streaming/tool-call/usage/error formats into the platform's **canonical runtime model**, and back. It is the **only** place in the system that understands a provider's wire reality; **everything downstream receives only canonical objects.** It is invoked by Reliability (`20`) on a route chosen by the Router (`19`), it hands canonical streams/responses to the Correctness Engines (`18`/`17`), and it emits canonical usage/errors consumed by Cost (`22`) and Usage Metering (`23`).
>
> **THE PROVIDER-ADAPTER INVARIANT (PA-INV):** *Never leak provider-specific semantics outside the adapter. Never fabricate provider data. Never silently normalize an incorrect value. Never weaken correctness. Never bypass Router, Reliability, StreamGuard, SchemaLock, or Governance.* On any uncertainty it **fails closed** — it surfaces a canonical error rather than emit a guessed or partially-translated object. This is the neutrality-layer realization of **AD-007 (multi-provider abstraction)** and **AD-002 (hexagonal, replaceable adapters)**: provider complexity is *contained*, never propagated.

---

## ✅ Resolution Notice (Independent Architecture Resolution Pass · 2026-07-22)

This document was frozen **after** the Independent Review Board's adversarial attack. All **Critical (PA-C1, PA-C2)**, **High (PA-H1…PA-H4)**, and **Medium (PA-M1…PA-M5)** findings are resolved **additively** through binding contracts:

- **§33.1** Credential Handling Contract (PA-C1) · **§14.1** Capability Ownership Contract (PA-C2)
- **§17.1** Timeout Ownership Contract (PA-H2) · **§20.1** Usage Normalization Ownership Contract (PA-H1)
- **§23.1** Determinism Contract — pure-core/impure-shell (PA-H4) · **§31.1** Connection-Pool Lifecycle & Backpressure Contract (PA-H3, PA-M1)
- **§16.1** Canonical Error Categories (PA-M4) · **§29.1** Latency-budget allocation (PA-M3) · **§7.1** Adapter dispatch & provider-metadata rules (PA-M2, PA-M5)

Build-enforced by **PA-A16…PA-A21**. **Low** items are deferred to **Appendix Z**. **No new architecture, service, bounded context, ownership, or store is introduced; AD-002/AD-007/AD-018/AD-020/AD-021/AD-022 are preserved exactly; no invariant is weakened.** No document `00`–`24A` and no ADR is modified.

---

## Table of Contents
**A. Charter** (§1–5) · **B. Domain & Canonical Model** (§6–7) · **C. Major Decisions** (PA-D1…PA-D12) · **D. Translation Model** (§8–13) · **E. Interfaces** (§14–22) · **F. Determinism, I/O & Replay** (§23–25) · **G. Failure Handling** (§26–28) · **H. Performance & Concurrency** (§29–32) · **I. Security** (§33) · **J. Observability** (§34–37) · **K. Testing** (§38) · **L. Build-Failing Rules** (PA-A1…PA-A21) · **M. Operations** (§39–41) · **N. Traceability** (§42) · **S. Reviews** · **Appendix Z** (deferred Low items)

---

## A. Charter

### §1 — Purpose
The Provider Adapter **contains all provider specificity**. It translates a **`CanonicalRequest`** into a provider-native call (SDK/HTTP/auth/format), executes the provider invocation, and translates the provider-native response/stream/tool-call/usage/error back into **canonical objects**. It is the **Anti-Corruption Layer** (AD-007): the single boundary where provider protocols are understood, so that Router (`19`), Reliability (`20`), StreamGuard (`18`), SchemaLock (`17`), Cost (`22`), Usage Metering (`23`), and Governance (`21`) operate on a **provider-neutral canonical model** and never on provider-native shapes.

### §2 — Scope
- **In scope (translate only, provider-specific inside / canonical outside):** provider SDK integration; HTTP transport; provider authentication (short-lived cached credentials, never owned/persisted — §33.1); request/response/stream/tool-call translation; usage **transport-format** normalization (StreamGuard owns runtime usage — §20.1); error normalization; capability **consumption** (read-only registry snapshot — §14.1); canonical runtime-model production.
- **One adapter implementation per provider** (AD-002 replaceable adapter); adding a provider = a new adapter, **zero diff** to any downstream module (AD-007).
- **Applies to:** every provider invocation on the hot path, for the route/provider selected upstream (`19`) and executed under Reliability's control (`20`).

### §3 — Responsibilities
1. Own **provider SDK integration**, **HTTP transport**, and **provider authentication** — using **short-lived credential material supplied by the existing Secrets mechanism** (C14 cached snapshot, `06 §9.6`, AD-022), held **only in process memory for the active invocation**; the adapter **owns no credential store and persists no credential** (§33.1 · PA-D8).
2. **Translate** `CanonicalRequest` → provider-native request; provider-native response/stream → `CanonicalResponse`/`CanonicalStream`; provider tool-call → `CanonicalToolCall`.
3. **Normalize** provider usage **transport format** → `CanonicalUsage` and provider errors → `CanonicalProviderError` — **facts only, never fabricated**; **runtime usage extraction is StreamGuard's** (§20.1).
4. **Consume** immutable capability snapshots to map provider features to canonical capabilities (AD-022) — the adapter **only consumes**; the **Provider Registry is the sole owner** and the adapter **never creates/authors/discovers/mutates** a capability descriptor (§14.1 · PA-D3).
5. **Produce the canonical runtime model** — the sole provider-neutral contract everything downstream consumes.
6. **Fail closed** on any translation/transport uncertainty — surface a `CanonicalProviderError`, never a guessed/partial canonical object (PA-INV).

### §4 — Non-Responsibilities (what the Adapter NEVER does)
- ❌ **routing / provider selection** — C1 Router (`19`); the adapter executes the *chosen* route.
- ❌ **retry / failover / timeout policy** — C2 Reliability (`20`) is the **sole timeout/retry/failover owner** (§17.1).
- ❌ **schema validation** — C3 SchemaLock (`17`); the adapter *translates* tool-calls, it does not *validate* them.
- ❌ **stream integrity / runtime usage extraction** — C3 StreamGuard (`18`); the adapter *produces* the canonical stream and does **transport-format** usage normalization only (§20.1).
- ❌ **governance / authorization** — C4 (`21`)/PEP.
- ❌ **pricing / billing** — C5 Cost (`22`)/C8.
- ❌ **optimization** — rejected as standalone (`24A`); would be a pre-routing plugin, not the adapter.
- ❌ **caching** — C2 cache tier (connection reuse is ephemeral transport, not caching — PA-D11/§31.1).
- ❌ **capability authoring/discovery** — C1 Provider Registry (`06 §9.2`); the adapter consumes snapshots only (§14.1).

### §5 — What the Adapter is NOT
| Provider Adapter (translate/contain) | Not the Adapter |
|---|---|
| ✓ understand provider wire reality | ✗ select the provider (Router `19`) |
| ✓ produce canonical objects only | ✗ decide retry/failover/timeout (Reliability `20`) |
| ✓ normalize usage transport-format as facts | ✗ extract runtime usage / meter / price (`18`/`23`/`22`) |
| ✓ contain SDK/auth/format | ✗ validate schema / guard streams (`17`/`18`) |
| ✓ consume capability snapshots | ✗ author/own capabilities (Provider Registry `06 §9.2`) |
| ✓ one adapter per provider | ✗ branch on provider name downstream (AD-007) |

---

## B. Domain & Canonical Model

### §6 — Canonical runtime model (the provider-neutral contract)
Immutable value objects (no infrastructure leakage, `11` hexagonal).

| Type | Kind | Description |
|---|---|---|
| `CanonicalRequest` | VO (input) | The provider-neutral request handed to the adapter (canonical model id `19 §9.2`, messages, tool defs, params) — produced upstream |
| `CanonicalResponse` | VO (immutable) | Provider-neutral non-streaming response: `{content, toolCalls[], finishReason, usage:CanonicalUsage, providerMeta(opaque, internal-only — §7.1)}` |
| `CanonicalStream` | VO (stream) | Provider-neutral ordered stream of canonical chunks (delta/tool-call-delta/usage/terminal), consumer-paced backpressure (§31.1), consumed by StreamGuard (`18`) |
| `CanonicalToolCall` | VO | Provider-neutral tool invocation `{name, arguments(raw, unvalidated), callId}` — translated shape; **validation is SchemaLock's (`17`)** |
| `CanonicalUsage` | VO (immutable) | Provider-neutral usage `{prompt, completion, reasoning, cached, toolTokens}` with `usageClass` (authoritative/estimated, `18 CV-5`) — **facts, never fabricated**; runtime extraction owned by StreamGuard (§20.1) |
| `CanonicalProviderError` | VO (immutable) | Provider-neutral error `{category(§16.1), retryableHint?, providerCodeOpaque, transient:bool}` — normalized; **retry *decision* is Reliability's (`20`)** |
| `CapabilityMapping` | VO (read-only, snapshot-derived) | Provider feature → canonical capability, derived from the **Provider Registry snapshot** (AD-022); the adapter *consumes* it, never authors it (§14.1) |

**Aggregate:** `ProviderInvocation` — the transient per-call aggregate coordinating translate-out → transport → translate-in; holds **no shared mutable business state across requests** (AD-021). Pooled transport connections carry **no request/tenant/credential identity** (§31.1).

### §7 — Ports (AD-002 hexagonal)
**Inbound (called by Reliability on a chosen route):**
```
ProviderAdapterPort:
  invoke(CanonicalRequest, RouteTarget, AttemptBudget) -> CanonicalResponse | CanonicalStream   // fail-closed to CanonicalProviderError
```
`AttemptBudget` is the per-attempt transport deadline handed down by Reliability (§17.1) — the adapter enforces it, it does not author it.

**Outbound (per adapter; provider-specific INSIDE only):**
```
ProviderTransportPort   // -> provider SDK/HTTP (the ONLY provider-coupled surface)
CredentialPort          // <- C14 Secrets: short-lived cached credential material (06 §9.6, AD-022) — §33.1
CapabilitySnapshotPort  // <- C1 Provider Registry: read-only capability/route snapshot (AD-022, 06 §9.2) — §14.1
TelemetryPort           // -> C9: canonical, provider-neutral telemetry (content-free)
ClockPort               // deterministic time (for the mapping core; transport uses real time)
```
- **Enforcement:** ArchUnit — **no provider SDK, no provider-native type, no provider-name string** may appear **outside** an adapter package; downstream modules import **only** the canonical model. The adapter imports **no** routing/retry/governance/pricing/schema-validation/stream-integrity types.

### §7.1 — Adapter dispatch & provider-metadata rules *(resolves Medium PA-M2 / PA-M5 — additive)*
- **Dispatch (no provider-name branching):** the concrete adapter is selected by an **adapter registry keyed on the canonical model id / `RouteTarget`** produced by the Router (`19`). Selection is a **data-driven registry lookup** (`canonicalModelId → adapter`, sourced from the registry snapshot, AD-022) — **never** a provider-name `switch`/`if` in pipeline code. The Reliability caller and all downstream modules hold only the `ProviderAdapterPort` interface (AD-002); they never name a provider. Adding a provider registers a new adapter binding — **zero pipeline diff** (AD-007).
- **Provider metadata (`providerMeta`):** opaque bytes, **adapter-internal provenance only** (for replay capture/debugging). **No downstream module may type, parse, branch on, or consume** `providerMeta`; it is never a typed cross-module dependency. **Build-Fail (PA-A19):** any downstream read/parse of `providerMeta`.

---

## C. Major Decisions (PA-D1 … PA-D12)
*9-field template: Problem · Decision · Alternatives · Why selected · Trade-offs · Failure Modes · Security Impact · Performance Impact · Enforcement.*

### PA-D1 — Anti-Corruption boundary (containment, not propagation)
- **Problem:** provider protocols differ wildly; if any provider shape escapes, neutrality (AD-007) is dead.
- **Decision:** the adapter is a **strict ACL** — provider SDKs/types/names live **only** inside the adapter package; every output crossing the boundary is a **canonical object**. `providerMeta` is **opaque and internal-only** (§7.1), never interpreted downstream.
- **Alternatives:** thin pass-through of provider objects — rejected (leaks coupling); per-consumer translation — rejected (scattered, un-auditable).
- **Why selected:** single containment locus; downstream neutrality by construction.
- **Trade-offs:** all provider knowledge concentrates here (accepted; that is the point).
- **Failure Modes:** untranslatable field ⇒ fail closed (canonical error), never leak.
- **Security Impact:** no provider surface downstream.
- **Performance Impact:** one translation pass (§29).
- **Enforcement:** ArchUnit (no provider import/type/name outside adapter) — PA-A1/A2/A3. **Build-Fail:** any provider object/type/name outside an adapter package.

### PA-D2 — Provider neutrality (canonical model id, no downstream branching)
- **Problem:** neutrality must hold end-to-end despite provider-specific behavior.
- **Decision:** the adapter keys everything on the **canonical model id** (`19 §9.2`); provider identity is **internal to the adapter**. Downstream **never branches on provider name** (AD-007); dispatch is a data-driven registry lookup (§7.1). Adding a provider = a new adapter, **zero downstream diff**.
- **Alternatives:** provider-name switches in the pipeline — rejected (AD-007).
- **Why selected:** extensible, neutral, testable.
- **Trade-offs:** each provider needs a full adapter (accepted).
- **Failure Modes:** unknown canonical model ⇒ fail closed (no such adapter/capability).
- **Security Impact:** no provider leak.
- **Performance Impact:** O(1) dispatch by canonical id.
- **Enforcement:** `15` T-015 (add-provider = zero diff); ArchUnit — PA-A4. **Build-Fail:** a provider-name branch outside an adapter.

### PA-D3 — Capability *consumption* only (Provider Registry is the sole owner) — see §14.1
- **Problem:** the platform needs each provider's capabilities; the frozen **Provider Registry (C1 CP, `06 §9.2`)** already owns the capability registry.
- **Decision:** the adapter **only consumes immutable capability snapshots** (AD-022) as a read-only `CapabilityMapping` to translate features. It **NEVER creates, authors, discovers, infers, probes, or mutates** a capability descriptor at runtime. The Provider Registry remains the **sole** owner/author of capability truth (§14.1). Any earlier wording implying runtime "capability descriptor production" is superseded: the adapter **consumes**, it does not **produce**.
- **Alternatives:** adapter authors/discovers capabilities at runtime — **rejected** (would move ownership from the Provider Registry — forbidden).
- **Why selected:** keeps registry ownership intact (C1 CP); the adapter stays a pure translator.
- **Trade-offs:** the adapter depends on a fresh registry snapshot.
- **Failure Modes:** missing/stale capability snapshot ⇒ fail closed (never guess/probe).
- **Security Impact:** none new.
- **Performance Impact:** snapshot read, O(1).
- **Enforcement:** cross-check `06 §9.2`; ArchUnit — no capability store, no capability authoring in the adapter — **PA-A16**. **Build-Fail:** the adapter creating/authoring/discovering/mutating/persisting a capability descriptor.

### PA-D4 — Streaming normalization (produce canonical stream; StreamGuard guards)
- **Problem:** provider streaming formats (SSE/chunked/proprietary) differ; downstream must see one canonical stream.
- **Decision:** the adapter converts the provider stream into an **ordered `CanonicalStream`** and hands it to **StreamGuard (`18`)**, which owns **integrity/terminal-verdict** (`18 §30`) and **runtime usage extraction** (§20.1). The adapter **produces**; it does **not** guard, decide terminal validity, or extract authoritative usage.
- **Alternatives:** adapter enforces stream integrity — rejected (that is `18`); pass provider stream raw — rejected (leak).
- **Why selected:** clean produce/guard split.
- **Trade-offs:** two stream stages (adapter produce, `18` guard) — correct separation.
- **Failure Modes:** malformed provider stream ⇒ canonical terminal error chunk; StreamGuard fails closed on integrity.
- **Security Impact:** no provider stream format leak.
- **Performance Impact:** streaming translation is **zero-copy where possible** and **consumer-paced** (§31.1).
- **Enforcement:** `15` T-012 (streaming); ArchUnit — no `18` integrity types in the adapter. **Build-Fail:** the adapter making a terminal-integrity decision. Backpressure ownership: §31.1 (PL-7).

### PA-D5 — Tool-call translation (translate shape; SchemaLock validates)
- **Problem:** provider tool-call formats differ; SchemaLock (`17`) owns tool-call *integrity/validation*.
- **Decision:** the adapter translates the provider tool-call into a **`CanonicalToolCall`** with **raw, unvalidated arguments**; **validation is SchemaLock's (`17`)**. The adapter never validates, repairs, or executes a tool call.
- **Alternatives:** adapter validates tool-calls — rejected (that is `17`).
- **Why selected:** translate/validate separation.
- **Trade-offs:** invalid arguments pass to `17` for the verdict (correct).
- **Failure Modes:** untranslatable tool-call ⇒ fail closed (canonical error).
- **Security Impact:** corrupt tool-call never delivered as valid (validated by `17`).
- **Performance Impact:** O(tool-calls).
- **Enforcement:** cross-check `17`; ArchUnit. **Build-Fail:** the adapter validating/repairing/executing a tool-call.

### PA-D6 — Usage *transport-format* normalization only (StreamGuard owns runtime usage) — see §20.1
- **Problem:** provider usage fields differ; Cost (`22`) and Metering (`23`) consume `CanonicalUsage`; StreamGuard (`18 CV-5`) owns runtime usage extraction.
- **Decision:** the adapter performs **provider transport-format normalization only** — converting provider-native usage *fields* into the `CanonicalUsage` *shape* when returned inline in a non-streaming response (a **transport translation**). It **does not own runtime usage extraction/accounting**; for streaming, **StreamGuard's terminal verdict (`18 CV-5`) is authoritative** (§20.1). Usage is **never fabricated**; missing ⇒ fail closed / `usageClass=estimated` only if provider-flagged.
- **Alternatives:** adapter owns/estimates usage — rejected (fabrication + ownership move, PA-INV/§20.1).
- **Why selected:** usage is a translated transport fact; runtime accounting stays with `18`/`23`.
- **Why selected (cont.):** removes the dual-owner ambiguity — StreamGuard owns runtime usage; the adapter owns only transport-format translation.
- **Trade-offs:** none material.
- **Failure Modes:** missing/ambiguous usage ⇒ fail closed (never guess).
- **Security Impact:** no usage manipulation.
- **Performance Impact:** O(1).
- **Enforcement:** `15` T-017; cross-check `18 CV-5`/`22`/`23` — **PA-A21**. **Build-Fail:** fabricated/estimated-as-authoritative usage; the adapter extracting/accounting runtime usage.

### PA-D7 — Error normalization (normalize shape; Reliability decides; hints advisory) — see §16.1/§17.1
- **Problem:** provider errors differ; Reliability (`20`) decides retry/failover.
- **Decision:** the adapter normalizes provider errors into a **`CanonicalProviderError`** carrying a fixed **canonical category** (§16.1) and an **advisory** `retryableHint`/`transient` flag — but the **retry/failover *decision* is Reliability's (`20`)** and **hints never change policy** (§17.1). The adapter classifies the *shape*, not the *policy*.
- **Alternatives:** adapter decides retryability — rejected (that is `20`); opaque error only — rejected (Reliability needs a neutral category).
- **Why selected:** normalize-shape vs decide-policy split.
- **Trade-offs:** the hint/decision line is pinned in §17.1 — hints are advisory and never change policy.
- **Failure Modes:** unknown provider error ⇒ conservative canonical category `unknown` (fail closed: non-success), never fabricated.
- **Security Impact:** no provider error detail leak (opaque provider code).
- **Performance Impact:** O(1).
- **Enforcement:** cross-check `20`; `15` T-018 — **PA-A20**. **Build-Fail:** a fabricated error; the adapter deciding retry policy.

### PA-D8 — Authentication boundary (uses short-lived cached credentials; owns no credential store) — see §33.1
- **Problem:** the adapter must authenticate to providers, yet the platform mandates no data-plane credential ownership and Secrets ownership in C14.
- **Decision:** the adapter **authenticates to providers using short-lived credential material supplied by the existing Secrets mechanism** (C14 cached snapshot, `06 §9.6`, AD-022). It **owns no credential store, persists no credential**, and holds credential material **only in process memory for the active invocation** (§33.1). **Credential ownership remains unchanged: Secrets (C14) owns; the adapter merely consumes short-lived material.**
- **Alternatives:** synchronous Secrets call on the hot path — rejected (AD-022); adapter owns a credential store — rejected (C14 ownership).
- **Why selected:** honors AD-022 (cached, no hot-path CP call) + C14 ownership; the adapter is the sanctioned *user*, never the *owner*, of provider credentials.
- **Trade-offs:** short-lived cache miss ⇒ fail safe (explicit error), per `06 §9.6`.
- **Failure Modes:** expired/missing credential ⇒ fail closed (no bypass, `06 §9.6`).
- **Security Impact:** the most sensitive surface; strongest hardening; never log/persist credentials (`13`, `14 §7.1`).
- **Performance Impact:** in-memory use, O(1).
- **Enforcement:** secret scanner (`13 §20`, `14 §7.1`); ArchUnit — no credential persistence — **PA-A17**. **Build-Fail:** a credential logged/persisted; an adapter-owned credential store; a synchronous Secrets call on the hot path.

### PA-D9 — Timeout ownership (Reliability is the sole owner; adapter enforces a handed budget) — see §17.1
- **Problem:** both the adapter (transport) and Reliability (`20`) have a claim on timeouts.
- **Decision:** **Reliability (`20`) is the sole owner** of the request deadline and retry/failover/timeout policy; the adapter enforces **only** the per-attempt **transport budget handed to it** via `AttemptBudget` (§7) and defines **no policy of its own** and performs **no internal retry/hedge/backoff** (§17.1). A transport timeout surfaces as `CanonicalProviderError{category:timeout, transient:true}`; the **failover decision is Reliability's**.
- **Alternatives:** adapter owns timeouts — rejected (fragments deadline policy).
- **Why selected:** single deadline authority (C2); the adapter is a mechanism, not a policy.
- **Trade-offs:** the adapter accepts a per-attempt budget parameter (contract §17.1).
- **Failure Modes:** budget exhausted ⇒ canonical transient error; `20` decides next.
- **Security Impact:** none new.
- **Performance Impact:** bounded per-attempt latency.
- **Enforcement:** cross-check `20`; timeout tests — **PA-A20**. **Build-Fail:** the adapter defining its own retry/deadline policy or performing an internal retry.

### PA-D10 — Version negotiation (pinned from the registry snapshot)
- **Problem:** provider API versions change; behavior must be deterministic and auditable.
- **Decision:** the provider API version is **pinned from the Provider Registry snapshot** (AD-022); the adapter uses the pinned version and **stamps it** on telemetry for replay (§23.1). It does not float to a provider's "latest" implicitly.
- **Alternatives:** implicit latest — rejected (nondeterministic, un-auditable).
- **Why selected:** deterministic, versioned, auditable.
- **Trade-offs:** version changes flow through the registry snapshot (config, not redeploy).
- **Failure Modes:** unsupported pinned version ⇒ fail closed.
- **Security Impact:** predictable provider surface.
- **Performance Impact:** negligible.
- **Enforcement:** snapshot-version stamping tests. **Build-Fail:** an un-pinned/implicit-latest provider version.

### PA-D11 — SDK isolation & connection pooling (stateless business posture; pooled transport with strict reset) — see §31.1
- **Problem:** provider SDKs carry connection pools, background threads, and mutable client state — in apparent tension with "stateless."
- **Decision:** provider SDKs are **isolated inside the adapter**; their objects **never** cross the boundary. **Connection reuse/pooling is ephemeral transport, not durable storage and not a response cache** (PA-D12 non-responsibility). "Stateless" (AD-006) means **no durable store and no cross-request *business* state** (AD-021); pooled connections carry **no request/tenant/credential identity** and are strictly reset on return (§31.1 PL-1…PL-7). **No request, tenant, or credential state survives pool return.**
- **Alternatives:** new SDK client per call — rejected (connection cost/latency); expose SDK client — rejected (leak).
- **Why selected:** contains the SDK; preserves the intent and the isolation guarantee of statelessness.
- **Trade-offs:** pooled connections are per-instance resources to bound (§30/§31.1).
- **Failure Modes:** pool exhaustion ⇒ backpressure/fail-safe, never unbounded growth.
- **Security Impact:** SDK contained; no client/tenant/credential leak across reuse.
- **Performance Impact:** connection reuse is a primary latency win (§29).
- **Enforcement:** ArchUnit — no SDK type outside adapter; pool-lifecycle + isolation tests — **PA-A18**. **Build-Fail:** an SDK object crossing the boundary; an unbounded pool; any request/tenant/credential state surviving pool return.

### PA-D12 — Canonical object contracts (stable, neutral, immutable)
- **Problem:** the canonical model is the contract the whole downstream depends on; drift breaks everyone.
- **Decision:** the canonical objects (§6) are **immutable, provider-neutral, versioned internal contracts** (`12 §27`): additive/backward-compatible within a major; no provider-native field ever added. `providerMeta` is opaque and never a typed downstream dependency (§7.1).
- **Alternatives:** loose/provider-shaped canonical objects — rejected (leak/drift).
- **Why selected:** stable neutral contract.
- **Trade-offs:** evolution under compat discipline.
- **Failure Modes:** none from immutability.
- **Security Impact:** no provider leak via canonical fields.
- **Performance Impact:** immutable VO construction, O(size).
- **Enforcement:** contract tests (`12 §27`); ArchUnit — no provider-native field in a canonical type — PA-A7/A19. **Build-Fail:** a provider-native field on a canonical object; downstream consumption of `providerMeta`.

---

## D. Translation Model

### §8 — Translation pipeline (per invocation)
```
CanonicalRequest + RouteTarget + AttemptBudget
  → translate-out (canonical → provider-native: request, auth, format)     [deterministic, pure core]
  → transport (SDK/HTTP invoke, pinned version, per-attempt budget)        [I/O, non-deterministic shell]
  → translate-in (provider-native → canonical: response/stream/tool/usage) [deterministic, pure core]
  → CanonicalResponse | CanonicalStream  (or CanonicalProviderError, fail-closed)
```
The **translate-out / translate-in cores are deterministic pure functions**; **only transport is non-deterministic I/O** (§23.1).

### §9 — Request translation & §10 — response translation
Canonical → provider request (params/messages/tool defs mapped per read-only `CapabilityMapping`); provider response → `CanonicalResponse` (content, tool-calls raw, finishReason canonicalized, usage transport-normalized). Unmappable field ⇒ fail closed.

### §11 — Stream translation (see PA-D4)
Provider stream → ordered `CanonicalStream` chunks; handed to StreamGuard (`18`) for integrity/terminal verdict and runtime usage extraction (§20.1).

### §12 — Tool-call translation (see PA-D5)
Provider tool-call → `CanonicalToolCall{raw arguments}`; validation is `17`.

### §13 — Usage & error normalization (see PA-D6/PA-D7)
Provider usage → `CanonicalUsage` (transport-format; runtime extraction is StreamGuard's §20.1); provider error → `CanonicalProviderError` (canonical category §16.1 + advisory hint). Never fabricated.

---

## E. Interfaces

### §14 — Provider Router (`19`)
The Router **selects** a `RouteTarget` (canonical model id + provider route) from the **Provider Registry snapshot**; the adapter **executes** it. The adapter consumes the **same registry snapshot** for the read-only `CapabilityMapping` (PA-D3) — it **consumes immutable capability snapshots only and never authors capabilities** (§14.1). Dispatch to the concrete adapter is a data-driven registry lookup (§7.1), never provider-name branching.

### §14.1 — Capability Ownership Contract *(resolves Critical PA-C2 — additive; pinned to `06 §9.2` + AD-022)*
| # | Aspect | Contract |
|---|---|---|
| CAP-1 | **Sole owner** | The **Provider Registry (C1 control plane, `06 §9.2`)** is the **only** owner and author of capability descriptors. |
| CAP-2 | **Adapter role** | The Provider Adapter **only consumes immutable capability snapshots** (AD-022) as a read-only `CapabilityMapping`. |
| CAP-3 | **Forbidden** | The adapter **NEVER creates, authors, discovers, infers, probes, or mutates** a capability descriptor at runtime. |
| CAP-4 | **No store** | The adapter holds **no capability store** and persists nothing; a snapshot is a read-only in-memory projection pinned per invocation (PA-D10). |
| CAP-5 | **Missing/stale** | A missing/stale capability snapshot ⇒ **fail closed** (never guess or probe a capability). |
| CAP-6 | **Wording** | Any earlier phrasing implying runtime "capability descriptor production" is **superseded**: the adapter **consumes**, it does not **produce**. |
- **Enforcement (PA-A16):** ArchUnit + cross-check `06 §9.2`. **Build-Fail:** the adapter creating/authoring/discovering/mutating/persisting a capability descriptor.

### §15 — Reliability Engine (`20`)
Reliability **invokes** `ProviderAdapterPort.invoke(...)` per attempt, owning retry/failover/deadline; the adapter executes **one** attempt and returns a canonical response/stream or a `CanonicalProviderError`. See timeout ownership (§17.1).

### §16 — Reliability error contract
The adapter returns **normalized errors** with a **canonical category** (§16.1) and **advisory hints**; **Reliability decides** retry/failover (§17.1). The category set is provider-neutral, fixed, and stable.

### §16.1 — Canonical Error Categories *(resolves Medium PA-M4 — additive)*
Fixed, provider-neutral category set (stable/versioned, `12 §27`) that Reliability (`20`) keys its policy on:

| Category | Meaning (provider-neutral) |
|---|---|
| `transport` | connection / DNS / TLS failure before a provider verdict |
| `timeout` | per-attempt transport budget / deadline exhausted (§17.1) |
| `rate_limited` | provider throttling / quota signal |
| `provider_unavailable` | provider 5xx / overloaded / capacity |
| `provider_rejected` | provider 4xx / invalid-request as the provider reports it |
| `auth_failed` | provider rejected the credential |
| `content_filtered` | provider policy/safety block |
| `malformed_response` | provider output untranslatable to canonical |
| `unknown` | conservative default (non-success; never fabricated) |

Each carries an **advisory** `transient` flag + opaque `providerCodeOpaque`. **Reliability maps category → retry/failover policy; the adapter never does.**

### §17 — Timeout contract (see §17.1)
Reliability passes a **per-attempt transport budget** (`AttemptBudget`, §7); the adapter enforces it and surfaces `CanonicalProviderError{category:timeout, transient:true}` on exhaustion.

### §17.1 — Timeout Ownership Contract *(resolves High PA-H2 — additive; pinned to `20`)*
| # | Aspect | Contract |
|---|---|---|
| TO-1 | **Sole owner** | **Reliability (`20`) is the sole owner** of request deadline, retry, timeout, and failover policy. |
| TO-2 | **Adapter role** | The adapter enforces **only** the per-attempt transport budget handed to it (`AttemptBudget`); it defines **no policy** of its own. |
| TO-3 | **Hints advisory** | The adapter may expose **advisory hints** (`retryableHint`, `transient`, category §16.1); hints **never change** retry, timeout, or failover policy — Reliability may ignore them entirely. |
| TO-4 | **Exhaustion** | Transport-budget exhaustion ⇒ `CanonicalProviderError{category:timeout, transient:true}`; the **failover decision is Reliability's**. |
| TO-5 | **No hidden retry** | The adapter performs **no internal retry/hedge/backoff**; one `invoke` = exactly one provider attempt. |
- **Enforcement (PA-A20):** cross-check `20`; timeout + fault tests (`15` T-018). **Build-Fail:** the adapter owning any retry/timeout/failover policy or performing an internal retry.

### §18 — StreamGuard (`18`)
The adapter produces the `CanonicalStream`; StreamGuard owns integrity/terminal verdict (`18 §30`), the authoritative final usage (`18 CV-5`, §20.1), and receives it under consumer-paced backpressure (§31.1). The adapter never guards or extracts runtime usage.

### §19 — SchemaLock (`17`)
The adapter produces `CanonicalToolCall{raw}`; SchemaLock validates/repairs. The adapter never validates.

### §20 — Cost (`22`) & Usage Metering (`23`)
`CanonicalUsage` feeds Metering (`23`), which records facts; Cost (`22`) prices them. The adapter neither meters nor prices, and does **not** extract runtime usage — that is StreamGuard's (§20.1).

### §20.1 — Usage Normalization Ownership Contract *(resolves High PA-H1 — additive; pinned to `18 CV-5` + `22`/`23`)*
| # | Aspect | Contract |
|---|---|---|
| US-1 | **Runtime usage owner** | **StreamGuard (`18 CV-5`) owns runtime usage extraction/accounting** — the authoritative final usage (streaming and terminal) is StreamGuard's. |
| US-2 | **Adapter role** | The adapter performs **provider transport-format normalization only** — converting provider-native usage *fields* into the `CanonicalUsage` *shape* when returned inline in a non-streaming response (a transport translation, not runtime accounting). |
| US-3 | **Streaming** | For streaming, the adapter **does not** compute/emit authoritative usage; it forwards canonical usage chunks, and **StreamGuard's terminal verdict (`18 §30`/CV-5) is authoritative** — aligning with `22`/`23` "usage normalized upstream." |
| US-4 | **Never fabricate** | Usage is **never fabricated**; missing ⇒ fail closed / `usageClass=estimated` only if provider-flagged; the adapter never invents a figure. |
| US-5 | **No accounting** | The adapter **never meters, aggregates, prices, or accounts** usage (that is `23`/`22`). |
- **Resolution of the dual-owner ambiguity:** **StreamGuard owns runtime usage; the adapter owns only transport-format translation.** No two components claim authoritative usage.
- **Enforcement (PA-A21):** `15` T-017; cross-check `18 CV-5`/`22`/`23`. **Build-Fail:** the adapter extracting/accounting runtime usage; a fabricated/estimated-as-authoritative figure.

### §21 — Governance (`21`)
Governance admits the request **before** the adapter runs; the adapter never bypasses/re-evaluates governance (AD-018). It operates only on already-admitted requests.

### §22 — Secrets subsystem (C14)
Credentials are **short-lived cached snapshots** from Secrets (`06 §9.6`, AD-022); the adapter **uses, never owns/persists** them, holding material only in process memory for the active invocation (§33.1 · PA-D8).

---

## F. Determinism, I/O & Replay

### §23 — Determinism boundary (specified in §23.1)
Unlike the pure-function engines (`17`–`23`), the adapter **performs real network I/O** and is therefore **not a deterministic pure function end-to-end** — and does not claim to be. It is architected as a **deterministic pure mapping core** wrapped by a **non-deterministic transport shell**; the exact contract is **§23.1**.

### §23.1 — Determinism Contract: pure-core / impure-shell *(resolves High PA-H4 — additive)*
| # | Aspect | Contract |
|---|---|---|
| DET-1 | **Pure core** | **Mapping logic (translate-out, translate-in) is a deterministic pure function** of `(canonical input | provider-native bytes, pinned versions, injected clock)` — no wall-clock/random/I/O (`11` R-063). |
| DET-2 | **Impure shell** | **Transport (SDK/HTTP invoke) is intentionally nondeterministic I/O** — network, provider behavior, timing — isolated behind `ProviderTransportPort`. |
| DET-3 | **Separation** | The pure core and impure shell are **separated at the port**: the core never performs I/O; the shell never makes a mapping decision. |
| DET-4 | **Replay scope** | **Replay reproduces mapping decisions, not provider behavior:** given recorded provider-native bytes + pinned versions, the core yields **identical canonical objects**. Replay does **not** reproduce latency, availability, or provider-side nondeterminism. |
| DET-5 | **Testability** | The core is unit/property/mutation-tested deterministically (`15 §G.1`, ≥85%); the shell is contract-tested against recorded provider fixtures + fault injection (`15` T-018). |
| DET-6 | **Honesty (no overclaim)** | The adapter is **not** deterministic end-to-end (it is an I/O boundary); **only the mapping core is deterministic** — stated without overclaim. |
- **Enforcement:** ArchUnit — no I/O in the mapping core; no mapping decision in the transport shell; replay tests (`15` T-035). **Build-Fail:** I/O in the mapping core; a mapping decision in the transport shell.

### §24 — Replay
Mapping cores are replayable from a **recorded provider-native interaction** (request/response/stream capture) + pinned version; transport is mocked/recorded. Replay reproduces **mapping decisions only** (§23.1 DET-4).

### §25 — Version/source stamping
Every invocation stamps the **pinned provider API version + adapter version + capability-snapshot version** on telemetry for reproduction (PA-D10/§23.1).

---

## G. Failure Handling

### §26 — Failure handling (fail closed to a canonical error)
| Failure | Handling |
|---|---|
| Untranslatable request/response/tool field | **`CanonicalProviderError{malformed_response}`** (fail closed; never leak/guess) |
| Missing/ambiguous usage | fail closed / `estimated` only if provider-flagged (§20.1) |
| Transport timeout / connection failure | `CanonicalProviderError{timeout|transport, transient:true}` — **Reliability decides** (§17.1) |
| Malformed provider stream | canonical terminal error chunk; StreamGuard fails closed (`18`) |
| Credential expired/missing (cache miss) | **fail closed** (`auth_failed`; no bypass, `06 §9.6`) |
| Unsupported pinned version / unknown capability | **fail closed** (never guess capability, §14.1/PA-D10) |
| Any unknown/internal translation error | **`CanonicalProviderError{unknown}`** (fail closed) |
- **Downstream effect:** a canonical error is a normal input to Reliability's failover (`20`); the adapter never fabricates success and never leaks provider detail.

### §27 — Recovery
Stateless business posture (no durable store); on restart the adapter re-establishes transport pools and resumes. No replay/outbox (it owns no store).

### §28 — Isolation on failure
One provider's failure/format-break is contained in its adapter; other providers' adapters are unaffected (AD-002 replaceable adapters).

---

## H. Performance & Concurrency

### §29 — Performance model
Translation (pure core) is **bounded CPU** (O(request/response size)); transport dominates wall-clock. The adapter fits within its **allocated share of the added-latency budget** (§29.1); **connection reuse (§31.1)** keeps transport setup off the per-request critical path. **Zero unnecessary copies** in translation, especially streaming. JMH-benchmarked (`15` T-026).

### §29.1 — Latency-budget allocation *(resolves Medium PA-M3 — additive)*
The adapter's **translation** work is allocated a bounded slice of the added-latency budget (`06 §11`, NFR-LAT-001) as an **operational-baseline** entry (`16 §I.1`), measured **excluding** the provider round-trip (which is provider latency, not platform added-latency). If translation exceeds its budgeted slice it is a perf regression caught by the perf-smoke gate (`15` T-026, `16 §J.2`) — it **never** silently inflates the request budget.

### §30 — Memory & resource limits
Bounded connection pools per provider/instance; bounded streaming buffers; no unbounded structures; sized within container limits (`16` D-044, ZGC AD-023). All numerics are **operational-baseline** entries (`16 §I.1`).

### §31 — Streaming throughput & backpressure (specified in §31.1)
The `CanonicalStream` handoff to StreamGuard (`18`) is **consumer-paced backpressure** — no unbounded buffering; the provider read is paced by downstream demand. Ownership is pinned in **§31.1 (PL-7)**. Reactive/virtual-thread model per AD-023.

### §31.1 — Connection-Pool Lifecycle & Backpressure Contract *(resolves High PA-H3 + Medium PA-M1 — additive)*
| # | Phase / Aspect | Contract |
|---|---|---|
| PL-1 | **Acquire** | A pooled transport connection is acquired from a **bounded per-instance pool** (size = operational baseline, `16 §I.1`); pool exhaustion ⇒ **backpressure/fail-safe**, never unbounded growth. |
| PL-2 | **Initialize** | On first use a connection is initialized with **transport-only** config (TLS/mTLS, endpoint, pinned version) — **no tenant and no credential bound to the connection**. |
| PL-3 | **Use** | Credentials for the active call are applied **per-invocation, in process memory only** (§33.1), never bound to the pooled connection; the connection carries **no request/tenant/credential identity**. |
| PL-4 | **Reset** | On return the connection is **reset**: request/response buffers cleared; **no request state, no tenant state, no credential** survives. |
| PL-5 | **Return** | The reset connection returns to the pool, reusable by **any** tenant's next call — safe because PL-3/PL-4 guarantee nothing tenant/credential-specific persists (AD-021 isolation preserved). |
| PL-6 | **Stateless meaning** | "Stateless" (AD-006) = **no durable store and no cross-request *business* state**; a pooled TCP/TLS connection is **ephemeral transport**, not durable storage and not a response cache (PA-D12 non-responsibility). |
| PL-7 | **Backpressure ownership** | Flow control across **adapter → StreamGuard → consumer is consumer-paced**: the adapter reads from the provider **only as fast as StreamGuard/the downstream consumer drains** (reactive demand, AD-023). The **adapter owns provider-read pacing**; **StreamGuard owns integrity**; the **consumer sets demand**. No unbounded buffering. |
- **Cardinal rule:** **No request state, tenant state, or credential may survive pool return.**
- **Enforcement (PA-A18):** pool-lifecycle + isolation + resource tests (`15` T-044). **Build-Fail:** an unbounded pool; any request/tenant/credential state surviving pool return; a connection bound to a tenant/credential.

### §32 — Concurrency & Virtual Threads
Runs on Virtual Threads (AD-023); per-call `ProviderInvocation` holds **no cross-request business state** (AD-021); pooled connections are per-instance transport resources reset on return (§31.1); no blocking-in-`synchronized` on the hot path (`11` R-049). **Build-Fail:** cross-request business state; a provider object escaping a call.

---

## I. Security

### §33 — Security considerations
- **Credential handling (PA-D8/§33.1):** the adapter **uses** short-lived cached provider credentials (C14, `06 §9.6`) — **owns/persists none**; never logs them (`13 §20`, `14 §7.1`).
- **TLS/mTLS:** all provider transport over TLS; mTLS where the provider supports it; zero-trust posture (AD-012).
- **Tenant isolation (AD-021):** per-request credential/tenant scoping; a pooled connection carries no tenant/credential identity (§31.1); one tenant's call never uses another's context.
- **SDK containment (PA-D11):** no provider client/object leaks.
- **No provider leak:** provider identity/codes internal-only; `providerMeta` opaque and non-consumable (§7.1); canonical outputs neutral (`12 §16.10`, AD-007).
- **Enforcement:** secret/content scanner (`13 §20`, `14 §7.1`); isolation tests (`15` T-044). **Build-Fail:** a credential logged/persisted; a provider client leaked; cross-tenant credential/connection reuse.

### §33.1 — Credential Handling Contract *(resolves Critical PA-C1 — additive; pinned to C14 `06 §9.6` + AD-022)*
| # | Aspect | Contract |
|---|---|---|
| CR-1 | **No credential store** | The Provider Adapter **owns no credential store**. |
| CR-2 | **No persistence** | The adapter **never persists** a credential (no disk, no cache-at-rest, no log). |
| CR-3 | **Source** | The adapter **consumes short-lived credential material supplied by the existing Secrets mechanism** (C14 cached snapshot, `06 §9.6`, AD-022) — no synchronous control-plane call on the hot path. |
| CR-4 | **In-memory only** | Credential material is held **only in process memory for the active invocation** and dropped thereafter; it is **never bound to a pooled connection** (§31.1 PL-3). |
| CR-5 | **Ownership unchanged** | **Credential ownership remains with Secrets (C14); this document moves no ownership.** The adapter is the sanctioned *user*, never the *owner*. |
| CR-6 | **Miss = fail closed** | A credential cache miss / expiry ⇒ **fail closed** (`auth_failed`), never a bypass (`06 §9.6`). |
| CR-7 | **Wording** | The adapter is **"credential-store-free," not "credential-unaware."** Any flat "credential-free" phrasing means **owns no credential store** (CR-1) — the adapter necessarily *handles* short-lived credential material to authenticate. |
- **Enforcement (PA-A17):** secret scanner (`13 §20`, `14 §7.1`) + ArchUnit (no credential persistence/store). **Build-Fail:** a credential logged/persisted; an adapter-owned credential store; a credential bound to a pooled connection; a synchronous Secrets call on the hot path.

---

## J. Observability

### §34 — Posture
Full instrumentation per `14`, **provider-neutral**, content-free, low-cardinality: spans per invocation (canonical model id, capability, versions, outcome), metrics (§35), logs (§36). Never prompt/response content, never credentials.

### §35 — Canonical metrics
- `pa_invocations_total{canonical_model, outcome}`, `pa_provider_errors_total{category}` (canonical category §16.1, **no provider label externally**), `pa_transport_latency`, `pa_translation_latency`, `pa_pool_saturation`, `pa_stream_chunks_total`.
- Model/capability labels bounded (top-N + `other`); **no provider-name label externally** (provider-neutral, AD-007 — provider dimension internal/Plane B only).

### §36 — Tracing & Logging
One span per invocation; neutral attributes (canonical model, capability, pinned versions, outcome, latency buckets) — never content/credentials/provider-native codes. **Correlation/causation IDs propagated** (`07 §6`, `14`); trace context propagated to the provider call where supported (Appendix Z L-4).

### §37 — Canonical errors in telemetry
Errors surfaced as **canonical categories** (§16.1); provider-native codes are opaque/internal-only (`providerCodeOpaque`), never a customer-facing or cross-service typed field.

---

## K. Testing

### §38 — Testing strategy (critical module — `15 §G.1`)
- **Unit (JUnit5/Mockito):** request/response/stream/tool/usage/error translation per adapter; fail-closed paths.
- **Contract tests (per provider):** recorded provider-native fixtures → canonical objects; **canonical-object contract stability** (`12 §27`).
- **Property-based (jqwik, `15` T-035):** *no provider field leaks into a canonical object*; *usage never fabricated*; *errors never fabricated*; *the mapping core is deterministic* given recorded I/O (§23.1).
- **Provider-neutrality (`15` T-015):** adding a provider = a new adapter, **zero downstream diff**; no provider-name outside adapters; dispatch is registry-driven (§7.1).
- **Streaming (`15` T-012):** canonical stream ordering + consumer-paced backpressure (§31.1); handoff to StreamGuard.
- **Pool lifecycle & isolation (`15` T-044):** Acquire/Initialize/Use/Reset/Return; **no request/tenant/credential state survives return**; bounded pool.
- **Failure injection (`15` T-018):** transport timeout, malformed stream, credential cache miss, unmappable field ⇒ fail closed canonical error.
- **Security (`15` T-044/T-052):** no credential leak/persistence; tenant isolation; SDK containment.
- **Mutation (`15 §G.1`):** **≥ 85%** — a mutant that leaks a provider shape, fabricates usage/error, or bypasses fail-closed must be killed.
- **Performance (`15` T-021/T-026, `16 §J.2`):** translation within its budgeted slice (§29.1); connection reuse; zero-copy streaming.
- **Follows:** `15`–`23` as listed.

---

## L. Build-Failing Rules (PA-A1 … PA-A21)

| # | Rule | Gate |
|---|---|---|
| PA-A1 | No provider logic/type outside an adapter package | ArchUnit |
| PA-A2 | No provider-native object crosses the canonical boundary | ArchUnit + property test |
| PA-A3 | No provider SDK import/type outside an adapter | ArchUnit |
| PA-A4 | No provider-name branch/string outside an adapter (downstream neutral; dispatch registry-driven §7.1) | ArchUnit + `15` T-015 |
| PA-A5 | No fabricated usage; missing usage ⇒ fail closed (estimated only if provider-flagged) | `15` T-017/T-018 |
| PA-A6 | No fabricated error; unknown provider error ⇒ conservative canonical category (§16.1) | `15` T-018 |
| PA-A7 | Provider-neutral canonical contracts only; no provider-native field on a canonical type | contract test (`12 §27`) |
| PA-A8 | No routing/retry/deadline/failover policy in the adapter (executes, does not decide) | ArchUnit + cross-check `19`/`20` |
| PA-A9 | No schema validation / stream-integrity / governance / pricing in the adapter | ArchUnit + cross-check `17`/`18`/`21`/`22` |
| PA-A10 | No credential persisted/logged; credentials only short-lived cached from C14, in-memory | secret scanner (`13 §20`, `14 §7.1`) |
| PA-A11 | No synchronous control-plane call on the hot path (AD-022) | ArchUnit |
| PA-A12 | No persistence / private store / database (stateless; pools are ephemeral transport) | ArchUnit |
| PA-A13 | Provider API version pinned from the registry snapshot; stamped on telemetry (no implicit-latest) | version test |
| PA-A14 | No capability authoring/persistence in the adapter (consumes the registry snapshot only) | cross-check `06 §9.2` |
| PA-A15 | Mutation score ≥ 85% (critical module) | PITest (`15 §G.1`) |
| PA-A16 | **Capability ownership (§14.1):** adapter consumes immutable snapshots only; never creates/authors/discovers/mutates a capability descriptor; no capability store | ArchUnit + cross-check `06 §9.2` |
| PA-A17 | **Credentials (§33.1):** owns no credential store; persists no credential; short-lived C14 material in process memory for the active invocation only; ownership unchanged | secret scanner + ArchUnit |
| PA-A18 | **Pool lifecycle (§31.1):** bounded pool; Acquire/Initialize/Use/Reset/Return enforced; no request/tenant/credential state survives return; no unbounded pool | isolation + resource tests (`15` T-044) |
| PA-A19 | **Provider metadata (§7.1):** no downstream module types/parses/branches on/consumes `providerMeta` | ArchUnit |
| PA-A20 | **Timeout (§17.1):** Reliability sole owner; adapter enforces only a handed per-attempt budget; hints advisory; no internal retry/hedge/backoff | cross-check `20` + `15` T-018 |
| PA-A21 | **Usage (§20.1):** StreamGuard owns runtime usage extraction; adapter does transport-format normalization only; never meters/accounts; never fabricates | cross-check `18 CV-5`/`22`/`23` + `15` T-017 |

---

## M. Operations

### §39 — Operational runbook
- **`pa_provider_errors{category=transient}` spike:** a provider is degrading; Reliability (`20`) fails over — the adapter is correctly normalizing, not deciding. Investigate the provider, not the adapter.
- **`pa_pool_saturation` spike:** transport backpressure — scale/bound pools (§30/§31.1); never let pools grow unbounded.
- **Credential cache-miss errors (`auth_failed`):** Secrets snapshot staleness (`06 §9.6`); fail-safe is expected — restore the snapshot path; never bypass auth.
- **Neutrality-leak alarm (ArchUnit/property):** a provider shape escaped — treat as a correctness Sev; the ACL is breached.

### §40 — Upgrade strategy
Ships within the data-plane release train (`16` D-014/D-055): rolling/canary, zero-downtime. **Adding/updating a provider is a new/updated adapter + registry snapshot** — zero downstream diff (AD-007). Provider API version changes flow via the registry snapshot (config), not necessarily a redeploy (PA-D10).

### §41 — Backward compatibility
Canonical object contracts are stable internal APIs (`12 §27`): additive within a major; no provider-native field ever added. Adapter internals evolve freely as long as canonical outputs are unchanged.

---

## N. Traceability

### §42 — Traceability matrix
| Adapter concern | BR | NFR | ADR | Domain/Svc | Sec | Obs | Test | Deploy |
|---|---|---|---|---|---|---|---|---|
| ACL / neutrality (PA-D1/D2/PA-INV) | BR-006 | NFR-IF-001 | **AD-007**/AD-002 | C1 (`06 §8` PA) | §33 | §35 | T-015 | `16` |
| Capability consumption (PA-D3/§14.1) | BR-006/007 | NFR-IF | AD-007/022 | C1 Registry (`06 §9.2`) | — | — | T-015 | — |
| Streaming translation (PA-D4/§11) | BR-002 | NFR-STRM-001 | AD-018 | C3 (`18`) | — | §35 | T-012 | — |
| Tool-call translation (PA-D5/§12) | BR-003 | NFR-SO-001 | AD-018 | C3 (`17`) | — | — | T-012 | — |
| Usage normalization (PA-D6/§20.1) | BR-012 | NFR-COST | AD-007 | C5/C3 (`18 CV-5`/`22`/`23`) | — | §35 | T-017 | — |
| Error normalization (PA-D7/§16.1) | BR-002 | NFR-REL | AD-016 | C2 (`20`) | §37 | §35 | T-018 | — |
| Auth boundary (PA-D8/§33.1) | BR-020 | NFR-SEC-SM | AD-012/022 | C14 (`06 §9.6`) | §33 | — | T-052 | — |
| Timeout ownership (PA-D9/§17.1) | BR-002 | NFR-REL/LAT | AD-016 | C2 (`20`) | — | — | T-018 | — |
| Version negotiation (PA-D10/§25) | BR-006 | NFR-VER | AD-015/022 | C1 Registry | — | §36 | T-049 | — |
| SDK isolation / pooling (PA-D11/§31.1) | BR-005 | NFR-PERF | AD-006/002/021 | C1 | §33 | — | T-044 | D-044 |
| Canonical contracts (PA-D12/§41) | BR-006 | NFR-IF-001 | AD-007 | `12 §27` | — | — | contract | — |
| Determinism/replay (§23.1) | BR-004 | NFR-TEST | AD-016 | `11` R-063 | — | §36 | T-035 | — |
| Dispatch / providerMeta (§7.1) | BR-006 | NFR-IF-001 | AD-002/007 | C1 | §33 | — | T-015 | — |
| Latency budget (§29.1) | BR-007 | NFR-LAT-001 | AD-020 | `06 §11` | — | §35 | T-026 | J.2 |
| Module in DP, stateless (§7/§31.1) | BR-005 | NFR-LAT/PERF | AD-020/006 | `06 §8` | — | — | T-051 | D-014 |

All former open seams are resolved by the Resolution Pass contracts (§7.1/§14.1/§16.1/§17.1/§20.1/§23.1/§29.1/§31.1/§33.1) and build-enforced (PA-A16…A21).

---

## S. Reviews

### 1. Internal Consistency Review (post-resolution)
| Frozen source | Requirement | This document | ✓ |
|---|---|---|---|
| AD-007 | provider abstraction; adapter is the only provider-coupled locus | PA-D1/D2/§7 (ACL; canonical outputs; registry dispatch) | ✅ |
| AD-002 | hexagonal, replaceable adapters | §7 ports; one adapter per provider; port-separated core/shell (§23.1) | ✅ |
| `06 §8` PA node | Provider Adapters is a C1 DP module | §1/§7 (C1 module, no store) | ✅ |
| `06 §9.2` Provider Registry | C1 CP is the **sole** capability owner | §14.1 CAP-1…CAP-6 (adapter consumes only) | ✅ |
| `06 §9.6` Secrets / C14 | short-lived cached creds; no hot-path CP call; ownership stays C14 | §33.1 CR-1…CR-7 (no store, in-memory only, ownership unchanged) | ✅ |
| AD-006 / AD-021 | stateless; tenant isolation | §31.1 PL-1…PL-7 (no request/tenant/credential survives reuse) | ✅ |
| `18 CV-5` | StreamGuard owns runtime usage | §20.1 US-1…US-5 (adapter = transport-format only) | ✅ |
| `20` | Reliability owns retry/failover/deadline | §17.1 TO-1…TO-5 (adapter enforces handed budget; hints advisory) | ✅ |
| `17` / `18` | SchemaLock validates; StreamGuard guards | PA-D4/D5 (translate only) | ✅ |
| AD-018 | non-bypassable pipeline | §21 (post-admission; never bypasses) | ✅ |
| `11` R-063 / AD-016 | deterministic core; testability | §23.1 DET-1…DET-6 (pure core / impure shell; replay = mapping only) | ✅ |
| AD-020 | co-located latency budget | §29.1 (allocated slice; excludes provider round-trip) | ✅ |
| `12 §27` | stable internal contracts | PA-D12/§16.1/§41 (canonical contracts, fixed category set) | ✅ |
| `24A` reconciliation | optimization is not the adapter's job | §4 (no optimization) | ✅ |

**No contradictions remain.** The Resolution Pass (§7.1/§14.1/§16.1/§17.1/§20.1/§23.1/§29.1/§31.1/§33.1, PA-A16…A21) is **additive only**: no new architecture, service, bounded context, ownership, or store; AD-002/007/018/020/021/022 preserved exactly; PA-INV reinforced.

### 2. Architecture Validation (post-resolution)
- **Ownership:** ✅ capability = Provider Registry (§14.1); credentials = Secrets/C14 (§33.1); usage = StreamGuard (§20.1); timeout = Reliability (§17.1) — **no ownership moved**.
- **Boundaries:** ✅ translate-only vs validate/guard/decide/account splits pinned (`17`/`18`/`19`/`20`).
- **Provider neutrality:** ✅ ACL containment, canonical model id, registry-driven dispatch, opaque non-consumable `providerMeta` (AD-007).
- **Performance:** ✅ budgeted translation slice (§29.1); consumer-paced backpressure with pinned ownership (§31.1 PL-7).
- **Security:** ✅ credential-store-free, in-memory-only, pool reset (§31.1/§33.1); TLS/mTLS; tenant isolation preserved across reuse.
- **Deployment:** ✅ add-provider = new adapter + snapshot, zero downstream diff.
- **Failure handling:** ✅ fail-closed to canonical error, contained per adapter (AD-002).
- **Determinism/testability:** ✅ pure-core/impure-shell contract with honest replay scope (§23.1).

### 3. Adversarial Review — Independent Review Board (re-run after resolution)
*Board: Principal Enterprise Architect · AI Infrastructure Architect · Distributed Systems Architect · Staff Reliability Engineer · Streaming Systems Expert · API Gateway Architect · Security Architect · Performance Engineer · Compliance Auditor · CTO.*

#### Accepted findings — resolution
- **PA-C1 → RESOLVED (§33.1).** "Credential-free" corrected to **credential-store-free**: owns no store, persists nothing, consumes short-lived C14 material, in-memory for the active invocation only, ownership unchanged (CR-1…CR-7). Build-enforced (PA-A17).
- **PA-C2 → RESOLVED (§14.1).** Provider Registry is the **sole** capability owner; the adapter **consumes immutable snapshots only** and never creates/authors/discovers/mutates a descriptor; all "capability production" wording superseded (CAP-1…CAP-6). Build-enforced (PA-A16).
- **PA-H1 → RESOLVED (§20.1).** **StreamGuard owns runtime usage extraction**; the adapter does **transport-format normalization only**; dual-owner ambiguity removed (US-1…US-5). Build-enforced (PA-A21).
- **PA-H2 → RESOLVED (§17.1).** **Reliability is the sole timeout/retry/failover owner**; the adapter enforces only a handed per-attempt budget; hints advisory; no internal retry (TO-1…TO-5). Build-enforced (PA-A20).
- **PA-H3 → RESOLVED (§31.1).** Connection-pool lifecycle Acquire/Initialize/Use/Reset/Return; **no request/tenant/credential state survives return**; "stateless" precisely scoped (PL-1…PL-7). Build-enforced (PA-A18).
- **PA-H4 → RESOLVED (§23.1).** Pure mapping core / impure transport shell; **replay reproduces mapping decisions, not provider behavior**; no end-to-end-determinism overclaim (DET-1…DET-6).
- **PA-M1 → RESOLVED (§31.1 PL-7):** backpressure ownership pinned (adapter paces provider read; StreamGuard integrity; consumer demand).
- **PA-M2 → RESOLVED (§7.1):** registry-driven adapter dispatch, no provider-name branching.
- **PA-M3 → RESOLVED (§29.1):** latency-budget slice allocated, excludes provider round-trip.
- **PA-M4 → RESOLVED (§16.1):** fixed canonical error category set enumerated.
- **PA-M5 → RESOLVED (§7.1):** `providerMeta` opaque, non-consumable downstream (PA-A19).

#### New findings from the re-run
- **🔴 Critical:** none. **🟠 High:** none. **🟡 Medium:** none blocking. **🟢 Low (deferred):** Appendix Z (metric names, per-provider fixtures/benchmarks, canonical schema location, trace-context propagation format) — cross-team/detail deliverables, contract-testable, non-blocking.

#### Internal Contradictions
- **None.** Credential-store-free, stateless-with-reset-pools, capability-consume-only, usage-transport-only, timeout-handed-budget are used consistently.

#### Cross-document Contradictions
- **None.** Consistent with `06 §9.2` (registry), `06 §9.6` (Secrets), `18 CV-5` (usage), `20` (timeout/retry), `17`/`18` (validate/guard), AD-002/007/018/020/021/022, and `24A`.

#### Scores
- **Architecture Readiness: 96 / 100** — all Critical/High/Medium resolved additively; four ownership seams (capability, credential, usage, timeout) pinned to their frozen owners; determinism honestly scoped; no ownership/architecture/store change. Residual 4 pts are Low cross-team deliverables.
- **Documentation Health: 96 / 100** — contradictions closed; contracts honest (credential-store-free, replay = mapping only, no end-to-end determinism overclaim).
- **Implementation Readiness: 94 / 100** — every load-bearing seam specified precisely (dispatch, credentials, capability, usage, timeout, pool lifecycle, determinism, error categories, latency budget, backpressure) — buildable without inventing a contract.

#### Recommendation: **FREEZE.** Critical = 0, High = 0, Medium = 0 (blocking); Low deferred (Appendix Z). No contradictions with `00`–`24A` or AD-001…AD-023; no new architecture/service/bounded-context/ownership/store; AD-002/007/018/020/021/022 preserved; PA-INV reinforced.

---

## Appendix Z — Deferred Low Items (non-blocking)
- **PA-L1** — exact metric/label names → reconcile with `14` observability registry.
- **PA-L2** — per-provider contract-fixture set + JMH benchmarks → `benchmarks/` / test fixtures.
- **PA-L3** — canonical object schema definitions / versioning location → `12 §27` internal-contract registry.
- **PA-L4** — trace-context propagation format to providers (where supported) → observability detailed design.

These are documentation/detail deliverables; none affects ownership, neutrality, correctness, or any invariant, and each is contract-/cross-team-testable.

---

*End of document — 25-ProviderAdapter.md · Status: **FROZEN** · Version: 1.0 (2026-07-22)*
