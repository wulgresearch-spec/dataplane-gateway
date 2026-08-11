# 24 — Token Optimization Engine (Runtime Optimization Component · proposed C6 Optimization)

**Document:** Component Implementation Architecture — Token Optimization Engine
**Project:** Reliability-First AI Gateway
**Status:** **DRAFT — NOT FROZEN** (pending Independent Review Board disposition; see §S)
**Bounded context (as proposed by this draft):** **C6 — Optimization** (see **⚠ Draft Notice** — this placement is *unresolved* against the frozen `05`/`06` context catalogue)
**Module:** `dp-token-optimization-engine` — a **stateless runtime optimization component** intended to be co-located on the data plane per **AD-020/AD-006** — **not** a new service, **not** a new store, **owns no database, holds no credentials, imports no provider SDK**.
**Audience:** Optimization/AI-infrastructure/platform/data-plane engineers, SRE, security, compliance, QA, reviewers
**Classification:** Internal — Component Architecture (Draft)
**Builds on (approved, frozen):** `00`–`23` and ADRs **AD-001…AD-023**. This draft *intends* to be **additive** and to introduce **no new architecture decision, no new service, no new module beyond this component, no new store, no ownership/event/data-ownership change, and no provider coupling.** Every TOE-Dx is expressed as a *component-internal implementation decision* via hexagonal ports (AD-002).

> **What this is.** The proposed implementation architecture of the **Token Optimization Engine** — the component that reduces token/context consumption of an already-validated request **without changing meaning** and emits **optimization hints** (and, where safe, deterministic lossless rewrites) for the request pipeline. It sits conceptually **after** the request is validated and **before** provider dispatch, and it **never** bypasses Governance (`21`), Router (`19`), Reliability (`20`), StreamGuard (`18`), or SchemaLock (`17`).
>
> **THE TOKEN-OPTIMIZATION INVARIANT (TOE-INV):** *Never change user intent. Never change model meaning. Never remove required context. Never increase hallucination risk. Never bypass Governance, Router, Reliability, StreamGuard, or SchemaLock. Never silently optimize.* On any uncertainty it **fails closed** — it **passes the original request through unchanged**. This is the optimization-layer realization of **AD-018 (non-bypassable correctness/governance)** and **AD-016 (reliability first)** applied to token reduction: optimization is a *best-effort economy*, never a correctness or governance actor.

---

## ⚠ Draft Notice (read before reviewing)

This document is a **DRAFT for adversarial review**. It has **not** been reconciled against the frozen `05-Domain-Model.md` / `06-Service-Boundaries.md` context catalogue. In particular:

- The proposed context slot **"C6 — Optimization" collides with the frozen C6 = Identity & Access** (`05 §5`, Supporting/critical).
- **No "Optimization" bounded context exists** in the frozen C1–C16 catalogue.
- Several proposed responsibilities (system-prompt optimization, conversation compaction, prompt normalization) touch **C11 — Prompt & Interaction Asset Management** ownership.

These are surfaced deliberately and are the subject of the review in **§S**. **Do not treat this draft as authoritative or as establishing any context, ownership, or invariant.** No document `00`–`23` and no ADR is modified by this draft.

---

## Table of Contents
**A. Charter** (§1–5) · **B. Domain & Interfaces** (§6–7) · **C. Major Decisions** (TOE-D1…TOE-D12) · **D. Optimization Descriptor & Model** (§8–12) · **E. Pipeline & Ordering** (§13–17) · **F. Optimization Families** (§18–25) · **G. Determinism & Replay** (§26–28) · **H. Interfaces** (§29–34) · **I. Failure Handling** (§35–37) · **J. Performance & Concurrency** (§38–40) · **K. Security** (§41) · **L. Observability** (§42–45) · **M. Testing** (§46) · **N. Build-Failing Rules** (TOE-A1…TOE-A15) · **O. Operations** (§47–49) · **P. Traceability** (§50) · **S. Reviews**

---

## A. Charter

### §1 — Purpose
The Token Optimization Engine **reduces the token and context-window cost** of an already-validated request **without altering its meaning**. For every request it consumes the **validated request** (post-Governance admission, post-SchemaLock request-shape validation) and produces an **`OptimizationDescriptor`** (what was, or could be, optimized) plus an **`OptimizationReport`** (estimated token savings, applied vs. advisory) — either as **hints** for the pipeline to apply, or as **deterministic, lossless rewrites** it is confident are meaning-preserving. It realizes token economy as a **stateless, provider-neutral, meaning-preserving, fail-closed** runtime component.

### §2 — Scope
- **In scope (optimize, meaning-preserving only):** prompt size; context-window usage; duplicate-context removal; conversation compaction (lossless/reference-preserving only); system-prompt optimization (normalization only); tool-schema compression (lossless); token budgeting; retrieval deduplication; prompt normalization; whitespace optimization; deterministic formatting; context prioritization.
- **Applies to:** every request on the hot path **for which optimization is enabled and safe** — always with an unconditional **pass-through of the original** when uncertain (TOE-INV).

### §3 — Responsibilities
1. Consume the **validated request** (never a raw/un-validated one).
2. Compute a **meaning-preserving** `OptimizationDescriptor` (candidate optimizations) and an `OptimizationReport` (estimated savings).
3. Apply **only** deterministic, lossless optimizations it is certain are safe; emit the remainder as **advisory hints**.
4. **Fail closed** on any uncertainty — pass the original request through **unchanged**, never silently optimize.
5. Never bypass or reorder Governance (`21`), Router (`19`), Reliability (`20`), StreamGuard (`18`), or SchemaLock (`17`).
6. Emit **optimization audit + metrics** (content-free) for replay and observability.

### §4 — Non-Responsibilities (what the Engine NEVER does)
- ❌ **routing / provider selection** — C1/`19`.
- ❌ **retries / execution** — C2/`20`.
- ❌ **governance / policy / quota-budget enforcement** — C4/`21`.
- ❌ **pricing / billing** — C5 Cost (`22`) / C8.
- ❌ **caching** — C2 cache tier.
- ❌ **semantic rewriting / summarization that changes meaning** — forbidden (TOE-INV).
- ❌ **hold credentials / call providers / own network / persist / own a database** — credential-free, SDK-free, HTTP-free, store-free (TOE-D12).

### §5 — What the Engine is NOT
| Token Optimization Engine (optimize) | Not the Engine |
|---|---|
| ✓ reduce tokens without changing meaning | ✗ route / select providers (C1 `19`) |
| ✓ deterministic, lossless, fail-closed | ✗ retry / execute (C2 `20`) |
| ✓ emit hints + safe rewrites | ✗ govern / enforce policy (C4 `21`) |
| ✓ estimate token savings | ✗ price / bill (C5 `22` / C8) |
| ✓ provider-neutral | ✗ summarize / rewrite meaning (forbidden) |

---

## B. Domain & Interfaces

### §6 — Domain model (optimization subdomain)
Pure domain types (no infrastructure, `11` hexagonal); immutable value objects unless noted.

| Type | Kind | Description |
|---|---|---|
| `ValidatedRequest` | VO (input) | The already-validated, already-governed request handed to the Engine (post-SchemaLock request validation `17`, post-Governance admission `21`) |
| `OptimizationDescriptor` | VO (immutable) | The canonical, provider-neutral description of candidate/applied optimizations: `{requestRef, optimizations[], appliedSet, advisorySet, safetyClass, descriptorVersion, sourceVersions}` |
| `Optimization` | VO | A single candidate transformation: `{kind, target, losslessness, estimatedSavings, applied:bool, reason}` |
| `Losslessness` | enum | `lossless` \| `reference-preserving` \| `unsafe` — only `lossless` may be auto-applied; `unsafe` is never applied and never emitted as an actionable hint |
| `OptimizationReport` | VO | `{estimatedTokensSaved, appliedCount, advisoryCount, passthrough:bool, tokenizerModel}` |
| `OptimizationDescriptorSnapshot` | VO (immutable, snapshot) | Versioned rules/policy for what may be optimized (AD-022 config snapshot) |
| `OptimizationResult` | VO | Terminal outcome: an optimized (or unchanged pass-through) request + descriptor + report |

**Aggregate:** `OptimizationExecution` — the transient per-request aggregate coordinating consume → analyze → apply-safe/emit-hints → report; holds **no shared mutable state across requests** (AD-021).

### §7 — Public interfaces (ports — AD-002 hexagonal)
**Inbound (called by the data-plane pipeline on a validated request):**
```
TokenOptimizationPort:
  optimize(ValidatedRequest) -> OptimizationResult   // meaning-preserving; fail-closed pass-through on uncertainty
```
**Outbound (no store, no credentials, no provider SDK):**
```
OptimizationPolicyPort   // <- C15/AD-022: optimization descriptor snapshot (what may be optimized)
TokenModelPort           // <- (see TOE-D6 open issue): canonical token-accounting descriptor for estimation
AuditSinkPort            // -> Audit (C10): content-free optimization audit records
TelemetryPort            // -> Observability (C9): optimization metrics/traces (content-free)
ClockPort / IdPort       // deterministic time / ids
```
- **Enforcement (intended):** ArchUnit — the Engine imports **no** provider SDK, **no** HTTP client, **no** persistence driver/database, **no** secrets/credential API, **no** routing/retry/governance/pricing types. Inputs are a validated request + snapshots; outputs are a descriptor + report + a request that is either losslessly optimized or byte-identical to the input.

---

## C. Major Decisions (TOE-D1 … TOE-D12)
*Each decision uses the standard 9-field template: Problem · Decision · Alternatives · Why selected · Trade-offs · Failure Modes · Security Impact · Performance Impact · Enforcement.*

### TOE-D1 — Meaning-preserving only (never change intent/meaning)
- **Problem:** any optimization that alters intent, meaning, or drops required context corrupts the request and can increase hallucination.
- **Decision:** the Engine performs **only meaning-preserving** transformations. It **never** summarizes, paraphrases, re-orders semantically, or drops content it cannot prove is redundant. On any doubt it **passes the original through unchanged** (TOE-INV).
- **Alternatives:** aggressive semantic compression/summarization — rejected (changes meaning, TOE-INV, hallucination risk).
- **Why selected:** the core TOE-INV guarantee; optimization is an economy, never a correctness actor.
- **Trade-offs:** lower savings ceiling than lossy summarization — accepted (correctness > savings).
- **Failure Modes:** cannot prove meaning-preserving ⇒ pass-through.
- **Security Impact:** no meaning-tampering surface.
- **Performance Impact:** analysis is bounded CPU (§38).
- **Enforcement:** property tests (meaning-preservation); ArchUnit forbids any summarization/LLM-call type. **Build-Fail:** any lossy/semantic rewrite path.

### TOE-D2 — Fail closed = pass original through unchanged
- **Problem:** an optimizer that fails "open" (emits a broken/altered request) is worse than no optimizer.
- **Decision:** every failure/uncertainty resolves to **pass-through of the byte-identical original request** with `passthrough=true` in the report — never a partial, guessed, or altered request. The Engine is **removable with zero behavioral change** to correctness/governance.
- **Alternatives:** best-effort partial optimization — rejected (can ship an altered request under uncertainty).
- **Why selected:** optimization must never be able to harm a request.
- **Trade-offs:** foregone savings on uncertain inputs — accepted.
- **Failure Modes:** none introduced; failure = original.
- **Security Impact:** no failure path that mutates a request.
- **Performance Impact:** pass-through is O(1).
- **Enforcement:** fault-injection tests. **Build-Fail:** any failure path that emits a non-identical request without a proven lossless transform.

### TOE-D3 — Non-bypassable pipeline preserved (AD-018)
- **Problem:** optimization must not weaken or route around the mandatory correctness/governance stages.
- **Decision:** the Engine **never** bypasses, disables, reorders, or short-circuits **Governance (`21`), Router (`19`), Reliability (`20`), StreamGuard (`18`), or SchemaLock (`17`).** It runs as an **additive, optional** step whose output is still subject to every mandatory stage downstream.
- **Alternatives:** optimize post-governance to "save" a governance pass — rejected (AD-018 non-bypass).
- **Why selected:** AD-018 invariant is inviolable.
- **Trade-offs:** if optimization changes request bytes, downstream validation may need to re-run (see TOE-D4 / §14 — **open issue**).
- **Failure Modes:** any ordering ambiguity ⇒ pass-through (§14).
- **Security Impact:** preserves the enforced pipeline.
- **Performance Impact:** adds one bounded stage.
- **Enforcement:** pipeline-order tests (`18`/`21` cross-checks). **Build-Fail:** any bypass/reorder of a mandatory stage.

### TOE-D4 — Optimize a validated request; re-validation is downstream's contract *(open ordering issue — see §14)*
- **Problem:** the Engine consumes a **validated** request, but if it mutates request bytes, what was validated is no longer exactly what dispatches.
- **Decision (proposed):** the Engine only applies transforms whose **output is guaranteed to satisfy the same validation** as the input (lossless, schema-preserving); any transform that *could* change what SchemaLock/Governance validated is emitted as an **advisory hint only**, never auto-applied. The **exact re-validation contract and pipeline position are unresolved** (§14).
- **Alternatives:** optimize before validation — rejected (would optimize un-governed content); optimize after and skip re-validation — rejected (AD-018).
- **Why selected:** keeps optimization inside the validated envelope.
- **Trade-offs:** narrows auto-apply to provably validation-invariant transforms.
- **Failure Modes:** unclear validation impact ⇒ advisory-only / pass-through.
- **Security Impact:** governed content stays governed.
- **Performance Impact:** neutral.
- **Enforcement:** re-validation tests (`17`/`21`). **Build-Fail:** auto-applying a transform that changes the validated/governed shape. *(Note: this decision is **not yet reconciled** with the frozen colocated-pipeline ordering — §14, board High.)*

### TOE-D5 — Deterministic, lossless, replayable optimization
- **Problem:** optimizations must be reproducible for audit and debugging.
- **Decision:** optimization is a **pure deterministic function** of `(ValidatedRequest, descriptor snapshot version, injected clock)` — no wall-clock/random (`11` R-063). Identical input + snapshot ⇒ identical `OptimizationDescriptor`. Every descriptor stamps its snapshot + source versions for exact replay.
- **Alternatives:** heuristic/nondeterministic optimization — rejected (un-auditable).
- **Why selected:** auditable, testable, replayable.
- **Trade-offs:** determinism over recorded inputs only.
- **Failure Modes:** none from determinism.
- **Security Impact:** enables strong testing/audit.
- **Performance Impact:** O(request size).
- **Enforcement:** ArchUnit forbids wall-clock/random in the core; replay tests. **Build-Fail:** wall-clock/random in the optimization core; an unstamped descriptor.

### TOE-D6 — Provider-neutral optimization *(open: token counting is model-specific — board High)*
- **Problem:** "estimated token savings" requires token counting, but tokenization is **model/tokenizer-specific**, which risks provider coupling (AD-007).
- **Decision (proposed):** the Engine performs **structural, provider-neutral** optimizations (dedup, whitespace, formatting, schema compression) and expresses savings via a **canonical, versioned token-accounting descriptor** (`TokenModelPort`) — **never** a provider SDK/tokenizer import. Where a precise per-model token count is unavailable, savings are reported as **structural estimates**, explicitly flagged as estimates.
- **Alternatives:** import provider tokenizers — rejected (AD-007 coupling, SDK ban).
- **Why selected:** keeps the Engine neutral.
- **Trade-offs:** token-savings figures are **estimates**, not exact per-provider counts — and the **source/ownership of the token-accounting descriptor is unresolved** (C5? C1 capability registry? — board Medium).
- **Failure Modes:** no token model ⇒ report structural estimate flagged, or pass-through if a size cap depends on an exact count.
- **Security Impact:** no provider coupling.
- **Performance Impact:** O(request size).
- **Enforcement:** ArchUnit — no provider tokenizer/SDK; `15` provider-neutrality test. **Build-Fail:** any provider tokenizer/SDK import or provider-name branch. *(Open: descriptor ownership, §29 / board.)*

### TOE-D7 — Lossless conversation compaction (reference-preserving only)
- **Problem:** conversation compaction is the highest-savings opportunity but the easiest way to **drop required context**.
- **Decision:** compaction is limited to **provably redundant** removal (exact-duplicate turns, verbatim repeated context) and **reference-preserving** restructuring — **never** summarization or dropping of non-duplicate history. Anything beyond exact/reference-preserving redundancy is **advisory-only** and left to a governed prompt-asset owner (**C11**, not this Engine — see board Cross-doc).
- **Alternatives:** summarize old turns — rejected (lossy, TOE-INV; also C11's asset domain).
- **Why selected:** compaction without meaning loss.
- **Trade-offs:** modest savings vs. summarization.
- **Failure Modes:** cannot prove redundancy ⇒ no compaction.
- **Security Impact:** no context-dropping surface.
- **Performance Impact:** O(history size).
- **Enforcement:** property tests (compacted ⊨ original meaning). **Build-Fail:** a compaction that drops non-duplicate context.

### TOE-D8 — Retrieval / duplicate-context deduplication (exact/near-exact, safe)
- **Problem:** RAG contexts frequently repeat chunks; dedup saves tokens but must not remove uniquely-required context.
- **Decision:** dedup removes only **exact or provably-equivalent** duplicate context blocks, preserving at least one instance and all provenance/citations. Near-duplicates are **advisory-only** unless provably equivalent.
- **Alternatives:** similarity-threshold dedup — rejected as auto-apply (can drop required nuance); allowed only as advisory.
- **Why selected:** safe, high-frequency savings.
- **Trade-offs:** conservative near-duplicate handling.
- **Failure Modes:** ambiguous equivalence ⇒ keep both.
- **Security Impact:** provenance preserved.
- **Performance Impact:** O(blocks) with bounded comparison.
- **Enforcement:** property tests. **Build-Fail:** dedup that drops a non-equivalent block or strips provenance.

### TOE-D9 — Tool-schema compression (lossless, semantics-preserving)
- **Problem:** tool schemas are token-heavy; compression must not change tool-call semantics validated by SchemaLock (`17`).
- **Decision:** schema compression is **lossless normalization only** (whitespace, ordering that SchemaLock treats as equivalent, removal of provably-ignored redundancy) — the compressed schema **must validate identically** under SchemaLock. Anything that changes the schema's accepted set is forbidden.
- **Alternatives:** drop "rarely used" fields — rejected (changes tool semantics; `17`).
- **Why selected:** savings with zero semantic change.
- **Trade-offs:** limited to lossless normalization.
- **Failure Modes:** any doubt ⇒ original schema.
- **Security Impact:** tool-call integrity preserved (`17`).
- **Performance Impact:** O(schema size).
- **Enforcement:** SchemaLock-equivalence tests (`17`). **Build-Fail:** schema compression that changes the accepted set.

### TOE-D10 — Token budgeting & context prioritization (advisory; never drop required context)
- **Problem:** when a request exceeds a context budget, something must give — but the Engine must never silently drop required context.
- **Decision:** the Engine computes a **budget/priority descriptor** (which blocks are lowest-value by explicit, deterministic rules) and emits it as **advisory hints**; it **never auto-drops** context to fit a budget. The decision to trim, and the governance of that decision, belongs to the caller/Governance (`21`), not this Engine.
- **Alternatives:** auto-trim to budget — rejected (silent context loss, TOE-INV).
- **Why selected:** budgeting informs; it never silently truncates.
- **Trade-offs:** the Engine cannot itself guarantee a request fits a budget.
- **Failure Modes:** over-budget with no safe lossless reduction ⇒ pass-through + advisory (surfaced), never silent drop.
- **Security Impact:** no silent context loss.
- **Performance Impact:** O(blocks).
- **Enforcement:** tests (no auto-drop). **Build-Fail:** auto-dropping required context to meet a budget.

### TOE-D11 — No optimization after streaming starts; request-side only
- **Problem:** optimization must not interfere with in-flight streaming (StreamGuard `18`).
- **Decision:** optimization is **request-side only** and occurs **before** provider dispatch; **once streaming has started, no optimization occurs** and StreamGuard (`18`) is untouched. The Engine never observes, buffers, or alters response streams.
- **Alternatives:** response-side compaction — rejected (StreamGuard's domain; latency/correctness risk).
- **Why selected:** clean separation from `18`.
- **Trade-offs:** no response-side savings.
- **Failure Modes:** n/a (response side is out of scope).
- **Security Impact:** StreamGuard untouched.
- **Performance Impact:** off the streaming path.
- **Enforcement:** ArchUnit — no StreamGuard/stream types. **Build-Fail:** any post-stream-start optimization.

### TOE-D12 — Security (credential-free, SDK-free, HTTP-free, store-free, content-minimizing)
- **Problem:** the Engine handles full request content (prompts) and must present zero credential/SDK/network/storage surface and minimal content exposure.
- **Decision:** the Engine holds **no secrets, no credentials, no provider SDK, makes no HTTP, owns no store/database**. It processes request content **in-memory only**, emits **content-free** audit/telemetry (counts/hashes, never prompt text), and confines any content per residency/classification (`08`/`14`).
- **Alternatives:** an optimization cache/store of prior prompts — rejected (store ownership; content-at-rest surface).
- **Why selected:** minimal attack surface.
- **Trade-offs:** no cross-request learning/caching (correct — that would be a new store).
- **Failure Modes:** n/a.
- **Security Impact:** eliminates credential/store surface; minimizes content exposure.
- **Performance Impact:** neutral.
- **Enforcement:** ArchUnit + secret/content-leak scanner (`13 §20`, `14 §7.1`). **Build-Fail:** any credential/HTTP/SDK/database reference; prompt content in audit/telemetry/logs.

---

## D. Optimization Descriptor & Model

### §8 — OptimizationDescriptor (see TOE-D5)
Canonical, immutable descriptor (§6): `requestRef`, `optimizations[]` (each with `kind/target/losslessness/estimatedSavings/applied/reason`), `appliedSet`, `advisorySet`, `safetyClass`, `descriptorVersion`, `sourceVersions`. **Content-free at rest** (references/hashes, not prompt text).

### §9 — Optimization policy snapshot & §10 — versioning
A versioned **`OptimizationDescriptorSnapshot`** (AD-022, authored by an admin/config owner via C15) declares **what may be optimized** and safety thresholds (all numerics are **operational-baseline** entries, `16 §I.1`). The Engine pins the snapshot version at request start and stamps it on the descriptor (deterministic, §26). *(Open: authoring owner of optimization policy is unresolved — board Medium.)*

### §11 — Optimization lifecycle
```
ValidatedRequest → analyze (policy snapshot + token model) → OptimizationDescriptor (candidates)
  → classify losslessness → apply ONLY lossless/validation-invariant; emit rest as advisory
  → OptimizationReport (estimated savings, passthrough?) → hand back optimized-or-identical request
  → content-free audit + metrics
```
Every step is fail-closed (pass-through) on uncertainty (§35).

### §12 — Lossless guarantee
Auto-applied transforms are **lossless and validation-invariant**; the produced request must **round-trip to the same meaning and validate identically** (`17`) — otherwise the transform is advisory-only or dropped.

---

## E. Pipeline & Ordering

### §13 — Position in the pipeline (proposed)
Optimization is intended to run **after Governance admission (`21`) and SchemaLock request-validation (`17`)** and **before Router (`19`)/Reliability (`20`) dispatch** — as an **additive, optional** stage. It is **removable** with no correctness/governance change (TOE-D2/D3).

### §14 — Ordering & re-validation contract *(UNRESOLVED — board High)*
This draft **does not** fully specify how an optimized (byte-changed) request re-satisfies the mandatory stages under the frozen **colocated pipeline (AD-020)** and **non-bypassable pipeline (AD-018)**:
- If optimization changes request bytes **after** SchemaLock/Governance validated them, either (a) the changed request must **re-run** those validations (cost/ordering impact, unspecified here), or (b) auto-apply must be restricted to transforms that are **provably validation-invariant** (TOE-D4) — the exact boundary is **not proven** in this draft.
- The interaction with **idempotency keying** (`12 §14`) — whether an optimized request keeps the same idempotency identity — is **unspecified**.
- **This is the primary open architectural issue** and is the reason the board recommends *Freeze With Changes*.

### §15 — No stage bypass (see TOE-D3)
Optimization never disables/reorders/short-circuits a mandatory stage.

### §16 — Streaming boundary (see TOE-D11)
No optimization once streaming starts; StreamGuard (`18`) untouched.

### §17 — Determinism across the pipeline
The same validated request + snapshot yields the same descriptor regardless of pipeline timing (TOE-D5).

---

## F. Optimization Families

### §18 — Prompt normalization & whitespace
Deterministic whitespace/formatting normalization that a tokenizer treats as equivalent — lossless; auto-applied. Never alters code blocks, quoted content, or anything where whitespace is semantically significant (fail-closed on doubt).

### §19 — Deterministic formatting
Stable canonical formatting (ordering that is provably semantics-neutral) — lossless; auto-applied only where equivalence is provable.

### §20 — Duplicate-context removal & retrieval dedup (see TOE-D8)
Exact/provably-equivalent duplicate blocks collapsed to one, provenance preserved; near-duplicates advisory-only.

### §21 — Conversation compaction (see TOE-D7)
Reference-preserving/exact-redundancy only; no summarization; anything else advisory (and arguably C11's domain — board).

### §22 — System-prompt optimization (see TOE-D7; C11 tension)
**Normalization only** of the system prompt; **not** authoring/editing prompt assets (that is **C11 Prompt & Interaction Asset Management**, `05 §5`). This boundary is **contested** (board Cross-doc).

### §23 — Tool-schema compression (see TOE-D9)
Lossless, SchemaLock-equivalent normalization only.

### §24 — Token budgeting & context prioritization (see TOE-D10)
Advisory descriptor only; never auto-drops context.

### §25 — Estimated token savings (see TOE-D6)
Structural, provider-neutral estimate via the canonical token-accounting descriptor; flagged as an **estimate** where an exact per-model count is unavailable.

---

## G. Determinism & Replay

### §26 — Determinism (see TOE-D5) & §27 — replay
Deterministic over recorded inputs (validated request + snapshot version + injected clock). **Replay:** a descriptor is reproduced from its recorded `(requestRef, snapshot version)` — no live lookup.

### §28 — Optimization audit trail
Every descriptor/report is a **content-free** audit record (hashes/counts, `13 §19`, `08 §10`) enabling dispute reproduction and drift detection.

---

## H. Interfaces

### §29 — Policy & token-model interfaces
- `OptimizationPolicyPort` ← versioned optimization snapshot (AD-022, via C15). *(Owner of policy authoring unresolved — board.)*
- `TokenModelPort` ← canonical token-accounting descriptor for savings estimation. *(Owner unresolved: C5 metering-adjacent? C1 capability registry? — board Medium.)*

### §30 — Pipeline interface
`TokenOptimizationPort.optimize(ValidatedRequest) -> OptimizationResult`, called by the data-plane pipeline between validation and dispatch (§13). Output is subject to all mandatory downstream stages (TOE-D3).

### §31 — Audit interface
Via `AuditSinkPort`, content-free optimization audit records (WORM/Merkle, `08 §10`, `13 §19`).

### §32 — Observability interface
See §42.

### §33 — No provider / no cost / no governance interface
The Engine has **no** provider, pricing (`22`), or governance-enforcement (`21`) interface — it neither prices savings nor enforces anything.

### §34 — No store interface
No persistence/database port (TOE-D12).

---

## I. Failure Handling

### §35 — Failure handling (every failure = pass-through)
| Failure | Handling |
|---|---|
| Cannot prove a transform meaning-preserving | **pass-through** (no optimization) |
| Missing/invalid policy snapshot | **pass-through** (never optimize under unknown policy) |
| Missing token-accounting descriptor | structural estimate flagged, or **pass-through** if a cap depends on an exact count |
| Ambiguous duplicate/equivalence | keep both (no dedup) |
| Over-budget with no lossless reduction | **pass-through + advisory** (surfaced), never silent drop |
| Any validation-impact uncertainty (§14) | **advisory-only / pass-through** |
| Any unknown/internal error | **pass-through** (byte-identical original) |
- **Downstream effect:** none — a pass-through request is exactly what the pipeline would have processed without the Engine. The Engine is **behaviorally removable**.

### §36 — Recovery behavior
The Engine holds **no durable state**; on restart it simply resumes per-request optimization. No replay/outbox needed (it owns no store, emits only advisory/audit).

### §37 — Removability
By TOE-D2, disabling the Engine (feature flag, `16`) leaves correctness/governance **unchanged** — the only effect is foregone token savings.

---

## J. Performance & Concurrency

### §38 — Performance model
Analysis is **bounded CPU** (O(request size) normalize/dedup + O(blocks) prioritization). Runs on the hot path but **must stay within the added-latency budget** (`06 §11`); if analysis would exceed its time budget it **bails to pass-through** (never blocks the request). JMH-benchmarked (`15` T-026). *(Open: hot-path latency budget for optimization is unspecified — board Medium.)*

### §39 — Memory model & baselines
Per-request state in the transient `OptimizationExecution` aggregate; policy snapshots are shared immutable reads. No unbounded structures; sized within container limits (`16` D-044, ZGC AD-023). **All numerics** (savings thresholds, dedup windows, time budget, cardinality-N) are **operational-baseline** entries (`16 §I.1`).

### §40 — Concurrency & Virtual Threads
Runs on Virtual Threads (AD-023); **no shared mutable per-request state** (AD-021); pure CPU, no blocking-in-`synchronized` on the hot path (`11` R-049). **Build-Fail:** shared mutable per-request state; mutable descriptor.

---

## K. Security

### §41 — Security considerations
- **Credential-free / SDK-free / HTTP-free / store-free** (TOE-D12).
- **Content minimization:** processes prompt content in-memory only; audit/telemetry are **content-free** (hashes/counts, never prompt text) (`13 §20`, `14 §7.1`).
- **Meaning integrity (TOE-INV):** never alters intent/meaning; fail-closed pass-through.
- **Tenant isolation (AD-021):** per-request, per-tenant; no cross-request state/cache.
- **Residency (AD-014):** in-region processing; content residency-confined (`14 §18.1`, `08` DA-D2).
- **No provider leak:** provider-neutral; no provider identity in output.
- **Enforcement:** secret/content-leak scanner (`14 §7.1`, `15` T-052); isolation tests (`15` T-044). **Build-Fail:** content/secret in a descriptor/report/log; cross-tenant/cross-request state; provider coupling.

---

## L. Observability

### §42 — Posture
Full instrumentation per `14`, **content-free**, **low-cardinality**: spans per optimization (kinds applied, losslessness class, versions), metrics (§43), structured logs (§44), events (§45 — advisory/audit only). Privacy (`14 §7.1`): never prompt content.

### §43 — Metrics
- `toe_tokens_saved_estimate{kind}`, `toe_optimizations_applied_total{kind}`, `toe_passthrough_total{reason}` (a spike ⇒ inputs the Engine cannot safely optimize), `toe_advisory_total{kind}`, `toe_latency`.
- Model/kind labels **bounded** (top-N + `other`); **no provider label**; no content labels.

### §44 — Tracing & Logging
One span per optimization; neutral attributes (kinds, losslessness class, savings bucket, versions) — never content. Structured content-free logs.

### §45 — Events
The Engine emits **no domain events on the hot path** beyond content-free **audit** records (C10) and telemetry (C9) — it produces hints/reports, not events others act on transactionally. **Build-Fail:** content/raw-prompt in any emission; a new provider-native event.

---

## M. Testing

### §46 — Testing strategy (critical module — `15 §G.1`)
- **Unit (JUnit5/Mockito):** normalization, dedup, compaction-redundancy, schema-compression equivalence, budgeting descriptor, fail-closed pass-through.
- **Property-based (jqwik, `15` T-035):** *meaning-preservation* (optimized request ⊨ same meaning/validation as original); *never removes required context*; *pass-through on uncertainty*; *determinism* (identical input+snapshot ⇒ identical descriptor); *lossless auto-applied transforms round-trip*.
- **Mutation (`15 §G.1`):** **≥ 85%** — a mutant that alters meaning, drops context, or optimizes under uncertainty must be killed.
- **Failure injection (`15` T-018):** missing policy/token model, ambiguous equivalence, over-budget, validation-impact uncertainty ⇒ pass-through / advisory; zero silent alteration.
- **Non-bypass (`15`, `17`/`18`/`21` cross-checks):** optimization never bypasses/reorders a mandatory stage; optimized request validates identically under SchemaLock (`17`).
- **Provider-neutrality (`15` T-015):** no provider branch/tokenizer; adding a provider = zero Engine diff.
- **Residency/isolation (`15` T-044/T-045):** in-region; per-request; no cross-request state.
- **Performance (`15` T-021/T-026, `16 §J.2`):** within added-latency budget; bail-to-pass-through under time pressure.
- **Follows:** `15` Testing · `16` Deployment · `17` SchemaLock · `18` StreamGuard · `19` Provider Router · `20` Reliability · `21` Governance · `22` Cost · `23` Usage Metering.

---

## N. Build-Failing Rules (TOE-A1 … TOE-A15)

| # | Rule | Gate |
|---|---|---|
| TOE-A1 | No meaning/intent change; no lossy/semantic rewrite or summarization | property test + ArchUnit |
| TOE-A2 | No removal of required context; dedup/compaction only on provable redundancy/equivalence | property test |
| TOE-A3 | Fail closed = byte-identical pass-through; no failure path emits an altered request | fault test (`15` T-018) |
| TOE-A4 | No bypass/reorder/short-circuit of Governance/Router/Reliability/StreamGuard/SchemaLock | pipeline test (`17`/`18`/`19`/`20`/`21`) |
| TOE-A5 | No auto-apply of a transform that changes the validated/governed shape (§14) | SchemaLock-equivalence test (`17`) |
| TOE-A6 | No optimization after streaming starts; no stream observation/mutation | ArchUnit (no `18` types) |
| TOE-A7 | Provider-neutral: no provider SDK/tokenizer/name branch | ArchUnit + `15` T-015 |
| TOE-A8 | Deterministic: no wall-clock/random in the core; every descriptor stamps snapshot/source versions | ArchUnit (`11` R-063) |
| TOE-A9 | No routing/retry/governance/pricing/billing/caching logic in the Engine | ArchUnit |
| TOE-A10 | No credential/secret/API-key/provider SDK/direct HTTP | ArchUnit + secret scanner (`13 §20`) |
| TOE-A11 | No persistence/private store/database (stateless, store-free) | ArchUnit |
| TOE-A12 | No content/secret in descriptor/report/telemetry/logs/audit (content-free) | `15` T-052, `14 §7.1` |
| TOE-A13 | Content never crosses a residency boundary | `15` T-045 |
| TOE-A14 | No hard-coded optimization numeric; all thresholds from operational baseline (§39) | config lint |
| TOE-A15 | Mutation score ≥ 85% (critical module) | PITest (`15 §G.1`) |

---

## O. Operations

### §47 — Operational runbook
- **`toe_passthrough` spike:** the Engine is correctly declining to optimize uncertain inputs — **never disable the fail-closed pass-through** to force savings (would risk meaning change, TOE-INV).
- **Latency-budget breach:** the Engine bails to pass-through; investigate analysis cost, never let optimization block a request.
- **Savings drift:** compare `toe_tokens_saved_estimate` against baseline; drift points to snapshot/token-model changes.

### §48 — Upgrade strategy
Ships within the data-plane release train (`16` D-014/D-055): rolling/canary, zero-downtime, feature-flagged (removable, TOE-D2/§37). Optimization-policy changes are snapshot/config (C15) — no redeploy to change what is optimized.

### §49 — Backward compatibility
The `OptimizationDescriptor`/`OptimizationReport` shape is a stable internal contract (`12 §27`): additive within a major. Deterministic core (§26) guarantees identical descriptors for identical recorded inputs across versions.

---

## P. Traceability

### §50 — Traceability matrix
| Optimization concern | BR | NFR | ADR | Domain/Svc | Sec | Obs | Test | Deploy |
|---|---|---|---|---|---|---|---|---|
| Meaning-preserving / TOE-INV (TOE-D1/§35) | BR-004 | NFR-SO-001 | AD-018/016 | *proposed C6* ⚠ | §41 | §43 | property/T-018 | `16` |
| Fail-closed pass-through (TOE-D2/§37) | BR-001 | NFR-SO-003 | AD-018 | pipeline | §41 | §43 | T-018 | D-031 |
| Non-bypass preserved (TOE-D3/§15) | BR-015 | NFR-AUTHZ-001 | AD-018 | C4/C3/C1/C2 | — | — | `17`/`21` | — |
| Ordering/re-validation (TOE-D4/§14) ⚠ | BR-001 | NFR-SO-001 | AD-018/020 | pipeline | — | — | `17`/`21` | — |
| Deterministic/replay (TOE-D5/§26) | BR-004 | NFR-AUD | AD-016 | `11` R-063 | — | §28 | T-035 | — |
| Provider-neutral / token estimate (TOE-D6/§25) ⚠ | BR-006 | NFR-IF-001 | AD-007 | C1?/C5? ⚠ | — | §43 | T-015 | — |
| Compaction/dedup (TOE-D7/D8/§20–21) | BR-004 | NFR-COST | AD-018 | C11 tension ⚠ | §41 | §43 | property | — |
| Schema compression (TOE-D9/§23) | BR-003 | NFR-STRM/SO | AD-018 | C3 (`17`) | — | — | `17` | — |
| Budget/prioritization (TOE-D10/§24) | BR-004 | NFR-COST | AD-018 | pipeline/C4 | §41 | §43 | property | — |
| Streaming boundary (TOE-D11/§16) | BR-002 | NFR-STRM-001 | AD-018 | C3 (`18`) | — | — | `18` | — |
| Security/content-minimization (TOE-D12/§41) | BR-019 | NFR-SEC | AD-012 | `13`/`14` | §41 | — | T-052 | — |
| Stateless module (§7/§39) | BR-005 | NFR-PERF | AD-020/006 | `06` | — | — | T-051 | D-014 |

*(⚠ = unresolved against frozen docs; see §S.)*

---

## S. Reviews

### 1. Internal Consistency Review
Unlike a frozen document, this draft's consistency table records **open** rows (⚠/❌), not all-green.

| Frozen source | Requirement | This draft | Status |
|---|---|---|---|
| `05 §5` context catalogue | **C6 = Identity & Access**; contexts are C1–C16 | Draft proposes **"C6 — Optimization"** | ❌ **collides with frozen C6** |
| `05 §5` / `06` | contexts are frozen; no "Optimization" context exists | Draft asserts an Optimization context/subdomain | ❌ **no such frozen context** |
| `05 §5` C11 | Prompt & Interaction Asset Mgmt owns prompt assets | Draft does system-prompt/conversation optimization | ⚠ **ownership tension with C11** |
| AD-018 | non-bypassable correctness/governance | TOE-D3/§15 (never bypass) | ✅ (asserted) |
| AD-018/AD-020 | colocated, ordered, mandatory pipeline | §14 re-validation/ordering **unspecified** | ❌ **ordering contract unproven** |
| AD-007 | provider neutrality | TOE-D6 — but token counting is model-specific | ⚠ **estimate-only; descriptor owner unresolved** |
| AD-022 | cached config snapshots | §9 optimization snapshot | ⚠ **authoring owner unresolved** |
| AD-021 | tenant isolation | §40/§41 (per-request, no shared state) | ✅ |
| AD-014 | residency confinement | §41 (in-region) | ✅ |
| AD-020/AD-006 | data-plane co-located stateless module | §7/§39 (stateless, store-free) | ✅ |
| AD-002 | hexagonal ports | §7 ports | ✅ |
| `17` SchemaLock | request/tool-call validation integrity | TOE-D4/D9 (validation-invariant) — §14 open | ⚠ **re-validation of optimized bytes unproven** |
| `12 §14` idempotency | stable request identity | §14 — optimized-request identity **unspecified** | ❌ **idempotency impact undefined** |
| `06 §11` | added-latency budget | §38 — optimization budget **unspecified** | ⚠ |

**Contradictions found:** the context placement (**C6 collision**, **no Optimization context**), the **pipeline ordering/re-validation contract** (§14), the **idempotency-identity** impact, and the **C11 ownership tension** are unresolved. This draft is **not internally or cross-document consistent** as written.

### 2. Architecture Validation
- **Ownership:** ⚠ **the proposed C6/Optimization ownership does not exist in the frozen model** — cannot be validated as "no ownership change" because it *is* a new context/ownership.
- **Boundaries/contexts:** ❌ collides with frozen C6 (Identity & Access); overlaps C11 (prompt assets), C5 (token accounting), C4 (budget governance).
- **Provider neutrality:** ⚠ structural optimizations are neutral, but token-savings estimation is inherently model/tokenizer-specific; neutrality holds only if the token-accounting descriptor is owned elsewhere and never an SDK.
- **Deployment:** ✅ data-plane release train, feature-flagged, removable.
- **Security:** ✅ credential/SDK/HTTP/store-free; content-minimizing — strong posture.
- **Observability:** ✅ content-free, low-cardinality.
- **Performance:** ⚠ hot-path stage with **unspecified latency budget**; bail-to-pass-through mitigates but is uncalibrated.
- **Conclusion:** the component *discipline* (stateless, fail-closed, meaning-preserving, non-bypass) is sound; the **placement, ordering, and ownership** are not yet valid against the frozen architecture.

### 3. Independent Review Board — Adversarial Review
*Board: Principal Enterprise Architect · AI Infrastructure Architect · LLM Optimization Specialist · Distributed Systems Architect · Performance Engineer · Security Architect · Reliability Engineer · Enterprise SaaS Architect · Compliance Auditor · CTO. Mandate: **attack the document, do not improve it.***

#### Executive Summary
The Token Optimization Engine is disciplined at the **component** level — stateless, credential/SDK/store-free, deterministic, meaning-preserving, fail-closed-to-pass-through, and explicitly non-bypassing of the mandatory pipeline. That discipline is genuine and the security/observability posture is strong. **However, the draft is placed on architectural ground that does not exist in the frozen model.** It claims a bounded context — **"C6 — Optimization"** — that **collides with the frozen C6 (Identity & Access)** and, more fundamentally, **there is no Optimization bounded context anywhere in the frozen C1–C16 catalogue**. It further leaves the **single most important seam — how an optimized (byte-changed) request re-satisfies SchemaLock/Governance under the frozen non-bypassable, colocated pipeline (AD-018/AD-020) — unspecified**, and it does not state the **idempotency-identity** consequence of mutating a request. Token-savings estimation quietly reintroduces **model-specific tokenization** into a provider-neutral platform. Several responsibilities (system-prompt/conversation optimization) **overlap C11's** owned domain. The component is salvageable, but not as drafted. **Recommendation: Freeze With Changes.**

#### Critical Findings
- **C-1 — Context collision / non-existent context.** The draft asserts **"C6 — Optimization"**; frozen `05 §5` defines **C6 = Identity & Access (Supporting, critical)**. There is **no Optimization context** in the frozen catalogue (C1–C16). Standing this document up as written **introduces a new bounded context**, violating the "no new architecture/context" constraint and directly contradicting `05`/`06`. Every "additive, no ownership change" claim in the header is false while this stands.
- **C-2 — Unspecified re-validation/ordering under AD-018/AD-020.** §14 admits the core contract is unresolved: if optimization changes request bytes **after** SchemaLock (`17`) and Governance (`21`) validated them, the draft does not prove the dispatched request is still the validated/governed one. Auto-applying *any* byte change without a proven validation-invariance or a defined re-validation step is a **latent bypass of the non-bypassable pipeline** — the exact failure AD-018 exists to prevent.

#### High Findings
- **H-1 — Idempotency identity undefined (`12 §14`).** If an optimized request differs in bytes from the original, does it retain the same idempotency key? The draft is silent. This threatens dedup/replay semantics relied on by Reliability (`20`) and Metering (`23`).
- **H-2 — Provider-neutral token counting is a contradiction in terms (AD-007).** "Estimated token savings" requires a tokenizer; tokenizers are model-specific. TOE-D6 defers this to an unnamed "canonical token-accounting descriptor" whose **owner does not exist** — risking either provider coupling (SDK) or savings figures so approximate they mislead cost/budget consumers.
- **H-3 — Hot-path latency budget unspecified (`06 §11`).** An optimization stage that runs analysis (dedup, compaction, schema normalization) on every request has no stated time budget. "Bail to pass-through" is asserted but uncalibrated; under load this stage could consume the very budget it claims to save.

#### Medium Findings
- **M-1 — C11 ownership overlap.** System-prompt optimization (§22) and conversation compaction (§21) touch **C11 Prompt & Interaction Asset Management**. The draft asserts "normalization only" but does not reconcile the boundary; two contexts editing prompt content is an ownership hazard.
- **M-2 — Budget/prioritization overlaps C4 Governance.** Context-prioritization/token-budget decisions (§24) are governance-adjacent; the draft makes them advisory but does not pin where the trim decision is actually governed.
- **M-3 — Token-accounting/optimization-policy authoring owner unresolved.** §9/§29 defer policy authoring and the token model to "an admin/config owner" and "C5? C1?" without a decision.
- **M-4 — "Lossless/meaning-preserving" is asserted, not proven.** The guarantee rests on a claim that transforms round-trip meaning; for natural-language prompts, "provably redundant" and "provably equivalent" are undefined for anything beyond byte-exact duplicates.

#### Low Findings
- **L-1** — exact metric/label names to reconcile with `14`.
- **L-2** — JMH benchmark set to `benchmarks/`.
- **L-3** — optimization-descriptor snapshot format to a config-authoring standard.
- **L-4** — advisory-hint consumer (who applies advisory optimizations, and under what governance) unnamed.

#### Internal Contradictions
- "Produces optimization hints only" (charter) vs. Responsibilities/§11 that **auto-apply** lossless transforms (dedup, whitespace, schema compression). The document is not consistent about whether the Engine mutates the request or only advises.
- "Never remove required context" (TOE-INV) vs. duplicate-context removal / conversation compaction / retrieval dedup — safe only under a "provably redundant" predicate the draft never rigorously defines (M-4).
- "Provider-neutral" (TOE-D6) vs. "estimated token savings" (§25), which requires model-specific tokenization (H-2).

#### Cross-document Contradictions
- **`05 §5` — C6 = Identity & Access** vs. this draft's "C6 — Optimization" (C-1).
- **`05 §5` — no Optimization context; C11 owns prompt assets** vs. §21–22 optimizing prompt/conversation content (M-1).
- **AD-018 / AD-020 — non-bypassable, colocated, ordered pipeline** vs. §14's unresolved re-validation of optimized bytes (C-2).
- **AD-007 — provider neutrality** vs. model-specific token counting (H-2).
- **`12 §14` — idempotency** vs. undefined identity of an optimized request (H-1).

#### Missing Specifications
- The re-validation/ordering contract (§14) — the load-bearing seam.
- Idempotency-key behavior for optimized requests.
- The canonical token-accounting descriptor: owner, shape, versioning, neutrality proof.
- Optimization-policy authoring owner and governance.
- Precise, testable definitions of "provably redundant" / "provably equivalent" / "meaning-preserving."
- Hot-path latency budget and the bail threshold.
- The advisory-hint consumer and its governance.

#### Hidden Assumptions
- That a validated request can be byte-changed and still be "the validated request" without re-running validation.
- That meaning-preservation of natural-language prompts is decidable by deterministic rules.
- That token savings can be estimated provider-neutrally with figures accurate enough for downstream cost/budget use.
- That "Optimization" is a legitimate ownership home when the frozen model has none.
- That whitespace/formatting is never semantically significant (false for code, YAML, significant-whitespace formats).

#### 10-Year Production Risks
- **Bypass creep:** once an optimizer can change request bytes on the hot path, pressure to skip re-validation "for latency" recurs indefinitely (AD-018 erosion).
- **Silent meaning drift:** heuristic "redundancy" predicates loosen over time; a compaction that was safe for one model quietly harms another; hallucination attribution becomes intractable.
- **Cost/budget mis-estimation:** provider-neutral token estimates diverge from real provider counts as tokenizers evolve, corrupting the FinOps signals (`22`/`23`) that consume them.
- **Ownership entropy:** an Optimization context overlapping C4/C5/C11 becomes a catch-all that accretes responsibilities it was told never to hold.
- **Debugging opacity:** "why did the model answer differently?" now has an extra, optional, request-mutating actor in the path.

#### Architecture Readiness Score: **58 / 100**
Strong component discipline (stateless, fail-closed, security posture) is undermined by a **non-existent/collision context (C-1)**, an **unspecified non-bypass re-validation contract (C-2)**, and unresolved neutrality/idempotency/ownership seams. Not ready to freeze.

#### Documentation Health: **62 / 100**
Well-structured and internally cross-referenced, but contains genuine internal contradictions (hints-only vs. auto-apply; neutral vs. token counting) and multiple explicitly-deferred load-bearing specs.

#### Implementation Readiness: **45 / 100**
The core seam (§14) is unimplementable as written; several ports (token model, policy authoring) have no owner; "meaning-preserving" lacks a testable definition. An engineer cannot build this without inventing the contracts the draft leaves open.

#### Recommendation: **FREEZE WITH CHANGES**
The component is architecturally salvageable and its discipline is worth keeping, but it **must not be frozen as drafted**. Required changes before any freeze: (1) resolve the context/ownership placement against the frozen `05`/`06` model (the "C6 — Optimization" collision and the absence of an Optimization context) — **C-1**; (2) specify the re-validation/ordering contract that keeps AD-018/AD-020 intact when request bytes change — **C-2**; (3) define idempotency-identity for optimized requests — **H-1**; (4) resolve provider-neutral token accounting ownership and honesty — **H-2**; (5) set the hot-path latency budget — **H-3**; (6) reconcile C11/C4 ownership overlaps and give "meaning-preserving" a testable definition — **M-1/M-2/M-4**. **No fixes are applied in this pass. This document remains a DRAFT and is NOT frozen.**

---

*End of document — 24-TokenOptimizationEngine.md · Status: **DRAFT — NOT FROZEN** · Review disposition: **Freeze With Changes** · Version: 0.1 (2026-07-21)*
