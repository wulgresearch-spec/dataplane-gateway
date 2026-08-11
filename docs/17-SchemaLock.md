# 17 — SchemaLock (Structured-Output Correctness Engine · Domain C3)

**Document:** Component Implementation Architecture — SchemaLock
**Project:** Reliability-First AI Gateway
**Status:** **FROZEN**
**Version:** 1.0 (frozen 2026-07-21)
**Bounded context:** **C3 — Correctness** (Core Domain, `05`)
**Deployment unit:** a **module inside the Data Plane** deployable (`dp-correctness-schemalock`), co-located per **AD-020/AD-006** — **not** a separate service.
**Audience:** Correctness-domain engineers, data-plane engineers, SRE, security, QA, reviewers
**Classification:** Internal — Component Architecture
**Builds on (approved, frozen):** `00`–`16` and ADRs **AD-001…AD-023**. Nothing here may contradict them. Introduces **no new architecture decision** — every SL-Dx below is a *component-internal implementation decision* inside the already-frozen C3, expressed through hexagonal ports (AD-002).

> **What this is.** The complete implementation architecture of **SchemaLock** — the engine that makes the platform's flagship promise real: **provider-neutral structured-output correctness with no silent incorrect delivery** (BRULE-1, `03` NFR-SO, `12 §16/§22`). SchemaLock guarantees that any structured output the platform returns either **conforms to the caller's declared schema** or is **explicitly surfaced as non-conforming** — it is never silently wrong. Every major design decision carries **Problem · Decision · Alternatives · Why selected · Trade-offs · Failure modes · Security impact · Performance impact · Enforcement**.
>
> **The SchemaLock invariant (SL-INV):** *SchemaLock never emits a value as schema-conformant unless it has been validated against the compiled schema and passed. On any uncertainty — malformed, truncated, unvalidatable, resource-exhausted — it fails closed (surfaces), never open (delivers).* This is a direct realization of **AD-016 (reliability first)** and **AD-018 (non-bypassable correctness)**.
>
> **Scope boundary with StreamGuard:** SchemaLock owns **schema conformance** (semantic: does the value match the schema?). **StreamGuard** owns **stream transport integrity** (ordering, exactly-once, terminal events, no silent truncation, `12 §16`). They collaborate on streaming structured output (§18/§43) with a crisp, non-overlapping contract.

---

## Table of Contents
**A. Charter** (§1–4) · **B. Domain & Interfaces** (§5–6) · **C. Pipeline & Lifecycle** (§7–8) · **D. Schema Handling** (§9–12) · **E. Provider-Neutral Strategy** (§13–17) · **F. Streaming** (§18–21) · **G. Violations, Repair, Retry, Errors** (§22–27) · **H. Correctness Properties** (§28–29) · **I. Budgets & Concurrency** (§30–32) · **J. Security & Observability** (§33–38) · **K. Data & Integration** (§39–47) · **L. Failure & Recovery** (§48–49) · **M. Testing & Enforcement** (§50–51) · **N. Operations** (§52–54) · **O. Traceability** (§55) · **P. Reviews**

---

## A. Charter

### §1 — Purpose
SchemaLock exists to **guarantee provider-neutral structured-output correctness**: given a caller-declared output schema, SchemaLock ensures the returned structured value is **validated to conform**, or the request is **surfaced as non-conforming** — never silently incorrect (BRULE-1). It is the concrete implementation of the Correctness domain's structured-output responsibility (`05` C3), operating inside the non-bypassable data-plane pipeline (AD-018).

### §2 — Scope
- **In scope:** schema compilation/caching; provider-capability-driven **strategy selection** for eliciting structured output (native / tool-calling / prompt-constrained); **incremental and completion validation**; **partial-object reconstruction** for streaming; **conservative repair** and **bounded guided retry**; **failure classification** and **error normalization**; production of the **provider-neutral canonical output model**; correctness **metrics/traces/events**.
- **Applies to:** every request that declares a structured-output schema (JSON Schema, `10 §7`) across all providers (AD-007) and all deployment targets (`16`).

### §3 — Responsibilities
1. Compile and cache caller schemas deterministically (§9–10).
2. Detect provider structured-output capability (neutral) and select the safest viable elicitation strategy (§13–14).
3. Validate output — incrementally while streaming, and fully at completion (§19–21).
4. On violation, classify, apply conservative repair or bounded guided retry, and either return a conformant value or surface a non-conforming error (§22–26).
5. Produce the canonical, provider-neutral output model (§27).
6. Emit correctness telemetry and domain events without exposing content (§34–38).

### §4 — Out of Scope
- **Model correctness / true production escape rate** — model-dependent, `OOS-6`; SchemaLock guarantees **detection and surfacing**, not that a model is "good" (consistent with `15` TM-3).
- **Stream transport integrity** (ordering, terminal events, truncation detection) — owned by **StreamGuard** (`12 §16`); SchemaLock consumes StreamGuard's reconstructed events.
- **Whether structured output is required/allowed** (policy) — owned by the **Policy Engine** (C4, AD-019); SchemaLock enforces the schema it is given, it does not decide policy.
- **Provider selection/routing, failover** — owned by the **Provider Router** (C1, AD-007).
- **Retry execution/budget accounting** mechanics — owned by the **Retry Engine**; SchemaLock decides *whether* a retry is warranted and *what feedback* to attach (§24).
- **Persistence of audit/metering records** — owned by Audit (C10) / Metering (C5); SchemaLock emits events, it owns no database (§39–40).
- **Semantic correctness beyond the schema** (business meaning of values) — out of scope; SchemaLock validates structure/constraints the schema expresses, nothing more.

---

## B. Domain & Interfaces

### §5 — Domain model (C3 structured-output subdomain)
Pure domain types (no infrastructure, `11` hexagonal). All immutable value objects unless noted.

| Type | Kind | Description |
|---|---|---|
| `OutputSchema` | VO | Caller-declared JSON Schema (Draft 2020-12) + `schemaId` (content hash) + version |
| `CompiledSchema` | VO (immutable, shareable) | Deterministic validation plan produced from `OutputSchema`; thread-safe, cacheable |
| `StructuredOutputRequest` | VO | Neutral request context: `schemaId`, tenant/correlation ids (envelope `07 §6`), provider capability handle, mode (batch/stream) |
| `ValidationVerdict` | VO | `{conformant: bool, violations: [SchemaViolation], classification}` |
| `SchemaViolation` | VO | `{pointer (JSON Pointer), keyword, expected, actualKind}` — **no raw value content** (§37) |
| `PartialObject` | VO | Reconstructed in-progress object during streaming (§20) |
| `CanonicalOutput` | VO | Provider-neutral result (§27) |
| `FailureClass` | enum | Classification taxonomy (§25) |
| `Strategy` | enum | `NATIVE_SCHEMA` / `TOOL_CALL` / `PROMPT_CONSTRAINED` (§14) |
| `SchemaLockPolicy` | VO | Injected budgets/limits (attempts, complexity caps, timeouts) from operational baseline (`16 §I.1`) |

**Aggregate:** `StructuredOutputExecution` — the transient per-request aggregate coordinating compile→strategy→generate→validate→resolve; holds **no shared mutable state** across requests (isolation, AD-021).

### §6 — Public interfaces (ports — AD-002 hexagonal)
SchemaLock exposes **inbound ports** (driven) and depends on **outbound ports** (driving adapters), never on concrete infrastructure (`11` AU-06/AU-09).

**Inbound (called by the data-plane request pipeline):**
```
SchemaLockPort:
  compile(OutputSchema) -> CompiledSchema            // idempotent, cached
  executeStructured(StructuredOutputRequest, CompiledSchema) -> CanonicalOutput   // batch
  openStream(StructuredOutputRequest, CompiledSchema) -> StructuredStreamSession   // streaming
```
**Outbound (implemented by adapters elsewhere in the data plane / control plane):**
```
ProviderGenerationPort   // -> Provider Router (C1): generate with a Strategy; capability query
StreamSourcePort         // -> StreamGuard: reconstructed canonical stream events / partial deltas
RetryDecisionPort        // -> Retry Engine: request bounded retry with feedback
PolicyDecisionPort       // -> Policy Engine (C4): structured-output policy (required/allowed)
MeteringSinkPort         // -> Metering (C5): usage/attempt counters (events)
AuditSinkPort            // -> Audit (C10): correctness outcome events (WORM)
SchemaValidatorPort      // -> replaceable JSON Schema validator adapter (§11)
ClockPort / IdPort       // -> deterministic time/id injection (§28)
```
- **Enforcement:** ArchUnit — SchemaLock domain/application packages import **no** provider SDK, no persistence driver, no web framework (`11` AU-06/07/09/10); all outward calls go through the ports above. **Build-Fail:** any concrete infra import in SchemaLock domain/application.

---

## C. Pipeline & Lifecycle

### §7 — Internal pipeline (stages)
```
[1 Compile] -> [2 Policy check] -> [3 Capability detect] -> [4 Strategy select]
   -> [5 Generate (via Provider Router)] -> [6 Validate (incremental|completion)]
   -> [7 Resolve: conformant? -> Canonical | violation -> repair/retry | exhausted -> surface]
   -> [8 Emit telemetry + events (async, off hot path)]
```
Every stage is **non-bypassable** (AD-018): there is no code path from generation (5) to a returned value (7) that skips validation (6). This is an ArchUnit + test-enforced property (§51).

### §8 — Request lifecycle (batch + streaming)
- **Batch:** compile (cache hit O(1)) → policy → capability → strategy → generate (full) → **completion-validate** → conformant ⇒ `CanonicalOutput`; violation ⇒ classify → repair(conservative)/retry(bounded) → re-validate → conformant ⇒ output; exhausted ⇒ **surface non-conforming** (never deliver).
- **Streaming:** compile → policy → capability → strategy → openStream → consume StreamGuard-reconstructed deltas → **incrementally validate** partial object (§19) → on unrecoverable incremental violation, **abort before terminal** (never emit `stream.done` as success) → at end-of-stream, **completion-validate** the fully reconstructed object → conformant ⇒ emit terminal success + `CanonicalOutput`; else surface `stream.error` (§18, `12 §16`).
- **Ordering guarantee (with StreamGuard):** SchemaLock's completion verdict **gates** StreamGuard's terminal `stream.done`/`tool_call.done` — the terminal success event is emitted **only after** SchemaLock confirms conformance (§43). This realizes `12 §16`'s "validate before `tool_call.done`."

---

## D. Schema Handling

### §9 — Schema compilation

**SL-D6 — JSON Schema (Draft 2020-12) via a replaceable validator adapter**
- **Problem:** structured output must be validated against caller schemas; the platform standard is JSON Schema (`10 §7`).
- **Decision:** support **JSON Schema Draft 2020-12** as the structured-output schema language; compilation parses, **meta-schema-validates**, and lowers the schema to an immutable `CompiledSchema` validation plan; the actual validation engine sits behind **`SchemaValidatorPort`** — a **replaceable adapter** (AD-002), not a hard dependency.
- **Alternatives:** (a) hand-rolled validator — rejected (correctness risk, maintenance); (b) bind directly to one library — rejected (AD-002 violation, lock-in); (c) a bespoke DSL — rejected (contradicts `10 §7`).
- **Why selected:** matches the frozen schema standard, keeps the engine swappable, deterministic, and standards-conformant.
- **Trade-offs:** JSON Schema's expressivity includes constructs that are ambiguous/expensive (e.g. unconstrained `patternProperties`, deep `$ref`); mitigated by §12/SL-D8 limits.
- **Failure modes:** unsupported/ambiguous construct ⇒ `SCHEMA_INVALID` at compile (caller error, fail fast — never at generation time).
- **Security impact:** meta-schema validation + remote-`$ref` prohibition (§33) prevent schema-driven SSRF/egress; complexity caps prevent schema-bomb DoS.
- **Performance impact:** compilation is done **once** and cached (§10); hot path pays O(validate), not O(compile).
- **Enforcement:** compile rejects non-2020-12 / meta-invalid / remote-`$ref` schemas; adapter behind port (ArchUnit). **Build-Fail:** direct validator-library import in domain; acceptance of a remote-`$ref` schema.

### §10 — Schema caching

**SL-D5 — Compiled-schema cache keyed by content hash, bounded LRU, local to instance**
- **Problem:** recompiling schemas per request would blow the latency budget; the data plane is stateless/co-located (AD-020) so the cache must be local.
- **Decision:** cache `CompiledSchema` in a **bounded, in-process LRU** keyed by the schema's **content hash** (`schemaId`); entries are immutable and safely shared across virtual threads; cache is **per-instance** (no shared external store on the hot path), warmed lazily. Size and TTL come from the operational baseline (`16 §I.1`).
- **Alternatives:** (a) shared Valkey cache of compiled plans — rejected on hot path (network hop + serialization defeats the purpose; data plane favors local snapshots, AD-022); (b) unbounded cache — rejected (memory DoS); (c) no cache — rejected (latency).
- **Why selected:** O(1) hot-path lookup, local, stateless-friendly, deterministic (content-hash key), bounded memory.
- **Trade-offs:** cold-start/first-use compiles; duplicate compiles across instances (acceptable — cheap, bounded).
- **Failure modes:** cache miss ⇒ compile (bounded by SL-D8); cache full ⇒ LRU-evict (never OOM).
- **Security impact:** key is a **content hash**, not tenant identity; entries hold only the compiled *schema* (caller config, not prompt/PII); never logged (§37). No cross-tenant data beyond the shared schema definition itself; a schema is caller-provided configuration, classified per `08`.
- **Performance impact:** meets §30 budget (validate-only on hot path).
- **Enforcement:** bounded-cache config required; eviction metric exposed. **Build-Fail:** an unbounded/again-unkeyed schema cache; a compiled-schema cache reachable across tenants by tenant identity rather than content hash.

**§10.1 — Schema classification & residency *(resolves SLM-4 — additive; no security/data-model change).*** A caller schema is **tenant-governed configuration metadata**, classified and handled under the frozen models — this document introduces no new classification:
- **Classification:** schemas are treated per `08` data classification as **tenant configuration** (not model content, not secret by default, but potentially sensitive — field names can reveal a tenant's domain). They are therefore handled as **tenant-scoped, non-public** metadata: never logged (§37), never in telemetry (§35/§7.1), never cross-tenant-exposed (AD-021).
- **Residency:** compiled schemas live **only in the in-memory cache of the in-region data-plane instance** that serves the tenant (§10). They are **residency-confined** by construction — SchemaLock holds no cross-region schema store and ships schemas nowhere (`08` DA-D2, `16` D-051). A schema for an EU tenant is compiled and cached only within EU-region instances serving that tenant.
- **Content-hash keying + isolation:** the cache key is a content hash; two tenants with an identical schema share one compiled artifact (a pure function of the schema text, carrying no tenant data) — this is safe and leaks nothing tenant-specific. Tenant-specific state never enters the shared cache.
- **Build-Fail:** a schema written to logs/telemetry; a compiled schema persisted or shipped outside its residency scope.

### §11 — JSON Schema support
- Supported: types, `enum`/`const`, `required`, `properties`/`additionalProperties`, numeric/string constraints, `items`/`prefixItems`, `oneOf`/`anyOf`/`allOf` (bounded depth), `$ref` to **local/bundled** definitions only, `format` (annotate + assert where safe).
- **Not supported / rejected at compile:** remote/network `$ref` (§33), unbounded recursion beyond the depth cap (§12), non-2020-12 dialects. Unsupported ⇒ `SCHEMA_INVALID` (caller error), surfaced clearly (never a runtime surprise).

### §12 — Avro compatibility (events only — not output validation)
- **Clarification (no contradiction):** **output validation uses JSON Schema**; **Avro** is used **only** for SchemaLock's **emitted events** (§38), governed by `07 §9` + Apicurio compatibility (backward/forward within a major). SchemaLock does **not** validate model output against Avro. Event schema evolution follows `16` D-033. This keeps `10 §7` (JSON Schema for AI output, Avro for events) intact.

---

## E. Provider-Neutral Strategy

### §13 — Provider capability detection

**SL-D2 — Capability-driven strategy selection (safest viable strategy wins)**
- **Problem:** providers differ wildly in structured-output support; SchemaLock must be provider-neutral (AD-007) yet exploit native enforcement where available.
- **Decision:** SchemaLock queries a **provider-neutral capability descriptor** (via `ProviderGenerationPort`, sourced from the C1 provider adapter's capability metadata) describing: native-schema support, tool-calling support, max schema complexity, streaming support. From this it selects a `Strategy` (§14). Capability is expressed **neutrally** — SchemaLock never inspects provider-native fields (`12 §16.10`).
- **Alternatives:** (a) assume a lowest-common-denominator (always prompt-constrained) — rejected (wastes native enforcement, higher escape risk); (b) provider-specific branches in SchemaLock — rejected (AD-007 violation, coupling).
- **Why selected:** neutral, future-proof (new providers = new capability descriptor, no SchemaLock change), maximizes correctness.
- **Trade-offs:** relies on capability descriptors being accurate (verified by conformance tests, `15` T-014/15).
- **Failure modes:** stale/incorrect capability ⇒ strategy falls back safely (§14); a provider claiming native support that fails ⇒ caught by validation ⇒ retry/surface (fail closed).
- **Security impact:** no provider-native data enters SchemaLock; capability is metadata only.
- **Performance impact:** capability lookup is O(1) cached with the provider snapshot (AD-022).
- **Enforcement:** ArchUnit AU-06 (no provider SDK in SchemaLock); conformance matrix (`15` T-015). **Build-Fail:** provider-specific branching keyed on provider identity inside SchemaLock.

**§13.1 — Capability descriptor: source, validation, invalidation *(resolves SLM-2 — additive).*** The neutral capability descriptor is **produced by the C1 provider adapter** (AD-007) and delivered to SchemaLock as part of the provider's **last-known-good snapshot** (AD-022) — SchemaLock never derives it from provider-native fields. It is:
- **Validated:** each descriptor is schema-checked (well-formed, known capability keys, sane complexity limits) before use; a malformed/unknown descriptor is treated as **least-capable** (forces `PROMPT_CONSTRAINED` or, if the schema needs native enforcement it can't get, `CAPABILITY_UNSUPPORTED` → surface). SchemaLock **fails safe on capability uncertainty**, never assumes a capability it cannot confirm.
- **Conformance-verified:** descriptor accuracy is continuously checked by the provider-conformance framework (`15 §C.1` CV-1…CV-8) — a provider that mis-declares native/tool support is caught by validation (fail-closed) and raises a drift ticket (`15 §C.1.5`).
- **Invalidated/refreshed:** the descriptor follows the config-snapshot lifecycle — it is refreshed when the provider snapshot updates (`16` D-034) and **invalidated on provider-version change** (`15 §C.1.4` freshness). A stale descriptor cannot silently persist; on snapshot loss SchemaLock uses last-known-good (AD-022) or fails safe.
- **Build-Fail:** strategy selection from an unvalidated/unknown capability descriptor without falling back to least-capable; a descriptor cached beyond the snapshot freshness window without invalidation.

### §14 — Structured-output strategy selection
Preference order (safest/highest-fidelity first), subject to capability + schema complexity:
1. **`NATIVE_SCHEMA`** — provider enforces the JSON Schema itself (§15). Preferred: highest conformance, lowest repair/retry.
2. **`TOOL_CALL`** — schema expressed as a single "response" tool's parameter schema; validate the tool-call arguments (§17).
3. **`PROMPT_CONSTRAINED`** — schema injected into the prompt as instruction; validate + guided-retry (§16). Last resort: highest escape risk (model-dependent, `OOS-6`).
- Selection is **deterministic** given (capability, schema, policy). If the schema exceeds a provider's native complexity limit, SchemaLock **downgrades** to the next viable strategy (never silently truncates the schema).

### §15 — Native structured-output providers
- SchemaLock passes the compiled schema through the neutral generation port; the provider adapter (C1) maps it to the provider's native structured-output mechanism. **Output is still validated** by SchemaLock at completion (SL-INV: trust-but-verify; a "native" provider can still drift). Conformant ⇒ canonical output; violation ⇒ retry/surface.

### §16 — Prompt-constrained providers
- Schema is injected as explicit instruction (neutral prompt-shaping via the adapter). Because the model may ignore instructions, **validation + bounded guided retry** (§24) is mandatory; on exhaustion, **surface** (never deliver unvalidated). This is the strategy where the **detection-not-model-correctness** boundary (§4, `OOS-6`) matters most — SchemaLock guarantees it will not deliver a non-conforming value, not that the model will comply.

### §17 — Tool-calling providers
- The schema becomes the parameter schema of a single canonical "respond" tool; the model's tool-call **arguments** are the structured output. SchemaLock validates the reconstructed arguments (reconstruction/transport handled by StreamGuard for streaming tool calls, §43) against the compiled schema **before** the tool-call is considered complete (`12 §16`). Multi-tool/agentic tool-calling transport is StreamGuard's concern; SchemaLock validates the *argument conformance*.

---

## F. Streaming

### §18 — Streaming structured output

**SL-D4 — Incremental validation + completion re-validation, terminal-gated**
- **Problem:** streaming must never emit a partial/invalid object as valid (`12 §16`), yet callers want progressive delivery.
- **Decision:** during streaming SchemaLock (a) consumes **StreamGuard-reconstructed deltas**, (b) maintains a **`PartialObject`** (§20), (c) runs **incremental validation** (§19) to fail fast on impossible-to-satisfy states, and (d) runs a **full completion validation** at end-of-stream; the **terminal success event is gated** on the completion verdict (§8). Partial objects are **never** labeled conformant mid-stream.
- **Alternatives:** (a) validate only at end — rejected (wastes work, late failure, larger buffers); (b) emit partials as valid — rejected (violates `12 §16`/BRULE-1); (c) SchemaLock re-implements stream reconstruction — rejected (duplicates StreamGuard, overlap risk).
- **Why selected:** fail-fast + guaranteed final conformance + clean StreamGuard boundary.
- **Trade-offs:** buffering the partial object costs memory (bounded, §31); incremental validation is best-effort (some violations only detectable at completion).
- **Failure modes:** truncation (StreamGuard-detected) ⇒ `TRUNCATED` ⇒ surface `stream.error`; incremental impossibility ⇒ abort early; completion violation ⇒ surface.
- **Security impact:** partial buffer bounded (DoS); no partial content logged (§37).
- **Performance impact:** incremental checks are O(delta); completion is O(object); within §30 streaming budget (`03` NFR-STRM).
- **Enforcement:** fault-injection tests for truncation/partial/corrupt (`15` T-012/T-018); terminal-gating test. **Build-Fail:** any path emitting `stream.done`/`tool_call.done` as success without a passed completion verdict.

### §19 — Incremental validation
- As deltas arrive, SchemaLock validates what is **decidable so far**: type of known fields, enum/const membership, constraint violations on completed scalars, and **structural impossibility** (e.g. a required-`oneOf` branch already contradicted). It **defers** checks that need the whole object (e.g. `required` completeness, cross-field `allOf`). Incremental verdicts are **advisory** (fail-fast only); the **authoritative** verdict is the completion validation (§21). Deterministic and side-effect-free.

**§19.1 — Incremental vs completion validation split (normative) *(resolves SLM-1 — additive).*** To prevent over-engineering and ambiguity, the checks are partitioned by decidability:

| Check | **Incremental (advisory, fail-fast)** | **Completion (authoritative)** |
|---|---|---|
| Type of a **completed** scalar/field | ✅ | ✅ (re-checked) |
| `enum` / `const` on a completed value | ✅ | ✅ |
| Scalar constraints (min/max, length, pattern) on completed values | ✅ | ✅ |
| `additionalProperties: false` — an unexpected key already seen | ✅ | ✅ |
| Structural impossibility (a `oneOf`/`anyOf` branch already contradicted; a completed key violating the schema) | ✅ | ✅ |
| Depth/size/field-count caps (§31) | ✅ (early fail-closed) | ✅ |
| `required` completeness | ❌ (unknowable until end) | ✅ |
| Cross-field `allOf`/`if-then-else`/dependency constraints | ❌ | ✅ |
| Array `minItems`/`uniqueItems`/`contains` | ❌ | ✅ |
| Final well-formed-object conformance | ❌ | ✅ |

- **Rule:** incremental validation **only** raises a violation it can prove *now* (a completed value that already fails, or a structural impossibility). It **never** flags an as-yet-incomplete-but-possibly-valid state. The **completion validation (§21) is authoritative and total** — it alone decides conformance (SL-INV). No conformance decision is ever made from an incremental verdict. **Build-Fail:** an incremental check that emits a violation for a still-satisfiable partial state, or a conformance decision taken before completion validation.

### §20 — Partial object reconstruction
- SchemaLock reconstructs a `PartialObject` from StreamGuard deltas **only** to enable incremental validation and final assembly. It relies on StreamGuard for **byte/stream-level** reconstruction and JSON well-formedness of deltas; SchemaLock assembles the **logical object**. Reconstruction is **bounded** (max depth/size/field-count, §31); exceeding a bound ⇒ fail closed (`RESOURCE_EXCEEDED` ⇒ surface). Never emits reconstructed partials as results.

### §21 — Completion validation
- At end-of-stream (or full batch output) SchemaLock performs the **authoritative** full validation against the `CompiledSchema`. This verdict alone decides conformance (SL-INV). Only a **passed** completion verdict yields a `CanonicalOutput` and a terminal success event.

---

## G. Violations, Repair, Retry, Errors

### §22 — Schema violations
- A `SchemaViolation` records `{JSON Pointer, keyword, expected, actualKind}` — **structural metadata only, never raw values** (§37, `14 §7.1`). Violations are collected (bounded count) and drive classification (§25), repair (§23), retry feedback (§24), telemetry (§35), and the surfaced error (§26).

### §23 — Repair strategy

**SL-D3 — Conservative repair: guided-retry over output mutation; never fabricate**
- **Problem:** a violation could sometimes be "fixed" by editing the output — but any semantic edit risks **silent incorrectness** (BRULE-1).
- **Decision:** repair is **strictly non-fabricating**. SchemaLock performs only **provably-safe, semantics-preserving normalizations** (e.g. unwrapping a provider's code-fence around JSON, trimming leading/trailing non-JSON prose when a single JSON object is unambiguously present) — and **nothing that invents, coerces, or alters values**. Anything beyond that becomes a **guided retry** (§24): re-ask the provider with the specific violations as feedback. SchemaLock **never** fills defaults, guesses enum values, or coerces types to force conformance.
- **Alternatives:** (a) aggressive coercion/defaulting — rejected (fabricates data = silent incorrectness, the exact failure the platform exists to prevent); (b) no repair at all — rejected (wastes trivially-recoverable cases like code-fenced JSON, driving needless retries/latency).
- **Why selected:** preserves correctness integrity absolutely while recovering only unambiguous, content-preserving cases.
- **Trade-offs:** more retries (latency/cost) than aggressive repair would incur — an accepted cost of correctness.
- **Failure modes:** an over-eager normalization rule could theoretically change meaning ⇒ mitigated by restricting normalization to a **small, audited, test-locked allowlist** with property-based tests proving semantics-preservation (`15` T-035).
- **Security impact:** normalization operates on already-received output; bounded; no injection surface.
- **Performance impact:** cheap normalizations avoid a full retry round-trip where safe.
- **Enforcement:** the normalization allowlist is closed and test-locked; property tests assert no value fabrication. **Build-Fail:** a repair operation that sets/coerces/defaults a value (value-fabricating repair), or a normalization outside the audited allowlist. *(Closed allow-list: §23.1.)*

### §23.1 — Repair Engine: Closed Normalization Allow-List *(resolves SLH-2 — additive; strengthens BRULE-1, weakens no invariant)*

The repair engine operates on an **explicit CLOSED allow-list**. **Only** the operations below are permitted; **anything not on this list is forbidden and fails closed** (§25 → surface). The list may be tightened, never silently extended: adding an entry requires a governed change (`16 §I.1`/`10 §16`) with new property + mutation tests proving semantics-preservation.

**ALLOWED (deterministic, structure-only, value-preserving):**
| # | Normalization | Guarantee |
|---|---|---|
| NR-1 | **Whitespace normalization** — strip insignificant leading/trailing whitespace and code-fence wrappers (```` ```json ```` … ```` ``` ````) around a single unambiguous JSON document | never alters any JSON value or key |
| NR-2 | **Newline normalization** — CRLF/CR → LF in insignificant positions | never alters string *content* semantics (only line endings outside JSON string values) |
| NR-3 | **Unicode normalization** — canonical form (NFC) of the JSON text envelope where it does not change any string value's codepoints | applied only where NFC is value-identical; otherwise skipped |
| NR-4 | **JSON escaping normalization** — normalize equivalent escape sequences to canonical JSON escaping (e.g. `A`→`A` where value-identical per JSON spec) | strictly value-preserving per RFC 8259 |
| NR-5 | **Deterministic formatting** — canonical whitespace/formatting of the parsed JSON before validation | reformatting only; no value/key/type change |

**FORBIDDEN FOREVER (value-fabricating — never permitted, no exception, not exception-waivable):**
- ❌ **Fabricate values** of any kind.
- ❌ **Infer missing fields** or add absent required properties.
- ❌ **Insert defaults** (even schema-declared `default`s — a default is not what the model produced).
- ❌ **Semantic corrections** (changing a value to "what was probably meant").
- ❌ **Hallucinated repairs** (any generative fill-in).
- ❌ **Business-rule guessing** or type coercion to force conformance.

- **Determinism & testing:** every NR-* operation is **pure and deterministic** (§28), **property-tested** (jqwik: *for any input, the parsed JSON value set is identical before and after normalization* — semantics-preservation invariant) and **mutation-tested** (`15 §G.1`, ≥85%; a mutant that makes a normalization value-altering must be killed). Golden datasets (`15` T-016) lock representative cases.
- **Unknown repair request fails closed:** if a violation is not resolvable by an NR-* normalization, the engine does **not** attempt anything else — it returns to classification (§25) → guided-retry (§24) → else **surface** (§48). There is no "creative repair" fallback.
- **Enforcement (extends SL-A6):** the allow-list is a closed enumeration in code; any repair branch outside it, or any normalization failing the semantics-preservation property test, **fails the build**. **Build-Fail:** a repair operation not in {NR-1…NR-5}; a normalization that alters any JSON value/key/type; a schema-`default` insertion presented as repair.

### §24 — Retry strategy
- SchemaLock **decides** a retry is warranted (recoverable class §25) and **requests** it via `RetryDecisionPort` (Retry Engine owns execution, budgets, backoff — `15` T-017). Retries are **bounded** (attempts from `SchemaLockPolicy`/operational baseline), **budget-capped** (≤10% retry budget, `15` T-017), and carry **violation feedback** (structural, no raw content) to improve the next attempt (esp. prompt-constrained, §16). Retries respect the **latency budget** (§30) — exhaustion ⇒ **surface**, never deliver. Idempotent per §29. *(Prompt-constrained execution envelope: §24.1.)*

### §24.1 — Prompt-Constrained Execution Envelope *(resolves SLH-3 — additive; bounds worst-case, weakens no invariant)*

Prompt-constrained providers (§16) are the retry-heaviest, highest-escape-risk path. Their execution is **hard-bounded** by an explicit envelope so worst-case latency/cost is finite and correctness is never traded for completion. All numeric values are **operational-baseline entries** (`16 §I.1`), not hard-coded (SLM-3); the values below are illustrative defaults.

| Budget | Rule | Illustrative default |
|---|---|---|
| **Maximum retry attempts** | hard cap on guided re-asks for a single structured request; independent of and bounded within the Retry Engine's ≤10% budget (`15` T-017) | ≤ 2 guided retries (3 total attempts) |
| **Retry decision criteria** | retry **only** for recoverable classes (`NON_CONFORMANT`, `MALFORMED`, `TRUNCATED`, `TIMEOUT`); **never** for `SCHEMA_INVALID`, `RESOURCE_EXCEEDED`, `CAPABILITY_UNSUPPORTED`; each retry must carry actionable structural feedback (§22) | — |
| **Latency budget** | total wall-clock for the structured request (all attempts) bounded; on projected breach, **stop and surface** rather than start another attempt | within `06 §11` + provider latency allowance |
| **Token budget** | cumulative tokens across attempts capped (cost + DoS control, mirrors `13 §13.1`); breach ⇒ stop + surface; reported to Metering (§46) | baseline-defined |
| **Timeout budget** | per-attempt timeout via `ClockPort` (§28); a timed-out attempt is a `TIMEOUT` failure, counted against attempts | baseline-defined |
| **Streaming interaction** | in streaming mode, a retry restarts a fresh stream; the prior partial is discarded (never merged/reused as valid); StreamGuard transport rules (§43.1) apply per attempt | — |
| **Provider cancellation** | on budget exhaustion, latency-breach, or client cancellation, SchemaLock **cancels the in-flight provider call** (cooperative cancellation via the generation port) to stop token spend — no orphaned generation | — |
| **Escalation path** | attempts exhausted / any budget breached → classify → **surface** neutral non-conforming error (§26) → emit correctness + audit events (§38/§47) → (if configured) the caller may fall back per its own policy; SchemaLock itself does not silently downgrade | — |

- **Cardinal rule (explicit):** **when any budget is exhausted, SchemaLock fails closed and surfaces the error. It NEVER silently relaxes validation, loosens the schema, accepts a "close enough" value, or delivers an unvalidated result.** Budget exhaustion changes *whether we keep trying*, never *whether the output must conform*.
- **Enforcement:** envelope caps read from the operational baseline; fault-injection (`15` T-018) asserts that budget exhaustion on a prompt-constrained provider yields a surfaced error with **zero silent delivery**; cancellation verified under test. **Build-Fail:** a prompt-constrained path without hard attempt/latency/token caps; any budget-exhaustion branch that relaxes validation or delivers a non-conforming value.

### §25 — Failure classification

**SL-D11 — A neutral failure taxonomy drives repair/retry/surface decisions**
- **Problem:** different failures need different responses; ad-hoc handling risks wrong recovery (e.g. retrying a caller schema error forever).
- **Decision:** every failure is classified into a **neutral `FailureClass`**:

| Class | Meaning | Recoverable? | Action |
|---|---|---|---|
| `SCHEMA_INVALID` | caller's schema is invalid | no (caller error) | fail fast at compile; surface `400`-class (`12 §12`) |
| `NON_CONFORMANT` | output parsed but violates schema | yes (bounded) | repair(safe)/guided-retry → else surface |
| `MALFORMED` | output not parseable as JSON | yes (bounded) | guided-retry → else surface |
| `TRUNCATED` | stream ended incomplete (StreamGuard) | yes (bounded) | retry → else surface |
| `CAPABILITY_UNSUPPORTED` | no viable strategy for schema+provider | maybe | downgrade strategy or surface |
| `RESOURCE_EXCEEDED` | complexity/size/depth cap hit | no | fail closed, surface |
| `PROVIDER_ERROR` / `TIMEOUT` | upstream failure | per Retry Engine | delegate (§24) |
- **Alternatives:** provider-native error codes — rejected (not neutral, `12 §16.10`).
- **Why selected:** deterministic, neutral, drives correct recovery; maps cleanly to the `12 §12` error taxonomy.
- **Trade-offs:** classification granularity vs simplicity — kept minimal and testable.
- **Failure modes:** misclassification ⇒ property/fault tests (`15` T-018) guard; default is the **most conservative** (surface).
- **Security impact:** neutral classes prevent provider-internal leakage.
- **Performance impact:** O(1) classification.
- **Enforcement:** classification table is exhaustive; unknown ⇒ fail closed. **Build-Fail:** an unclassified failure path, or a default that delivers rather than surfaces.

### §26 — Error normalization
- Surfaced errors use the **neutral error model** (`12 §12`, RFC 9457 Problem Details): a stable `type`, human-readable `title`, and **structural** violation details (JSON Pointers/keywords) — **no provider-native error text, no raw output/prompt content** (`12 §16.10`, `14 §7.1`). A non-conforming result is a **first-class, explicit** outcome (e.g. `structured_output_nonconformant`), never a `200`-with-bad-body.

### §27 — Canonical output model

**SL-D10 — Provider-neutral canonical output; zero provider leakage**
- **Problem:** the returned result must be identical in shape across providers (AD-007, `12 §16`).
- **Decision:** SchemaLock returns a `CanonicalOutput`:
```
CanonicalOutput {
  conformant: bool                 // true only if completion-validated
  value: <validated object>        // present iff conformant
  schemaId, schemaVersion
  strategyUsed: Strategy
  attempts: int
  repairApplied: bool              // safe-normalization only
  classification: FailureClass?    // present iff not conformant
  violations: [SchemaViolation]?   // structural, no raw values
}
```
- **Alternatives:** pass provider response through — rejected (leaks provider shape, breaks neutrality).
- **Why selected:** uniform contract, self-describing, audit-friendly, leak-free.
- **Trade-offs:** an extra mapping layer (cheap).
- **Failure modes:** none material; mapping is total.
- **Security impact:** guarantees no provider-native field leak (`12 §16.10`); violations carry no content.
- **Performance impact:** O(1) construction.
- **Enforcement:** conformance tests assert identical canonical shape across providers (`15` T-015); leak scanner. **Build-Fail:** a provider-native field present in `CanonicalOutput`.

---

## H. Correctness Properties

### §28 — Deterministic behavior

**SL-D7 — Pure, deterministic validation core**
- **Problem:** correctness verdicts must be reproducible and testable; non-determinism defeats mutation/property testing and audit.
- **Decision:** the validation core is a **pure function** of `(CompiledSchema, output)` — **no wall-clock, no randomness, no ambient state**; time (for timeouts) and ids come via `ClockPort`/`IdPort` (`11` R-063). Violation ordering is **stable/canonical**. Given the same provider output and schema, the verdict is identical every time.
- **Alternatives:** allow incidental nondeterminism — rejected (untestable, non-auditable).
- **Why selected:** enables property-based (`15` T-035) + mutation (`15 §G.1`) testing and reproducible audit.
- **Trade-offs:** timeouts/ids must be injected (minor ceremony).
- **Failure modes:** none; determinism is a guardrail.
- **Security/Performance impact:** neutral/positive (cache-friendly).
- **Enforcement:** ArchUnit forbids `Instant.now()`/`Math.random()` in the core (`11` R-063). **Build-Fail:** wall-clock/random in the validation core.

### §29 — Idempotency
- Validation is idempotent over its inputs (§28). The **request** carries an idempotency key (`12 §14`); a replay with the same key returns the same `CanonicalOutput` without re-invoking the provider where the prior result is retained by the request pipeline. **Note:** idempotency is over the **validation function and request handling**, not over the provider's (inherently nondeterministic) generation — SchemaLock guarantees a stable verdict for a given output, and stable request-replay semantics via the key.

**§29.1 — Replay & duplicate-stream semantics (normative) *(resolves SLM-5 — additive).*** Precise behavior for the three replay cases:
1. **Idempotency-key replay with a retained result:** SchemaLock returns the **exact prior `CanonicalOutput`** (conformant or surfaced) without re-invoking the provider — a retry/replay never re-runs generation or produces a different verdict for the same key (`12 §14`, `15` T-017). Result retention/lookup is owned by the request pipeline; SchemaLock is a pure function over the retained result.
2. **Idempotency-key replay with no retained result** (e.g. prior attempt not persisted): SchemaLock **re-executes the pipeline** (§8); because generation is nondeterministic, the new provider output may differ, but the **verdict is still authoritative and fail-closed** — a fresh validation, never a stale "assumed pass." No silent divergence: the outcome is whatever the fresh validation decides.
3. **Duplicate streams** (the same streaming request delivered twice — e.g. transport retry): each stream is validated **independently**; SchemaLock does **not** merge partials across duplicate streams (a discarded partial is never reused, §24.1). StreamGuard owns transport-level exactly-once *within* a sequence (RB-12, §43.1); across a genuinely re-issued request, the idempotency key (case 1/2) governs. A duplicate that arrives after a terminal verdict returns the retained result (case 1), never a second delivery.
- **Guarantee:** in every case the result is either the identical retained outcome or a fresh fail-closed validation — **never** a relaxed, merged, or assumed-conformant outcome. **Build-Fail:** a replay/duplicate path that returns an unvalidated or merged-partial result, or diverges from the retained result for a matching key.

---

## I. Budgets & Concurrency

### §30 — Performance budget
- SchemaLock's work must fit within the platform **added-latency budget** (`06 §11`: P50 ≤ 5 ms / P95 ≤ 20 ms / P99 ≤ 50 ms added, streaming first-token per `03` NFR-PERF-002). Hot-path costs: cache-hit compile (O(1)), validate (O(object)), canonical mapping (O(1)). **Retries and prompt-constrained round-trips add provider latency** — bounded by attempt caps (§24) and surfaced if the budget would be exceeded. Microbenchmarks (JMH) baseline the validation hot path (`15` T-026, `16 §J.2` perf-smoke). **Enforcement:** perf-smoke gate on SchemaLock hot path (`16 §E.2`/`§J.2`). **Build-Fail:** hot-path P99 regression beyond baseline.

### §31 — Memory budget
- Bounded by construction: compiled-schema LRU cap (§10); partial-object buffer cap (depth/size/field-count, §20); violation-list cap (§22). Exceeding any bound ⇒ **fail closed** (`RESOURCE_EXCEEDED` ⇒ surface), never OOM. Sized within container limits (`16` D-044, JVM heap ≤ limit, ZGC AD-023). **Build-Fail:** an unbounded buffer/cache in SchemaLock.

**§31.1 — All numeric thresholds are configurable operational baselines *(resolves SLM-3 — additive).*** Every numeric limit in this document — retry-attempt caps (§24.1), latency/token/timeout budgets (§24.1/§30), schema depth/size/ref caps (§33), partial-object depth/size/field-count caps (§20/§31), violation-list cap (§22), compiled-schema LRU size/TTL (§10), incremental-vs-completion cost bounds — is a **named entry in the per-environment `operational-baseline`** (`16 §I.1`), schema-validated and change-audited, **not a hard-coded constant**. The values shown in this document are **illustrative defaults**. Baselines may tighten freely; **loosening a value derived from a frozen guarantee** (added-latency `06 §11`, retry budget `15` T-017, escape target `03` NFR-SO) requires a recorded exception (`16` D-065) and may **never** cross the frozen floor. **Build-Fail:** a hard-coded SchemaLock threshold bypassing the operational baseline; a baseline value set beyond a frozen `03`/`06`/`15` bound.

### §32 — Concurrency model
- Runs on **Virtual Threads** (AD-023). `CompiledSchema` is **immutable and shared**; per-request state lives in the transient `StructuredOutputExecution` aggregate — **no shared mutable state across requests** (isolation, AD-021). Validation is CPU-bound: **no blocking in `synchronized`** (avoid carrier pinning, `11` R-049); locks (if any, e.g. cache) are short and non-pinning. **Enforcement:** concurrency + isolation tests (`15` T-027/T-044); pinning detection (`15` T-028). **Build-Fail:** shared mutable request state, or blocking-in-`synchronized` on the hot path.

---

## J. Security & Observability

### §33 — Security considerations

**SL-D8 — Fail-closed resource limits + remote-`$ref` prohibition (DoS & SSRF defense)**
- **Problem:** attacker-influenced schemas/outputs could cause resource exhaustion (schema/JSON bombs) or SSRF (remote `$ref`).
- **Decision:** **reject remote/network `$ref`** (local/bundled refs only) — also required for air-gap (`16 §D.1`); enforce **hard caps** on schema depth/size/ref-count and on output/partial-object depth/size/field-count; all limit breaches **fail closed** (surface). No secrets/PII/prompt/completion content in logs/metrics/traces/violations (`13 §20`, `14 §7.1`). Schemas classified as tenant config (`08`); tenant isolation absolute (AD-021).
- **Alternatives:** allow remote refs with allowlist — rejected (egress + SSRF surface, breaks air-gap); soft limits — rejected (DoS).
- **Why selected:** closes DoS/SSRF/leakage vectors deterministically.
- **Trade-offs:** callers must bundle refs (documented, standard practice).
- **Failure modes:** limit hit ⇒ `RESOURCE_EXCEEDED` surfaced (no partial delivery).
- **Security impact:** primary defensive control (STRIDE: DoS, information disclosure, SSRF).
- **Performance impact:** caps also protect latency (bounded work).
- **Enforcement:** compile rejects remote `$ref`; limit checks in hot path; content-leak scanner on telemetry (`14 §7.1`, `15` T-052). **Build-Fail:** remote-`$ref` acceptance; any content in telemetry/violations; an unbounded limit.

### §34 — Observability
- Full instrumentation per `14`: spans for each pipeline stage (§7), metrics (§35), structured logs (§37), and events (§38). **Two-tier** (`14 §5.1`): operational counters → Plane A (Prometheus, low-cardinality); high-cardinality analytical detail → Plane B. **Telemetry privacy** (`14 §7.1`): allow-list + scan; **no output/prompt content ever**.

### §35 — Metrics (SLI-bearing)
- `schemalock_conformance_rate` (per strategy) — a **correctness canary signal** (`16 §E.2`).
- `schemalock_escape_total` — **must be 0**; any non-zero = silent-incorrect-delivery indicator (first-occurrence canary abort, `16 §E.2`); a firing is a Sev-class incident (§52).
- `schemalock_repair_applied_total`, `schemalock_retry_total`, `schemalock_strategy{native|tool|prompt}`, `schemalock_validation_latency`, `schemalock_resource_exceeded_total`, `schemalock_nonconformant_surfaced_total`.
- All low-cardinality on Plane A; **no tenant/schema content in labels** (`14 §7.1`).

### §36 — Tracing
- One span per stage with neutral attributes (strategy, attempts, classification, conformant) — **never** raw values/prompts. Correlation/causation ids propagated (`07 §6`, `14`). Exemplars link latency to traces (`14`).

### §37 — Logging
- Structured, leveled, **content-free**: log ids, classification, strategy, attempt counts, violation **structure** (pointers/keywords) — **never** raw output, prompt, or values (`13 §20`, `14 §7.1`). A schema/value never appears in a log line.

### §38 — Events emitted
- Off the hot path (async, AD-005), **Avro** via Apicurio (`07 §9`), standard envelope (`07 §6`), owned by **C3** per the `06 §23` event-ownership matrix. Emitted (within C3's owned topics): structured-output **validated**, **violation-detected**, **repair-applied**, **surfaced-nonconformant**, and (should-never-fire) **escape-detected**. Payloads are **content-free** (ids, classification, strategy, counts). Consumed by Metering (C5), Audit (C10), Observability (C9). Event schema evolution follows `16` D-033. **Note:** specific event names sit inside C3's already-owned event space (`06 §23`); this document does not add a new topic owner.

---

## K. Data & Integration

### §39 — Data ownership
- SchemaLock is **stateless** (AD-020) and owns **no persistent database**. It owns only **ephemeral, in-memory** state: the compiled-schema LRU (§10) and per-request transient aggregates. Durable correctness records are owned by **Audit (C10)** and **Metering (C5)**, reached via events (§38) — consistent with store-per-service (`08`, `06 §24`). **Build-Fail:** SchemaLock acquiring a private persistent store (contradicts `06 §24`).

### §40 — Storage usage
- In-process memory only (bounded, §31). No disk, no external DB on the hot path. Optional: schema definitions themselves are caller-provided per request or referenced from the caller's config context (owned by C15 Configuration, not SchemaLock).

### §41 — Caching strategy
- Only the **compiled-schema LRU** (§10), local + bounded + content-hash-keyed. No result caching of model outputs (they are nondeterministic and potentially sensitive). Aligns with the data plane's local-snapshot philosophy (AD-022) — no hot-path network cache.

### §42 — Integration with Provider Router (C1)
- Via `ProviderGenerationPort`: SchemaLock requests generation under a chosen `Strategy` and queries neutral capability (§13). The Router owns provider selection/failover/neutralization (AD-007); SchemaLock is **provider-agnostic**. **Boundary:** Router = *which provider + neutral I/O*; SchemaLock = *schema conformance of the output*.

### §43 — Integration with StreamGuard
- Via `StreamSourcePort`: StreamGuard delivers **reconstructed canonical stream deltas** and **transport verdicts** (ordering, truncation, terminal). SchemaLock performs **logical-object assembly + schema validation** and returns a **completion verdict that gates the terminal success event** (§8). **Crisp boundary (no overlap):** StreamGuard = *stream/transport integrity + byte reconstruction + no silent truncation*; SchemaLock = *does the reconstructed object conform to the schema*. Neither re-implements the other. Both are `15 §G.1` critical modules. *(Normative contract: §43.1.)*

### §43.1 — Normative StreamGuard ↔ SchemaLock Interface Contract *(resolves SLH-1 — additive; no ownership/boundary change, only formalization of the frozen `06`/`12 §16` split)*

This is the **signed, testable division of responsibility**. It restates — it does not move — the boundary already implied by `06` (C3 vs streaming integrity) and `12 §16`. **Each responsibility has exactly one owner; neither module duplicates the other's work.**

| # | Responsibility | **Owner** | Contract at the seam |
|---|---|---|---|
| RB-1 | **Ownership boundary** | shared, by role | StreamGuard = *transport/bytes*; SchemaLock = *schema/semantics*. The seam is the **`StreamSourcePort`**: StreamGuard produces canonical deltas + transport verdicts; SchemaLock consumes them. |
| RB-2 | **Byte reconstruction** (reassembling provider stream chunks into ordered byte/char deltas) | **StreamGuard** | SchemaLock never touches raw provider bytes/chunks. |
| RB-3 | **UTF-8 validation** (decode integrity of the byte stream) | **StreamGuard** | SchemaLock receives only validly-decoded text; an invalid-encoding condition is a StreamGuard transport verdict → `MALFORMED`/`TRUNCATED` surfaced. |
| RB-4 | **Partial JSON well-formedness** (is the delta stream syntactically progressing as JSON?) | **StreamGuard** | StreamGuard guarantees each delta it emits is a syntactically valid incremental fragment and **never emits partial JSON as a complete value** (`12 §16`). SchemaLock does not re-parse for syntax below the logical-object level. |
| RB-5 | **Logical object assembly** (building the in-progress typed object from valid deltas) | **SchemaLock** | SchemaLock assembles `PartialObject` (§20) from StreamGuard's well-formed deltas; StreamGuard has no schema/type awareness. |
| RB-6 | **Schema validation** (does the assembled object conform to the compiled schema?) | **SchemaLock** | Exclusive to SchemaLock; StreamGuard performs no schema checks. |
| RB-7 | **Tool-call argument ownership** | **split at the seam** | StreamGuard = *reconstruct the tool-call argument bytes/JSON stream to well-formedness* (RB-2/3/4); SchemaLock = *validate the reconstructed arguments against the tool's parameter schema* (§17). The tool-call is "complete" only when **both** StreamGuard (transport complete) **and** SchemaLock (schema-conformant) agree (RB-9). |
| RB-8 | **Completion ownership** | **split, gated** | StreamGuard signals *transport completion* (stream ended cleanly, not truncated); SchemaLock issues the *authoritative completion verdict* (§21). **Terminal success requires both**; SchemaLock's verdict gates the terminal event (§8). |
| RB-9 | **Failure ownership** | by class | Transport failures (truncation, decode, ordering) → **StreamGuard** classifies → surfaced (`TRUNCATED`/`MALFORMED`). Schema failures (non-conformance) → **SchemaLock** classifies (§25). Neither reclassifies the other's domain. |
| RB-10 | **Retry ownership** | **SchemaLock decides, Retry Engine executes** | Neither StreamGuard nor SchemaLock loops internally; SchemaLock requests retry via `RetryDecisionPort` (§24) using the combined failure class. |
| RB-11 | **Emitted events** | by domain | StreamGuard emits transport-integrity events (its owned space); SchemaLock emits correctness events (C3, §38). No shared/duplicated event. |
| RB-12 | **Exactly-once guarantees** | **StreamGuard** | Exactly-once-per-sequence delivery of deltas is StreamGuard's guarantee (`12 §16`); SchemaLock relies on it and does not de-duplicate transport. |
| RB-13 | **Ordering guarantees** | **StreamGuard** | Per-sequence ordering is StreamGuard's guarantee; SchemaLock assembles in the delivered order and does not re-order. |

- **Non-duplication rule (normative):** *SchemaLock MUST NOT perform byte reconstruction, UTF-8 decoding, transport de-duplication, ordering, or partial-JSON syntax detection; StreamGuard MUST NOT perform schema validation, logical-object assembly, or tool-argument schema conformance.* Each capability has one owner (table above).
- **Seam is a versioned contract:** the `StreamSourcePort` delta/verdict shape is an internal contract, contract-tested both sides (`15` T-008/T-012) so a change on one side cannot silently break the other.
- **Enforcement / Build-Fail (new rule SL-A13):** ArchUnit + contract tests — a byte/UTF-8/ordering/dedup/partial-JSON-syntax operation inside SchemaLock, **or** a schema-validation/logical-assembly operation inside StreamGuard, fails the build (boundary-violation gate). A terminal success event emitted without **both** the StreamGuard transport-complete verdict **and** the SchemaLock completion verdict fails the build (extends SL-A10).

### §44 — Integration with Retry Engine
- Via `RetryDecisionPort`: SchemaLock supplies the recoverable `FailureClass` + structural feedback; the Retry Engine enforces attempts/budget/backoff (`15` T-017). SchemaLock never loops unbounded itself.

### §45 — Integration with Policy Engine (C4)
- Via `PolicyDecisionPort`: before generation, SchemaLock consults policy (PEP/PDP, AD-019) for whether structured output is **required/permitted** and any schema-governance constraints. SchemaLock **enforces** the resulting decision; it does not author policy. Governance remains **non-bypassable** (AD-018).

### §46 — Integration with Metering (C5)
- Via `MeteringSinkPort` (events): attempts, retries, and strategy usage are reported for cost/usage accounting (C5 owns metering). Content-free counters only.

### §47 — Integration with Audit (C10)
- Via `AuditSinkPort` (events): every correctness outcome (conformant / surfaced-nonconformant / escape-should-never-happen) produces a **content-free, tamper-evident** audit event (WORM + Merkle, `08 §10`, `13 §19`). Audit completeness is an invariant (RPO=0, `08 §16`).

---

## L. Failure & Recovery

### §48 — Failure modes (summary)

**SL-D9 — Fail-closed on all uncertainty (BRULE-1 realized)**
- **Problem:** any ambiguous state must resolve safely; "deliver anyway" is the cardinal sin.
- **Decision:** **every** failure/uncertainty path — malformed, truncated, non-conformant-after-retries, resource-exceeded, validator error, capability gap — resolves to **surface** (explicit non-conforming outcome), never to deliver an unvalidated value. There is no code path from "uncertain" to "returned as conformant."
- **Alternatives:** best-effort delivery — rejected (defeats the platform's purpose).
- **Why selected:** the single most important property (BRULE-1, AD-016).
- **Trade-offs:** more surfaced errors than a lenient engine (correct behavior).
- **Failure modes table:**

| Failure | Detection | Recovery |
|---|---|---|
| Malformed output | parse fail | guided-retry → surface `MALFORMED` |
| Non-conformant | validation | safe-repair/guided-retry → surface `NON_CONFORMANT` |
| Truncated stream | StreamGuard | retry → surface `TRUNCATED` (no terminal success) |
| Resource-exceeded | limit check | fail closed, surface `RESOURCE_EXCEEDED` |
| Validator adapter error | exception | fail closed, surface (never deliver), alarm |
| Capability gap | capability detect | downgrade strategy → else surface `CAPABILITY_UNSUPPORTED` |
| Provider error/timeout | Router/Retry | delegate; exhaustion → surface |
- **Security/Performance impact:** fail-closed also bounds work (no runaway).
- **Enforcement:** fault-injection suite asserts **zero silent delivery** under every injected failure (`15` T-018). **Build-Fail:** any failure branch that returns a value as conformant.

### §49 — Recovery behavior
- SchemaLock holds no durable state, so **recovery is stateless**: a restarted instance rebuilds its schema cache lazily (§10) and serves immediately (data plane fast-start, `16` D-041). In-flight requests during a deploy are drained (`16` D-031); a killed in-flight structured request is a surfaced failure to the caller (never a silent partial). Control-plane/policy unavailability ⇒ operate on last-known-good policy/capability snapshots (AD-022) or fail closed if none — never bypass (AD-018).

---

## M. Testing & Enforcement

### §50 — Testing strategy (SchemaLock is a `15 §G.1` critical module)
- **Unit (JUnit5/Mockito):** strategy selection, classification, canonical mapping, repair allowlist.
- **Property-based (jqwik, `15` T-035):** *for any schema+output, the verdict is deterministic and total*; *for any fragmented stream, the object is conformant-or-surfaced, never partial-as-valid*; *repair never fabricates a value*.
- **Fault injection (`15` T-018, per-PR):** malformed/truncated/partial/corrupt/provider-error ⇒ **zero silent delivery**; escape count = 0.
- **Golden datasets (`15` T-016):** synthetic, PII-free schema+output corpora for correctness/streaming baselines.
- **Multi-provider conformance (`15` T-014/T-015):** identical neutral behavior + canonical shape across providers; no provider-native leak.
- **Mutation (`15 §G.1`):** **≥ 85%** on SchemaLock (named critical module); Security-adjacent paths per `§J.3`.
- **Concurrency/isolation (`15` T-027/T-044):** no cross-request/tenant leakage under load; VT no-pinning (`15` T-028).
- **Performance (`15` T-021/T-026, `16 §J.2`):** hot-path within budget; perf-smoke gate.
- **Contract/event (`15` T-008/T-011):** ports + emitted-event Avro compat.

### §51 — Build-failing architectural rules
| # | Rule | Gate |
|---|---|---|
| SL-A1 | No provider SDK / provider-specific branch in SchemaLock | ArchUnit AU-06 |
| SL-A2 | No concrete infra (DB driver, web, validator lib) in domain/application | ArchUnit AU-09/10 |
| SL-A3 | No path from generate→return that skips validation (non-bypass) | ArchUnit + `15` T-018 |
| SL-A4 | No wall-clock/random in validation core | ArchUnit (`11` R-063) |
| SL-A5 | No content (output/prompt/values) in logs/metrics/traces/violations/events | `14 §7.1` scanner, `15` T-052 |
| SL-A6 | No value-fabricating repair; normalization only from audited allowlist | property test (`15` T-035) |
| SL-A7 | No remote `$ref`; hard caps on schema/output size/depth | compile check + fault test |
| SL-A8 | No shared mutable request state; no blocking-in-`synchronized` hot path | `15` T-027/T-028 |
| SL-A9 | No private persistent store (stateless, `06 §24`) | ArchUnit AU-07 |
| SL-A10 | Terminal success event never emitted without a passed completion verdict | `15` T-012 |
| SL-A11 | Mutation score ≥ 85% (critical module) | PITest (`15 §G.1`) |
| SL-A12 | `schemalock_escape_total` > 0 fails the correctness canary | `16 §E.2` |
| SL-A13 | No StreamGuard↔SchemaLock boundary violation (byte/UTF-8/ordering/dedup/partial-JSON in SchemaLock, or schema-validation/logical-assembly in StreamGuard); terminal success needs both verdicts | ArchUnit + `15` T-008/T-012 (§43.1) |
| SL-A14 | No repair outside the closed {NR-1…NR-5} allow-list; no value-altering normalization; prompt-constrained path has hard attempt/latency/token caps | property/mutation test (§23.1) + fault test (§24.1) |

---

## N. Operations

### §52 — Operational runbook
- **Escape detected (`schemalock_escape_total` > 0):** treat as a **Sev1 correctness incident** — auto-halt canary (`16 §E.2`), capture the (content-free) classification + schemaId, roll back (`16` D-030), open incident, root-cause (validator, strategy, or reconstruction). Never "accept and move on."
- **Conformance-rate drop:** investigate provider drift (`15 §C.1`), strategy downgrade, or schema change; canary auto-aborts on threshold (`16 §E.2`).
- **Retry/latency spike:** check prompt-constrained share + provider health; verify budget caps (§24/§30).
- **Resource-exceeded spike:** inspect for schema/output-bomb attempts (security, §33) + tune caps via operational baseline (`16 §I.1`).
- **Validator adapter errors:** fail-closed already protects correctness; alarm + patch adapter.

### §53 — Upgrade strategy
- SchemaLock ships within the **data-plane release train** (`16` D-014/D-055): rolling/canary, zero-downtime (`16` D-031), **correctness-signal-gated** (`16 §E.2`). Validator-adapter upgrades are contract-tested (`15` T-008) and canary-gated on conformance metrics. Schema-cache format changes are backward-safe (cache is ephemeral — rebuilt on start).

### §54 — Backward compatibility
- **Output validation contract (JSON Schema Draft 2020-12)** and the **`CanonicalOutput` shape** are stable API surfaces (`12 §27`): changes are additive/backward-compatible within a major. **Emitted events** evolve under Avro compat (`07 §9`, `16` D-033). Strategy internals may evolve freely (private). A caller's schema that validated before continues to validate identically (deterministic core, §28).

---

## O. Traceability

### §55 — Traceability matrix
| SchemaLock concern | BR | NFR | ADR | Domain/Svc | API/Event | Sec | Obs | Test | Deploy |
|---|---|---|---|---|---|---|---|---|---|
| Structured-output correctness (SL-INV, §1/§48) | BR-001/002 | NFR-SO-001 | AD-016/018 | C3 (`05`) | `12 §16/§22` | — | `14 §11` | T-018/T-013 | `16 §E.2` |
| Provider neutrality (§13–17/§27) | BR-006 | NFR-IF-001 | AD-007 | C1↔C3 (`06`) | `12 §16.10` | — | — | T-014/T-015 | — |
| Module in data plane, stateless (§39) | BR-005 | NFR-PERF | AD-020/006 | `06 §10` | — | — | — | T-051 | D-014 |
| Hexagonal ports (§6) | — | — | AD-002 | `05`/`11` | — | — | — | T-008 | — |
| Schema = JSON Schema; events = Avro (§9/§12/§38) | BR-011 | NFR-Q | AD-005/009 | — | `10 §7`,`07 §9` | — | `14 §9` | T-011 | D-033 |
| Streaming integrity boundary (§18/§43) | BR-002 | NFR-STRM-001 | AD-018 | C3↔StreamGuard | `12 §16` | — | `14 §11` | T-012 | — |
| Deterministic/idempotent (§28/§29) | BR-004 | NFR-RTY-001 | AD-016 | `11` R-063 | `12 §14` | — | — | T-035 | — |
| Performance/memory budget (§30/§31) | BR-005 | NFR-PERF-001/002 | AD-020/023 | `06 §11` | — | — | `14 §15` | T-021/T-026 | D-044/§J.2 |
| Concurrency/isolation (§32) | BR-021 | NFR-REL-002/CONC | AD-021/023 | — | — | §15 | — | T-027/T-028/T-044 | — |
| Security: caps, no remote-$ref, no content (§33/§37) | BR-018/019/021 | NFR-SEC/SO | AD-018/021 | — | `12 §16.10` | §11/§13.1/§20 | §7.1 | T-018/T-052 | §D.1 |
| Retry/repair (§23/§24/§25) | BR-004 | NFR-RTY-001 | AD-016 | Retry Engine | `12 §12/§14` | — | — | T-017 | — |
| Policy/governance (non-bypass) (§45) | BR-013 | NFR-GOV | AD-018/019 | C4 (`06`) | — | §7 | — | T-031 | — |
| Metering/Audit events (§46/§47/§38) | BR-011/024 | NFR-AUD-001 | AD-005/009 | C5/C10 | `07 §6` | §19 | §8 | T-046 | — |
| Failure classification/normalization (§25/§26) | BR-001 | NFR-SO | AD-016 | — | `12 §12` | — | — | T-018 | — |
| Testing (critical module) (§50/§51) | BR-001 | NFR-TEST-001 | AD-016 | — | — | §27 | §19 | §G.1/T-035/T-018 | §E.2 |
| Upgrade/back-compat (§53/§54) | BR-028 | NFR-UPG/VER | AD-015 | — | `12 §27` | — | — | T-049 | D-014/D-055 |
| StreamGuard↔SchemaLock contract (§43.1) | BR-002 | NFR-STRM-001 | AD-018 | C3↔StreamGuard (`06`) | `12 §16` | — | `14 §11` | T-012/T-008 | — |
| Closed repair allow-list (§23.1) | BR-001 | NFR-SO | AD-016 | — | — | — | — | T-035/§G.1 | — |
| Prompt-constrained envelope (§24.1) | BR-004 | NFR-RTY-001/PERF | AD-016 | Retry Engine | `12 §12/§14` | §13.1 | — | T-017/T-018 | §I.1 |
| Incremental/completion split (§19.1) | BR-002 | NFR-STRM-001 | AD-018 | — | `12 §16` | — | — | T-012 | — |
| Capability descriptor validation (§13.1) | BR-006 | NFR-IF-001 | AD-007/022 | C1↔C3 | `12 §16.10` | — | — | T-014/§C.1 | D-034 |
| Schema classification/residency (§10.1) | BR-021/023 | NFR-MR | AD-014/021 | — | — | §15/§20 | §7.1 | T-044/T-045 | D-051 |
| Replay/duplicate-stream semantics (§29.1) | BR-004 | NFR-RTY-001 | AD-016 | — | `12 §14` | — | — | T-017 | — |
| Numeric baselines (§31.1) | — | NFR-PERF/SO | AD-016/022 | — | — | — | §19 | — | §I.1 |

---

## P. Reviews

### Internal Consistency Review
| Frozen source | Requirement | This document | ✓ |
|---|---|---|---|
| AD-018 | non-bypassable correctness | §7/§8/§48/§51 SL-A3 (no generate→return without validate) | ✅ |
| AD-016 / BRULE-1 | reliability first / no silent incorrect delivery | SL-INV, SL-D9 fail-closed (§48) | ✅ |
| AD-007 / `12 §16.10` | provider abstraction, no leaks | §13–17/§27, SL-D2/D10 | ✅ |
| AD-020/AD-006 | data plane co-located stateless module | §39/§41/§49 (no store, stateless) | ✅ |
| AD-002 | hexagonal replaceable adapters | §6 ports, SL-D6 (validator adapter) | ✅ |
| AD-021 | tenant isolation | §32 (no shared request state), §33 | ✅ |
| AD-022 | last-known-good snapshots | §49 (policy/capability snapshots) | ✅ |
| AD-023 | Java 21 + Virtual Threads + ZGC | §32 (VT, no pinning), §31 (heap≤limit) | ✅ |
| `03` NFR-SO / NFR-STRM | escape/streaming targets | §35 (escape=0), §18–21 | ✅ |
| `06 §11` | added-latency budget | §30 | ✅ |
| `06 §23/§24` | C3 event ownership, store-per-service | §38 (C3 topics), §39 (no store) | ✅ |
| `10 §7` | JSON Schema (output) / Avro (events) | §9/§12/§38 | ✅ |
| `12 §16` | streaming contract, validate before done | §8/§18/§43 (terminal gating) | ✅ |
| `13 §20/§7.1` | no content in telemetry; DoS | §33/§37, SL-D8 | ✅ |
| `14 §5.1/§7.1/§11` | two-tier, privacy, correctness signals | §34–38 | ✅ |
| `15 §G.1` | SchemaLock critical module, mutation ≥85% | §50/§51 SL-A11 | ✅ |
| `16 §E.2` | correctness-signal canary gating | §35/§52/SL-A12 | ✅ |
| `06`/`12 §16` | C3↔StreamGuard boundary (transport vs schema) | §43.1 (RB-1…RB-13, non-duplication) | ✅ |
| `16 §I.1` | configurable operational baselines | §31.1 (all numerics) | ✅ |
| AD-014/`08` DA-D2 | residency confinement | §10.1 (in-region cache only) | ✅ |

**No contradictions found.** The additive corrective pass (§43.1, §23.1, §24.1, §19.1, §13.1, §10.1, §29.1, §31.1, SL-A13/A14) introduced **no new architecture decision, no provider coupling, no ownership change, no event/data-ownership change, no security-model change, no consistency-model change, and no deployment-model change**, and **weakened no reliability invariant** — every addition formalizes (restates precisely) a boundary, allow-list, budget, or classification already implied by the frozen documents. Verified against **00–16** and **AD-001…AD-023**: consistent.

### Architecture Validation
- **Provider neutrality (AD-007):** strategy selection remains capability-driven and neutral; §13.1 sources the capability descriptor from the C1 adapter's neutral snapshot — no provider identity branching introduced. ✅
- **Non-bypass (AD-018):** structurally impossible to return an unvalidated value (SL-A3/§48); §43.1 RB-8 + SL-A13 make terminal streaming success require **both** transport-complete and completion-verdict; §24.1 makes budget exhaustion fail closed. ✅
- **Modular monolith (AD-020):** unchanged — SchemaLock stays an in-process stateless data-plane module; §10.1 keeps schema cache in-region and in-memory (no store). ✅
- **Reliability first (AD-016/BRULE-1):** reinforced — §23.1 forbids value fabrication forever; §24.1 never relaxes validation on exhaustion; §29.1 never returns unvalidated/merged replay results. ✅
- **Clean StreamGuard boundary:** §43.1 RB-1…RB-13 + the non-duplication rule + SL-A13 formalize the seam with one owner per responsibility — no duplicated reconstruction, no gap. ✅
- **Residency/isolation (AD-014/AD-021):** §10.1 confirms schemas are tenant-governed metadata, residency-confined, never cross-tenant — no security/data-model change. ✅
- Introduces **no new architecture decision**; every addition is a formalization inside frozen C3.

### Adversarial Review (re-run after corrective pass)

**Prior High findings — resolution**
- **SLH-1 → RESOLVED (§43.1).** Normative StreamGuard↔SchemaLock contract RB-1…RB-13 pins ownership of byte reconstruction, UTF-8, partial-JSON, logical object, schema validation, tool-call arguments, completion, failure, retry, events, exactly-once, ordering; adds a **non-duplication rule** and a **build-failing boundary gate (SL-A13)**.
- **SLH-2 → RESOLVED (§23.1).** Repair engine reduced to a **closed allow-list {NR-1…NR-5}** (whitespace/newline/unicode/JSON-escaping/deterministic-formatting), an explicit **forbidden-forever** list (no fabrication/inference/defaults/semantic-correction/hallucination/business-guessing), all NR-* **property- and mutation-tested** for semantics-preservation, **unknown repairs fail closed** (SL-A14).
- **SLH-3 → RESOLVED (§24.1).** Prompt-constrained **execution envelope**: max attempts, retry criteria, latency/token/timeout budgets, streaming interaction, provider cancellation, escalation path — with the cardinal rule that **budget exhaustion fails closed and surfaces, never relaxes validation** (SL-A14).

**Prior Medium findings — resolution**
- **SLM-1 → RESOLVED (§19.1):** normative incremental-vs-completion check partition; completion validation is sole authority.
- **SLM-2 → RESOLVED (§13.1):** capability descriptor sourced from C1 neutral snapshot, validated, conformance-verified (`15 §C.1`), invalidated on version change / snapshot refresh; fail-safe to least-capable.
- **SLM-3 → RESOLVED (§31.1):** all numeric thresholds are operational-baseline entries (`16 §I.1`), never crossing frozen floors.
- **SLM-4 → RESOLVED (§10.1):** schemas classified as tenant-governed metadata, residency-confined in-region in-memory, content-hash-keyed with no tenant data in the shared cache.
- **SLM-5 → RESOLVED (§29.1):** replay/duplicate-stream semantics normatively defined — retained-result replay, no-result re-execute (fail-closed), independent duplicate streams, never merged/relaxed.

**New findings from the re-run**

**🔴 Critical:** none.
**🟠 High:** none.
**🟡 Medium:** none blocking. (Numeric values remain explicit operational-baseline entries under §31.1 — a stated, governed design choice within frozen floors; model-dependence on prompt-constrained providers remains honestly bounded as `OOS-6`, correctly handled by fail-closed surfacing.)
**🟢 Low (deferred to later standards, non-blocking):**
- **SLL-1 — Concrete JSON Schema validator adapter choice** → tooling standard (§9).
- **SLL-2 — Exact emitted-event names/topics** → reconcile with `06 §23` registry.
- **SLL-3 — JMH benchmark set for the hot path** → `benchmarks/` (§30).
- **SLL-4 — Format-keyword assertion policy** → detailed design (§11).
- **SLL-5 — NR-* allow-list default parameters & baseline values** → first-build calibration (§31.1).

### Readiness Score: **97 / 100**
All three High findings and all five Medium findings resolved additively, with no new architecture, no provider coupling, no ownership/security/consistency/deployment-model change, and no reliability invariant weakened. The StreamGuard contract, closed repair allow-list, prompt-constrained envelope, incremental/completion split, capability-descriptor lifecycle, schema classification/residency, and replay semantics are now fully specified and machine-enforceable (SL-A1…A14). The residual 3 points reflect only honest calibration items (baseline defaults) and deferred Low tooling choices — none blocking. The document faithfully and provably implements C3's flagship correctness guarantee.

### Recommendation: **FREEZE.** Critical = 0, High = 0, Medium = 0 (blocking); remaining Low items are explicitly deferred and non-blocking. No contradictions with `00`–`16` or AD-001…AD-023; no new architecture, no provider coupling, no ownership/event/data-ownership change, no security-model, consistency-model, or deployment-model change; no reliability invariant weakened.

---

*End of document — 17-SchemaLock.md · Status: FROZEN · Version: 1.0 (2026-07-21)*
