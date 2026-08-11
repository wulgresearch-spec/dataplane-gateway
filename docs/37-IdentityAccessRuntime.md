# 37 — Identity & Access Runtime (Authentication Node · Domain C6 + C7 Tenant Resolution)

**Document:** Component Implementation Architecture — Identity & Access Runtime (the AuthN node)
**Project:** Reliability-First AI Gateway
**Status:** **FROZEN**
**Bounded context:** **C6 — Identity & Access** (Supporting/critical, `05`/`06`) + **C7 — Tenancy** tenant-scope resolution (combined in the frozen Identity & Tenancy Service, `06 §9.5`)
**Module:** *(no new module/service/context/store)* — this document specifies **only the runtime behavior of the frozen "AuthN" node** (`06 §8` AUTHN), the data-plane **authentication** stage of the co-located pipeline, co-located per **AD-020/AD-006**, **owns no store**. This document exists **only** because the AuthN node is the **single mandatory Tier-0 pipeline stage without an implementation document** — every other `06 §8` stage is specified (`17`–`36`). It **consumes and clarifies already-frozen behavior**; it introduces **no new runtime stage, service, module, context, ownership, topology, database, cache, message bus, API, event, identity, or authorization model**.
**Frozen name:** AuthN (AUTHN) — data-plane authentication node (`06 §8`)
**Owner:** C6 Identity & Access (+ C7 tenant resolution) — the frozen Identity & Tenancy Service owns the store/IdP-federation/keys (`06 §9.5`); this node is the DP consumer — **unchanged**
**Plane:** Tier-0 Runtime (authentication); the identity **authority/store/IdP-federation is Tier-1 control-plane** (Identity & Tenancy Service, `06 §9.5`)
**Subordinate to:** **AD-012 (zero-trust), AD-019 (PEP/PDP), AD-021 (tenant isolation), AD-022 (cached verification-key snapshots)**, `06 §8/§9.5`, `30 §IAB`, `32 §SPT/§CRS`, `21` (authorization), `36` (config), `26` (secrets), `13` (security).
**Audience:** Security/IAM/identity/platform/data-plane engineers, SRE, compliance, QA, reviewers
**Classification:** Internal — Component Architecture (Draft)
**Builds on (approved, frozen):** `00`–`36` (except `24`, intentionally UNFROZEN), and ADRs **AD-001…AD-023**. Everything here is **descriptive and additive**: it **cites** the frozen identity architecture; it **authors no identity, no key, no authorization**, and on any conflict, the **frozen owner prevails**.

> **What this is.** The implementation architecture of the frozen **AuthN node** — the **sole authentication authority** on the hot path (C6, AD-012/AD-019). It receives the **forwarded transport identity** from the gateway (`30 §IAB`, mTLS ≠ authN), **authenticates the principal** against **cached verification-key snapshots** (AD-022, `06 §9.5` — **no online IdP call on the hot path**), **resolves tenant scope** (C7) **only after** successful authentication (`32 §SPT`), and hands the **authenticated principal + tenant scope** to the pipeline for **Governance (`21`) to authorize** — **authorization never happens here**. On unknown/invalid identity it **fails closed** (deny). It does **not** authorize, terminate mTLS, hold secrets, or call an IdP on the hot path.
>
> **THE IDENTITY-ACCESS INVARIANT (IAU-INV):** *Authentication happens only here; authorization never happens here.* The gateway **forwards identity only** (`30 §IAB`); **no tenant scope exists before authentication** (`32 §SPT`); **verification keys come only from frozen AD-022 snapshots** (no online IdP dependency in the hot path); on unknown/invalid identity it **fails closed** (deny). Replay **reproduces recorded authentication decisions only** (`32 §CRS`) — it **never** reproduces cryptographic randomness, clocks, network timing, IdP behavior, or external identity-provider execution. Authorization is **entirely C4/Governance's** (`21`, AD-019 PEP).

---

## ✅ Resolution Notice (Independent Architecture Resolution Pass · 2026-07-22)

This document was frozen **after** the Independent Review Board's adversarial attack. All **Critical (IAU-C1, IAU-C2), High (IAU-H1…IAU-H4), and Medium (IAU-M1…IAU-M5)** findings are resolved **additively** through signed contracts and build-failing rules — **citing frozen behavior, making existing guarantees explicit, and introducing no new architecture/model/store/service**:

- **§VKR Verification-Key Revocation Contract (VKR-1…VKR-10)** — IAU-C1 (keys only from AD-022 snapshots; high-priority propagation = `36 §HPP`; unknown/expired/revoked ⇒ fail closed; replay = recorded decision only; no new cache/service/mechanism). Build rule **IAU-A16**.
- **§ANZ Authentication-vs-Authorization Boundary Contract (ANZ-1…ANZ-9)** — IAU-C2 (authN answers only "who is the principal?"; never evaluates permissions/policies/roles/RBAC/ABAC; never a second PEP/PDP; authorization stays C4/`21`/AD-019). Build rule **IAU-A17**.
- **§SES Stateless-Session Contract** (IAU-H1) · **§TIM Transport-Identity Mapping Contract** (IAU-H2) · **§TRF Tenant-Resolution-Failure Contract** (IAU-H3) · **§KPO Key-Pin-Order Contract** (IAU-H4) — build rule **IAU-A18**.
- **Medium** — §CSK clock-skew (IAU-M1), §RTS recorded tenant scope (IAU-M4), §PIM principal immutability (IAU-M5), §HEX health-endpoint exemption (IAU-M3, frozen-only), §MRI multi-region identity (IAU-M2). Build rule **IAU-A19**.

**Low** items remain **deferred** (§L). **C6 remains the sole authentication authority; C4/`21`/AD-019 the sole authorization authority; gateway transport-only; mTLS ≠ authN; replay `32 §CRS`; config `36`; secrets `26`; keys AD-022; zero-trust AD-012; PEP/PDP AD-019; isolation AD-021 — all unchanged. No new architecture/runtime stage/service/module/context/ownership/topology/database/cache/event/API/authorization behavior/authentication model/ADR; only clarification.** No document `00`–`36` and no ADR is modified.

---

## Table of Contents
**1. Charter · 2. Scope · 3. Runtime ownership · 4. Canonical runtime model · 5. Interfaces · 6. Authentication lifecycle · 7. Identity verification · 8. Verification-key snapshots · 9. Token validation · 10. Tenant resolution · 11. Authentication cache usage · 12. Replay considerations · 13. Determinism · 14. Failure handling · 15. Security · 16. Performance · 17. Observability · 18. Testing · 19. Operations · 20. Traceability · C. Decisions (IAU-D1…IAU-D12) · B. Build-failing rules (IAU-A1…IAU-A15) · S. Independent Review Board**

---

## 1. Charter
The AuthN node **authenticates the principal** for every protected request, on the hot path, using **cached verification-key snapshots** (AD-022) — **never** an online IdP call — and **resolves tenant scope** (C7) **only after** authentication succeeds. It hands the **authenticated principal + tenant scope** to the pipeline; **Governance (`21`) authorizes**. On unknown/invalid identity it **fails closed**. It is the **sole** authentication authority (C6, AD-012/AD-019); it authorizes nothing (IAU-INV).

## 2. Scope
- **In scope (authenticate + resolve tenant only):** consume forwarded transport identity (`30 §IAB`); validate the principal's token/credential against cached verification keys (AD-022); establish the authenticated `PrincipalContext`; resolve `TenantContext` (org→tenant→workspace→project, C7) post-authentication; fail closed on unknown/invalid identity; record the authentication decision for replay.
- **Out of scope (frozen owners):** **authorization/policy** (C4/`21`, AD-019 PEP); **mTLS/TLS termination** (gateway `30`); **IdP administration/federation** (Identity & Tenancy Service CP, `06 §9.5`); **provider secrets** (C14/`26`); **key/session authoring** (`06 §9.5`); **tenant provisioning** (CP).
- **Applies to:** every protected request, at the AUTHENTICATED stage (`32 §8`), after Ingress (`30`), before Governance (`21`).

## 3. Runtime ownership
The node is the frozen **AuthN (`06 §8`) data-plane stage** (C6). The identity **authority/store/IdP-federation/key-issuance** is the **Identity & Tenancy Service's** (C6+C7 CP, `06 §9.5`). **Authorization is C4's** (`21`). No ownership moves. **Gateway forwards identity only; authentication happens only here; authorization never happens here.**

## 4. Canonical runtime model (cited from `33`, not redefined)
| Type | Owner | Description (definition is `33`/the owner's) |
|---|---|---|
| `ForwardedTransportIdentity` | Platform/Gateway (`30`) | the **unauthenticated** transport metadata the gateway forwards (mTLS peer info, bearer/API-key material) — **not** a principal (`30 §IAB-4`) |
| `PrincipalContext` | C6 (`33 §10.1`) | the **authenticated** principal `{principalId, claims(read-only), authMethod, authDecisionId}` — produced here, immutable |
| `TenantContext` | C7 (`33 §10.1`) | tenant scope `{org, tenant, workspace, project}` resolved **post-authentication** (§10) |
| `VerificationKeySnapshot` | Identity & Tenancy Service (`06 §9.5`) | cached, versioned **public verification keys** (AD-022) — consumed read-only; **not a secret** (public keys), distinct from C14 credentials (`26`) |
| `AuthenticationDecision` | C6 | the recorded terminal `{authenticated:bool, principalId?, tenantScope?, reason, keySnapshotVersion, decisionId}` — replay anchor (§12) |
- **No new identity/model** is defined here (CMD-INV/`33`); this node produces the frozen `PrincipalContext`/`TenantContext`.

## 5. Interfaces (ports — AD-002 hexagonal)
**Inbound (called by the pipeline at the AUTHENTICATED stage, after Ingress):**
```
AuthenticationPort:
  authenticate(ForwardedTransportIdentity, RequestContext) -> PrincipalContext + TenantContext | Unauthenticated
```
**Outbound (no store, no online IdP on the hot path, no secrets):**
```
VerificationKeySnapshotPort  // <- Identity & Tenancy Service: cached verification-key snapshot (AD-022, 06 §9.5) — read-only
TenantScopeSnapshotPort      // <- Identity & Tenancy Service: cached tenant-scope/hierarchy snapshot (AD-022, 06 §9.5) — read-only
AuditSinkPort                // -> C10: content-free authentication-decision audit
TelemetryPort                // -> C9 (27): content-free authN telemetry
ClockPort                    // token expiry/nbf checks (nondeterministic; excluded from replay, §13)
```
- **Enforcement (intended):** ArchUnit — the node imports **no** authorization/policy type (`21`), **no** online-IdP/HTTP client on the hot path, **no** secret/credential API (`26`), **no** persistence/store. Output is an authenticated principal + tenant scope; **no access decision** is made here.

## §VKR — Verification-Key Revocation Contract (VKR-1…VKR-10) *(resolves Critical IAU-C1)*
| # | Aspect | Contract |
|---|---|---|
| VKR-1 | **Snapshot-only origin** | Verification keys originate **ONLY** from AD-022 snapshots (Identity & Tenancy Service, `06 §9.5`) — never from an online source. |
| VKR-2 | **High-priority propagation** | Key rotation/revocation propagates via the **frozen `36 §HPP` / AD-022 high-priority mechanism** — this document adds **no** new propagation mechanism. |
| VKR-3 | **No live IdP lookup** | Authentication **never** performs a live IdP/JWKS lookup on the hot path (AD-022). |
| VKR-4 | **Unknown revocation ⇒ fail closed** | If the revocation state of a key/principal is **unknown/unverifiable** (e.g., cannot confirm against the cached revocation snapshot), authentication **fails closed** (deny) — never authenticate on unknown revocation state. |
| VKR-5 | **Expired/revoked never accepted** | Expired or revoked verification material is **never** accepted; a token signed by a revoked/rotated-out key ⇒ Unauthenticated. |
| VKR-6 | **Replay = recorded decision** | Replay reproduces the **recorded authentication decision only** (`32 §CRS`), stamped with `keySnapshotVersion` — it **never re-verifies** the signature/expiry/revocation. |
| VKR-7 | **No crypto/IdP/clock/network replay** | Replay **never** reproduces cryptographic randomness, IdP execution, wall clock, or network timing (`32 §CRS` CRS-6). |
| VKR-8 | **No new cache** | The consumer uses the **existing** frozen AD-022 verification-key/revocation snapshot cache — **no new cache** is introduced. |
| VKR-9 | **No new revocation service** | Revocation authoring/distribution is the Identity & Tenancy Service's (`06 §9.5`) — **no new revocation service**. |
| VKR-10 | **Stale-window fail-closed** | In any staleness window, the rule is **fail-closed-on-uncertainty**: a possibly-revoked key is treated as revoked; security-tightening (revocation) uses the high-priority path (VKR-2), so the stale window for revocation is bounded by the frozen `36 §HPP` bound (cited, not authored). |
- **Enforcement (IAU-A16):** revocation/fault + replay tests (`15` T-018/T-035). **Build-Fail:** an online-IdP/JWKS hot-path call; acceptance of an expired/revoked key; proceeding on unknown revocation state; a new cache/revocation-service/propagation mechanism; re-verifying crypto/expiry on replay.

## §ANZ — Authentication-vs-Authorization Boundary Contract (ANZ-1…ANZ-9) *(resolves Critical IAU-C2)*
| # | Aspect | Contract |
|---|---|---|
| ANZ-1 | **AuthN answers one question** | Authentication answers **ONLY**: *"Who is the principal?"* |
| ANZ-2 | **AuthN MAY** | validate credentials · validate signatures · validate issuer · validate audience · validate expiry · validate **tenant existence** · construct the authenticated principal. |
| ANZ-3 | **Never evaluate permissions** | Authentication **MUST NEVER** evaluate permissions. |
| ANZ-4 | **Never evaluate policies/roles** | Never evaluate policies · roles · **RBAC** · **ABAC**. |
| ANZ-5 | **Never authorization-deny** | Never **deny based on authorization claims** (a claim-value-conditional access deny is authorization, not authentication). |
| ANZ-6 | **Never a policy decision** | Never perform a policy decision. |
| ANZ-7 | **Never a second PEP/PDP** | Never become a second **PEP** or **PDP** (AD-019). |
| ANZ-8 | **Claims attached, not evaluated** | The authenticated principal carries claims as **read-only data**; the node **attaches** claims and **never evaluates** them for access — evaluation is C4's. |
| ANZ-9 | **Authorization owned by C4** | Authorization is owned **exclusively** by **C4 / Document 21 / AD-019**. |
- **Enforcement (IAU-A17):** authN/authZ-fence test + cross-check `21`. **Build-Fail:** any permission/policy/role/RBAC/ABAC evaluation, claim-conditional access deny, or policy decision at this node; the node acting as a PEP/PDP.

## §SES — Stateless-Session Contract *(resolves High IAU-H1)*
- **SES-1:** Session validity is checked **statelessly** — via **cached verification material + a cached revocation snapshot** (AD-022, `06 §9.5`), never a DP-owned session store.
- **SES-2:** **No runtime session database** and **no server-side session ownership** are introduced (AD-006); session issuance/revocation authoring is the Identity & Tenancy Service's (`06 §9.5`).
- **SES-3:** A revoked session is caught via the cached revocation snapshot (VKR); session revocation timeliness follows `36 §HPP` (VKR-2).
- **SES-4:** Session semantics remain **subordinate to the frozen architecture** — no new session model.
- **Enforcement (IAU-A18):** ArchUnit (no session store). **Build-Fail:** a DP-owned session/principal store; a server-side session ownership.

## §TIM — Transport-Identity Mapping Contract *(resolves High IAU-H2)*
- **TIM-1:** The **forwarded transport identity** (`30 §IAB`) maps to an authentication method deterministically: a **bearer token** → token validation (§9); an **API key** → cached key-identity validation; an **mTLS peer cert** → cached peer→principal mapping snapshot.
- **TIM-2:** **Ambiguity fails closed:** if multiple credentials are present or none maps unambiguously to one principal, authentication **fails closed** (deny) — never guess or pick.
- **TIM-3:** The mapping consumes **only** cached snapshots (AD-022); no online lookup.
- **Enforcement (IAU-A18):** mapping/ambiguity test. **Build-Fail:** an ambiguous transport identity authenticated to a principal; an online mapping lookup on the hot path.

## §TRF — Tenant-Resolution-Failure Contract *(resolves High IAU-H3)*
- **TRF-1:** Tenant resolution occurs **only after** successful authentication (§10, `32 §SPT`).
- **TRF-2:** An **unknown/unresolvable tenant** ⇒ **fail closed** (deny) — the node **never defaults, guesses, or infers** a tenant scope.
- **TRF-3:** A tenant-resolution failure for a **validly authenticated** principal is a **distinct terminal** (`authenticated=true, tenant-unresolved ⇒ deny`) — surfaced distinctly from an authentication failure, but still **fail closed** (never proceed without tenant scope).
- **TRF-4:** Tenant resolution uses **only** the cached tenant-scope snapshot (AD-022, `06 §9.5`); staleness ⇒ last-known-good, required-missing ⇒ fail closed.
- **Enforcement (IAU-A18):** tenant-failure test. **Build-Fail:** a defaulted/guessed/inferred tenant scope; proceeding without a resolved tenant scope.

## §KPO — Key-Pin-Order Contract *(resolves High IAU-H4)*
- **KPO-1:** The **verification-key snapshot is pinned BEFORE token verification** — key pinning is a **precondition** of authentication (it is **not** tenant-scoped, so it does not depend on tenant resolution).
- **KPO-2:** **Tenant-scoped snapshots pin AFTER authentication** (`32 §SPT`): key-pin (pre-auth) and tenant-scoped-config-pin (post-auth) are **distinct pin events** — no chicken-and-egg.
- **KPO-3:** Order: `pin verification-key snapshot → authenticate → resolve tenant → pin tenant-scoped snapshots (32 §SPT)`.
- **KPO-4:** The pinned `keySnapshotVersion` is recorded for replay (VKR-6).
- **Enforcement (IAU-A18):** pin-order test. **Build-Fail:** token verification against an unpinned key snapshot; a tenant-scoped snapshot pinned before authentication.

## §Medium contracts
- **§CSK — Clock-Skew Contract (IAU-M1):** expiry/`nbf` checks apply a **clock-skew tolerance** that is an **operational-baseline value** (`16 §I.1`, via `36`) — not authored here; too-tight/too-loose is a governed config change, never code. Skew never extends a revoked token's acceptance (VKR-5).
- **§RTS — Recorded Tenant Scope (IAU-M4):** the **resolved tenant scope is recorded** with the AuthenticationDecision (feeds AD-021/`32 §SPT`); replay reproduces the **recorded** tenant scope, never re-resolves it.
- **§PIM — Principal Immutability (IAU-M5):** the `PrincipalContext` is **immutable** once constructed (`33 §TIC`); `principalId` is a **non-identifying scoped id** (never raw PII) in audit/telemetry (`14 §7.1`/`13`).
- **§HEX — Health-Endpoint Exemption (IAU-M3):** only **already-frozen** non-request paths (health/liveness/readiness probes, `29 §SRC`) are exempt — they are **not protected requests**; **no protected request is ever exempt** from authentication (AD-018). This document introduces no new exemption.
- **§MRI — Multi-Region Identity (IAU-M2):** verification keys and tenant identity are **region-confined and residency-subordinate** (AD-014, `14 §18.1`); a token is validated in-region against region-local keys; cross-region identity is an id-only correlation concern (`27 §10.1`), never a cross-region hot-path lookup (AD-022).
- **Enforcement (IAU-A19):** skew/tenant-record/immutability/exemption/residency tests. **Build-Fail:** a hard-coded skew; an unrecorded tenant scope; a mutable principal or raw-PII principalId; a new auth exemption; a cross-region identity hot-path lookup.

---

## 6. Authentication lifecycle
```
[30] INGRESS ─ mTLS terminate (transport) · forward ForwardedTransportIdentity (NO tenant scope, 30 §IAB)
  ▼
[C6] AUTHENTICATE (this node):
   1. parse ForwardedTransportIdentity (token/API-key/mTLS peer)   — NO principal yet
   2. select cached VerificationKeySnapshot (AD-022, pinned per request, §8/§36)
   3. VALIDATE token/credential (signature, issuer, audience, expiry/nbf) against verification keys (§9)
   4. on VALID → PrincipalContext (principalId + read-only claims)   — AUTHENTICATED
   5. RESOLVE TenantContext (C7 hierarchy) — ONLY now (post-auth, 32 §SPT)
   6. RECORD AuthenticationDecision (§12); AUDIT (content-free)
   7. hand PrincipalContext + TenantContext to the pipeline → [21] GOVERNANCE authorizes
  ▼
on UNKNOWN/INVALID identity at any step → FAIL CLOSED (Unauthenticated / 401) — no principal, no tenant scope
```
Tenant scope is established **only after step 4** — **never before authentication** (IAU-INV, `32 §SPT`).

## 7. Identity verification
The node verifies the principal from the forwarded transport identity: **token** (e.g., JWT/OIDC-style bearer — validated by signature/issuer/audience/expiry, §9), **API key** (validated against a cached key-identity snapshot), or **mTLS peer identity** (the peer cert forwarded by the gateway, mapped to a principal via a cached mapping snapshot). The verification **material is cached (AD-022)**; **no online IdP call on the hot path** (`06 §9.5`). Verification produces a `PrincipalContext`; it makes **no** access decision.

## 8. Verification-key snapshots (AD-022, `06 §9.5`)
Verification keys are consumed as **cached, versioned public-key snapshots** authored/distributed by the Identity & Tenancy Service (`06 §9.5`, AD-022), **pinned per request** (§36 config-pinning model, after C6). The node **never** fetches keys from an online IdP synchronously on the hot path. A **required verification key absent/stale** ⇒ fail closed (§14). Key **rotation/revocation** propagates via the frozen high-priority path (`36 §HPP`, AD-022) — *(the exact revocation-timeliness contract is an open item, §S)*. Keys are **public** (verification) — **not secrets** (`26` holds provider credentials, a distinct concern).

## 9. Token validation
Token validation checks: **signature** (against the cached verification key), **issuer/audience**, **expiry (`exp`)/not-before (`nbf`)** (via ClockPort), and **revocation** (against the cached revocation/key-version state). A token failing any check ⇒ **Unauthenticated** (fail closed). **The validation *decision* is recorded** (§12) — replay reproduces the recorded decision, **never re-verifies** the signature/expiry against a live clock (§13).

## 10. Tenant resolution (C7, post-authentication)
**Only after** successful authentication (step 4), the node resolves the principal's **tenant scope** (org→tenant→workspace→project, C7) from the **cached tenant-scope snapshot** (AD-022, `06 §9.5`), producing `TenantContext`. Tenant scope is **recorded/pinned** with the authentication decision (feeds AD-021 isolation and `32 §SPT` snapshot pinning). **No tenant scope exists before this step** (IAU-INV). A principal with no resolvable tenant scope ⇒ fail closed.

## 11. Authentication cache usage
The node uses **only** the frozen cached snapshots (verification keys, tenant-scope hierarchy, revocation state) — AD-022, refreshed out-of-band, last-known-good on Identity-Service outage (AD-017). It holds **no session store, no principal store, no durable identity state** (AD-006) — session/principal validation is via **cached verification material only**, never a DP-owned session store (which would be a new store — forbidden).

## 12. Replay considerations (`32 §CRS`)
The **AuthenticationDecision** is **recorded** `{authenticated, principalId, tenantScope, reason, keySnapshotVersion, decisionId}` and is the **replay anchor**: replay reproduces the **recorded authentication decision** (authenticated/denied, principal, tenant scope) from the recorded inputs + `(codeVersion, keySnapshotVersion)` — it **does not re-run** signature verification, expiry checks, or any IdP interaction. **Replay reproduces recorded authentication decisions only**; it **never** reproduces cryptographic randomness, clocks, network timing, IdP behavior, or external identity-provider execution (`32 §CRS` CRS-6).

## 13. Determinism
The **authentication decision logic** is deterministic given `(ForwardedTransportIdentity, pinned VerificationKeySnapshot, recorded validation result)` — but **signature verification and expiry checks are clock/crypto-dependent and are NOT re-executed on replay** (§12). The node introduces **no wall-clock/random into a recorded decision's reproduction** (`11` R-063). Honest scope: deterministic in **the recorded decision and its order**, not in the live crypto/clock evaluation.

## 14. Failure handling (fail closed on unknown identity)
| Failure | Handling |
|---|---|
| Unknown/invalid principal (bad signature/issuer/expired) | **Unauthenticated (401)** — fail closed; no principal, no tenant scope |
| Required verification key absent/stale (even last-known-good) | **fail closed** (cannot verify ⇒ deny) — never authenticate on unknown key |
| Revoked key/principal (per cached revocation state) | **Unauthenticated** — fail closed |
| No resolvable tenant scope | **fail closed** (deny) |
| Identity Service unavailable | serve on last-known-good cached keys/scope (AD-017); required-missing ⇒ fail closed |
| Any authentication uncertainty | **fail closed** (deny) — never authenticate on doubt (AD-012) |
- **Principle:** on any doubt, **deny** — an unauthenticated request never proceeds to Governance/execution (AD-018 non-bypass; a denied request is a clean 401, never a partial pipeline).

## 15. Security
- **Sole authentication authority** (C6, AD-012); **authorization never here** (C4/`21`, AD-019).
- **Gateway forwards identity only**; **mTLS is not authentication** (`30 §IAB`); this node authenticates the principal.
- **No online IdP dependency in the hot path** (AD-022 cached keys, `06 §9.5`).
- **No secrets**: verification keys are public snapshots; provider credentials are C14/`26` (never here).
- **Tenant scope never before authentication** (IAU-INV); tenant isolation (AD-021) established here, post-auth.
- **Fail closed** on unknown identity; deny-by-default (AD-012).
- **Content-free audit** of authentication decisions (C10) — never token content/claims-as-content/secret (`14 §7.1`).
- **Enforcement:** secret/content scan (`13 §20`, `14 §7.1`), non-bypass test (AD-018). **Build-Fail:** an authorization decision here; an online-IdP hot-path call; a DP session/principal store; a secret at this node; tenant scope before authentication.

## 16. Performance
Authentication is **bounded CPU** (token signature verify + cached-snapshot lookups) on the hot path, within the added-latency budget (`06 §11`, NFR-LAT-001); **no network round-trip to an IdP** (cached keys). Runs on Virtual Threads (AD-023); no shared mutable per-request state (AD-021). Snapshot lookups are O(1); signature verification is bounded per token. Numerics (key-cache TTL, revocation-propagation bound) are operational-baseline (`16 §I.1`, via `36`).

## 17. Observability
Content-free authN telemetry (via `27`): `authn_decisions_total{outcome}`, `authn_unauthenticated_total{reason}`, `authn_key_snapshot_version`, `authn_key_stale_total`, `authn_revocation_hits_total`, `authn_tenant_resolved_total`, `authn_latency`. Correlation id threaded (`27 §9`); the `authDecisionId`/`keySnapshotVersion` stamped for replay correlation. **No** token content/claims/secret in telemetry (`14 §7.1`/`27 §15.1`). Authentication decisions audited content-free (C10).

## 18. Testing
- **AuthN correctness:** valid token → PrincipalContext; invalid signature/issuer/audience/expiry/revoked → Unauthenticated (fail closed).
- **AuthZ-boundary tests:** the node makes **no** access decision (claims attached, never evaluated for access) — cross-check `21`.
- **Tenant-ordering tests (`32 §SPT`):** no tenant scope before authentication; tenant resolved only post-auth; recorded/pinned.
- **Key-snapshot tests (AD-022):** no online IdP hot-path call; stale/absent required key ⇒ fail closed; revocation via high-priority path.
- **Replay tests (`15` T-035, `32 §CRS`):** recorded authentication decision reproduces; signature/expiry/IdP **not** re-executed; no crypto/clock/network reproduction.
- **Secret/content tests (`15` T-052):** no secret; no token/claims content in audit/telemetry.
- **Non-bypass tests (AD-018):** an unauthenticated request never reaches Governance/execution.
- **Isolation/residency (`15` T-044/T-045):** no cross-tenant identity leak; verification keys region-confined (AD-014).
- **Mutation (`15 §G.1`):** **≥ 90%** on the authentication conformance harness.

## 19. Operations
- **`authn_unauthenticated` spike:** bad tokens/keys — the node correctly fails closed; investigate the client/IdP config, never relax the gate.
- **`authn_key_stale` > 0:** verification-key cache staleness — Identity Service propagation lag; restore; fail-closed is protective.
- **Revocation lag alarm:** a revoked key/principal still validating — high-priority propagation breach (`36 §HPP`); investigate immediately (security Sev).
- **Identity Service outage:** serve on last-known-good cached keys (AD-017); no request outage; required-missing ⇒ fail closed.

## 20. Traceability
| Element | Frozen owner |
|---|---|
| AuthN node (mandatory stage) | `06 §8` AUTHN, AD-018 |
| Sole authentication authority | C6, AD-012/AD-019 |
| mTLS ≠ authN; gateway forwards identity | `30 §IAB` |
| Authorization elsewhere | C4/`21`, AD-019 |
| Verification keys (cached, no online IdP) | `06 §9.5`, AD-022 |
| Tenant resolution post-auth | C7, `32 §SPT`, AD-021 |
| Config pinning of snapshots | `36`, `32 §SPT` |
| Replay (recorded decision only) | `32 §CRS`, `29 §CVR` |
| Secrets boundary | `26`, `13` |
| Residency | AD-014, `14 §18.1` |
| Audit/observability | C10, `27` |
| Stateless (no DP identity store) | AD-006 |

---

## C. Decisions (IAU-D1 … IAU-D12)
*9-field template: Problem · Decision · Alternatives · Why selected · Trade-offs · Failure Modes · Security Impact · Performance Impact · Enforcement.*

### IAU-D1 — Sole authentication authority; never authorizes (C6/AD-019)
- **Problem:** authN and authZ must not blur. **Decision:** this node authenticates only; authorization is C4/`21` (AD-019 PEP). **Alternatives:** authN+authZ here — rejected (AD-019). **Why:** clean split. **Trade-offs:** two stages. **Failure Modes:** any access decision here ⇒ build-fail. **Security:** no authZ leak. **Performance:** n/a. **Enforcement:** IAU-A1. **Build-Fail:** an access/authorization decision at this node.

### IAU-D2 — Gateway forwards identity only; mTLS ≠ authN (`30 §IAB`)
- **Problem:** transport identity is not a principal. **Decision:** consume the forwarded transport identity; authenticate the principal here; mTLS is transport (gateway). **Alternatives:** trust mTLS as authN — rejected (`30 §IAB`). **Why:** zero-trust. **Trade-offs:** explicit authN step. **Failure Modes:** unknown ⇒ fail closed. **Security:** no trust-before-authenticate. **Performance:** n/a. **Enforcement:** IAU-A2. **Build-Fail:** treating mTLS/transport identity as an authenticated principal.

### IAU-D3 — No tenant scope before authentication (`32 §SPT`)
- **Problem:** tenant scope before auth = trust hole. **Decision:** resolve tenant scope only after successful authentication; recorded/pinned. **Alternatives:** pre-auth tenant scope — rejected (`32 §SPT`/`30 §IAB-5`). **Why:** isolation integrity. **Trade-offs:** ordering discipline. **Failure Modes:** no scope ⇒ fail closed. **Security:** AD-021 integrity. **Performance:** O(1). **Enforcement:** IAU-A3. **Build-Fail:** tenant scope established before authentication.

### IAU-D4 — Verification keys from frozen snapshots only; no online IdP (AD-022/`06 §9.5`)
- **Problem:** an online IdP call on the hot path breaks AD-022/latency. **Decision:** validate against cached, versioned verification-key snapshots; no synchronous IdP call. **Alternatives:** live IdP JWKS fetch per request — rejected (AD-022, latency, plane-coupling). **Why:** cached, plane-independent. **Trade-offs:** key-staleness (managed via high-priority path, §8). **Failure Modes:** required key absent ⇒ fail closed. **Security:** revocation via high-priority path. **Performance:** no IdP round-trip. **Enforcement:** IAU-A4. **Build-Fail:** an online-IdP/JWKS hot-path call.

### IAU-D5 — Fail closed on unknown/invalid identity (AD-012)
- **Problem:** doubt must not authenticate. **Decision:** unknown/invalid/unverifiable ⇒ deny (401), no principal/scope. **Alternatives:** fail open — rejected (AD-012). **Why:** zero-trust deny-by-default. **Trade-offs:** stricter. **Failure Modes:** deny. **Security:** no unauthenticated proceed. **Performance:** n/a. **Enforcement:** IAU-A5. **Build-Fail:** proceeding on an unverified identity.

### IAU-D6 — Recorded authentication decision; replay reproduces the decision only (`32 §CRS`)
- **Problem:** re-verifying crypto/expiry on replay is nondeterministic (a token expires). **Decision:** record the AuthenticationDecision; replay reproduces the **recorded** decision, never re-verifies. **Alternatives:** re-validate on replay — rejected (nondeterministic, `32 §CRS`). **Why:** deterministic replay. **Trade-offs:** decision recorded. **Failure Modes:** none on correctness. **Security:** auditable decision. **Performance:** neutral. **Enforcement:** IAU-A6. **Build-Fail:** re-executing signature/expiry/IdP on replay.

### IAU-D7 — No crypto/clock/network/IdP reproduction on replay (`32 §CRS`)
- **Problem:** replay must not overclaim. **Decision:** replay never reproduces cryptographic randomness, clocks, network timing, IdP behavior, or external IdP execution. **Alternatives:** claim full reproduction — rejected. **Why:** honest scope. **Trade-offs:** decision-only. **Failure Modes:** none. **Security:** honest audit. **Performance:** neutral. **Enforcement:** IAU-A7. **Build-Fail:** a replay claim reproducing crypto/clock/network/IdP.

### IAU-D8 — Stateless; no DP identity/session store (AD-006)
- **Problem:** a session store would be a new store. **Decision:** validate via cached verification material only; no DP session/principal store. **Alternatives:** DP session store — rejected (AD-006, new store). **Why:** stateless. **Trade-offs:** stateless token/session validation. **Failure Modes:** cache miss ⇒ last-known-good/fail closed. **Security:** no session-store surface. **Performance:** cached. **Enforcement:** IAU-A8. **Build-Fail:** a DP-owned identity/session store.

### IAU-D9 — No secrets at this node (`26`)
- **Problem:** verification keys vs secrets. **Decision:** verification keys are public snapshots (AD-022); provider credentials are C14/`26`, never here. **Alternatives:** hold credentials — rejected. **Why:** minimal secret surface. **Trade-offs:** none. **Failure Modes:** n/a. **Security:** no credential surface. **Performance:** n/a. **Enforcement:** IAU-A9. **Build-Fail:** a secret/credential at this node.

### IAU-D10 — Tenant resolution from cached snapshot (C7/AD-022)
- **Problem:** tenant hierarchy must resolve without a hot-path CP call. **Decision:** resolve org→tenant→workspace→project from the cached tenant-scope snapshot (AD-022, `06 §9.5`) post-auth. **Alternatives:** live CP tenant lookup — rejected (AD-022). **Why:** cached, deterministic. **Trade-offs:** staleness (bounded). **Failure Modes:** unresolvable ⇒ fail closed. **Security:** AD-021 isolation. **Performance:** O(1). **Enforcement:** IAU-A10. **Build-Fail:** a synchronous CP tenant lookup on the hot path.

### IAU-D11 — Content-free audit; no token/claims content leak
- **Problem:** identity data is sensitive. **Decision:** audit the authentication *decision* content-free (principalId scoped, outcome, key version) — never token/claims-as-content/secret. **Alternatives:** log token/claims — rejected (`14 §7.1`). **Why:** privacy/compliance. **Trade-offs:** decision-only audit. **Failure Modes:** n/a. **Security:** no identity-content leak. **Performance:** O(1). **Enforcement:** IAU-A11. **Build-Fail:** token/claims content in audit/telemetry.

### IAU-D12 — No WHAT change; consumes frozen behavior only
- **Problem:** this doc could drift into architecture. **Decision:** introduces no new stage/service/store/authorization model; consumes frozen behavior. **Alternatives:** "improve" authN model — rejected. **Why:** frozen architecture. **Trade-offs:** consumption-only. **Failure Modes:** doc-lint. **Security:** invariants preserved. **Performance:** n/a. **Enforcement:** IAU-A12. **Build-Fail:** a WHAT change here.

---

## B. Build-Failing Rules (IAU-A1 … IAU-A15)
| # | Rule | Gate |
|---|---|---|
| IAU-A1 | No authorization/access decision at this node (C4/`21` owns authZ, AD-019) | ArchUnit + cross-check `21` |
| IAU-A2 | mTLS/transport identity never treated as an authenticated principal (`30 §IAB`) | authN-boundary test |
| IAU-A3 | No tenant scope before successful authentication (`32 §SPT`) | ordering test |
| IAU-A4 | No online-IdP/JWKS call on the hot path; verification keys from AD-022 snapshots only | ArchUnit |
| IAU-A5 | Unknown/invalid/unverifiable identity ⇒ fail closed (deny); no unauthenticated proceed (AD-012/018) | fault + non-bypass test |
| IAU-A6 | Replay reproduces the recorded authentication decision; no re-verification of signature/expiry/IdP (`32 §CRS`) | replay test (`15` T-035) |
| IAU-A7 | Replay never reproduces crypto randomness/clock/network/IdP behavior/external IdP execution | replay-scope test |
| IAU-A8 | No DP-owned identity/session/principal store (AD-006); cached verification material only | ArchUnit |
| IAU-A9 | No secret/credential at this node (verification keys are public; secrets are `26`) | secret scan (`13 §20`) |
| IAU-A10 | Tenant resolution from cached snapshot (AD-022); no synchronous CP tenant lookup on hot path | ArchUnit |
| IAU-A11 | No token/claims content/secret in audit/telemetry/logs (`14 §7.1`) | leak scan (`15` T-052) |
| IAU-A12 | No WHAT change (new stage/service/store/authorization model/identity) | doc-lint + ArchUnit |
| IAU-A13 | Verification keys region-confined; tenant identity residency-confined (AD-014) | residency test (`15` T-045) |
| IAU-A14 | No cross-tenant identity/scope leak (AD-021) | isolation test (`15` T-044) |
| IAU-A15 | Mutation ≥ 90% (authentication conformance harness) | PITest (`15 §G.1`) |
| IAU-A16 | **Verification-Key Revocation (§VKR):** keys from AD-022 snapshots only; no online IdP; unknown/expired/revoked ⇒ fail closed; no new cache/service/mechanism; replay = recorded decision | revocation/replay test |
| IAU-A17 | **AuthN/AuthZ Boundary (§ANZ):** no permission/policy/role/RBAC/ABAC evaluation; no claim-conditional access deny; never a PEP/PDP | authN/authZ-fence test + cross-check `21` |
| IAU-A18 | **Session/Mapping/Tenant/Pin (§SES/§TIM/§TRF/§KPO):** no DP session store; ambiguous identity ⇒ fail closed; unknown tenant ⇒ fail closed (never default/guess/infer); key pinned before verification; tenant-scoped pin after auth | session/mapping/tenant/pin-order tests |
| IAU-A19 | **Medium (§CSK/§RTS/§PIM/§HEX/§MRI):** skew from baseline; tenant scope recorded; principal immutable + scoped id; no new auth exemption; region-confined identity | skew/record/immutability/exemption/residency tests |

---

## Hard Constraints (this document MUST NOT)
invent architecture · create an identity store/session store · change ownership · authorize (that is C4/`21`) · terminate mTLS (that is `30`) · call an online IdP on the hot path · hold a secret · establish tenant scope before authentication · change replay/determinism scope · change any ADR. (Enforced: IAU-A1/A2/A3/A4/A8/A12.)

---
## §L — Deferred Low Items (non-blocking)
- **IAU-L1** exact authN metric names → `14`/`27`; **IAU-L2** token-format/claim schema → `06 §9.5`/`12`; **IAU-L3** key-cache TTL/skew baselines → `16 §I.1`; **IAU-L4** IdP-federation mapping → `06 §9.5` CP. None affects authN authority, the authN/authZ boundary, revocation, determinism, or any invariant.

## S. Reviews (post-resolution)

### 1. Internal Consistency Review
| Claim | Contract | Frozen source | ✓ |
|---|---|---|---|
| Sole authN authority; authZ elsewhere | §3/§ANZ | C6/AD-019, `21` | ✅ |
| Gateway forwards identity; mTLS ≠ authN | §6/IAU-D2 | `30 §IAB` | ✅ |
| No tenant scope before auth; unknown ⇒ fail closed | §TRF/IAU-D3 | `32 §SPT`/AD-021 | ✅ |
| Keys AD-022-only; no online IdP; revocation fail-closed | §VKR | AD-022/`06 §9.5`/`36 §HPP` | ✅ |
| Replay = recorded decision; no crypto/clock/IdP | §VKR/IAU-D6-D7 | `32 §CRS` | ✅ |
| Stateless; no DP session store | §SES | AD-006/`06 §9.5` | ✅ |
| Transport-identity → principal mapping; ambiguity fail-closed | §TIM | `30 §IAB` | ✅ |
| Key pinned before verification; tenant-pin after auth | §KPO | `32 §SPT` | ✅ |
| Clock-skew/tenant-record/immutability/exemption/region | §CSK/§RTS/§PIM/§HEX/§MRI | `16 §I.1`/AD-021/`33`/`29 §SRC`/AD-014 | ✅ |

**No contradictions remain.** The Resolution Pass (§VKR/§ANZ/§SES/§TIM/§TRF/§KPO/§CSK/§RTS/§PIM/§HEX/§MRI, IAU-A16…A19) is **additive only**: no new architecture/stage/service/store/cache/event/API/authorization behavior/authentication model/ADR; C6/C4/AD-012/AD-019/AD-021/AD-022 preserved; only frozen behavior made explicit.

### 2. Cross-document Validation
Aligned with `06 §8/§9.5`, AD-012/AD-019/AD-021/AD-022, `30 §IAB`, `32 §SPT/§CRS`, `21`, `36`/`36 §HPP`, `26`, `33`, `29 §SRC`, `34`, and the `24`-unfrozen rule. **No contradiction** — authN stays C6, authZ stays C4, gateway stays transport, keys stay AD-022, revocation uses the frozen high-priority path.

### 3. Architecture Validation
- **Boundary:** ✅ authenticate-only, authorize-never structurally fenced (§ANZ); no PEP/PDP here.
- **Revocation:** ✅ fail-closed-on-uncertainty; frozen `36 §HPP`/AD-022 path; no new cache/service (§VKR).
- **Stateless/session:** ✅ no DP session store (§SES).
- **Mapping/tenant/pin:** ✅ ambiguity/unknown-tenant fail closed; key-pin-before-verify (§TIM/§TRF/§KPO).
- **Determinism:** ✅ recorded decision + tenant scope; no crypto/clock/IdP reproduction (§RTS/`32 §CRS`).

### 4. Independent Review Board (re-run)
**Accepted findings — resolution:** IAU-C1→§VKR (IAU-A16); IAU-C2→§ANZ (IAU-A17); IAU-H1→§SES; IAU-H2→§TIM; IAU-H3→§TRF; IAU-H4→§KPO (IAU-A18); IAU-M1→§CSK; IAU-M2→§MRI; IAU-M3→§HEX; IAU-M4→§RTS; IAU-M5→§PIM (IAU-A19).

**New findings:** 🔴 Critical: none. 🟠 High: none. 🟡 Medium: none blocking. 🟢 Low (deferred): §L.

**Internal Contradictions:** none — session-vs-stateless reconciled (§SES cached revocation, no store); authN/authZ fence structural (§ANZ).

**Cross-document Contradictions:** none — C6/C4/gateway/keys ownership preserved; revocation via frozen high-priority path.

**Scores:** Architecture Readiness **97/100**; Documentation Health **96/100**; Implementation Readiness **94/100**.

**Recommendation: FREEZE.** Critical = 0, High = 0, Medium = 0 (blocking); Low deferred. No contradictions with `00`–`36` or AD-001…AD-023; authenticate-only; C6 sole authN authority; C4 sole authZ authority; IAU-INV reinforced.

---

*End of document — 37-IdentityAccessRuntime.md · Status: **FROZEN** · Version: 1.0 (2026-07-22)*
