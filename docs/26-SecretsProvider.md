# 26 — Secrets Provider (Runtime Credential Materialization · Domain C14)

**Document:** Component Implementation Architecture — Secrets Provider
**Project:** Reliability-First AI Gateway
**Status:** **FROZEN**
**Bounded context:** **C14 — Secrets & Credentials** (Generic/critical, `05`/`06`)
**Module:** *(no new module)* — this document specifies **only the implementation of the frozen runtime **Secret cache (C14)** node** (`06 §1/§8`), the data-plane presence of C14, co-located per **AD-020/AD-006**. It is **not** a new service, **not** a new bounded context, **not** a new module, **not** a new pipeline stage, and **owns no store**. The identifier `dp-secrets-provider` names the credential-materialization *behavior of that frozen node*, not a new component (see **§Runtime Identity Contract, RIC-1…RIC-8**).
**Frozen name:** Secret cache (C14) — data-plane presence
**Owner:** Security (Platform) — the frozen C14 owner (`06 §9.6`) — **unchanged**
**Plane:** Tier-0 Runtime (security-critical)
**Audience:** Security/secrets/platform/data-plane engineers, SRE, cryptography, compliance, QA, reviewers
**Classification:** Internal — Component Architecture
**Builds on (approved, frozen):** `00`–`25`, `24A-Architecture-Reconciliation` (informational), and ADRs **AD-001…AD-023**. Everything here is **additive**. Introduces **no new architecture decision, no new bounded context, no new service, no new module, no new store, no new ownership, no new pipeline stage, no provider-specific logic, and no credential persistence.** Every SP-Dx is a *component-internal implementation decision* inside the already-frozen C14 data-plane presence, expressed through hexagonal ports (AD-002). **The C14 control plane (`06 §9.6`) retains sole ownership of the secret store, key rotation, revocation, and secret lifecycle — this document moves none of it.**

> **What this is.** The complete implementation architecture of the **runtime credential-materialization behavior** of the **frozen Secret cache (C14)** node — the component that **retrieves short-lived credential material** from the **frozen C14 cached snapshot** (`06 §9.6`, AD-022), **materializes** a per-invocation credential **in memory only**, **supplies it exclusively to the Provider Adapter** (`25 §7 CredentialPort`/`§33.1`), then **sanitizes** it (§Memory Sanitization Contract). It **owns no credential store and persists nothing.** It is the DP-side realization of the "existing Secrets mechanism" that `25 §33.1` already depends on.
>
> **THE SECRETS-PROVIDER INVARIANT (SP-INV):** *The Secrets Provider shall never — persist credentials · log credentials · serialize credentials · cache beyond policy · cross tenants · reuse expired credentials · materialize unauthorized credentials · guess missing credentials · recover invalid credentials · share credentials across requests.* On any uncertainty it **fails closed** — it surfaces `CredentialUnavailable` and materializes nothing. This is the credential-layer realization of **AD-012 (zero-trust)**, **AD-021 (tenant isolation)**, and **AD-022 (cached snapshots, no hot-path control-plane call)**.

---

## ✅ Resolution Notice (Independent Architecture Resolution Pass · 2026-07-22)

This document was frozen **after** the Independent Review Board's adversarial attack. All **Critical (SP-C1, SP-C2)**, **High (SP-H1, SP-H2, SP-H3)**, and **Medium** findings are resolved **additively** through binding contracts:

- **§17.1 Memory Sanitization Contract (MSC-1…MSC-10)** — resolves SP-C1 (zeroization overclaim vs ZGC/AD-023). Build-enforced by **SP-A16**.
- **§Runtime Identity Contract (RIC-1…RIC-8)** — resolves SP-C2 (module identity pinned to the frozen Secret-cache node). Build-enforced by **SP-A17**.
- **§20.1 Credential Redaction Contract** — resolves SP-H1 (no credential in replay/trace/log/crash-dump/debug/provider capture). Build-enforced by **SP-A18**.
- **§Credential Lease Contract (CLC-1…CLC-10)** + **§11.1 Lease Lifecycle** — resolve SP-H2 (authorization boundary) and SP-H3 (lease lifecycle Acquire→Materialize→Use→Release→Zeroize→Expire). Build-enforced by **SP-A19**.
- **Medium clarifications** — master-vs-lease sanitization (§17.1 MSC-6/7), snapshot residency ownership (§13.1), credential-lifetime policy (§14.1), `auth_failed` mapping (§21.2), consistent naming (§0).

**Low** items remain **deferred** (Appendix B). **No new architecture, service, module, bounded context, ownership, store, or pipeline stage is introduced; AD-002/AD-007/AD-012/AD-020/AD-021/AD-022/AD-023 are preserved exactly; no invariant is weakened — the security posture is strengthened by stating honestly what IS and is NOT guaranteed.** No document `00`–`25` and no ADR is modified.

---

## §0 — Naming (consistent, ownership-preserving)
- **Secrets Service (C14 control plane, `06 §9.6`)** — owns the secret store, rotation, revocation, lifecycle. **Unchanged.**
- **Secret cache (C14, `06 §1/§8`)** — the frozen data-plane node holding the short-TTL credential snapshot. **This document implements its behavior.**
- **Secrets Provider / runtime materialization** — the **behavior** of the Secret-cache node specified here (retrieve → materialize → supply → sanitize). **Not** a separate component; a name for the runtime behavior (RIC-1…RIC-8).

---

## Table of Contents
**A. Charter** (§1–5) · **B. Canonical Runtime Model & Interfaces** (§6–7) · **C. Major Decisions** (SP-D1…SP-D12) · **D. Lifecycle & Materialization** (§8–11 · Lease Lifecycle §11.1) · **E. Memory, Caching, TTL, Rotation, Sanitization** (§12–17 · MSC §17.1) · **F. Concurrency & Replay** (§18–20 · Redaction §20.1) · **G. Failure Handling** (§21–22) · **H. Security** (§23) · **I. Observability** (§24–26) · **J. Testing** (§27) · **K. Operations** (§28–30) · **L. Build-Failing Rules** (SP-A1…SP-A19) · **M. Traceability** (§31) · **N. Contracts** (Runtime Identity · Credential Lease) · **O. Appendices** · **S. Reviews**

---

## A. Charter

### §1 — Purpose
The Secrets Provider **materializes short-lived provider credentials at runtime**, in memory only, for the single purpose of letting the **Provider Adapter (`25`)** authenticate one provider invocation — then **sanitizes** them (§17.1). It **retrieves** credential material from the **frozen C14 cached snapshot** (`06 §9.6`, AD-022), **never** calling the C14 control plane synchronously on the hot path. It **owns no store, persists nothing, and never talks to providers, routes, retries, prices, meters, governs, or authorizes** — those remain frozen responsibilities of C1/C2/C4/C5 and the C14 control plane.

### §2 — Scope
- **In scope (materialize/supply/sanitize only):** consume the C14 credential snapshot; materialize a per-invocation, in-memory, sanitizable credential lease; supply it **exclusively** to the Provider Adapter via the frozen credential port (`25 §7`); enforce TTL/expiry by refusal; sanitize on release; emit content-free materialization audit.
- **Applies to:** every provider invocation that requires a credential, for the already-admitted (`21`) request and the route/provider chosen upstream (`19`) and executed under Reliability (`20`).

### §3 — Responsibilities
1. **Retrieve** short-lived credential material from the **C14 cached snapshot** (`06 §9.6`, AD-022) — read-only, no synchronous C14 call on the hot path.
2. **Materialize** a per-invocation **`CredentialLease`** in memory only (sanitizable container), scoped to `(tenant, canonical route)` (CLC-1…CLC-10).
3. **Supply** the lease **only to the Provider Adapter** (`25 §7 CredentialPort`) for the active invocation — never to any other module, never serialized, never logged, never captured (§20.1).
4. **Enforce TTL/expiry** by **refusing** expired/near-expired material (fail closed); never reuse an expired credential.
5. **Sanitize** the materialized credential deterministically on lease release per the **Memory Sanitization Contract (§17.1)** — best-effort runtime zeroization with stated, honest guarantees.
6. **Emit** content-free materialization audit (C10) — the fact of materialization, **never the credential value**.
7. **Fail closed** on any uncertainty — `CredentialUnavailable`, materialize nothing (SP-INV).

### §4 — Non-Responsibilities (what the Secrets Provider NEVER does — frozen C14/other ownership)
- ❌ **own a secret/credential store** — C14 control plane (`06 §9.6`).
- ❌ **key rotation / revocation / secret lifecycle** — C14 control plane (`06 §9.6`); the SP **consumes** the result, never owns it.
- ❌ **talk to providers** — C1 Provider Adapter (`25`); the SP never makes a provider call.
- ❌ **routing / retry / failover** — C1 (`19`)/C2 (`20`).
- ❌ **pricing / metering** — C5 (`22`/`23`).
- ❌ **authorization / governance** — C4/PEP (`21`); **Governance authorizes; the SP never authorizes** (CLC boundary §N).
- ❌ **persist / log / serialize / capture / cache-beyond-policy credentials** — forbidden (SP-INV, §17.1, §20.1).

### §5 — What the Secrets Provider is NOT
| Secrets Provider (materialize/sanitize) | Not the Secrets Provider |
|---|---|
| ✓ materialize in-memory short-lived credentials | ✗ own a secret store (C14 CP `06 §9.6`) |
| ✓ consume the frozen C14 snapshot | ✗ rotate / revoke / manage lifecycle (C14 CP) |
| ✓ supply only to the Provider Adapter | ✗ call providers (Adapter `25`) |
| ✓ sanitize on release (§17.1) | ✗ persist / log / serialize / capture credentials |
| ✓ validate lease/tenant/snapshot/identity | ✗ authorize / govern / route / meter (`21`/`19`/`23`) |

---

## B. Canonical Runtime Model & Interfaces

### §6 — Canonical runtime model (credential-materialization subdomain)
Immutable value objects except the mutable, sanitizable secret container.

| Type | Kind | Description |
|---|---|---|
| `CredentialRequest` | VO (input) | The adapter's ask: `{tenantScope, routeTarget(canonical), canonicalModelId, requestCorrelationId, resolvedIdentityRef}` — carries **no** credential |
| `CredentialSnapshotRef` | VO | A read-only reference/handle into the frozen C14 cached snapshot (AD-022) — **not** the credential value |
| `CredentialLease` | **mutable, sanitizable** | The per-invocation materialized credential: `{leaseId, tenantScope, routeRef, secretMaterial:SanitizableBuffer, notBefore, notAfter, singleUse:true, state}` — **never serialized/logged/captured**, `AutoCloseable` (release ⇒ sanitize, §17.1) |
| `SanitizableBuffer` | mutable | Off-heap `ByteBuffer` / `char[]` holding secret material, explicitly overwritten on release (best-effort on ZGC — §17.1) |
| `CredentialResult` | VO | Terminal outcome: a `CredentialLease` or a fail-closed **`CredentialUnavailable{reason}`** |
| `MaterializationRecord` | VO (immutable) | **Content-free** audit record: `{leaseId, tenantScope, routeRef, outcome, snapshotVersion, timestamp}` — **never the secret** |

**Aggregate:** `MaterializationExecution` — the transient per-request aggregate coordinating retrieve → materialize → supply → sanitize; holds **no shared mutable secret state across requests** (AD-021); each request gets its **own** lease copy (§18).

### §7 — Ports (AD-002 hexagonal) — realizes the supply side of the frozen `25 §7 CredentialPort`
**Inbound (called by the Provider Adapter for the active invocation):**
```
SecretsProviderPort:
  materialize(CredentialRequest) -> CredentialLease | CredentialUnavailable   // fail-closed; scoped; single-use
  // CredentialLease is AutoCloseable: close()/release() ⇒ sanitize (§17.1)
```
**Outbound (no store, no provider SDK, no HTTP to providers):**
```
SecretSnapshotPort   // <- C14 control plane: short-TTL credential snapshot (cached, AD-022, 06 §9.6) — read-only
AuditSinkPort        // -> Audit (C10): content-free MaterializationRecord (never the credential)
TelemetryPort        // -> Observability (C9): content-free credential-materialization metrics
ClockPort            // deterministic time for TTL/expiry checks
```
- **Enforcement:** ArchUnit — the SP imports **no** provider SDK, **no** HTTP-to-provider client, **no** persistence driver/database, **no** serialization of a `CredentialLease`/`SanitizableBuffer`, **no** logging binding for secret types. The credential value is reachable **only** through the `CredentialLease` handed to the adapter and **nowhere else**.

---

## C. Major Decisions (SP-D1 … SP-D12)
*9-field template: Problem · Decision · Alternatives · Why selected · Trade-offs · Failure Modes · Security Impact · Performance Impact · Enforcement.*

### SP-D1 — Runtime materialization only (no store, no persistence)
- **Problem:** credentials must be available at invocation time without the DP owning or persisting any secret.
- **Decision:** the SP **materializes credentials in memory only**, per-invocation, from the C14 snapshot; it **owns no store and persists nothing** (no disk, no DB, no cache-at-rest, no log, no capture). The materialized value exists only for the active invocation and is sanitized on release (§17.1).
- **Alternatives:** a DP-side credential store — rejected (store ownership; C14 owns); materialize-and-hold across requests — rejected (SP-INV never-share).
- **Why selected:** minimal at-rest surface; honors C14 ownership.
- **Trade-offs:** re-materialization per invocation (cheap, §29).
- **Failure Modes:** any inability to materialize ⇒ `CredentialUnavailable`.
- **Security Impact:** no credential at rest in the DP.
- **Performance Impact:** in-memory, O(1).
- **Enforcement:** ArchUnit — no persistence/store; secret scanner (`13 §20`, `14 §7.1`) — SP-A1. **Build-Fail:** any credential persisted/serialized/logged/captured.

### SP-D2 — Snapshot consumption (cached, no synchronous C14 hot-path call)
- **Problem:** the hot path must not make a synchronous control-plane call (AD-022).
- **Decision:** the SP consumes the **frozen C14 short-TTL credential snapshot** (`06 §9.6`, AD-022) via `SecretSnapshotPort` — read-only, refreshed **out-of-band ahead of expiry** by the frozen snapshot mechanism (owned by C14, §13.1). The SP **never** calls the C14 control plane synchronously on the hot path.
- **Alternatives:** synchronous C14 fetch per request — rejected (AD-022, latency, CP-coupling).
- **Why selected:** honors AD-022; C14 outage degrades gracefully (`06 §9.6`).
- **Trade-offs:** depends on out-of-band refresh freshness.
- **Failure Modes:** stale/missing snapshot ⇒ fail closed (`06 §9.6`).
- **Security Impact:** no new CP call surface; revocation ≤ 5 min honored via TTL (`06 §9.6`).
- **Performance Impact:** cached read, O(1).
- **Enforcement:** ArchUnit — no synchronous CP call — SP-A2. **Build-Fail:** a synchronous C14 call on the hot path.

### SP-D3 — Credential TTL (consumed, not owned)
- **Problem:** credentials expire; TTL must be respected without the SP owning TTL policy.
- **Decision:** TTL/`notAfter` is **carried by the C14 snapshot** (authored by C14 CP); the SP **enforces** it by **refusing** expired/near-expired material and never reusing it — it **does not author or extend** TTL. A configurable **safety margin** (operational baseline, `16 §I.1`, §14.1) refuses material too close to expiry.
- **Alternatives:** SP-defined TTL — rejected (would own lifecycle; C14's).
- **Why selected:** enforce-not-own; matches `06 §9.6`.
- **Trade-offs:** near-expiry refusals cause a (rare) fail-safe.
- **Failure Modes:** expired/near-expired ⇒ `CredentialUnavailable`.
- **Security Impact:** no stale-credential use.
- **Performance Impact:** clock compare, O(1).
- **Enforcement:** TTL tests; `15` T-018 — SP-A3. **Build-Fail:** the SP authoring/extending TTL; reuse of an expired credential.

### SP-D4 — Rotation handling (C14 owns rotation; SP consumes the new snapshot)
- **Problem:** rotation/revocation must propagate without the SP owning them.
- **Decision:** **rotation and revocation are owned by the C14 control plane** (`06 §9.6`); on rotation, C14 publishes a **new snapshot** which the SP picks up **out-of-band**; **revocation ≤ 5 min is honored via TTL** (`06 §9.6`). The SP never rotates, revokes, or manages a credential lifecycle.
- **Alternatives:** SP-driven rotation — rejected (C14 ownership).
- **Why selected:** preserves frozen C14 ownership.
- **Trade-offs:** revocation latency bounded by TTL (frozen `06 §9.6` property, not weakened).
- **Failure Modes:** rotation-in-flight ⇒ old material valid until its TTL, then refused (fail closed).
- **Security Impact:** revocation window is the frozen ≤ 5 min TTL bound.
- **Performance Impact:** snapshot swap, O(1).
- **Enforcement:** cross-check `06 §9.6`; rotation tests — SP-A4. **Build-Fail:** the SP performing rotation/revocation/lifecycle.

### SP-D5 — Pool safety (credentials never bound to pooled connections)
- **Problem:** the Provider Adapter reuses pooled connections (`25 §31.1`); a credential must never persist on a pooled resource.
- **Decision:** the materialized credential is **per-invocation** and is applied by the adapter **per call in process memory only** (`25 §31.1 PL-3`, `25 §33.1 CR-4`); the SP's `CredentialLease` is **single-use** and **never attached to a pooled connection**. The lease is sanitized on release (§17.1), and **the adapter cannot retain it beyond the invocation** (CLC-9/§11.1).
- **Alternatives:** bind credential to a connection — rejected (`25 §31.1`; cross-tenant reuse hazard).
- **Why selected:** consistent with the frozen adapter pool contract; preserves AD-021.
- **Trade-offs:** per-invocation application (cheap).
- **Failure Modes:** a lease outliving its invocation ⇒ forced sanitization on scope exit (§11.1).
- **Security Impact:** no credential on a reusable connection.
- **Performance Impact:** O(1).
- **Enforcement:** isolation tests (`15` T-044); cross-check `25 §31.1` — SP-A5. **Build-Fail:** a credential bound to a pooled connection.

### SP-D6 — Memory sanitization (best-effort runtime zeroization; honest guarantees — see §17.1)
- **Problem:** materialized credentials must be erased from memory promptly; but the platform uses a **relocating GC (ZGC, AD-023)** where absolute erasure of every heap copy is **not achievable**.
- **Decision:** the SP performs **deterministic best-effort runtime memory sanitization** per the **Memory Sanitization Contract (§17.1)**: secrets live in **explicitly sanitizable containers** (off-heap `ByteBuffer`/`char[]`), **never** immutable `String`; mutable buffers are **overwritten immediately** on release; immutable JVM objects (if unavoidable) are **dereferenced as early as possible**; credential lifetime is **minimal**. §17.1 states precisely **what IS** and **what is NOT** guaranteed — **no overclaim** (this is *runtime memory hygiene*, distinct from *cryptographic destruction*, which is C14/KMS's, MSC-9).
- **Alternatives:** rely on GC to collect `String` secrets — rejected (no control, long residency); claim absolute zeroization — rejected (false on ZGC, AD-023).
- **Why selected:** the strongest erasure a managed runtime allows, stated honestly.
- **Trade-offs:** residual heap copies possible until GC (bounded, minimized) — disclosed (MSC-5).
- **Failure Modes:** release-not-called ⇒ scope-guarded forced sanitization (§11.1).
- **Security Impact:** minimizes in-memory credential window; no persistence/log/serialize/capture.
- **Performance Impact:** overwrite is O(len).
- **Enforcement:** ArchUnit — no `String` secret type; sanitization tests — **SP-A16**. **Build-Fail:** a secret in immutable `String`; a missing sanitize-on-release path.

### SP-D7 — Tenant isolation (AD-021; keyed by tenant; cross-tenant never)
- **Problem:** one tenant's credential must never be materialized for or leaked to another.
- **Decision:** materialization is **keyed by `(tenantScope, route)`**; a lease is bound to its tenant and **never** shared across tenants or requests (SP-INV, CLC-4). A mismatch ⇒ fail closed.
- **Alternatives:** tenant-agnostic materialization — rejected (AD-021).
- **Why selected:** absolute tenant isolation.
- **Trade-offs:** none material.
- **Failure Modes:** tenant/route mismatch ⇒ `CredentialUnavailable`.
- **Security Impact:** no cross-tenant credential exposure.
- **Performance Impact:** O(1) keyed lookup.
- **Enforcement:** isolation tests (`15` T-044) — SP-A7. **Build-Fail:** cross-tenant credential materialization/reuse.

### SP-D8 — Provider neutrality (opaque credential material; no provider-specific logic)
- **Problem:** the SP must not become provider-coupled (AD-007).
- **Decision:** the SP treats credential material as **opaque bytes keyed by canonical route**; it **never interprets provider credential format**, never branches on provider name, and never contains provider-specific logic. Provider-specific credential shaping is the **Provider Adapter's** concern (`25`).
- **Alternatives:** SP formats provider auth — rejected (provider coupling; that is `25`).
- **Why selected:** neutral, extensible.
- **Trade-offs:** the adapter applies provider-specific auth (correct).
- **Failure Modes:** unknown route ⇒ fail closed.
- **Security Impact:** no provider coupling.
- **Performance Impact:** O(1).
- **Enforcement:** ArchUnit — no provider SDK/name — SP-A8. **Build-Fail:** provider-specific logic/branch in the SP.

### SP-D9 — Replay/capture ban (credentials NEVER recorded — see §20.1)
- **Problem:** the platform supports replay/capture (`25 §23.1`); credentials must **never** enter any replay/trace/log/crash-dump/debug/provider capture.
- **Decision:** a materialized credential is **never recorded, captured, serialized, or replayed** — the **Credential Redaction Contract (§20.1)** mandates that replay/tracing/logging/crash-dumps/debug/provider captures expose **references only**, and that **provider `Authorization` headers are always redacted**. Materialization is reproducible only as the **fact** (`MaterializationRecord`, content-free), never the value.
- **Alternatives:** record credentials for exact replay — rejected (catastrophic leak).
- **Why selected:** replay reproduces decisions/facts, never secrets.
- **Trade-offs:** provider auth cannot be byte-replayed (correct).
- **Failure Modes:** none introduced.
- **Security Impact:** no credential in any durable/replayable/observable artifact.
- **Performance Impact:** none.
- **Enforcement:** ArchUnit — no serialization of secret types; redaction tests — **SP-A18**. **Build-Fail:** a credential in any capture/replay/audit/telemetry/log/crash-dump.

### SP-D10 — Concurrency (per-request lease copies; no shared mutable secret)
- **Problem:** concurrent requests must not share or race on secret material.
- **Decision:** each request receives its **own** `CredentialLease` (its own `SanitizableBuffer` copy) derived from the read-only snapshot; the **shared snapshot master is read-only** and evicted only on TTL/rotation; **per-lease sanitization never affects another request** (§17.1 MSC-6/7). Runs on Virtual Threads (AD-023); no blocking-in-`synchronized` on the hot path (`11` R-049).
- **Alternatives:** shared mutable secret buffer — rejected (races, cross-request leak, sanitization hazard).
- **Why selected:** race-free, isolation-preserving.
- **Trade-offs:** per-lease copy allocation (bounded, sanitized).
- **Failure Modes:** none from concurrency.
- **Security Impact:** no cross-request secret sharing.
- **Performance Impact:** O(len) copy per lease.
- **Enforcement:** concurrency/isolation tests (`15` T-027/T-044) — SP-A10. **Build-Fail:** a shared mutable secret buffer across requests.

### SP-D11 — Failure behavior (fail closed; never guess/recover/reuse)
- **Problem:** any credential uncertainty must never degrade into a guessed or reused credential.
- **Decision:** every failure/uncertainty ⇒ **`CredentialUnavailable`** (fail closed): missing/expired/near-expiry/revoked/unauthorized-scope/stale-snapshot/materialization-error. The SP **never guesses** a missing credential, **never recovers** an invalid one, and **never reuses** an expired one (SP-INV). Downstream, the adapter maps this to `auth_failed` (`25 §16.1`, §21.2).
- **Alternatives:** best-effort/fallback credential — rejected (SP-INV).
- **Why selected:** the core SP-INV guarantee.
- **Trade-offs:** unavailable-credential requests fail (surfaced) — deliberate.
- **Failure Modes:** all ⇒ `CredentialUnavailable`.
- **Security Impact:** no unauthorized/stale credential ever used.
- **Performance Impact:** O(1).
- **Enforcement:** fault tests (`15` T-018) — SP-A11. **Build-Fail:** a guessed/recovered/reused credential path.

### SP-D12 — Auditability (content-free materialization audit)
- **Problem:** materialization must be auditable for compliance without ever recording the secret.
- **Decision:** every materialization emits a **content-free `MaterializationRecord`** (leaseId, tenant, route, outcome, snapshot version, timestamp) to Audit (C10, WORM/Merkle `08 §10`) — **never the credential value**. Provides a tamper-evident who/when/what-scope trail (`13 §19`).
- **Alternatives:** no audit — rejected (compliance gap); audit with value — rejected (catastrophic).
- **Why selected:** auditable + zero secret exposure.
- **Trade-offs:** one content-free record per materialization (cheap).
- **Failure Modes:** audit-sink failure ⇒ frozen audit durability (RPO=0, `06 §9.9`); never blocks sanitization.
- **Security Impact:** compliance evidence without secret exposure.
- **Performance Impact:** O(1) event.
- **Enforcement:** content-free audit tests (`14 §7.1`, `15` T-052) — SP-A12. **Build-Fail:** a credential value in an audit/telemetry record.

---

## D. Lifecycle & Materialization

### §8 — Materialization lifecycle
```
CredentialRequest(tenant, route, resolvedIdentityRef)  [from Provider Adapter, 25 §7]
  → resolve snapshot (SecretSnapshotPort, C14 cached, AD-022)              [read-only]
  → CLC validation: resolved identity · lease · tenant binding · snapshot   [fail closed on any miss]
  → materialize per-invocation CredentialLease (SanitizableBuffer copy)     [in memory only]
  → supply lease to the Provider Adapter (single-use, non-retainable)       [never elsewhere]
  → adapter applies per-call (25 §31.1 PL-3), invokes provider
  → release(lease) ⇒ SANITIZE (§17.1)                                       [deterministic on scope exit]
  → content-free MaterializationRecord → Audit (C10)
```
Every step is fail-closed on uncertainty (§21).

### §9 — Materialization (see SP-D1/D10)
Per-request, in-memory, own-copy lease; no shared mutable secret; no persistence.

### §10 — Supply to the Provider Adapter
The lease is handed **only** to the adapter via the frozen credential port (`25 §7`), as an **`AutoCloseable` single-use, non-retainable** handle. The adapter applies it per call and does not bind it to a pooled connection (`25 §31.1 PL-3`) **and cannot retain it beyond the invocation** (CLC-9). No other module ever receives the lease.

### §11 — Release & scope guard
The lease is used within a bounded scope (try-with-resources / structured concurrency, AD-023); **scope exit forces `release()` ⇒ sanitization** even on exception, so a credential never outlives its invocation (§11.1/§17.1).

### §11.1 — Lease Lifecycle *(resolves High SP-H3 — additive)*
| Phase | Contract |
|---|---|
| **Acquire** | The adapter calls `materialize(CredentialRequest)`; the SP validates per CLC (§N) before any secret is touched. |
| **Materialize** | A per-invocation `CredentialLease` (own `SanitizableBuffer` copy) is created in memory only, in `state=ACTIVE`, `singleUse=true`. |
| **Use** | The adapter applies the credential **per call, in process memory** (`25 §31.1 PL-3`); the lease is **never retained, copied, serialized, logged, or captured** (SP-A18/CLC-9). |
| **Release** | On invocation completion (success or exception) the lease `close()`/`release()` runs; **scope exit forces release** — the adapter **cannot** retain a credential past the invocation. |
| **Zeroize** | Release triggers **best-effort runtime sanitization** (§17.1): the `SanitizableBuffer` is overwritten immediately; the lease transitions to `state=SANITIZED`; further use throws. |
| **Expire** | A lease whose `notAfter` (minus margin) passes before use is **refused/invalidated** (`CredentialUnavailable`); an expired lease is never used or reused (SP-D3). |
- **Cardinal rule:** **No credential survives lease completion; the Provider Adapter cannot retain credentials.**
- **Enforcement (SP-A19):** lease-lifecycle + leak tests (`15` T-044); `sp_lease_leak_detected = 0`. **Build-Fail:** a retained/re-used lease; a lease usable after `SANITIZED`; a lease not released on scope exit.

---

## E. Memory, Caching, TTL, Rotation, Sanitization

### §12 — Memory residency
Secret material resides in memory **only** as: (a) the **read-only C14 snapshot** (frozen DP Secret-cache node, evicted on TTL/rotation), and (b) **per-request lease copies** (sanitized on release). No secret is written anywhere else (no disk, no log, no serialization, no capture).

### §13 — Caching (bounded by frozen C14 policy)
The only "cache" is the **frozen C14 short-TTL credential snapshot** (`06 §9.6`, AD-022) — the SP **consumes** it and **never caches beyond that policy** (SP-INV). The SP holds **no independent credential cache**; per-request leases are **not** a cache (single-use, sanitized).

### §13.1 — Snapshot residency ownership *(resolves Medium — additive)*
The **residency window and eviction policy of the credential snapshot are owned by C14** (`06 §9.6`) and expressed as the snapshot TTL + out-of-band refresh cadence (AD-022); the SP **consumes** them and **evicts on TTL/rotation** — it authors none. "Never cache beyond policy" = the snapshot is dropped no later than its C14-authored TTL; no SP-private retention exists.

### §14 — TTL (see SP-D3)
`notAfter` from the snapshot; a safety margin refuses near-expiry material; never extended.

### §14.1 — Credential lifetime policy *(resolves Medium — additive)*
Credential lifetime is **minimal by construction**: a lease exists only for its single invocation and is sanitized at release (§11.1). The snapshot TTL and the near-expiry **safety margin** are **operational-baseline** entries (`16 §I.1`) authored in the C14/config domain — the SP consumes them (no hard-coded value, SP-A14).

### §15 — Rotation (see SP-D4)
C14 owns rotation/revocation; the SP consumes new snapshots out-of-band; revocation ≤ 5 min via TTL (`06 §9.6`).

### §16 — Snapshot freshness & staleness
A snapshot older than its TTL, or absent, ⇒ **fail closed** (`06 §9.6`) — never materialize from stale/absent material.

### §17 — Sanitization (see SP-D6 / §17.1)
On release the `SanitizableBuffer` is overwritten and dereferenced; the precise guarantee is the **Memory Sanitization Contract §17.1**.

### §17.1 — Memory Sanitization Contract (MSC-1…MSC-10) *(resolves Critical SP-C1 — additive; honest vs AD-023/ZGC)*
| # | Aspect | Contract |
|---|---|---|
| MSC-1 | **Sanitizable containers** | Secrets are held **only** in mutable, sanitizable containers — **off-heap `ByteBuffer`** (preferred) or `char[]` — **never** an immutable `String` or interned literal. |
| MSC-2 | **Immediate overwrite** | On lease release the mutable buffer is **overwritten immediately** (e.g., fill with zeros / random then zero) **before** dereference. |
| MSC-3 | **Early release of immutable objects** | Any unavoidable immutable JVM object touching secret bytes is **dereferenced as early as possible** to minimize GC-reachable lifetime. |
| MSC-4 | **Minimal lifetime** | Credential lifetime is **minimal**: single-invocation, sanitized at release (§11.1); no lingering references. |
| MSC-5 | **What is NOT guaranteed (no overclaim)** | On a **relocating GC (ZGC, AD-023)** the runtime **may retain transient copies** of a heap secret until collection; therefore **absolute erasure of every historical copy is NOT guaranteed**. Off-heap buffers (MSC-1) are **not** GC-relocated and **are** overwritten deterministically. |
| MSC-6 | **Master vs lease** | The **read-only snapshot master** is evicted on TTL/rotation (§13.1); **per-lease copies** are overwritten on release (MSC-2). Per-lease sanitization **never** affects another lease or the master; master eviction **never** leaves a live lease. |
| MSC-7 | **Isolation under concurrency** | Each request owns its buffer (SP-D10); sanitization of one lease is independent and race-free (VT-safe, AD-023). |
| MSC-8 | **No secret escapes sanitization** | No persistence, no logging, no serialization, no capture (SP-A1/A18) — so the only sanitization target is transient in-memory material. |
| MSC-9 | **Runtime hygiene ≠ cryptographic destruction** | This contract is **best-effort runtime memory sanitization**, **distinct** from **cryptographic key destruction**, which is owned by **C14/KMS** (`06 §9.6`) — the SP does not perform and does not claim cryptographic destruction. |
| MSC-10 | **Fail closed** | Any sanitization error ⇒ the request still fails closed and the incident is surfaced (`sp_zeroization{result=partial}`); a credential is never treated as safely erased when it may not be. |
- **What IS guaranteed:** off-heap secret material is **deterministically overwritten** on release; secrets are **never** in immutable `String`, **never** persisted/logged/serialized/captured; lifetime is minimal and single-use.
- **What is NOT guaranteed:** absolute erasure of every transient heap copy under ZGC relocation (MSC-5) — stated honestly, not overclaimed.
- **Enforcement (SP-A16):** ArchUnit (no `String` secret; sanitize-on-release present); sanitization + best-effort heap-absence tests. **Build-Fail:** a `String`-typed secret; a missing/late sanitization path; a secret held beyond lease release.

---

## F. Concurrency & Replay

### §18 — Concurrency & lease isolation (see SP-D10/§17.1 MSC-6/7)
Per-request own-copy leases; the shared snapshot is read-only; per-lease sanitization is independent; scope-guarded forced release. No cross-request secret sharing; VT-safe (AD-023), no hot-path `synchronized` blocking.

### §19 — Determinism (facts, not secrets)
Materialization is **not** a deterministic pure function of a secret (the secret is never a reproducible input) — only the **fact** of materialization is reproducible (`MaterializationRecord`). The TTL/scope decision logic is deterministic over `(request scope, snapshot version, injected clock)`.

### §20 — Replay (see SP-D9 / §20.1)
Credentials are **never** captured/recorded/replayed; captures hold **references only** (§20.1).

### §20.1 — Credential Redaction Contract *(resolves High SP-H1 — additive; spans the `25 §23.1` capture boundary)*
| # | Surface | Contract |
|---|---|---|
| RED-1 | **Replay** | Any replay capture (including the Provider-Adapter provider-native capture, `25 §23.1`) contains **references only** (`CredentialSnapshotRef`/`leaseId`), **never** credential material. |
| RED-2 | **Provider `Authorization` headers** | Provider request captures **always redact** the `Authorization`/credential-bearing headers **before** any recording — the redaction happens at the capture boundary; a raw auth header is **never** written to a capture. |
| RED-3 | **Tracing** | Spans carry route/outcome/version only; **never** a credential or secret-derived value. |
| RED-4 | **Logging** | No log statement may emit a credential; secret types have no loggable representation (`toString` redacted). |
| RED-5 | **Crash dumps / debug capture** | Off-heap sanitizable buffers (MSC-1) minimize crash-dump exposure; debug capture of secret types is forbidden and lint-enforced. |
| RED-6 | **Audit** | Audit records are content-free (SP-D12): fact + scope + version, **never** the value. |
- **Cross-doc note (additive, no frozen change):** `25` is frozen; this contract **adds** the redaction obligation that any credential-bearing capture path **must** satisfy — the SP **never** exposes a credential to a capture surface, and provider `Authorization` headers are redacted at the capture boundary. No frozen text is altered; the obligation is stated where the credential originates.
- **Enforcement (SP-A18):** redaction + leak-scan tests (`15` T-052, `14 §7.1`). **Build-Fail:** a credential/`Authorization` header in any replay/trace/log/crash-dump/debug/provider capture.

---

## G. Failure Handling

### §21 — Failure handling (fail closed; materialize nothing)
| Failure | Handling |
|---|---|
| Missing/absent snapshot | **`CredentialUnavailable`** (fail closed, `06 §9.6`) |
| Expired / near-expiry material | **`CredentialUnavailable`** (never reuse, SP-D3) |
| Revoked (TTL-propagated) | **`CredentialUnavailable`** (≤ 5 min bound, SP-D4) |
| Tenant/route scope mismatch | **`CredentialUnavailable`** (never cross-tenant, SP-D7) |
| Identity/lease/snapshot validation fails (CLC) | **`CredentialUnavailable`** (never materialize unauthorized, §N) |
| Materialization/sanitization error | **`CredentialUnavailable`**; forced sanitization attempted (§17.1 MSC-10) |
| Any unknown/internal error | **`CredentialUnavailable`** (fail closed) |

### §21.2 — `auth_failed` mapping *(resolves Medium — additive)*
`CredentialUnavailable` (any reason) ⇒ the Provider Adapter surfaces a **`CanonicalProviderError{category:auth_failed}`** (`25 §16.1`); Reliability (`20`) then decides retry/failover per its policy (`25 §17.1`). The SP never proceeds without a valid credential and never influences the retry decision.

### §22 — Recovery
Stateless; on restart the SP re-consumes the C14 snapshot and resumes materialization. No durable state to recover; no credential survives a restart (nothing persisted).

---

## H. Security

### §23 — Security considerations
- **No persistence / no logging / no serialization / no capture** of credentials (SP-INV; SP-D1/D9; §17.1; §20.1).
- **In-memory-only, per-request, single-use, non-retainable** leases; **sanitized on release** (§17.1, honest guarantees).
- **Tenant isolation (AD-021):** keyed by `(tenant, route)`; never cross-tenant (SP-D7).
- **Zero-trust (AD-012):** least-privilege consumer of the C14 snapshot; no long-lived secret.
- **No provider coupling (AD-007):** opaque material; no provider logic (SP-D8).
- **Revocation:** honored via the frozen ≤ 5 min TTL (`06 §9.6`); not weakened.
- **Audit (C10):** content-free materialization trail; never the value (SP-D12).
- **Enforcement:** secret/content scanner (`13 §20`, `14 §7.1`, `15` T-052); isolation tests (`15` T-044). **Build-Fail:** any credential persisted/logged/serialized/captured; cross-tenant materialization; a `String`-typed secret.

---

## I. Observability

### §24 — Posture
Full instrumentation per `14`, **content-free**, **low-cardinality**: spans per materialization (tenant scope hash, route, outcome, snapshot version), metrics (§25), logs (§26) — **never** a credential or secret-derived value.

### §25 — Metrics
- `sp_materializations_total{outcome}`, `sp_credential_unavailable_total{reason}` (snapshot/TTL/rotation health), `sp_sanitization_total{result}`, `sp_lease_leak_detected_total` (should be 0 — a lease not released within scope), `sp_materialize_latency`.
- Tenant/route labels **bounded/hashed** (top-N + `other`); **no provider-name label**; **no secret-derived label**.

### §26 — Tracing & Logging
One span per materialization; neutral attributes (route, outcome, snapshot version, TTL-margin outcome) — never content/secret (§20.1). Correlation IDs propagated (`07 §6`, `14`). **A leaked-lease alarm (`sp_lease_leak_detected`) is a security Sev.**

---

## J. Testing

### §27 — Testing strategy (maximally-critical module — `15 §G.1`)
- **Unit (JUnit5/Mockito):** materialization, CLC validation, TTL, fail-closed paths, release/sanitization invocation.
- **Property-based (jqwik, `15` T-035):** *no credential ever persisted/logged/serialized/captured*; *never reused after expiry*; *never cross-tenant*; *every lease sanitized on release*; *fail-closed on any uncertainty*; *adapter cannot retain a lease*.
- **Sanitization tests (§17.1):** post-release off-heap buffer overwritten; **best-effort heap-absence** checks (acknowledged non-absolute on ZGC, MSC-5).
- **Redaction tests (§20.1):** credentials/`Authorization` headers never in replay/trace/log/crash-dump/debug/provider capture.
- **Lease-lifecycle/leak tests (§11.1):** Acquire→Materialize→Use→Release→Zeroize→Expire; forced sanitization on exception; `sp_lease_leak_detected = 0`; use-after-sanitize throws.
- **Failure injection (`15` T-018):** missing/expired/revoked/stale/scope-invalid ⇒ `CredentialUnavailable`, zero silent materialization.
- **Isolation/concurrency (`15` T-027/T-044):** massive concurrency; per-lease isolation; no cross-tenant; per-lease sanitization independence; VT no-pinning (`15` T-028).
- **Provider-neutrality (`15` T-015):** no provider branch; opaque material.
- **Mutation (`15 §G.1`):** **≥ 90%** — a mutant that persists, logs, captures, reuses, leaks, cross-tenants, or fails to sanitize a credential must be killed.
- **Follows:** `15` Testing · `16` Deployment · `17`–`25` (esp. `25 §31.1/§33.1/§16.1/§23.1`).

---

## K. Operations

### §28 — Operational runbook
- **`sp_credential_unavailable` spike:** snapshot/TTL/rotation health — the SP correctly fails closed; **fix the C14 snapshot path, never disable the fail-closed gate**. Cascades to adapter `auth_failed` + Reliability failover — expected, protective.
- **`sp_lease_leak_detected` > 0 (any):** a materialized credential not released within scope — **security Sev**; root-cause immediately.
- **`sp_sanitization{result=partial}`:** best-effort sanitization could not confirm overwrite (MSC-10) — investigate GC/allocation; track as a security signal.
- **Snapshot staleness:** C14 out-of-band refresh lag (`06 §9.6`); fail-safe is expected — restore refresh; never bypass TTL.

### §29 — Performance
Materialization is **sub-millisecond CPU** (snapshot read + O(len) buffer copy); off the provider round-trip path; within the added-latency budget (`06 §11`). All numerics (TTL margin, window sizes, cardinality-N) are **operational-baseline** entries (`16 §I.1`).

### §30 — Upgrade & compatibility
Ships within the data-plane release train (`16` D-014/D-055): rolling/canary, zero-downtime. The `CredentialLease`/`SecretsProviderPort` shape is a stable internal contract (`12 §27`), additive within a major. Credential-snapshot schema is C14-owned; no SP redeploy to rotate a secret.

---

## L. Build-Failing Rules (SP-A1 … SP-A19)

| # | Rule | Gate |
|---|---|---|
| SP-A1 | No credential persisted/serialized/captured to any store/disk/DB | ArchUnit + secret scanner (`13 §20`) |
| SP-A2 | No synchronous C14 control-plane call on the hot path (cached snapshot only, AD-022) | ArchUnit |
| SP-A3 | No SP-authored/extended TTL; no reuse of an expired credential | TTL test (`15` T-018) |
| SP-A4 | No rotation/revocation/lifecycle in the SP (C14 owns; consume snapshot only) | cross-check `06 §9.6` |
| SP-A5 | No credential bound to a pooled connection (`25 §31.1`) | isolation test (`15` T-044) |
| SP-A6 | No secret in an immutable `String`; sanitizable container + sanitize-on-release path | ArchUnit + sanitization test |
| SP-A7 | No cross-tenant credential materialization/reuse (keyed by tenant, AD-021) | isolation test (`15` T-044) |
| SP-A8 | No provider SDK/name/provider-specific logic in the SP (AD-007) | ArchUnit + `15` T-015 |
| SP-A9 | No credential serialized/recorded/captured/replayed (audit/telemetry/trace/replay) | ArchUnit + `15` T-052 |
| SP-A10 | No shared mutable secret buffer across requests (per-lease copy) | concurrency test (`15` T-027) |
| SP-A11 | No guessed/recovered/reused credential; every uncertainty ⇒ `CredentialUnavailable` | fault test (`15` T-018) |
| SP-A12 | No credential value in any audit/telemetry/log record (content-free) | `15` T-052, `14 §7.1` |
| SP-A13 | No credential-materialization outside an admitted, tenant/route-scoped request (CLC) | scope test |
| SP-A14 | No hard-coded secret numeric; all thresholds from operational baseline (§14.1/§29) | config lint |
| SP-A15 | Mutation score ≥ 90% (maximally-critical module) | PITest (`15 §G.1`) |
| SP-A16 | **Memory sanitization (§17.1):** off-heap/`char[]` only (no `String` secret); immediate overwrite on release; no absolute-erasure overclaim; runtime hygiene ≠ crypto destruction | ArchUnit + sanitization test |
| SP-A17 | **Frozen runtime identity (§N RIC):** no new service/module/ownership/pipeline stage; implements the frozen Secret-cache (C14) node only | ArchUnit + cross-check `06 §1/§8/§9.6` |
| SP-A18 | **Credential redaction (§20.1):** no credential/`Authorization` header in any replay/trace/log/crash-dump/debug/provider capture; captures hold references only | redaction/leak test (`15` T-052) |
| SP-A19 | **Lease lifecycle (§11.1):** Acquire→Materialize→Use→Release→Zeroize→Expire; no credential survives lease completion; adapter cannot retain; use-after-sanitize throws | lease-lifecycle/leak test (`15` T-044) |

---

## M. Traceability

### §31 — Traceability matrix
| Concern | BR | NFR | ADR | Domain/Svc | Sec | Obs | Test | Deploy |
|---|---|---|---|---|---|---|---|---|
| Runtime materialization / SP-INV (SP-D1/§21) | BR-020 | NFR-SEC-SM | AD-012 | C14 (`06 §9.6`) | §23 | §25 | T-018 | `16` |
| Snapshot consumption (SP-D2/§13.1) | BR-020 | NFR-SEC/CFG | AD-022 | C14 CP (`06 §9.6`) | §23 | — | T-008 | — |
| TTL / lifetime (SP-D3/§14.1) | BR-020 | NFR-SEC-SM | AD-022 | C14 | §23 | §25 | T-018 | — |
| Rotation/revocation (SP-D4/§15) | BR-020 | NFR-SEC-SM | AD-012/022 | C14 CP (`06 §9.6`) | §23 | — | T-018 | — |
| Pool safety (SP-D5/§10) | BR-005 | NFR-SEC | AD-006/021 | C1 (`25 §31.1`) | §23 | — | T-044 | — |
| Memory sanitization (SP-D6/§17.1 MSC) | BR-020 | NFR-SEC-SM | AD-023/012 | C14 | §23 | §25 | sanitization | — |
| Tenant isolation (SP-D7/§18) | BR-021 | NFR-SEC | AD-021 | C7/C14 | §23 | — | T-044 | — |
| Provider neutrality (SP-D8/§19) | BR-006 | NFR-IF-001 | AD-007 | C1/C14 | — | — | T-015 | — |
| Replay/redaction (SP-D9/§20.1) | BR-024 | NFR-AUD/SEC | AD-012 | C10 (`25 §23.1`) | §23 | §26 | T-052 | — |
| Concurrency (SP-D10/§18) | BR-005 | NFR-PERF | AD-023/021 | C14 | §23 | — | T-027 | — |
| Failure / auth_failed (SP-D11/§21.2) | BR-020 | NFR-REL | AD-016/012 | C14/C1 (`25 §16.1`) | §23 | §25 | T-018 | — |
| Auditability (SP-D12/§26) | BR-011/024 | NFR-AUD-001 | AD-009 | C10 (`06 §9.9`) | §23 | §26 | T-052 | — |
| Runtime identity (§N RIC) | BR-005 | NFR-LAT/PERF | AD-020/006 | `06 §8` (Secret cache) | — | — | T-051 | D-014 |
| Lease lifecycle (§11.1 CLC) | BR-018/020/021 | NFR-SEC/AUTHZ | AD-019/012 | C4/C14 (`21`) | §23 | §25 | T-044 | — |

All former open seams are resolved by the Resolution Pass contracts (§11.1/§13.1/§14.1/§17.1/§20.1/§21.2, RIC, CLC) and build-enforced (SP-A16…A19).

---

## N. Contracts

### Runtime Identity Contract (RIC-1…RIC-8) *(resolves Critical SP-C2 — additive)*
| # | Aspect | Contract |
|---|---|---|
| RIC-1 | **Implements only** | This document specifies **only the implementation** of the existing frozen runtime **Secret cache (C14)** node (`06 §1/§8`). |
| RIC-2 | **No new service** | It introduces **no new service**. |
| RIC-3 | **No new module** | It introduces **no new module**; `dp-secrets-provider` names the *behavior* of the frozen node, not a component. |
| RIC-4 | **No new bounded context** | It introduces **no new bounded context**; C14 is unchanged. |
| RIC-5 | **No new ownership** | Ownership stays with **Security (Platform)** and the C14 control plane (`06 §9.6`); nothing moves. |
| RIC-6 | **No new store** | It owns **no store**; the only secret store remains C14's (`06 §9.6`). |
| RIC-7 | **No new pipeline stage** | It adds **no pipeline stage**; materialization is a supply behavior invoked by the frozen Provider-Adapter credential port (`25 §7`), not a new stage. |
| RIC-8 | **Consume-only** | The node **consumes** the C14 snapshot and **materializes/sanitizes** in memory; it authors no secret, no rotation, no lifecycle. |
- **Enforcement (SP-A17):** ArchUnit + cross-check `06 §1/§8/§9.6`. **Build-Fail:** any new service/module/context/ownership/store/pipeline-stage implied by this node.

### Credential Lease Contract (CLC-1…CLC-10) *(resolves High SP-H2 — additive; authorization boundary)*
| # | Aspect | Contract |
|---|---|---|
| CLC-1 | **Never authorizes** | The Secrets Provider **never authorizes**. **Governance (C4/PEP `21`) authorizes.** |
| CLC-2 | **Validates only** | The SP validates **only**: (a) **resolved identity** (from the admitted request), (b) **credential lease** integrity, (c) **tenant binding**, (d) **snapshot validity/TTL** — **nothing else**. |
| CLC-3 | **Precondition** | Governance admission (`21`) is a **precondition**; the SP operates only on an already-admitted request and **confines** to its resolved identity/tenant/route scope (it does not re-derive or grant authorization). |
| CLC-4 | **Tenant binding** | A lease is bound to `(tenant, route)`; a binding mismatch ⇒ `CredentialUnavailable` (never cross-tenant). |
| CLC-5 | **Scope confinement** | The SP materializes **only** the credential for the admitted resolved identity's tenant/route — never a broader or different scope. |
| CLC-6 | **No scope widening** | The SP **cannot** widen, elevate, or substitute scope; it has no authorization authority to do so. |
| CLC-7 | **Snapshot validity** | Material is used only from a valid, non-expired, non-revoked snapshot (SP-D2/D3/D4). |
| CLC-8 | **Single-use** | Each lease is single-use for one invocation (§11.1). |
| CLC-9 | **Non-retainable** | The Provider Adapter **cannot retain** the lease/credential beyond the invocation (SP-D5/§11.1). |
| CLC-10 | **Fail closed** | Any validation miss ⇒ `CredentialUnavailable`; the SP never guesses/recovers/materializes-unauthorized. |
- **Resolution of the "never materialize unauthorized vs never authorize" tension:** the SP **validates** identity/lease/tenant/snapshot and **confines to the Governance-admitted scope** — it **never authorizes**. "Never materialize unauthorized" = never materialize outside the admitted, validated scope.
- **Enforcement (SP-A13/A19):** scope + lease tests. **Build-Fail:** the SP authorizing/granting/widening scope; materializing outside the admitted tenant/route.

---

## O. Appendices

### Appendix A — Sanitization on a managed runtime (informative)
On ZGC (AD-023, relocating/generational), a heap object may be copied during collection before an explicit overwrite runs; overwrite-on-release erases the **current** buffer, not necessarily every historical copy (MSC-5). Mitigations: (a) never store secrets in immutable `String`; (b) prefer off-heap `ByteBuffer` (not GC-relocated, deterministically overwritten); (c) minimize secret lifetime/scope; (d) overwrite deterministically on release. These **minimize residency**; they are **runtime memory hygiene**, distinct from **cryptographic destruction** (C14/KMS, MSC-9).

### Appendix B — Deferred Low items (non-blocking)
- **SP-L1** — exact metric/label names → reconcile with `14`.
- **SP-L2** — off-heap allocator / secure-buffer library choice → detailed design.
- **SP-L3** — `CredentialLease` handle API shape → `12 §27` internal contract.
- **SP-L4** — heap-absence test harness (best-effort) → test tooling.

These are documentation/detail deliverables; none affects ownership, neutrality, correctness, or any invariant, and each is contract-/cross-team-testable.

---

## S. Reviews

### 1. Internal Consistency Review (post-resolution)
| Frozen source | Requirement | This document | ✓ |
|---|---|---|---|
| `06 §9.6` C14 | C14 owns store/rotation/revocation/lifecycle; DP holds short-TTL cached creds | §4/SP-D2/D4/RIC | ✅ |
| `06 §1/§8` | DP presence of C14 is the "Secret cache" node | §Module/RIC-1…RIC-8 (pinned) | ✅ |
| AD-022 | cached snapshot; no hot-path CP call | SP-D2/§13.1 | ✅ |
| AD-021 | tenant isolation | SP-D7/§18/CLC-4 | ✅ |
| AD-012 | zero-trust | §23 | ✅ |
| AD-023 (ZGC) | relocating managed GC | §17.1 MSC-1…MSC-10 (best-effort, honest guarantees) | ✅ |
| SP-INV | no persist/log/serialize/capture; sanitize | §17.1/§20.1 (best-effort stated, not overclaimed) | ✅ |
| `25 §23.1` | provider-native replay capture | §20.1 RED-1…RED-6 (references only; Authorization redacted) | ✅ |
| `25 §31.1`/`§33.1` | credential in-memory-only, not on pooled conn; not retained | SP-D5/§11.1/CLC-9 | ✅ |
| `25 §16.1` | adapter `auth_failed` category | §21.2 (CredentialUnavailable → auth_failed) | ✅ |
| `21`/AD-019 | authorization is C4/PEP | §N CLC-1…CLC-10 (SP never authorizes; validates only) | ✅ |
| AD-007 | provider neutrality | SP-D8 | ✅ |

**No contradictions remain.** The Resolution Pass (§11.1/§13.1/§14.1/§17.1/§20.1/§21.2, RIC, CLC, SP-A16…A19) is **additive only**: no new architecture, service, module, bounded context, ownership, store, or pipeline stage; AD-002/007/012/020/021/022/023 preserved exactly; SP-INV reinforced and stated honestly.

### 2. Architecture Validation (post-resolution)
- **Ownership:** ✅ store/rotation/revocation/lifecycle = C14 CP; identity/tenancy = C4/C7; the node consumes and materializes only (RIC) — **nothing moved**.
- **Boundaries:** ✅ materialize/supply/sanitize only; authorization = Governance (CLC-1).
- **Security:** ✅ honest sanitization (MSC), redaction across all capture surfaces (§20.1), tenant isolation, fail-closed — posture strengthened, not weakened.
- **Provider neutrality:** ✅ opaque material, no provider logic.
- **Performance:** ✅ sub-ms, off the provider path.
- **Failure handling:** ✅ fail-closed to `CredentialUnavailable` → adapter `auth_failed`.
- **Runtime identity:** ✅ pinned to the frozen Secret-cache node; no new service/module/stage.

### 3. Adversarial Review — Independent Review Board (re-run after resolution)
*Board: Principal Enterprise Architect · Security Architect · Cryptography & Secrets Specialist · JVM/Runtime Memory Expert · Distributed Systems Architect · Staff Reliability Engineer · Zero-Trust Architect · Compliance Auditor · Performance Engineer · CTO.*

#### Accepted findings — resolution
- **SP-C1 → RESOLVED (§17.1 MSC-1…MSC-10).** Zeroization overclaim replaced by an honest **Memory Sanitization Contract**: off-heap/`char[]` only, immediate overwrite, minimal lifetime, **what IS / what is NOT guaranteed** stated explicitly vs ZGC (AD-023), runtime hygiene distinguished from cryptographic destruction (C14/KMS). Build-enforced (SP-A16).
- **SP-C2 → RESOLVED (§N RIC-1…RIC-8).** Module identity pinned to the **frozen Secret-cache (C14)** node: no new service/module/context/ownership/store/pipeline-stage. Build-enforced (SP-A17).
- **SP-H1 → RESOLVED (§20.1 RED-1…RED-6).** Credential Redaction Contract: replay/tracing/logging/crash-dumps/debug/provider captures hold **references only**; provider `Authorization` headers **always redacted** at the capture boundary. Build-enforced (SP-A18).
- **SP-H2 → RESOLVED (§N CLC-1…CLC-10).** Authorization boundary: **Governance authorizes; the SP never authorizes**; the SP validates **only** resolved identity, lease, tenant binding, snapshot validity, and confines to the admitted scope. 
- **SP-H3 → RESOLVED (§11.1).** Lease lifecycle Acquire→Materialize→Use→Release→Zeroize→Expire; **no credential survives lease completion; the adapter cannot retain**. Build-enforced (SP-A19).
- **Medium → RESOLVED:** master-vs-lease sanitization (§17.1 MSC-6/7); snapshot residency ownership (§13.1); credential-lifetime policy (§14.1); `auth_failed` mapping (§21.2); consistent naming (§0).

#### New findings from the re-run
- **🔴 Critical:** none. **🟠 High:** none. **🟡 Medium:** none blocking. **🟢 Low (deferred):** Appendix B (metric names, off-heap allocator choice, lease-handle API, heap-absence harness) — detail deliverables, non-blocking.

#### Internal Contradictions
- **None.** Sanitization stated as best-effort-with-honest-guarantees consistently; authorize-vs-validate resolved (CLC); "persists nothing" reconciled with the C14-owned snapshot residency (§13.1).

#### Cross-document Contradictions
- **None.** Consistent with `06 §9.6` (C14 ownership), `06 §1/§8` (Secret-cache node), AD-012/021/022/023, AD-007, and `25 §16.1/§23.1/§31.1/§33.1`. The `25 §23.1` capture-leak risk is closed additively by §20.1 without altering frozen `25`.

#### Scores
- **Architecture Readiness: 96 / 100** — all Critical/High/Medium resolved additively; sanitization honest vs ZGC; identity pinned to the frozen node; redaction spans all capture surfaces; authorization boundary crisp; no ownership/architecture/store/stage change. Residual 4 pts are Low detail deliverables.
- **Documentation Health: 96 / 100** — contradictions closed; contracts honest (what IS/IS NOT guaranteed; runtime hygiene ≠ crypto destruction).
- **Implementation Readiness: 94 / 100** — every security-critical seam specified (sanitization, redaction, lease lifecycle, authorization boundary, snapshot residency) — buildable without inventing a guarantee the runtime cannot keep.

#### Recommendation: **FREEZE.** Critical = 0, High = 0, Medium = 0 (blocking); Low deferred (Appendix B). No contradictions with `00`–`25` or AD-001…AD-023; no new architecture/service/module/context/ownership/store/pipeline-stage; AD-002/007/012/020/021/022/023 preserved; SP-INV reinforced.

---

*End of document — 26-SecretsProvider.md · Status: **FROZEN** · Version: 1.0 (2026-07-22)*
