# 13 — Security Standards (The Security Constitution)

**Document:** Security Standards & Governance
**Project:** Reliability-First AI Gateway
**Status:** **Approved & Frozen (v1.0, 2026-07-20)** — **binding security standard**
**Corrective pass applied:** SH-1 internal mTLS (§21.1, SEC-D1), SH-2 dual-control (§29.1) + break-glass (§29.2), SH-3 cost-based DoS (§13.1), SM-1…SM-5 (§§11/21.2/22/25.1). Frozen after a clean §31 consistency review (0 Critical, 0 High; §32 score 98/100).
**Audience:** Every engineer, SRE, security engineer, auditor, and the Security Architecture Board
**Classification:** Internal — Security Standard
**Builds on (approved, frozen):** `00`–`12` and ADRs **AD-001…AD-023**. Nothing here may contradict them.

> **What this is.** The permanent security law governing **every** service, SDK, plugin, deployment, API, event, database, cache, secret, and infrastructure component. It aggregates and formalizes the security decisions already frozen (AD-012 zero-trust, AD-021 tenant isolation, AD-018 non-bypass, `08 §14` encryption/keys/crypto-shred, `07 §17` event security, `12 §15.1/§16/§23` API/webhook/streaming security, `11 §H` secure coding) and adds the threat model, identity, secrets, compliance, testing, vuln-management, incident-response, and governance law that binds them. **No code, no implementation, no architecture redesign** — only rules, controls, and governance.
>
> **The nine security principles (every rule derives from these):**
> **Zero Trust · Least Privilege · Defense in Depth · Secure by Default · Fail Secure · Privacy by Design · Tenant Isolation · Compliance First · Never Trust Provider Output.**

---

## Table of Contents
1. Purpose · 2. Scope · 3. Security Principles · 4. Threat Model (STRIDE) · 5. Identity · 6. Authentication · 7. Authorization · 8. Secrets Management · 9. Encryption · 10. PII/PHI Protection · 11. Prompt Security · 12. Provider Security · 13. API Security · 14. Event Security · 15. Database Security · 16. Cache Security · 17. Object Storage Security · 18. Webhook Security · 19. Audit Security · 20. Logging Security · 21. Infrastructure Security · 22. Supply-Chain Security · 23. Secure SDLC · 24. Vulnerability Management · 25. Incident Response · 26. Compliance · 27. Security Testing · 28. Build-Failing Rules · 29. Security Governance · 30. Traceability · 31. Internal Consistency Review · 32. Adversarial Security Review, Score & Findings

---

## 1. Purpose
Define every security control the platform must implement so that any team can build, deploy, and operate securely without ambiguity. This is the security constitution: it makes the platform trustworthy enough to run regulated healthcare, finance, and government workloads (`02`).

## 2. Scope
**In scope:** all runtime components (data-plane, 13 control-plane services), SDKs/CLI, plugins, APIs (`12`), events (`07`), all datastores (PostgreSQL/MongoDB/Valkey/object/WORM/Kafka), secrets/keys, infrastructure (K8s/containers/network), supply chain, SDLC, and the human/process controls around them. Both **the platform's own security posture** and **the controls it provides customers**. **Out of scope:** customer application security above the gateway; provider-internal security (we treat providers as untrusted, §12).

## 3. Security Principles

| Principle | Rule |
|---|---|
| **Zero Trust** | No implicit trust — every request and inter-component call is authenticated, authorized, and encrypted; the network is never trusted (AD-012). |
| **Least Privilege** | Every identity, service, key, and token holds the minimum rights for the minimum time; deny-by-default everywhere. |
| **Defense in Depth** | Every critical property (isolation, non-exposure, integrity) is protected by ≥2 independent controls, so one failure never breaches it. |
| **Secure by Default** | The default configuration is the secure one; insecure options require explicit, recorded, approved exception (§29). |
| **Fail Secure** | On error/uncertainty, deny and fail safe (reject, don't leak) — never fail open (CP-1, BRULE-1). |
| **Privacy by Design** | Data minimization, classification-driven handling, redaction, and residency are built in, not added (`08 §5`). |
| **Tenant Isolation** | Absolute — data, keys, quota, and telemetry never cross tenants; any crossing is a Sev1 (AD-021, invariant). |
| **Compliance First** | Controls satisfy regulatory obligations by construction; evidence is producible on demand (§26). |
| **Never Trust Provider Output** | Provider responses are untrusted input — validated, neutralized, and governed before use (§12, `07`). |

---

## 4. Threat Model (STRIDE)

### 4.1 Threat actors
| Actor | Capability | Primary controls |
|---|---|---|
| **External attacker** | Unauthenticated network access | zero-trust edge, WAF, TLS/mTLS, rate limiting, input validation, no info leak |
| **Malicious tenant** | Valid credentials, hostile intent | tenant isolation, quotas, object-level authz (BOLA), abuse detection, cost caps |
| **Insider (privileged)** | Admin/operator access | least privilege, **separation of duties**, **dual-control for irreversible ops** (crypto-shred/key destruction), full audit, break-glass with review |
| **Compromised provider** | Malicious/altered model output | never-trust-output, correctness validation, neutralization, egress control, no secret exposure to providers |
| **Supply-chain attacker** | Malicious dependency/artifact | SBOM, signing/provenance, dependency scanning, pinned deps, admission control (§22) |
| **Malicious plugin** | Runs in the platform | sandbox, additive-only, least privilege, no bypass (AD-004), signing/vetting |
| **Prompt-injection/jailbreak** | Untrusted content manipulates the model | content-trust controls, output validation, tool-abuse limits, transparency of residual risk (§11) |

### 4.2 STRIDE matrix (categories × mitigations)
| STRIDE | Threat | Mitigations |
|---|---|---|
| **S**poofing | Forged identity/tenant/provider | OAuth2/OIDC/JWT validation, mTLS, workload identity, API-key hashing, tenant-claim binding (§5/§6/§7) |
| **T**ampering | Altered request/event/audit/data | TLS/mTLS integrity, schema validation, **audit tamper-evidence (signatures+Merkle)**, WORM, envelope integrity (§9/§14/§19) |
| **R**epudiation | Deny an action | complete, correlation-linked, immutable audit (§19, `07`/`08 §10`) |
| **I**nfo disclosure | Data exfiltration/leak | encryption (transit+rest), per-tenant/subject keys, redaction, no-secrets-in-logs/events, egress control, PII/PHI protection (§8–§10, §20) |
| **D**oS | Resource/cost exhaustion | rate limiting, quotas, **cost caps**, bounded buffers, circuit breakers, autoscaling, backpressure (§13, `03`) |
| **E**levation of privilege | Gain unauthorized rights | least privilege, deny-by-default authz, no bypass (AD-018), sandboxed plugins, K8s RBAC/PSS (§7/§21) |

### 4.3 Named-vector controls
- **Data exfiltration:** classification + per-tenant/subject encryption + egress control + DLP scanning + audit; regulated data never in logs/events/URLs.
- **Credential theft:** secrets in KMS/Vault only, short-TTL caches, rotation/revocation ≤5 min, no plaintext, hashed API keys, mTLS.
- **SSRF:** no user-controlled URLs to internal networks; egress allow-list; metadata-endpoint blocking; provider calls only via adapters.
- **RCE:** no unsafe deserialization (banned default-typing, `11` R-008), input validation, distroless/non-root containers, dependency scanning.
- **Replay:** idempotency keys, HMAC+timestamp for webhooks, short token lifetimes, nonce for high-assurance (§6/§13/§18).
- **Event poisoning:** schema validation (Avro/Apicurio), producer authz (topic ACLs), tenant-scoped consumers, idempotency (§14).
- **Cache poisoning:** tenant-namespaced keys, no cross-tenant serve, `Vary`/`private` (`12 §21`), signed/validated cache entries where applicable (§16).
- **Jailbreak/prompt injection:** §11 (bounded assurance, transparency).

---

## 5. Identity

- **Users:** human principals via **OIDC/OAuth2**; identity federated to the enterprise IdP (`06`); MFA for admin.
- **Services (control-plane):** each service is a distinct **service identity** (mTLS cert + OAuth2 client) with least-privilege scopes; no shared identities.
- **Workloads (K8s):** workload identity via Kubernetes ServiceAccount + OIDC projection (SPIFFE/SPIRE-class where adopted); pods authenticate with short-lived, automatically-rotated credentials — **no long-lived secrets in pods**.
- **Machines:** mTLS client certificates from an internal CA; certs short-lived and auto-rotated.
- **API keys:** high-entropy, **tenant-bound, scoped, revocable**; **stored only as a keyed hash (HMAC-SHA-256) with a lookup prefix** — never plaintext, never reversible; last-N shown once at creation; rotation supported; not sufficient alone for admin/high-assurance ops (`12 §18`).
- **Service accounts:** first-class principals with scopes and rotated credentials (`08` secrets); owned and audited.
- **Rule:** every request resolves to exactly one authenticated principal bound to exactly one tenant; anonymous access to protected functions is impossible (`11` R-071).

---

## 6. Authentication

- **OAuth2/OIDC:** Authorization Code + PKCE (users), Client Credentials (services); tokens are **JWT** validated on **every** request (stateless, AD-006).
- **JWT validation (mandatory):** verify signature via **JWKS**; enforce `iss`, `aud`, `exp`, `nbf`, `iat`; **reject `alg: none` and algorithm confusion** (pin allowed algs; never accept symmetric where asymmetric expected); validate the **tenant claim** against the resolved tenant (AD-021). Clock-skew ≤ 60 s.
- **mTLS:** mutual TLS for all service-to-service and workload traffic (zero-trust mesh, §21); certs from the internal CA, short-lived.
- **API keys:** bearer over TLS only; hashed at rest (§5); scoped; revocable; rate-limited; never in URLs/logs.
- **Token lifetime:** access tokens **short-lived (≤ 15 min)**; refresh tokens **rotating** (one-time-use, reuse-detection revokes the family); mTLS certs short-lived (≤ 24 h) auto-rotated.
- **Refresh/rotation:** refresh-token rotation with reuse detection; key/cert rotation automated (§8/§9).
- **Revocation:** token deny-list + short TTL → effective revocation within the TTL; API-key/secret revocation effective **≤ 5 min** (`08 §9.6`); compromised-credential kill-switch.

---

## 7. Authorization

- **Model:** **RBAC + ABAC**, enforced at the PEP (`06`, deny-by-default, AD-018). RBAC = roles→permissions; ABAC attributes = tenant, region/residency, data classification, scope, resource ownership.
- **Tenant isolation:** the token's tenant is authoritative; cross-tenant access is **impossible** (AD-021); mismatch → 404/403 per the `12 §19` matrix.
- **Resource ownership (object-level authz / BOLA):** authorize the **specific** resource, not just the type (`12 §19`, OWASP API1).
- **Permission model:** granular scopes (`completions:write`, `admin:governance`); tokens carry minimal scopes; **field-level authz** for sensitive attributes (OWASP API3).
- **Separation of duties:** no single identity can both perform and approve high-risk actions (key destruction, policy-for-regulated-data, secret rotation) — dual-control (§29).
- **Rule:** authorization is checked **before** any side effect; there is **no bypass path** (AD-018); every decision is audited (`07`/§19).

---

## 8. Secrets Management

- **Store:** all secrets/keys in **KMS/HSM + Vault-class (OpenBao/Vault)** (`09` TD-010, `08 §14`); **never** in code, config, env-in-repo, images, logs, or events.
- **Envelope encryption:** data encrypted with data keys (DEKs) wrapped by tenant KEKs / CMKs; only wrapping keys rotate broadly (§9).
- **Key rotation:** automated, scheduled; key versions retained for the backup window (`08` M4); rotation is non-disruptive (re-wrap).
- **Secret rotation:** provider credentials rotated on policy; short-TTL hot-path caches (`11` R-069); rotation is an audited admin action.
- **No plaintext secrets:** enforced by secret scanning (§28) across repo, config, images; the emit/log path scans for secret patterns (`07 §17`).
- **Emergency rotation:** a documented break-glass rotation revokes and re-issues affected secrets/keys within minutes; **dual-control** for destructive key operations; fully audited.
- **Access:** least-privilege, per-service secret access; **no service reads another service's secrets** (`06`/§7); all secret access attributed and audited.

---

## 9. Encryption

- **In transit:** **TLS 1.2 minimum, 1.3 preferred**; strong ciphers only; deprecated protocols disabled; **mTLS** internally; HSTS at the edge (`03` NFR-ENC-001).
- **At rest:** **AES-256** (or equivalent) on all durable stores, backups, and object storage.
- **Envelope encryption + key hierarchy:** platform / **per-tenant KEK** / **per-subject DEK** (regulated-PII/PHI at subject granularity) / CMK (Enterprise/Regulated) / secret material in KMS/HSM (`08 §14`).
- **Field-level encryption:** **mandatory field/row/tenant-partition-level** encryption for Confidential/Regulated data — never DB-level single-key (`08 §14` C3); this makes cross-tenant crypto-isolation real.
- **Per-tenant / per-subject keys:** blast-radius isolation + subject-granular **crypto-shredding** (destroy the subject DEK → data unrecoverable without mutating immutable records, `08 §15`); DEK-unwrap cached in memory only (`08` r4).
- **Crypto-shredding:** the erasure mechanism for immutable/WORM data and event-log payloads; **irreversible → requires dual-control + audit + legal-hold check** (§29, `08 §15`).

---

## 10. PII / PHI Protection

- **Detection:** inbound/outbound scanning for PII/PHI patterns (DLP); classification at write (`08 §5`: Public…Regulated-PHI/Secret).
- **Masking/redaction:** classification-driven redaction/tokenization **before** emission to logs/events/telemetry/errors (`07 §17`, `11` R-068); masking in non-prod and in support views.
- **Classification:** every datum carries a class (`08 §5`); class drives encryption key, residency, retention, and event-eligibility.
- **HIPAA:** PHI encrypted (transit+rest), access-controlled + audited, minimum-necessary, BAAs with providers where PHI is processed; audit trail of PHI access (§26).
- **GDPR:** lawful basis, data minimization, **right-to-erasure via crypto-shredding** (§9/§29), residency/data-transfer controls, DPA support, breach notification (§25/§26).
- **Rule:** regulated data appears **only** in classified, governed audit records — **never** in logs, URLs, metrics, or non-audit events (`07 §17`).

---

## 11. Prompt Security (bounded assurance — transparency required, BRULE-9)

- **Prompt-injection detection:** untrusted content (user prompts, retrieved docs, tool outputs) passes through **content-trust controls** at designated points (`BR-019`, `07`, `12 §23`); risky patterns flagged/blocked per policy.
- **System-prompt protection:** system/developer instructions are isolated from untrusted content; the gateway does not let untrusted content override system instructions or governance; system prompts are not exposed to clients or providers beyond necessity.
- **Tool-abuse prevention:** tool calls are validated (schema) and **authorized** before execution (`12 §16.3`, AD-018); dangerous/over-broad tool invocations are policy-gated; no tool executes on unvalidated arguments (BRULE-1).
- **Indirect prompt injection:** content retrieved/ingested (RAG, tool outputs, web) is treated as **data, not instructions**; provenance tracked; governance applied (`PRB-013/PRB-027`).
- **Output validation:** structured/tool output validated against declared schemas (`12 §16.4`, `NFR-SO`); grounding controls where configured (`BR-009`).
- **Evaluation methodology (SM-2):** control efficacy is **measured, not assumed** — a **maintained adversarial red-team suite** (direct + indirect injection, jailbreak, tool-abuse, exfiltration payloads) runs continuously (§27); a **pass-through / block-rate metric** is tracked over releases (`03` NFR-SEC-003) and gates regressions. The metric expresses **risk reduction, not a guarantee**; a drop in efficacy is a Security signal. New attack classes are added to the suite as discovered.
- **Honest limitation:** prompt injection is **not fully solvable**; the platform **reduces and governs** risk and is **transparent about residual risk** (BRULE-9, OOS-6) — it never claims a guarantee it cannot make.

---

## 12. Provider Security (Never Trust Provider Output)

- **Trust model:** providers are **untrusted upstreams**; their output is untrusted input (§3 principle 9). No provider is granted implicit trust.
- **Provider isolation:** each provider is reached only through its **adapter** (AD-007); a compromised/misbehaving provider is contained to its adapter; failover to a healthy provider (residency-safe, `08`).
- **Credential isolation:** per-provider credentials via the **secret port** (§8); one provider's compromise cannot yield another's credentials; least-privilege provider keys.
- **No provider-specific data leaks:** provider ids/errors/finish-reasons/fields are **neutralized** and never leak past the adapter (`12 §16.10`); provider errors surface as neutral 502/504 (`12 §7`).
- **Egress control:** provider calls respect egress allow-lists, residency, and no-SSRF (`08` networking, §4); regulated data sent to a provider only per policy/BAA (§10).
- **Output handling:** all provider output validated for structure/streaming/tool-call integrity (`07`/`12 §16`) before it can affect the enterprise (BRULE-1, AD-018).

---

## 13. API Security

- **OWASP API Security Top 10 (2023):** mapped and enforced per `12 §23` (BOLA, broken auth, property-level authz, resource consumption, function-level authz, sensitive-flow abuse, SSRF, misconfiguration, inventory, unsafe 3rd-party consumption).
- **Rate limiting & cost caps:** per global/tenant/key/burst (`12 §20`); **inference endpoints additionally enforce cost/token budgets** to prevent cost-based DoS (`03`/`08 §24.5`).
- **Replay protection:** idempotency keys (`12 §14`); short token lifetimes; timestamp/nonce for high-assurance.
- **Request validation:** JSON-Schema/OpenAPI 3.1 validation; size/depth/array bounds; content-type enforcement (`12 §22/§23`).
- **Response validation:** correctness validation of model output before delivery (`12 §16`); no internal/provider leakage in errors (`12 §11`).
- **CORS (resolving `12` L4):** **deny-by-default**; explicit **origin allow-list** per API; **credentialed requests never with wildcard `*`**; preflight validated; no reflecting arbitrary `Origin`.
- **Compression (resolving `12` L3):** compression is **disabled on authenticated/sensitive responses that reflect request-derived secrets** (BREACH/CRIME mitigation); where enabled, length-hiding/padding considered for sensitive endpoints; SSE not compressed.
- **API-key handling (resolving `12` L5):** high-entropy keys, **HMAC-hashed at rest** (§5), scoped/tenant-bound, revocable, rotation supported, rate-limited, never logged.

---

### 13.1 Inference Cost-Based DoS Protection — complete model (SH-3)

Layered budgets protect against cost-based DoS while allowing legitimate burst. **Fully compatible with rate limiting (`12 §20`) and metering/billing/quota (`06 §24.5`, `08 §24.5`)** — budgets derive from Billing entitlements and are metered authoritatively (`05` C5).

- **Per-request token limits:** every inference request has a max input+output token cap (per model/tier); exceeding → `422` `gateway.validation.token_limit_exceeded`. Generation is never unbounded.
- **Per-window budgets:** **per-minute / per-hour / per-day** token **and** cost budgets, enforced via sliding-window counters in Valkey (`12 §20`) at each scope below.
- **Hierarchical quota scopes:** **API-key ⊆ user ⊆ tenant ⊆ organization** — the **effective limit is the minimum** across the hierarchy; each scope has its own token/cost budget (entitlements from Billing, `06 §24.5`).
- **Burst handling:** short bursts permitted within a token-bucket burst allowance above the steady rate; sustained overage is throttled.
- **Soft-limit behavior:** at a **soft threshold (default 80%)** the request proceeds, a non-blocking advisory is surfaced (header), and an **alert** fires.
- **Hard-limit behavior:** at the hard limit, requests are **rejected `429` `gateway.rate_limit.budget_exceeded`** (`retryable=true`) + `Retry-After` + `RateLimit-*` (`12 §20`); **rejected requests incur no token cost**.
- **Budget exhaustion:** when a window/period budget is exhausted, further requests `429` until reset; in-flight streams complete or are cut at the token cap — **never silently truncated** (BRULE-1).
- **Grace period:** a small, configurable soft-overage grace MAY be allowed for Enterprise tiers before hard cut (per entitlement) — bounded and audited; **never** for hard security/cost ceilings.
- **Circuit-breaker interaction:** budget rejection is **independent** of provider circuit breakers — a cost limit `429`s the client; a provider outage fails over (`08 §16`); neither masks the other.
- **Retry interaction:** `429` budget errors are `retryable` with `Retry-After`; SDKs back off + jitter reusing the idempotency key (`12 §14`); **retries re-check budgets and never bypass them**.
- **Billing interaction:** budgets = **entitlements published by Billing** (`06 §24.5`, `08`); usage metered authoritatively (`05` C5); overage handling per plan; rejected requests unbilled.
- **Alert thresholds:** alerts at **80% / 95% / 100%**; **cost-anomaly detection** flags abnormal spikes (potential abuse/DoS) → Security signal (`07`).
- **Audit logging:** every budget decision (soft-warn / hard-reject / exhaustion / grace) is audited (`07`/`08 §10`), correlation-linked, per tenant/org/key/user.

---

## 14. Event Security

- **Transport & access:** Kafka with **TLS/mTLS** + **topic ACLs** (least-privilege: a service produces only to topics it owns, consumes only authorized topics — `06 §24`, `07 §17`).
- **Producer authenticity/integrity:** events carry the producer identity in the envelope; integrity is protected by the durable log + **audit tamper-evidence** (per-record signature + Merkle checkpoints, §19); **external webhooks are additionally HMAC-signed** (§18) — internal events rely on transport auth + audit integrity (no contradiction with `07`).
- **Schema validation:** all events validated against **Avro** schemas via **Apicurio** compatibility gates (`07 §9`); off-contract events rejected.
- **Replay detection:** idempotent consumers keyed by the `06 §23` idempotency key; replay-critical consumers use natural/version-guarded idempotency (`07 §13`).
- **DLQ:** poison messages → DLQ after bounded retry; **ZL streams never silently drop** (`07 §14`); DLQ inherits encryption/ACLs/tenant-scope.
- **Tenant isolation & content:** every event carries tenant scope; **no secrets/regulated data in payloads** beyond classified audit events (`07 §17`); dedicated-tier physical isolation option (`07 §17`).

---

## 15. Database Security

- **Encryption:** at rest (AES-256) + field/row-level for Confidential/Regulated (§9, `08 §14`); TLS in transit.
- **Access:** least-privilege, **per-service** database credentials (`11` R-034); **no cross-service DB access** (`08 §24`, AU-07); credentials from the secret store, rotated.
- **Tenant isolation:** tenant-scoped every query (`11` R-040) **plus** per-tenant encryption **plus** **PostgreSQL Row-Level Security (RLS)** as defense-in-depth — a query bug alone cannot cross tenants (AD-021, `08 §19`).
- **Audit:** database access to Confidential/Regulated data is audited (`08 §19`); admin/DBA access is privileged, dual-controlled where destructive, and logged.
- **Backups:** encrypted, integrity-verified, residency-confined, **immutable/WORM for audit**, tested-restore quarterly (`08 §16`); key-version retention for restore (§8).
- **Injection:** parameterized queries only; no string-built SQL/NoSQL (`11` R-067).

---

## 16. Cache Security (Valkey)

- **Tenant isolation:** **namespaced, tenant-scoped keys**; **no cross-tenant serve** (AD-021, `08 §24.8`); isolation-violation detectors.
- **Encryption:** **TLS in transit**; the tier holds no system-of-record data; **sensitive/regulated data is not cached by default** (`08 §12`); where a short-TTL secret/credential is cached, it is per-tenant-encrypted and never persisted (`11` R-069).
- **TTL:** all cache entries have bounded TTL; snapshot/secret caches short-TTL; no unbounded caches (`11` R-052).
- **No sensitive persistence:** the cache is ephemeral; loss is tolerable and fail-safe (rate limits conservative, cache miss → origin); nothing durable/regulated persists here (`08 §6.3`).
- **Poisoning:** entries are tenant/policy-scoped and validated; response cache honors `Vary`/`private` (`12 §21`).

---

## 17. Object Storage Security

- **WORM:** immutable, write-once **Object-Lock** for sealed audit records + backups (`08 §10/§16`, `12` alignment).
- **Retention:** compliance-driven retention with **legal-hold** override; lifecycle governed (§26, `08 §15`).
- **Integrity:** content checksums + **Merkle-checkpoint anchoring** for audit (§19); tamper-evident.
- **Encryption:** AES-256 at rest, **per-tenant keys**, residency-confined; crypto-shred via key destruction (§9); access-governed and audited (`08 §48`).

## 18. Webhook Security
Governed in full by **`12 §15.1`** (frozen): **HMAC-SHA256 signatures** (`X-Gateway-Signature: v1,t=…,sig=…`), **timestamp validation** (5-min window), **replay protection** (window + event-id dedup), **signature versioning**, **signing-secret rotation** (overlap window; secrets in the Secret store), **retries** (backoff+jitter, at-least-once), **max-retry duration** (24 h), **DLQ**, **idempotency** (dedup by event id), best-effort **ordering** (with caveat), and **full audit logging**. This section adopts `12 §15.1` verbatim as the webhook security law.

## 19. Audit Security
- **Tamper-evidence:** each sealed audit record carries a **cryptographic signature/digest**; the Audit service computes **Merkle-tree checkpoints per partition (tenant/time)** anchored to WORM + an integrity log — **not** a global hash chain (scalable, `08 §10`).
- **Immutability:** sealed records are **WORM** (§17), immutable; corrections/erasures are recorded, not mutations (crypto-shred for erasure, §9).
- **Completeness:** 100% coverage via the event fabric + the `RequestFinalized` manifest (`06 §23.11`); zero-loss (RPO=0); loss detector = 0 (invariant).
- **Retention & access:** governed retention + legal hold; **access to audit data is itself access-governed and audited** (`08 §10/§19`, `03` OBS-5).

## 20. Logging Security
- **No secrets, no PHI/PII:** absolute — never in logs (`11` R-055/R-068, `07 §17`); redaction applied before emission.
- **Redaction:** classification-driven; structured logs with allow-listed fields.
- **Correlation IDs:** every log carries the correlation id (`12 §24`, `07 §6`) — enabling forensics without exposing content.
- **Integrity & access:** logs are access-controlled; security-relevant logs feed the SIEM (`04 §7`); log tampering is detectable.

---

## 21. Infrastructure Security (Kubernetes)

- **Kubernetes:** RBAC least-privilege; **default-deny NetworkPolicies** (explicit allow only); **Pod Security Standards = restricted** (no privileged, no host-network/PID, drop capabilities); namespaces per plane/tenant-tier; secrets via CSI/external-secrets (never in manifests).
- **Containers:** **distroless/minimal base**, **non-root**, **read-only root filesystem**, no shell where avoidable, seccomp/AppArmor profiles; resource limits set (DoS).
- **Network:** zero-trust mesh (**mTLS** between services); egress control/allow-lists; ingress via Spring Cloud Gateway + WAF; no direct public egress from sensitive workloads (`08` networking).
- **Image signing:** all images **signed (Sigstore/cosign)** with **SLSA provenance** (`10 §16`); only signed, from trusted registries.
- **SBOM:** **CycloneDX SBOM** per image/artifact (`10`); vulnerability-scanned.
- **Admission policies:** **OPA/Kyverno** admission control enforces: only-signed-images, no-privileged, no-latest-tag, required-labels, restricted-PSS, no-plaintext-secrets — **build/deploy-failing** (§28).

### 21.1 Internal mTLS / Service-to-Service Security — definitive platform standard (SH-1)

**Governance decision (SEC-D1): every internal service-to-service and workload connection SHALL use mutual TLS (mTLS). There is ZERO plaintext internal traffic.** Technology-neutral — **no specific mesh product is required**.

- **Universal mTLS:** every connection between any two internal components (data-plane, the 13 control-plane services, workers, sidecars, and datastores where supported) is **mutually authenticated and encrypted**. Plaintext internal traffic is prohibited and admission-blocked (§28).
- **Workload identity — SPIFFE/SPIRE preferred:** workload identity uses the **SPIFFE** model (SPIFFE IDs, e.g. `spiffe://<trust-domain>/ns/<namespace>/sa/<service>`), issued via **SPIRE** or an equivalent SPIFFE-compatible issuer. Identity derives from **workload attestation** (K8s ServiceAccount + node/pod attestation), never from network location (zero trust).
- **Service mesh vs application-enforced (either satisfies the standard):**
  - **If a service mesh is present** (Istio/Linkerd/Cilium or equivalent), it **SHALL terminate and manage workload certificates** (issue, rotate, handshake) with mesh mTLS set to **STRICT** (no permissive/plaintext).
  - **If no service mesh exists**, mTLS **SHALL be enforced by the application/runtime** using issued workload certificates (SPIFFE SVIDs) via the platform mTLS libraries; the same identity model and rotation apply.
  - The **security guarantee is identical** either way; the mesh is an implementation convenience, not a requirement.
- **Short-lived identities:** workload certificates/SVIDs are **short-lived** (default ≤ 24 h; typically ≤ 1 h in-mesh). No long-lived service certificates.
- **Automatic rotation — mandatory:** issuance and rotation are **fully automated** (SPIRE/mesh/agent), non-disruptive (overlapping validity); no manual certificate handling.
- **Certificate revocation:** revocation via **short TTL** (primary — a revoked identity expires within its lifetime) **plus** **trust-bundle update / identity deny-list** for immediate revocation; a compromised workload's SVIDs stop validating once removed from the trust bundle.
- **Trust domain design:** **one trust domain per security boundary** (per environment/region as policy requires), each with its own root/intermediate CA; workloads trust only their trust domain's bundle by default.
- **Certificate issuance workflow:** workload attests to the issuer (SPIRE agent / mesh CA) → issuer validates attestation (K8s SA + node identity) → issues a short-lived SVID scoped to the workload's SPIFFE ID → auto-rotated. **No human in the issuance hot path.**
- **Cross-cluster trust:** clusters within a trust domain **federate trust bundles** (bundle exchange); cross-cluster mTLS is least-privilege authorized per service.
- **Cross-region trust:** cross-region traffic uses **federated trust bundles between regional trust domains**, subject to **residency/policy** (`08`/AD-014) — federation **never bypasses residency**; regulated flows stay in-region.
- **Failure behavior (fail secure):** if identity validation fails (expired/invalid/unknown SVID, untrusted CA, SPIFFE-ID/hostname mismatch), the connection is **refused — fail secure, never fail open** (§3, CP-1). No fallback to plaintext or unauthenticated.
- **Enforcement:** STRICT mTLS (or app-enforced equivalent) is verified by admission policy + runtime checks; a workload without a valid identity **cannot communicate** (§28).

### 21.2 Web Application Firewall (WAF) (SM-4)
- A **WAF is mandatory at the public ingress** (managed cloud WAF or self-hosted, e.g., an OWASP-CRS-based engine), in **blocking mode**, with the **OWASP Core Rule Set** as the baseline, tuned to the API surface (`12`) to minimize false positives.
- WAF provides: injection/XSS/protocol-anomaly filtering, request-size/rate anomaly protection, and **L7 DDoS** mitigation ahead of the gateway; it complements (does not replace) input validation (`11`/`12`) and rate limiting (§13.1).
- WAF rule changes are change-controlled and audited; the WAF fails closed on the security-critical path per policy.

---

## 22. Supply-Chain Security
- **Dependency scanning:** SCA (OWASP Dependency-Check/Snyk-class) on every build; **critical CVEs fail the build** (§24/§28).
- **SLSA (committed, SM-1):** **SLSA Build Level 3 is the committed baseline at GA** for all published artifacts (hermetic, isolated builds with signed, non-falsifiable provenance); **Level 4** is the aspirational target as tooling matures. No artifact ships below Level 3 provenance at GA (§28 build-failing).
- **Sigstore:** artifact + image **signing and verification** (cosign); provenance attestations.
- **Artifact signing:** all published artifacts (images, SDKs, Helm, plugins) signed; consumers verify.
- **Pinning/licensing:** pinned dependencies + CI action SHAs; license policy enforced (`09`/`10 §16` — no SSPL/BSL/AGPL on distributed components without review).

## 23. Secure SDLC
- **Threat modeling:** required for new services/major changes (§4 STRIDE); recorded in `security/`.
- **Code review:** CODEOWNERS + **security review** for `libs`/`schemas`/`security`/auth/crypto/boundary changes (`10 §16`, `11`).
- **SAST:** on every PR (SpotBugs/FindSecBugs + SAST) — high/critical fails (§28).
- **DAST:** against staging in CI (auth, injection, misconfig).
- **Dependency & secret scanning:** every PR (§22, `11` R-079) — build-failing.
- **Secure defaults & least privilege** enforced by `11`/§3.

## 24. Vulnerability Management
- **CVSS policy & patch SLA (`03` NFR-SEC-001):** **Critical (≥9.0) mitigate ≤ 24 h / fix ≤ 72 h; High (7.0–8.9) ≤ 7 d; Medium ≤ 30 d; Low ≤ 90 d.** Exploited-in-wild critical → **emergency response immediately**.
- **Emergency fixes:** expedited, dual-reviewed, hot-patch/rollback ready; post-incident review (§25).
- **Tracking:** vulnerabilities tracked with age/SLA dashboards; **overdue critical → escalation + release gate**.
- **Scanning:** continuous dependency + container + IaC scanning (§21/§22).

## 25. Incident Response
- **Detection:** SIEM + security signals (`07`) + anomaly/isolation-violation detectors; **Sev1 acknowledge ≤ 15 min (24/7)** (`03` NFR-SEC-002).
- **Containment:** ≤ 1 h target; kill-switches (credential/tenant/traffic), circuit-breakers, isolate compromised components.
- **Recovery:** DR runbooks (`08 §16`); rotate compromised secrets/keys (§8); restore from immutable backups.
- **Forensics:** immutable audit + correlated telemetry enable reconstruction (§19); preserve evidence; root-cause + post-incident review.
- **Notification:** breach notification within **regulatory/contractual windows** (HIPAA/GDPR, §26); coordinated disclosure.

### 25.1 KMS / Root-Key Compromise Runbook (SM-5)
The catastrophic case — suspected compromise of a KMS root, intermediate, or CMK:
1. **Detect & declare** — anomalous key-use/access signals trigger a Sev1; declare a key-compromise incident (≤ 15 min MTTA).
2. **Contain** — **freeze** affected key operations; **revoke** the compromised key's ability to unwrap (KMS policy) and **isolate** the KMS path; enable break-glass (§29.2) under dual control.
3. **Rotate (dual-control, §29.1)** — issue new root/intermediate/CMK; **re-wrap** DEKs under the new KEK (envelope re-wrap, not re-encrypt-all); rotate dependent secrets.
4. **Assess exposure** — determine which tenants/data classes were unwrappable during the window (per-tenant/subject key isolation bounds blast radius); preserve immutable audit for forensics (§19).
5. **Recover** — restore integrity; verify no tampering via Merkle checkpoints (§19); resume operations only after verification.
6. **Notify** — affected customers + regulators within windows (§26) where exposure is possible.
7. **Post-incident** — root cause, control improvements, HSM/access hardening; blast-radius review.
- **Bounded blast radius:** per-tenant/subject keys mean a single tenant/subject key compromise is **tenant/subject-scoped**; only a **root/HSM** compromise is platform-wide — hence HSM-backed roots, strict access, M-of-N quorum (§8/§29), and continuous monitoring.

## 26. Compliance
- **Posture:** the platform provides the technical controls + evidence for customers' regimes and is auditable against recognized frameworks (`03` NFR-COMP-001, COMP-7 — the platform *enables* compliance; the customer owns theirs).
- **HIPAA:** PHI safeguards (encryption, access control, audit, minimum-necessary, BAAs); §10.
- **GDPR:** lawful basis, minimization, erasure (crypto-shred), residency, DPA, breach notification; §10/§25.
- **SOC 2:** security/availability/confidentiality controls + continuous evidence; annual audit.
- **ISO 27001:** ISMS controls mapped; certification target.
- **PCI DSS (where applicable):** if payment data is handled (e.g., billing) — scope-minimized, tokenized, segmented.
- **Evidence:** controls are continuously evidenced (control monitoring, `03` NFR-COMP); audit exports on demand (`08 §10`).

## 27. Security Testing
- **Penetration testing:** ≥ annually **and on major change** by qualified parties (`03` NFR-SECT-001); findings remediated per SLA (§24).
- **Chaos security ("security game days"):** exercise credential compromise, tenant-isolation breach attempts, key-revocation, provider compromise; verify detection/containment.
- **Fuzzing:** API/input fuzzing (`12`), event/schema fuzzing, streaming/correctness fault-injection (`03 §54`).
- **API security testing:** BOLA/authz negative tests, rate-limit/replay tests, injection, CORS (§13, `12 §30`).
- **Red-team:** adversarial testing of prompt-injection/content-trust controls (§11).

## 28. Build-Failing Rules (CI/CD MUST reject)

| Gate | Fails when… |
|---|---|
| **SAST** | any high/critical security finding on changed code |
| **Secret scan** | any secret/credential/private-key detected (repo/config/image) |
| **Dependency/CVE** | any **critical** CVE (or overdue high) in the dependency set |
| **License** | disallowed license (SSPL/BSL/AGPL) on a distributed component w/o approval |
| **Image signing/provenance** | unsigned image or missing SLSA provenance/SBOM |
| **Admission policy** | privileged container, host-network, `latest` tag, missing PSS-restricted, plaintext secret in manifest |
| **Network policy** | a workload without a default-deny NetworkPolicy |
| **Crypto** | weak/broken crypto (ECB, MD5/SHA-1 for security, static IV, home-grown) (`11` R-070) |
| **AuthZ** | a protected endpoint/handler without an authorization check (`11` R-071) |
| **Encryption config** | a datastore/bucket configured without encryption-at-rest |
| **Insecure deserialization** | Jackson default-typing / unsafe deserialization (`11` R-008) |
| **PII/secret in logs/events** | secret/PII pattern in a log/event emission or spec example |
| **TLS config** | deprecated protocol/cipher enabled; non-mTLS internal service |

## 29. Security Governance
- **Approval workflow:** security-relevant changes (auth, crypto, key mgmt, network, boundaries, exceptions) require **Security review** + owner approval (`10 §16`); new services require a threat model (§23).
- **Exception process:** any deviation from this constitution requires a **recorded, time-boxed, risk-assessed exception** in `security/exceptions.md`, approved by the **Security lead** (+ Architecture for architectural exceptions); exceptions are reviewed quarterly and expire.
- **Risk acceptance:** residual risk is explicitly accepted by an accountable owner with documented rationale and compensating controls; invariant-weakening is **never** an acceptable risk (CP-3).
- **Dual-control:** irreversible/high-blast-radius operations require **two-person authorization** + technical enforcement + audit — the exact list is §29.1.
- **Separation of duties:** develop/approve/deploy/access-production are separated; **requester ≠ approver**; no self-approval of security exceptions.

### 29.1 Dual-Control (Two-Person) Privileged Operations (SH-2)
The operations below **REQUIRE two-person authorization**. **General rules apply to all:** requester ≠ approver (separation of duties); both hold the required role; the action is **immutably audited** (§19) with both identities, reason, and timestamp; the authorization is **time-boxed**; **technical enforcement is mandatory** (not policy-only). **An operation that would weaken an invariant (isolation, no-silent-delivery, audit integrity, residency, non-exposure) is NEVER authorizable, even with dual control (CP-3).**

| Operation | Required approvers | Technical enforcement | Audit | Time limit | Emergency (break-glass) |
|---|---|---|---|---|---|
| **Root/KMS key destruction** | 2× Security (Security lead + CISO) | KMS **M-of-N quorum** + irreversible-op gate | full, sealed | auth ≤ 1 h | 2 approvers + immediate exec review |
| **Tenant master-key (KEK) deletion** | 2× Security + tenant-owner sign-off | KMS M-of-N; legal-hold check | full | ≤ 1 h | expedited, 2 approvers |
| **CMK removal (customer-managed)** | Security + **customer authorization** | grace-period + confirmation workflow (`08` M6) | full + customer notice | grace ≥ 72 h | no silent removal |
| **Crypto-shredding (erasure)** | Security + Compliance | dual-control gate; **legal-hold precedence** (`08 §15`) | full erasure record | per request | expedited, 2 approvers |
| **WORM retention modification** | Security + Compliance | Object-Lock governance; append-only | full | ≤ 1 h | none (never weaken silently) |
| **Audit retention modification** | Security + Compliance | policy gate; audit-of-audit | full | ≤ 1 h | none |
| **Production break-glass access** | 2× (Security + service owner) | **JIT access, auto-expiry, session recording** | full, session-recorded | **≤ 4 h auto-revoke** | this IS the emergency path (§29.2) |
| **Security policy override** | Security lead + Architecture | policy-engine change control; invariant-safe | full | time-boxed | expedited review |
| **Global rate-limit override** | Platform + Security | change control + alert | full | time-boxed | expedited |
| **Billing override** | Finance + service owner | approval workflow | full | per case | expedited |
| **Global feature flag affecting security** | Security + owner | flag-registry gate; **invariant-flags forbidden** (`11` R-060) | full | short-lived flag | expedited |
| **Plugin approval (publish)** | Ecosystem + Security | vetting gate (AD-004) | full | per plugin | n/a |
| **Emergency secret rotation** | 2× Security | rotation workflow; revoke ≤ 5 min | full | immediate | this is an emergency op |
| **Production data export** | Data owner + Security/Compliance | DLP + approval + encryption | full | per case | expedited w/ review |
| **DR failover** | SRE lead + Security | runbook + approval; **residency-safe** | full | per incident | incident-driven |
| **Cross-region replication override** | Platform + Compliance | **residency policy gate** (never violate residency) | full | time-boxed | none if residency-violating |

### 29.2 Break-Glass Procedure (SM-3)
- **When:** genuine emergencies where normal approval is impossible and inaction causes greater harm.
- **How:** a **JIT (just-in-time), time-boxed, dual-approved** elevated grant, **session-recorded**, scoped to the minimum needed.
- **Auto-revoke:** access **automatically expires** (default ≤ 4 h) and **cannot be silently extended**.
- **Audit & review:** every grant/use is immutably audited and triggers a **mandatory Security post-hoc review within 24 h**; unjustified use → incident.
- **Never** bypasses invariants (CP-3); **never** disables audit.

## 30. Traceability

| Security area | BR | NFR | ADR | Coding (`11`) | API (`12`) |
|---|---|---|---|---|---|
| Zero-trust/auth (§5/6/7) | BR-021 | NFR-AUTH/AUTHZ-001 | AD-012/019 | R-071 | §18/§19 |
| Tenant isolation (§7/15/16) | BR-021 | NFR-REL-002 | AD-021 | R-040 | §19 |
| Secrets/keys (§8) | BR-020 | NFR-SEC-SM-001 | AD-012 | R-069 | — |
| Encryption/crypto-shred (§9) | BR-018 | NFR-ENC/PRIV | AD-012 | R-070 | §21 |
| PII/PHI (§10) | BR-018 | NFR-PRIV/DP-001 | AD-012 | R-068 | §23 |
| Prompt security (§11) | BR-019 | NFR-SEC-003 | AD-018 | R-067 | §16/§23 |
| Provider security (§12) | BR-006 | NFR-IF-001 | AD-007 | R-018/AU-06 | §16 |
| API security (§13) | BR-013/019 | NFR-SEC | AD-012 | R-067/R-071 | §23 |
| Event security (§14) | BR-011 | NFR-AUD/Q | AD-005/009 | R-042 | — |
| Database (§15) | BR-018 | NFR-DP/REL-002 | AD-010/021 | R-034/R-040 | — |
| Cache (§16) | BR-013 | NFR-CACHE/REL-002 | AD-008/021 | R-069 | §21 |
| Object/WORM (§17) | BR-011 | NFR-AUD/BAK | AD-010 | — | — |
| Webhook (§18) | BR-029 | NFR-RTY | AD-004 | — | §15.1 |
| Audit (§19) | BR-011/024 | NFR-AUD-001 | AD-009 | — | — |
| Logging (§20) | BR-010 | NFR-LOG-001 | AD-011 | R-055/R-068 | §24 |
| Infra (§21) | BR-031 | NFR-SCALE/HA | AD-012/TD-012 | — | — |
| Supply chain (§22) | BR-007 | NFR-SECT-001 | AD-015 | R-078/R-079 | — |
| SDLC (§23) | — | NFR-SECT-001 | AD-016 | §I | §30 |
| Vuln mgmt (§24) | — | NFR-SEC-001 | — | R-078 | — |
| Incident (§25) | — | NFR-SEC-002 | — | — | — |
| Compliance (§26) | BR-022 | NFR-COMP-001 | AD-012 | — | — |
| Testing (§27) | — | NFR-SECT-001 | — | R-080…086 | §30 |
| Internal mTLS / SEC-D1 (§21.1) | BR-021 | NFR-ENC/AUTH/REL-002 | AD-012/006/014 | R-071 | §18 |
| WAF (§21.2) | BR-013 | NFR-SEC | AD-012 | R-067 | §23 |
| Dual-control / privileged ops (§29) | BR-018/020 | NFR-SEC-SM/AUD | AD-012/016/021 | R-060 | — |
| Cost-based DoS (§13.1) | BR-013/017 | NFR-SCALE-002 | AD-008 | R-052 | §20/§14 |
| KMS-compromise runbook (§25.1) | BR-020 | NFR-SEC-002/SEC-SM | AD-012 | — | — |

## 31. Internal Consistency Review

| Frozen source | Requirement | This document | ✓ |
|---|---|---|---|
| AD-012 | zero-trust everywhere | §3/§5–§9/§21 | ✅ |
| AD-021 | absolute tenant isolation | §3/§7/§15/§16 (RLS+keys+scope defense-in-depth) | ✅ |
| AD-018 | no bypass of authz/correctness | §7/§11/§12 | ✅ |
| `08 §14/§15` | keys, field-level, per-tenant/subject, crypto-shred | §9 (verbatim alignment) | ✅ |
| `07 §17` | event security, no secrets/regulated in events | §14/§20 | ✅ |
| `12 §15.1` | webhook security | §18 (adopts verbatim) | ✅ |
| `12 §16` | provider neutralization, output validation | §12/§11 | ✅ |
| `12 §23` | OWASP API Top 10 | §13 | ✅ |
| `11 §H` | secure coding, R-067…071/008/055/068 | §13/§15/§20/§28 | ✅ |
| `03` NFR-SEC-001/002 | patch SLA, incident MTTA | §24/§25 | ✅ |
| `03` NFR-ENC/PRIV/COMP | encryption, privacy, compliance | §9/§10/§26 | ✅ |
| `09` TD-010/012 | KMS/Vault→OpenBao, Kubernetes | §8/§21 | ✅ |
| `10 §16` | signing/SBOM/provenance/secret-scan | §21/§22/§28 | ✅ |
| BRULE-9/OOS-6 | transparency about residual (prompt) risk | §11 (honest limitation) | ✅ |
| AD-012/006/014 (mTLS) | zero-trust internal, short-lived identity, residency-safe federation | §21.1 (SEC-D1) | ✅ |
| AD-021/`08 §15` (dual-control) | invariant-safe privileged ops; residency-safe DR/replication | §29.1 (never authorize invariant weakening) | ✅ |
| `06 §24.5`/`08`/`12 §20` (cost-DoS) | budgets from Billing entitlements; RL-compatible; metered | §13.1 (hierarchical budgets, unbilled rejects) | ✅ |

**No contradictions found.** Every corrective change is additive and traces to a frozen decision; none modifies `00`–`12` or AD-001…AD-023.

---

## 32. Adversarial Security Review — Post-Corrective (Resolutions, Score & Freeze)

> The corrective pass resolved all High and Medium findings from the first review.

### High — RESOLVED
- **SH-1 — Internal mTLS defined (§21.1, SEC-D1).** Universal mTLS, SPIFFE/SPIRE workload identity, mesh-or-app enforcement (technology-neutral), short-lived identities + mandatory auto-rotation, revocation, trust-domain design, issuance workflow, cross-cluster/cross-region federation (residency-safe), fail-secure on validation failure, zero plaintext internal traffic. ✅
- **SH-2 — Dual-control fully enumerated (§29.1).** The exact 16-operation list with approvers, technical enforcement, audit, time limits, emergency path, and separation of duties; invariant-weakening never authorizable. ✅
- **SH-3 — Cost-based DoS complete (§13.1).** Per-request token caps; per-minute/hour/day budgets; hierarchical API-key⊆user⊆tenant⊆org quotas (min-wins); burst; soft/hard behavior; exhaustion; grace; circuit/retry/billing interactions; alerts; audit — compatible with `12 §20` and `06 §24.5`. ✅

### Medium — RESOLVED
- **SM-1** SLSA **Build Level 3 committed at GA** (§22). ✅
- **SM-2** Prompt-injection **evaluation methodology** (adversarial suite + tracked reduction metric) (§11). ✅
- **SM-3** **Break-glass procedure** (JIT, dual-approved, session-recorded, ≤4h auto-revoke, mandatory review) (§29.2). ✅
- **SM-4** **WAF** specified (OWASP CRS baseline, blocking mode, L7 DDoS) (§21.2). ✅
- **SM-5** **KMS/root-key compromise runbook** (§25.1). ✅

### Low — deferred (as directed)
- **SL-1** PKI/cert cadence → `16-Deployment-Standards.md`; **SL-2** SIEM/detection rule catalog → `14-Observability-Standards.md`; **SL-3** DLP pattern specifics → module design; **SL-4** CORS allow-list workflow → ops. None block freeze.

### Inherent, honestly-disclosed residual risks (NOT defects — limitations to disclose, BRULE-9/OOS-6)
- **Prompt injection is reduced, not prevented** (§11) — now with a measured efficacy metric, still not a guarantee.
- **A compromised provider returning plausible-but-malicious content isn't fully detectable** (§12/OOS-6) — never-trust-output catches format/injection, not semantic sabotage.
- **KMS root/HSM and the highly-privileged insider are concentration points** — bounded by per-tenant/subject keys, HSM, M-of-N, dual-control, WORM+Merkle audit, and the §25.1 runbook, but not theoretically eliminable.
These are stated plainly as the platform's residual risk surface; the constitution does not claim guarantees it cannot make.

### Consistency re-review
Re-ran §31 against `00`–`12` and AD-001…AD-023 with the corrective changes: **no contradictions**; every addition is additive and traces to a frozen decision (mTLS→AD-012/006/014; dual-control→AD-012/016/021/`08 §15`; cost-DoS→`06 §24.5`/`12 §20`). No frozen document or ADR was modified.

### Recalculated Security Readiness Score: **98 / 100**
All High/Medium resolved; internal mTLS, dual-control, and cost-DoS are now first-class and enforceable. The 2 residual points reflect the **inherent, disclosed** limitations above (prompt injection, provider/insider/KMS concentration) — not defects.

### Remaining findings
**🔴 Critical: none. 🟠 High: none. 🟡 Medium: none.** 🟢 Low ×4 deferred to `14`/`16`/module design — non-blocking.

### Recommendation: **FREEZE at v1.0.** Zero Critical, zero High. Consistency review clean.

---

*End of document — 13-Security-Standards.md (v1.0, frozen)*
