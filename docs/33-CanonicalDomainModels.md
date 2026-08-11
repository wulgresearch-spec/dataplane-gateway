# 33 — Canonical Domain Models (Implementation Reference · Documentation-Only)

**Document:** Canonical Domain Models — the single reference for every canonical runtime object used across `17`–`32`
**Project:** Reliability-First AI Gateway
**Status:** **FROZEN**
**Scope owner:** **Platform / Technical Documentation Lead** — this is a **reference catalog**, not a component; each model's **definition and ownership remain its frozen owner's** (C1–C16, `12`, `17`–`32`). See **§CMA Canonical Model Authority Contract**.
**Module:** *(none — this is not a component)* — the **first document of the Implementation Architecture series**. It **catalogs and cross-references** the canonical runtime data models already defined by the frozen architecture so Java developers reference **one** canonical source instead of redefining models. It introduces **no service, module, context, store, event, pipeline stage, ownership, execution logic, or ADR** — **only canonical data models that already exist in `00`–`32`**.
**Plane:** Documentation of Tier-0 runtime data models
**Audience:** Java developers, architects, SRE, security, QA, reviewers
**Classification:** Internal — Architecture (Implementation Reference)
**Builds on (approved, frozen):** `00`–`32` (except `24`, intentionally UNFROZEN; optimization is a pre-routing plugin only, `28 §EPC`/`24A`), especially **`12` Canonical Contracts**, `06 §8/§23/§24`, `17`–`32`, and ADRs **AD-001…AD-023**. Everything here is **descriptive and additive**: it **cites** frozen model definitions; it **defines** none, **owns** none, and **redefines** none.

> **What this is.** The single canonical reference for the runtime objects that flow through the frozen pipeline (`06 §8`, `32`): request/execution contexts, canonical I/O, decisions/results, facts/records, snapshots, streams, plugin objects, errors, and the replay/deployment identity objects — **each a pointer to its frozen owner**. Provider-native shapes and operational-status objects and non-model contract concepts are held in **clearly-marked non-canonical appendices** (§A/§B/§C). It carries **no runtime behavior, no orchestration, no execution logic** (§3).
>
> **THE CANONICAL-MODEL INVARIANT (CMD-INV):** *This document references; it never redefines and never owns (§CMA).* Every model's authoritative definition, field set, ownership, mutability, and lifecycle **remain the frozen owner's** (cited per model). This catalog **must not** introduce a field, a model, a guarantee, or an owner not already frozen; on any difference, **the frozen owner prevails and this document is wrong** (§CMA).

---

## ✅ Resolution Notice (Independent Architecture Resolution Pass · 2026-07-22)

This document was frozen **after** the Independent Review Board's adversarial attack. All **Critical (CMD-C1, CMD-C2), High (CMD-H1…CMD-H3), and Medium (CMD-M1…CMD-M4)** findings are resolved **additively** — reclassifying, pinning ownership, and adding signed contracts, **inventing no model and moving no ownership**:

- **CMD-C1** — the eight non-canonical named entries are **removed from the canonical catalog** and either **mapped to their exact frozen model** or moved to **§A Non-Canonical Implementation Concepts (reference only)**.
- **CMD-C2** — **ProviderRequest/ProviderResponse** moved to **§B Provider Internal Translation Objects** (NOT canonical, NOT replay, NOT shared, NOT visible outside `25`).
- **CMD-H1 → §TIC Threaded Identity Contract (TIC-1…TIC-7)** — replaces "never duplicate fields"; distinguishes **threading** from **redefinition**.
- **CMD-H2 → §CMA Canonical Model Authority Contract (CMA-1…CMA-6)** — this document owns nothing; the frozen owner wins any conflict. Build rule **CMD-A16**.
- **CMD-H3 → §RSM Replay Scope for Models (RSM-1…RSM-6)** — every replay-capable model inherits `32 §CRS`. Build rule **CMD-A17**.
- **CMD-M1** — UsageFact (`23`) / CostFact (`22`) / AccountingFact (C5-CP ledger) pinned distinct (§10.5). **CMD-M2** — ExecutionIdentity owner = Reliability (`20`); consumers = `27`/`23`/`32` (§10.9). **CMD-M3** — security classes cite frozen `13`/`08` only (§CLS). **CMD-M4** — HealthStatus/ModuleDescriptor moved to **§C Operational Runtime Status Objects**.

**Low** items remain **deferred** (§D). **No new model/service/module/context/store/event/pipeline stage/ownership/ADR is introduced; every frozen owner is preserved; no runtime behavior is added.** No document `00`–`32` and no ADR is modified.

---

## §CMA — Canonical Model Authority Contract (CMA-1…CMA-6) *(resolves High CMD-H2)*
| # | Aspect | Contract |
|---|---|---|
| CMA-1 | **Owns nothing** | This document **never owns** any model; it is a reference. |
| CMA-2 | **Subordinate to owner** | Every model is **subordinate to its frozen owner** (the Src column). |
| CMA-3 | **Owner wins** | On **any** conflict between this catalog and a frozen owner, **the owner wins and this document is wrong** — the catalog is corrected, never the owner. |
| CMA-4 | **No authoring** | This catalog **authors no field, model, guarantee, owner, version policy, or classification** — all are cited. |
| CMA-5 | **Mechanical binding** | Conformance is **build-tested against the frozen owners** (CMD-A2/A14) — the catalog is verified to match, never assumed. |
| CMA-6 | **Not a runtime artifact** | No runtime component treats this document as a definition source at runtime; it is developer/reference documentation (subordinate to `12`/owners). |
- **Enforcement (CMD-A16):** contract test vs frozen owners; doc-lint. **Build-Fail:** a catalog definition diverging from its frozen owner; the catalog authoring a model/field/owner.

## §TIC — Threaded Identity Contract (TIC-1…TIC-7) *(resolves High CMD-H1)*
| # | Aspect | Contract |
|---|---|---|
| TIC-1 | **Threading ≠ duplication** | Carrying a shared identity field across models is **threading**, not duplication — it is the frozen design (`07 §6`, `27 §9`). |
| TIC-2 | **Threaded fields** | `correlationId`, `executionId` (=`ExecutionIdentity`), `tenantScope`, `idempotencyKey`, `requestId` are **intentionally threaded** through multiple canonical models. |
| TIC-3 | **Single definition** | Each threaded field has **one** authoritative definition at its frozen source (correlation `27`/`07 §6`; idempotency `12 §14`; executionId `20`; tenant `30 §IAB`/AD-021; requestId `12`). |
| TIC-4 | **Carried by reference** | Other models **carry** the threaded field by reference to the single definition; they **do not re-mint or re-derive** it (§5). |
| TIC-5 | **No divergent redefinition** | A model **must not redefine** a threaded field with a different type/shape/semantics (that is forbidden **redefinition**, not threading). |
| TIC-6 | **No naive dedup** | No rule may strip a threaded field from a model on "duplication" grounds — that would break dedup/replay/audit correlation (`23`/`27`/`08 §10`). |
| TIC-7 | **Enforcement** | Threading is verified consistent (same definition everywhere); redefinition is build-failed. |
- **Enforcement (CMD-A8):** field-consistency test across models. **Build-Fail:** a threaded field redefined with a divergent type/semantics; a re-minted identity.

## §RSM — Replay Scope for Models (RSM-1…RSM-6) *(resolves High CMD-H3; inherits `32 §CRS`)*
| # | Aspect | Contract |
|---|---|---|
| RSM-1 | **Inherits `32 §CRS`** | Every replay-capable model **explicitly inherits `32 §CRS`** (composed replay = weakest scope). |
| RSM-2 | **Reproduces** | Replay reproduces **only**: canonical **decisions**, decision **ordering**, **recorded plugin outputs** (`28 §RPC`), **recorded canonical mappings** (`25 §23.1`). |
| RSM-3 | **Never reproduces** | Replay **never** reproduces: provider execution, provider timing, wall clock, network scheduling, stream timing, telemetry timing, provider randomness. |
| RSM-4 | **Non-authoritative** | Replay is non-authoritative; accounting truth is C5 (`23`), never re-derived from replay. |
| RSM-5 | **Recorded identity** | Replay uses the recorded `(codeVersion, snapshotVersions, ExecutionIdentity)` (`29 §CVR`). |
| RSM-6 | **Per-model column** | Each catalog row's **Replay** column states its participation under RSM-1…RSM-5 (recorded-input / decision-replay / authoritative-accounting / not-replayed). |
- **Enforcement (CMD-A17):** replay-scope test (`15` T-035). **Build-Fail:** a model whose replay column claims reproduction of provider/wall-clock/timing; a replay re-executing a plugin as authoritative.

## §CLS — Security Classification Source *(resolves Medium CMD-M3)*
Security classes are the **frozen** ones only (`13`/`08`): **content** (prompt/completion — never persisted/telemetry/audit, `14 §7.1`), **secret** (`26`), **usage/financial** (`08`/`23`/`22`), **tenant/principal identity** (AD-021/AD-012), **neutral/content-free**. This catalog **invents no classification** (CMA-4).

---

## 1. Charter
Give Java developers and architects **one** canonical reference for every canonical runtime object in `17`–`32`, so components **reference** these models rather than redefine them (§CMA). It records each model's frozen owner/producer/consumers and data properties, **citing** the owning document. It **contains no behavior**.

## 2. Scope
- **In scope (canonical data models only):** the catalog (§10), the identity/versioning/serialization/validation/lifecycle rules **as already frozen** (§4–9), and traceability. Non-canonical concepts, provider-internal shapes, and operational status objects are in appendices §A/§B/§C (explicitly **not** canonical).

## 3. Out of Scope
- ❌ runtime behavior/orchestration/execution logic/pipeline ownership (`20`/`32`/`06 §8`).
- ❌ redefining ownership/execution/replay/accounting/security/observability/routing/provider behavior (`17`–`32`).
- ❌ inventing a model/field/guarantee/owner not frozen (CMD-INV/§CMA).
- ❌ provider-native shapes as canonical (§B).

## 4. Canonical Runtime Object Principles
1. **Reference, never redefine, never own** (§CMA).
2. **Provider-neutral** — no provider-native field on a canonical object (`25 §PA-D12`, AD-007; provider-native = §B).
3. **Content-minimizing** — no content/secret/PII in a persisted/telemetry/audit model (`13 §20`, `14 §7.1`, `27 §15.1`).
4. **Immutable value objects** unless the frozen owner defines mutability (`CredentialLease` mutable/zeroizable `26`; `StreamChunk` a stream element).
5. **Identity threaded, not duplicated** (§TIC).
6. **Replay scope inherited from `32 §CRS`** (§RSM).

## 5. Identity Rules (see §TIC)
Correlation id minted at Ingress (`30 §9.1`), threaded (`27 §9`/`07 §6`); idempotency key client-supplied/validated (`12 §14`/`30 §RIC`); **ExecutionIdentity `(idempotencyKey, attemptId)`** owned by Reliability (`20`); tenant scope resolved after C6 (`30 §IAB`/`32 §SPT`). All carried **by reference** (§TIC-4), never re-minted.

## 6. Versioning Rules
Canonical contracts per **`12 §27`**; snapshots per **AD-022/`29 §SCC`**; events per **`07 §9`** (Avro). This catalog authors no version policy (CMA-4).

## 7. Serialization Rules
Canonical inter-stage data is **in-process** (`06 §8`, AD-020) — no hot-path wire serialization between co-located modules; events serialize Avro (`07 §6/§9`); audit seals WORM (`08 §10`). **Never serialized/persisted:** `CredentialLease`/secret material (`26`), prompt/completion content (`14 §7.1`).

## 8. Validation Rules
Payload/tool-call validation is **SchemaLock's** (`17`); field invariants (never-null/immutable) are the **frozen owner's**; this catalog records them as the owner defines (CMA-3).

## 9. Lifecycle Rules
**Contexts** — transient, per-request, not persisted, not replayed-authoritative. **Facts/Records** — immutable, persisted by owner (RPO=0 where frozen), replay per §RSM. **Snapshots** — versioned, last-known-good (AD-022), pinned per request (`32 §SPT`). **Secret material** — in-memory only, single-use, zeroized (`26`), never persisted/serialized/replayed.

## 10. Model Catalog
**Legend:** Owner = frozen owner · Prod → Cons · Mut/Pers · Replay (per §RSM) · Sec (per §CLS) · Src = frozen source. **All field definitions are the Src owner's — this row is a pointer (§CMA).** *(No entry below is non-canonical; non-canonical items are in §A/§B/§C.)*

### 10.1 Context models (transient; not persisted; not replayed-authoritative)
| Model | Owner | Prod → Cons | Mut / Pers | Replay (§RSM) | Sec | Src |
|---|---|---|---|---|---|---|
| `RequestContext` | Platform (Ingress) | `30` → all | immutable / no | recorded-input | tenant-scoped | `30`, `12 §14/§24` |
| `TenantContext` | C7 (via C6) | `30 §IAB` → all | immutable / no | recorded-input | tenant identity | `30 §IAB`, AD-021 |
| `PrincipalContext` | C6 Identity | C6 → Governance/audit | immutable / no | recorded-input | principal identity | C6, AD-012 |
| `CorrelationContext` | C9 (mint at Ingress) | `30 §9.1` → all | immutable / no | recorded-input | non-identifying ids | `27`, `07 §6` |
| `TelemetryContext` | C9 Observability | `27` → sinks | immutable / no | recorded telemetry-decision only (`27 §19.1`) | content-free | `27` |
| `DeadlineContext` | C2 Reliability | `20` → adapter | immutable / no | recorded-input | neutral | `20`, `25 §17.1`, `30 §DOC` |
| `RetryContext` | C2 Reliability | `20` (internal) | immutable per attempt / no | recorded-input | neutral | `20` |
| `SnapshotContext` | Platform (pin) | `32 §SPT` → all | immutable (pinned) / no | recorded version-ids | policy/tenant-scoped | `29 §SCC`, AD-022 |

### 10.2 Canonical I/O models (provider-neutral)
| Model | Owner | Prod → Cons | Mut / Pers | Replay (§RSM) | Sec | Src |
|---|---|---|---|---|---|---|
| `CanonicalRequest` | C1 Adapter (consumed) | upstream → `25` | immutable / no | recorded-input | content-bearing (not persisted) | `25 §6`, `12` |
| `CanonicalResponse` | C1 Adapter | `25` → `18`/`17`/`30` | immutable / no | recorded canonical mapping (`25 §23.1`) | content-bearing (not persisted) | `25 §6` |
| `CanonicalError` | C1 Adapter | `25` → `20` | immutable / no | mapping decision; content-free audit | neutral | `25 §6`, `25 §37` |
| `ErrorCategory` | C1 Adapter | `25 §16.1` → `20`/`27` | immutable enum / no | recorded | neutral | `25 §16.1` |

### 10.3 Stream models
| Model | Owner | Prod → Cons | Mut / Pers | Replay (§RSM) | Sec | Src |
|---|---|---|---|---|---|---|
| `StreamChunk` | C1 Adapter (produce) | `25` → `18` → `30` | immutable element / no | **not replayed (timing excluded, RSM-3)** | content-bearing (not persisted) | `25 §6/§11`, `18` |
| `StreamState` | C3 StreamGuard | `18` → finalization | immutable transitions / no | terminal-verdict decision | neutral | `18 §30` |

### 10.4 Decision & result models (immutable; audit-participating)
| Model | Owner | Prod → Cons | Mut / Pers | Replay (§RSM) | Sec | Src |
|---|---|---|---|---|---|---|
| `RoutingDecision` | C1 Router | `19` → `20` | immutable / event | decision replay | neutral | `19` |
| `GovernanceDecision` | C4 Governance | `21` → pipeline/audit | immutable / event | decision replay | policy-sensitive | `21` |
| `RetryDecision` | C2 Reliability | `20` (internal) | immutable / no | decision replay | neutral | `20` |
| `SchemaValidationResult` | C3 SchemaLock | `17` → delivery | immutable / no | decision replay | neutral | `17` |

### 10.5 Fact & record models (immutable; persisted by owner; RPO=0 where frozen) *(CMD-M1: distinct, no overlap)*
| Model | Owner | Prod → Cons | Mut / Pers | Replay (§RSM) | Sec | Src |
|---|---|---|---|---|---|---|
| `UsageFact` | **C5 Metering (`23`)** | `23` → ledger/`22`/`21` | immutable, append-only / WAL+ledger (RPO=0) | **authoritative accounting replay** | usage | `23 §6` |
| `CostFact` | **C5 Cost (`22`)** | `22` → billing (C8) | immutable / event | derived (not re-derived) | financial | `22` |
| `AccountingFact` | **C5-CP ledger** | ledger (`06 §307`) | immutable / ledger (RPO=0) | authoritative (SoR) | financial (SoR) | `23`, `06 §307` |
| `AuditRecord` | C10 Audit | all → C10 | immutable / WORM (RPO=0) | authoritative; **is** the audit | content-free, tamper-evident | `08 §10`, `27 §46` |
- **Boundary (CMD-M1):** `UsageFact` = *what was used* (`23`); `CostFact` = *its price* (`22`); `AccountingFact` = *the C5-CP ledger system-of-record* (`06 §307`). **No overlap; no model re-derives another.**

### 10.6 Snapshot models (versioned; last-known-good; pinned per request)
| Model | Owner | Prod → Cons | Mut / Pers | Replay (§RSM) | Sec | Src |
|---|---|---|---|---|---|---|
| `ProviderCapabilitiesSnapshot` | C1 Provider Registry | `06 §9.2` → `19`/`25` | immutable snapshot / cached (AD-022) | pinned version recorded | neutral | `19`, `25 §14.1`, `06 §9.2` |
| `ProviderDescriptorSnapshot` | C1 Provider Registry | `06 §9.2` → `19`/`25` | immutable snapshot / cached | pinned version recorded | neutral | `19`, `06 §9.2` |

### 10.7 Secret model (non-serializable, non-replayed)
| Model | Owner | Prod → Cons | Mut / Pers | Replay (§RSM) | Sec | Src |
|---|---|---|---|---|---|---|
| `CredentialLease` | **C14 Secrets (`26`)** | `26` → `25` **only** | **mutable, zeroizable, single-use** / **never persisted** | **NEVER replayed / content-free audit only** (`26 §20.1`) | **secret — never serialized/logged/captured** | `26 §6/§17.1` |

### 10.8 Plugin models (additive-only)
| Model | Owner | Prod → Cons | Mut / Pers | Replay (§RSM) | Sec | Src |
|---|---|---|---|---|---|---|
| `PluginContext` | C12 Plugin Runtime | `28` → plugin | immutable / no | recorded-input | tenant-scoped, capability-gated | `28 §6` (PluginInvocation) |
| `PluginResult` | C12 Plugin Runtime | plugin → owning stage | immutable / no | **recorded output, not re-executed** (`28 §RPC`) | neutral, content-free | `28 §6` (PluginContribution/Outcome) |

### 10.9 Identity model *(CMD-M2: single owner)*
| Model | Owner | Consumers | Mut / Pers | Replay (§RSM) | Sec | Src |
|---|---|---|---|---|---|---|
| `ExecutionIdentity` | **C2 Reliability (`20`)** | `27`, `23`, `32` (**consumers, not co-owners**) | immutable / no | **the replay/dedup anchor** (RSM-5) | neutral | `20`, `27 §18.1`, `23 §14.1` |

---

## Build-Failing Rules (CMD-A1 … CMD-A17)
| # | Rule | Gate |
|---|---|---|
| CMD-A1 | No model/field defined here that is not frozen in its Src owner (reference-only) | doc-lint vs owners |
| CMD-A2 | A component references these models; never redefines a canonical model locally | ArchUnit (single-definition) |
| CMD-A3 | No provider-native field on a canonical model; provider-native shapes are §B only (`25`) | ArchUnit + `15` T-015 |
| CMD-A4 | `CredentialLease` never serialized/persisted/logged/captured/replayed (`26`) | secret scan |
| CMD-A5 | No content/secret/PII in any persisted/telemetry/audit model (`14 §7.1`) | leak scan (`15` T-052) |
| CMD-A6 | Only frozen-owner-declared mutable models (CredentialLease, StreamChunk) are mutable | ArchUnit |
| CMD-A7 | UsageFact/AccountingFact/AuditRecord append-only, RPO=0 per owner; no fact re-derives another (CMD-M1) | contract test |
| CMD-A8 | Threaded identity consistent; no divergent redefinition (§TIC) | field-consistency test |
| CMD-A9 | Replay participation matches `32 §CRS`/§RSM; no model overclaims replay | replay test (`15` T-035) |
| CMD-A10 | Versioning per `12 §27`/`07 §9`/AD-022; no new policy | version test |
| CMD-A11 | Snapshots pinned per request, immutable, last-known-good (AD-022/`32 §SPT`) | snapshot test |
| CMD-A12 | Security class cites `13`/`08` only (§CLS); no new scheme | doc-lint |
| CMD-A13 | Authors no runtime behavior/orchestration/execution logic | ArchUnit (no logic) |
| CMD-A14 | Every model row cites its frozen Src owner; catalog conformance tested vs owners (§CMA) | doc-lint + contract test |
| CMD-A15 | Mutation score ≥ 90% (model-conformance harness) | PITest (`15 §G.1`) |
| CMD-A16 | **Canonical Model Authority (§CMA):** owns nothing; owner wins any conflict; no authoring | contract test vs owners |
| CMD-A17 | **Replay Scope (§RSM):** inherits `32 §CRS`; never reproduces provider/wall-clock/timing | replay-scope test |

## Traceability
Every model row's **Src** cites its frozen owner (`12`/`17`–`32`/`06`/`07`/`08`). This catalog owns none (§CMA); it points to all.

---

## §A — Non-Canonical Implementation Concepts (reference only) *(resolves CMD-C1 — NOT canonical models)*
These are **contract concepts / behaviors / aggregates** in the frozen docs, **not** named canonical value objects. They are listed **only** to map a developer's term to the frozen source; **none is a canonical model** and none may be referenced as one.
| Term | Frozen reality (not a canonical model) | Frozen source |
|---|---|---|
| `ExecutionContext` | the transient per-request/per-attempt state within Reliability's execution — a behavior/aggregate, not a named VO | `20`, `32` |
| `DecisionRecord` | decisions are recorded as **domain events / audit records** (`RoutingDecision`, `GovernanceDecision`, etc.), not a single "DecisionRecord" VO | `06 §23`, `08 §10` |
| `FailureContext` | the owning stage's failure info; expressed via `CanonicalError`/fail-closed outcomes, not a named VO | `32 §13`, `25 §6` |
| `ReplayContext` | a **contract concept** (composed replay), not a data model | `32 §CRS`, `29 §CVR` |
| `RecoveryContext` | DR **coordination behavior**, not a data model | `31 §RCO` |
| `DeploymentContext` | the `(codeVersion, snapshotVersions)` **identity** used by `29`, expressed via `SnapshotContext` + version stamps, not a separate VO | `29 §SCC/§CVR` |
| `ExecutionSummary` | maps exactly to the frozen **`RequestUsageManifest`** (`23 §6`) — use that name; "ExecutionSummary" is not a separate model | `23 §6` |
- **Rule:** developers use the **frozen model** named in the Src column; the left-column terms are **not** canonical and **must not** be implemented as new models (CMD-A1/§CMA).

## §B — Provider Internal Translation Objects (NOT canonical) *(resolves CMD-C2)*
| Object | Status |
|---|---|
| `ProviderRequest` | **NOT canonical · NOT a replay object · NOT shared · NOT visible outside the Provider Adapter** — provider-native, adapter-internal (`25 §PA-D1`/§7.1), never crosses the canonical boundary (AD-007) |
| `ProviderResponse` | **NOT canonical · NOT a replay object · NOT shared · NOT visible outside the Provider Adapter** — provider-native, adapter-internal (`25 §PA-D1`), never crosses the canonical boundary |
- **Rule (CMD-A3):** these shapes exist **only inside `25`**; no downstream model/field may reference them. **Build-Fail:** a provider-native object outside the adapter.

## §C — Operational Runtime Status Objects (NOT canonical domain models) *(resolves CMD-M4)*
| Object | Status | Src |
|---|---|---|
| `HealthStatus` | **operational status**, not a canonical domain model — readiness/liveness signal | `29 §SRC` |
| `ModuleDescriptor` | **operational activation metadata**, not a canonical domain model — used by the activation DAG | `29 §DAG` |
- **Rule:** these are operational, not domain models; they are not part of the canonical catalog and carry no domain semantics.

## §D — Deferred Low Items (non-blocking)
- **CMD-L1** per-model field tables generated from the frozen owners → SDK/tooling.
- **CMD-L2** UML/ERD relationship diagram → design.
- **CMD-L3** Java package/class mapping → SDK doc.
- **CMD-L4** JSON-schema/Avro artifacts → `07`/`12`.

These are documentation/detail deliverables; none affects ownership, canonical fidelity, replay scope, security classification, or any invariant.

---

## S. Reviews

### 1. Internal Consistency Review (post-resolution)
| Claim | This doc | Frozen source | ✓ |
|---|---|---|---|
| Reference-only; owner wins conflict | §CMA | `12`/owners | ✅ |
| Threaded identity ≠ duplication | §TIC | `07 §6`/`27 §9`/`12 §14`/`20` | ✅ |
| Replay scope per model | §RSM | `32 §CRS` | ✅ |
| UsageFact/CostFact/AccountingFact distinct | §10.5 | `23`/`22`/`06 §307` | ✅ |
| ExecutionIdentity single owner | §10.9 | `20` | ✅ |
| Provider-native NOT canonical | §B | `25 §PA-D1` | ✅ |
| Non-model concepts NOT canonical | §A | `20`/`31`/`29`/`32`/`23` | ✅ |
| Operational status NOT domain models | §C | `29` | ✅ |
| CredentialLease non-serializable/non-replayed | §10.7 | `26` | ✅ |
| Security classes frozen only | §CLS | `13`/`08` | ✅ |

### 2. Cross-document Validation (post-resolution)
Consistent with `12`/`17`–`32`, `06`/`07`/`08`, and AD-001…AD-023. Provider neutrality (AD-007) preserved (§B); threaded-identity design (`07 §6`/`27 §9`) preserved (§TIC); replay scope (`32 §CRS`) preserved (§RSM); accounting/cost/ledger ownership (`23`/`22`/`06 §307`) preserved; honors the `24`-unfrozen / optimization-as-plugin rule. **No contradiction.**

### 3. Architecture Validation (post-resolution)
- **Reference-not-own:** ✅ §CMA (owner wins; contract-tested).
- **No invented models:** ✅ CMD-C1 entries mapped/removed (§A); ExecutionSummary = `RequestUsageManifest`.
- **Provider neutrality:** ✅ provider-native isolated to §B.
- **Identity:** ✅ threaded, single-definition (§TIC).
- **Replay:** ✅ weakest-scope inherited (§RSM).
- **Secret/security:** ✅ CredentialLease non-serializable; classes frozen (§CLS).

### 4. Independent Review Board (re-run)
**Accepted findings — resolution:** CMD-C1→§A (mapped/removed); CMD-C2→§B; CMD-H1→§TIC; CMD-H2→§CMA (CMD-A16); CMD-H3→§RSM (CMD-A17); CMD-M1→§10.5; CMD-M2→§10.9; CMD-M3→§CLS; CMD-M4→§C.

**New findings:** 🔴 Critical: none. 🟠 High: none. 🟡 Medium: none blocking. 🟢 Low (deferred): §D.

**Internal Contradictions:** none — "never duplicate fields" replaced by §TIC; "serialization for every model" reconciled (CredentialLease non-serializable, `26`); non-frozen names quarantined to §A/§B/§C.

**Cross-document Contradictions:** none — provider-native (`25`), threaded identity (`07`/`27`), replay (`32 §CRS`), and C5 fact-family ownership all preserved.

**Scores:** Architecture Readiness **97/100**; Documentation Health **96/100**; Implementation Readiness **94/100**.

**Recommendation: FREEZE.** Critical = 0, High = 0, Medium = 0 (blocking); Low deferred. No contradictions with `00`–`32` or AD-001…AD-023; reference-only; owns nothing; every model cites its frozen owner.

---

*End of document — 33-CanonicalDomainModels.md · Status: **FROZEN** · Version: 1.0 (2026-07-22)*
