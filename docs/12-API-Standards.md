# 12 — API Standards (The API Constitution)

**Document:** API Standards & Governance
**Project:** Reliability-First AI Gateway
**Status:** **Approved & Frozen (v1.0, 2026-07-20)** — **binding API standard**
**Corrective pass applied:** H1 streaming contract (§16), H2 envelope decision (§10, API-D2), H3 webhook security (§15.1), M1–M5 (§§15/19/20/21/22). Frozen after a clean §35 consistency review (no Critical/High remaining; §36 score 97/100).
**Audience:** Every engineer, API designer, reviewer, SDK author, and the API Design Review Board
**Classification:** Internal — Engineering Standard
**Builds on (approved, frozen):** `00`–`11` and ADRs **AD-001…AD-023**. Nothing here may contradict them.
**Stack:** Java 21 · Spring Boot 3.x · Spring Cloud Gateway · Virtual Threads · PostgreSQL · MongoDB · Valkey · Kafka · OpenTelemetry · **Avro (events)** · **Apicurio** · Kubernetes · **OpenAPI 3.1 (REST) + JSON Schema** · OAuth2 · JWT · mTLS.

> **What this is.** The permanent law for every API the platform exposes — the **data-plane request contract** and the **control-plane admin/control contracts** (`04`/`06`). It defines URIs, methods, status codes, headers, envelopes, errors, pagination, versioning, idempotency, streaming, auth, rate limiting, caching, validation, security, observability, OpenAPI, SDK, compatibility, performance, testing, governance, anti-patterns, build-failing rules, traceability, and a consistency review. **No code, controllers, DTOs, or implementation** — only rules and contracts. Format split is fixed by `10 §7`: **REST payloads = JSON validated by JSON Schema via OpenAPI 3.1; Kafka events = Avro.**

---

## Table of Contents
1. Purpose · 2. Goals · 3. Scope · 4. API Design Principles · 5. URI Standards · 6. HTTP Methods · 7. HTTP Status Codes · 8. Headers · 9. Request Envelope · 10. Response Envelope · 11. Error Envelope · 12. Pagination/Sorting/Filtering · 13. Versioning · 14. Idempotency · 15. Long-Running Operations · 16. Streaming APIs · 17. File Upload · 18. Authentication · 19. Authorization · 20. Rate Limiting · 21. Caching · 22. Validation · 23. Security · 24. Observability · 25. OpenAPI Standards · 26. SDK Compatibility · 27. Backward Compatibility · 28. Deprecation Lifecycle · 29. Performance Standards · 30. Testing Requirements · 31. Governance · 32. Anti-Patterns (50+) · 33. Build-Failing Rules · 34. Traceability · 35. Internal Consistency Review · 36. Adversarial Review, Score & Issues

---

## 1. Purpose
Define every rule an API must follow so that any team can design a correct, consistent, secure, evolvable API without asking "how." This is the API constitution: predictable for consumers, safe for the platform, stable for a decade (AD-015).

## 2. Goals
- **Consistency** across all services and SDKs (Java/Python/TS/Go).
- **Backward compatibility** and predictable evolution (AD-015).
- **Security & tenant isolation by default** (AD-012/021).
- **Observability & auditability by construction** (`07`/`11 §G`).
- **Provider independence** — the API never leaks provider specifics (`00`, AD-007).
- **Machine-readable everything** — OpenAPI 3.1 is the source of truth; SDKs and docs are generated.

## 3. Scope
**In scope:** all externally- and internally-exposed HTTP APIs (data-plane request contract, control-plane admin/control APIs), their streaming variants, auth, and their OpenAPI specs in `apis/`. **Out of scope:** Kafka event contracts (governed by `07`/`schemas/`, Avro), gRPC/internal in-process module `api` (governed by `11`), and implementation. Where a rule differs for **data-plane** (developer-facing, latency-critical) vs **control-plane** (admin, richer), it is marked **[DP]**/**[CP]**.

---

## 4. API Design Principles

| Principle | Rule |
|---|---|
| **REST-first** | Resource-oriented HTTP/JSON; RPC-style verbs in URIs only for explicit actions (`:action` sub-resource), never as the default. |
| **Predictable** | Same patterns everywhere: URIs, status codes, errors, pagination, headers are uniform across services. |
| **Provider-agnostic** | The contract exposes a neutral model; **no provider name, field, or error leaks** into the public API (AD-007, `00`). |
| **Idempotent** | Unsafe operations accept an `Idempotency-Key`; safe methods are inherently idempotent (§14). |
| **Backward-compatible** | Additive-only within a major; breaking → new major + deprecation (§27/§28, AD-015). |
| **Self-describing** | OpenAPI 3.1 + hypermedia-lite links for relations & next-page; discoverable via `OPTIONS`/spec. |
| **Stateless** | No server session; every request carries its own auth/tenant/correlation (AD-006). |
| **Observable** | Every request is traced, correlated, metered, and audited (§24, `07`). |
| **Secure** | Zero-trust: authenticated, authorized, encrypted, validated (§18/19/23, AD-012). |
| **Tenant-aware** | Every request is tenant-scoped; isolation is absolute (§19, AD-021). |

---

## 5. URI Standards

- **Base:** `https://api.<domain>/{plane}/v{MAJOR}/...` — data plane and control plane are distinct hosts/paths.
- **Naming:** lowercase, **kebab-case** path segments; **plural nouns** for collections; resource IDs are opaque strings. No verbs in paths except explicit actions as a sub-resource with a leading colon: `POST /v1/requests/{id}:cancel`.
- **Nesting:** shallow — nest to express ownership one level (`/tenants/{tid}/projects/{pid}`); **never** nest beyond 2 levels; prefer top-level resources with filters over deep nesting.
- **Versioning:** **major version in the path** (`/v1`) — §13.
- **Examples (illustrative, not endpoints to build):** `GET /v1/projects/{id}` · `GET /v1/projects?tenant_id=...&cursor=...` · `POST /v1/completions` · `POST /v1/requests/{id}:cancel` · `GET /v1/operations/{id}`.
- **Forbidden patterns:** verbs in paths (`/getUser`, `/createProject`); file extensions (`.json`); trailing slashes; query params for identity; PII/secrets in URIs (they land in logs — §23); unbounded deep nesting; snake_case or camelCase in paths (kebab-case only); action verbs without the `:action` form.

---

## 6. HTTP Methods

| Method | Use | Idempotent? | Body | Rules / Prohibited |
|---|---|---|---|---|
| **GET** | Read a resource/collection | Yes (safe) | none | Never mutates; never has side effects; cacheable per §21. **Prohibited:** GET with a request body for semantics. |
| **POST** | Create; non-idempotent actions; inference | No (unless Idempotency-Key) | yes | Creation → `201` + `Location`; actions → `200/202`. **Requires** `Idempotency-Key` for consequential ops (§14). |
| **PUT** | Full replace / idempotent upsert | Yes | full | Full representation only; **prohibited** for partial updates. |
| **PATCH** | Partial update | Should be (with `If-Match`) | partial | Use **JSON Merge Patch (RFC 7386)** as default; JSON Patch (RFC 6902) only where explicitly needed. **Requires** `If-Match` for concurrency (§8/§21). |
| **DELETE** | Remove/retire | Yes | none | Idempotent (deleting twice → `204`/`404` consistently); soft-delete/retire preferred for audited resources. |
| **OPTIONS** | Discovery/CORS preflight | Yes | none | Advertises allowed methods; never mutates. |
| **HEAD** | Metadata/existence | Yes | none | Same headers as GET, no body; for cache validation. |

- **Prohibited:** custom methods; tunneling (`POST` with `_method`); mutating GET; overloading POST for reads.

---

## 7. HTTP Status Codes (complete matrix)

| Code | Meaning | When (rules) |
|---|---|---|
| **200** OK | Success w/ body | GET, successful action/inference, PUT/PATCH returning representation |
| **201** Created | Resource created | POST create; **must** include `Location` + the created representation |
| **202** Accepted | Async accepted | Long-running op (§15); **must** return an operation resource + `Location` |
| **204** No Content | Success, no body | DELETE, PUT/PATCH with no body returned |
| **400** Bad Request | Malformed/invalid syntax | Unparseable body, bad params (distinct from 422) |
| **401** Unauthorized | Missing/invalid auth | No/invalid token/mTLS (§18); `WWW-Authenticate` set |
| **403** Forbidden | Authn ok, not permitted | Scope/RBAC/tenant denial (§19) — **never** reveal existence of forbidden resources |
| **404** Not Found | Resource absent OR hidden-for-authz | Also used to avoid leaking existence (§19/§23) |
| **409** Conflict | State conflict | Duplicate create, version conflict without ETag, concurrent modification |
| **410** Gone | Permanently removed | Sunset/removed version or resource (§28) |
| **412** Precondition Failed | `If-Match`/`If-Unmodified-Since` failed | Optimistic-concurrency mismatch (§21) |
| **415** Unsupported Media Type | Wrong `Content-Type` | Non-JSON where JSON required, unsupported upload type |
| **422** Unprocessable Entity | Valid syntax, **semantic** validation failure | Business/field validation errors (with `errors[]`, §11) |
| **429** Too Many Requests | Rate/quota exceeded | Includes `Retry-After` + `RateLimit-*` (§20) |
| **500** Internal Server Error | Unexpected gateway fault | Never leak internals; correlationId returned; alerted |
| **502** Bad Gateway | Upstream provider returned invalid/failed | Provider error surfaced as a **neutral** gateway error (not provider-specific) |
| **503** Service Unavailable | Overload/maintenance/degraded | With `Retry-After`; used for shed load / circuit-open |
| **504** Gateway Timeout | Upstream provider timed out | Provider-timeout surfaced neutrally; correlationId returned |

- **Rules:** use the **most specific** code; **400 vs 422** — 400 = can't parse/route; 422 = parsed but invalid. **502/503/504** are the neutral surface for provider failures (never expose provider status/messages, AD-007). Every non-2xx returns the **error envelope** (§11).

---

## 8. Headers (standard set)

| Header | Direction | Rule |
|---|---|---|
| `Authorization: Bearer <jwt>` | in | Required for authenticated endpoints (§18); mTLS may substitute for service-to-service |
| `X-Correlation-Id` | in/out | Client-provided or gateway-generated; propagated to logs/traces/events (`07`, §24). **Always echoed.** |
| `X-Request-Id` | out | Per-request unique id (server-assigned); returned on every response |
| `X-Tenant-Id` | in | Tenant scope (or derived from token claim); **must** resolve to the token's tenant (no cross-tenant, AD-021) |
| `X-Region` | in/out | Residency/region routing hint; enforced against policy (`08` residency) |
| `traceparent` / `tracestate` | in/out | **W3C Trace Context** — mandatory propagation (§24, OTel) |
| `Content-Type` | in/out | `application/json` (REST); `text/event-stream` (SSE, §16); `application/problem+json` (errors) |
| `Accept` | in | Content negotiation; default `application/json` |
| `Idempotency-Key` | in | Required for consequential POST/PATCH (§14); UUID/opaque, client-generated |
| `If-Match` / `If-None-Match` | in | Optimistic concurrency / conditional requests (ETag, §21) |
| `ETag` | out | Strong entity tag for cacheable/versioned resources (§21) |
| `RateLimit-Limit` / `RateLimit-Remaining` / `RateLimit-Reset` | out | **IETF `RateLimit` header fields**; present on rate-limited endpoints (§20) |
| `Retry-After` | out | On 429/503; seconds or HTTP-date |
| `Deprecation` | out | **RFC 8594** — set when an endpoint/version is deprecated (§28) |
| `Sunset` | out | **RFC 8594** — the removal date (§28) |

- **Rules:** custom headers are `X-`-prefixed only where no standard exists (prefer standards: `RateLimit-*`, `Deprecation`, `Sunset`, `traceparent`). **Never** put secrets/PII/tokens-beyond-Authorization in headers logged by default (§23). Correlation & trace propagation are **mandatory**.

---

## 9. Standard Request Envelope

**Decision (API-D1): No mandatory body wrapper. Cross-cutting metadata travels in HEADERS, not the body.** The request body **is** the resource/command representation (JSON, JSON-Schema-validated). We deliberately reject a heavy `{"meta":{...},"data":{...}}` request wrapper (it duplicates headers, bloats payloads, and complicates SDKs — a common anti-pattern, §32).

- **Resource requests** (create/update): body = the resource fields directly.
- **Command/inference requests** (e.g., completions): body = the command's typed fields (neutral, provider-agnostic).
- **Batch requests** (where offered, §15): a top-level `items: [...]` array with a shared `batch_options` object — the *only* sanctioned request "envelope."
- **Metadata** (idempotency, tenant, correlation, region, auth) → **headers** (§8).

## 10. Standard Response Envelope

**Decision (API-D2): Single resources are returned directly (no wrapper); collections use one standard pagination envelope; all responses carry standard headers.** This matches how Stripe/Google return resources and avoids the "everything wrapped in `data`" anti-pattern.

- **Single resource:** the resource JSON directly (`200`), with `ETag`, `X-Request-Id`, `X-Correlation-Id`, `traceparent`.
- **Collection (standard pagination envelope):**
  - `data`: array of resources
  - `page`: `{ next_cursor?, prev_cursor?, has_more, limit }`
  - `links`: `{ self, next?, prev? }` (hypermedia-lite)
- **Created (`201`):** the created resource + `Location`.
- **Async (`202`):** an **operation** resource (§15) + `Location`.
- **All responses:** standard observability headers (§8/§24); no server session; deterministic field ordering not required (clients must not depend on it).

**Governance decision (API-D2, ratified) — the lightweight envelope model is the permanent standard:**
- **Standard responses SHALL return the resource directly** (no `{data,meta}` wrapper).
- **Collections SHALL use the pagination envelope** above (the *only* sanctioned body wrapper).
- **Errors SHALL use RFC 9457 Problem Details** (§11).
- **Metadata SHALL be transmitted through headers** (correlation, request id, trace, rate-limit, deprecation, ETag) **unless it is functionally impossible** to express in a header — in which case it is documented per endpoint.
- **Advisory `warnings`** (e.g., deprecation, clamped page size) are surfaced via the `Warning`/`Deprecation` headers, not by wrapping the body.

**Why heavy wrapper envelopes are rejected (rationale):**
1. **Duplication** — a body `meta` block re-encodes what headers already carry (correlation, request id, rate limits), violating single-source-of-truth.
2. **Payload bloat & latency** — every response pays for a wrapper it rarely needs; on the latency-critical data plane (`06 §11`) this is pure overhead.
3. **SDK ergonomics** — clients want the resource, not `response.data.data`; wrappers force unwrap boilerplate in every language (`10`/`sdk/`).
4. **Cache/HTTP semantics** — ETag/conditional requests/streaming operate on the resource; a wrapper breaks clean HTTP caching (§21) and SSE (§16).
5. **Consistency with the ecosystem** — Stripe/Google/GitHub return resources directly; matching this lowers integration friction.
6. **Errors are already uniform** — Problem Details (§11) gives the one place a machine-readable envelope is genuinely needed. Uniformity is achieved by **headers + Problem Details**, not by wrapping success bodies.

## 11. Standard Error Envelope

**Built on RFC 9457 Problem Details** (fixed by `11` R-030), media type `application/problem+json`, with governed extensions. **Machine-readable, provider-neutral, safe** (never leaks internals/provider messages).

**Fields:**
- `type` (URI identifying the error class), `title`, `status`, `detail` (safe, human-readable), `instance` (this occurrence)
- **Extensions:** `code` (stable machine code, below), `category` (below), `correlation_id`, `retryable` (bool), `retry_after` (when applicable), `errors[]` (field-level: `{ field, code, message }` for 422), `doc_url`

**Error code scheme:** `gateway.<category>.<specific>` — stable, documented, SDK-mapped. Examples: `gateway.validation.field_required`, `gateway.auth.token_expired`, `gateway.authz.forbidden`, `gateway.rate_limit.exceeded`, `gateway.provider.upstream_error`, `gateway.provider.timeout`, `gateway.correctness.non_conforming_output`, `gateway.governance.policy_denied`, `gateway.compliance.residency_violation`, `gateway.gateway.internal`.

**Categories & mapping:**

| Category | HTTP | Retryable | Notes |
|---|---|---|---|
| `validation` | 400/422 | No | field-level `errors[]` |
| `auth` (authentication) | 401 | No | `WWW-Authenticate` |
| `authz` (authorization) | 403/404 | No | 404 to avoid existence leak |
| `not_found` | 404 | No | |
| `conflict` | 409/412 | No | concurrency/state |
| `rate_limit` | 429 | **Yes** | `Retry-After`, `RateLimit-*` |
| `provider` (upstream) | 502/503/504 | **Often** | **neutralized** — no provider details |
| `correctness` | 422/502 | Maybe | non-conforming/tool-call/stream failure (`07`) |
| `governance` | 403 | No | policy/residency/quota denial |
| `compliance` | 403 | No | residency/data-handling |
| `gateway` (internal) | 500/503 | Maybe | correlationId returned; alerted |

- **Rules:** exactly one envelope for all errors; `retryable` tells clients/SDKs when to retry (with backoff); **no stack traces, no provider messages, no internal identifiers** in `detail` (§23). Validation errors are always `422` with `errors[]`.

---

## 12. Pagination, Sorting, Filtering, Searching

- **Pagination (default = cursor):** opaque, forward-stable **cursor** (`?cursor=...&limit=...`); response `page.next_cursor`/`has_more`. **Offset pagination is discouraged** (unstable under writes, expensive at depth) and **prohibited beyond a bounded offset** for large collections. **[CP]** offset permitted for small admin lists.
- **Max page sizes:** default `limit=25`, **max `limit=100`** (data-plane); **max 500** for bulk export (`[CP]`); requests above max → clamp + `warning` or `400` (documented per endpoint).
- **Sorting:** `?sort=field` / `?sort=-field` (descending); only whitelisted fields; multiple via comma; unstable-sort → tiebreak on id.
- **Filtering:** explicit, whitelisted query params (`?status=active&created_after=...`); **no arbitrary query DSL** in the URL (injection/complexity risk, §23/§32); complex filters → a documented, validated filter object on a `POST /:search` action.
- **Searching:** dedicated `POST /:search` where needed; validated, bounded, tenant-scoped.

---

## 13. Versioning Strategy

- **Primary: URI major version** (`/v1`, `/v2`) — one axis, unambiguous, cache/proxy-friendly (`11` R-027).
- **Header/media-type versioning:** **rejected as the primary scheme** (harder to test, cache, and reason about; hidden from URLs). Minor/backward-compatible evolution is expressed **within** a major via additive fields — **not** a new version.
- **Compatibility:** additive-only within a major (§27); consumers tolerate unknown fields (forward-compat).
- **Breaking-change rules:** any of {removing/renaming a field, changing a type/semantics, removing an endpoint/enum value, tightening validation, changing an error code} = **breaking** → **new major** + deprecation of the old (§28) + Design-Review-Board approval (§31). **Never** break within a major.
- **Deprecation:** signaled via `Deprecation`/`Sunset` headers + OpenAPI `deprecated: true` (§28).

---

## 14. Idempotency

- **`Idempotency-Key` header** (client-generated UUID/opaque) is **required** on consequential `POST`/`PATCH` (create, inference-with-side-effects, money, provisioning) — `11` R-046, `NFR-RTY-001`.
- **Semantics:** first request executes and its result is **stored keyed by (tenant, endpoint, idempotency-key)**; a replay with the same key returns the **same stored response** without re-executing side effects. A same-key request with a **different body** → `409` (`gateway.conflict.idempotency_key_reuse`).
- **PUT/DELETE** are idempotent by definition (no key needed).
- **Storage:** Valkey (fast) + durable backing for money/consequential ops; scoped per tenant (isolation). **TTL: 24h default** (configurable per endpoint; ≥ the max client retry window).
- **Replay detection:** in-flight duplicate → `409` `idempotency_key_in_flight` or await-and-return (documented); completed → cached response.
- **Retries:** SDKs/clients retry only on `retryable=true` errors, with backoff+jitter, reusing the same key (safe by construction).

---

## 15. Long-Running Operations

- **Async pattern:** `POST` returns **`202`** + an **operation resource** (`GET /v1/operations/{id}` → `{ id, status: pending|running|succeeded|failed|cancelled, result?, error?, progress? }`) + `Location`.
- **Polling:** clients poll the operation with backoff; `Retry-After` hints cadence; operations are tenant-scoped and idempotent to create (§14).
- **Callbacks/Webhooks:** optionally, on completion the platform emits a **webhook** (Extensibility service, `06`/EV-D2) — see the complete webhook security & delivery spec in §15.1.
- **Cancellation:** `POST /v1/operations/{id}:cancel` → best-effort; terminal states are immutable.
- **Batch** (`[CP]`/bulk): submit `items[]` → an operation; results retrievable/streamable — see the partial-failure contract in §15.2.

### 15.1 Webhook Security & Delivery (complete)
Webhooks are delivered by the Extensibility service (`06` C12, EV-D2), consuming events (`07`) and delivering to customer endpoints. The full security & delivery contract:

- **HMAC signatures:** every delivery is signed **HMAC-SHA256** over `"<timestamp>.<raw-body>"` using a per-subscription signing secret, sent as `X-Gateway-Signature: v1,t=<unix_seconds>,sig=<hex>`. Consumers recompute and **constant-time compare**; reject on mismatch.
- **Signature versioning:** the scheme version is in the header (`v1`); a new scheme is added additively (`v2`) with an **overlap window** during which both are sent (comma-separated) — never a silent break (AD-015).
- **Timestamp validation:** consumers **reject deliveries older than 5 minutes** (`|now − t| > 300s`) — bounds the replay window; clock-skew tolerance documented.
- **Replay protection:** timestamp window **plus** event-`id` dedup; the Gateway **never re-signs a replay with a new timestamp for the same event id**, so a captured delivery cannot be replayed within the window and is caught by id dedup outside it.
- **Signing-secret rotation:** two active signing secrets during rotation (**overlap window**, default 24 h); deliveries are signed with the current secret; consumers accept **either** during overlap. Secrets are stored in the Secret service (`08 §14`), **never** exposed in the API or logs; rotation is an audited admin action.
- **Delivery retries:** **at-least-once** with **exponential backoff + jitter**; retry on connection failure or non-2xx response; each attempt is signed with a fresh timestamp but the **same event id**.
- **Maximum retry duration:** retries continue up to a bounded window (**default 24 h**, configurable per subscription); after that the delivery is exhausted.
- **Dead-letter behavior:** an exhausted delivery is routed to the **webhook DLQ** (`07 §14`), raises an alert, is visible to the tenant admin, and is **replayable** (manually or automatically) — **never silently dropped** (BRULE-1).
- **Idempotency & duplicate detection:** every event carries a stable `id`; delivery is **at-least-once**, so consumers **MUST dedup by `id`** (idempotent processing, `07 §13`). Retries of the same event always carry the same `id`.
- **Event ordering:** **best-effort per subscription; strict ordering is NOT guaranteed** across retries (a retried older event may arrive after a newer one). Consumers order using `occurred_at` + the per-aggregate `sequence` in the payload; this limitation is documented in the webhook contract.
- **Audit logging:** **every** delivery attempt (success, failure, DLQ) and every signing-secret access/rotation is audited (`07`/`08 §10`), correlation-linked (`06 §23`).
- **Payload:** the canonical event envelope (`07 §6`, neutralized) — no provider specifics, no secrets/regulated data beyond policy (`07 §17`).

### 15.2 Batch partial-failure contract (M4)
- A batch submits `items[]` (§9) and returns a **batch operation** (`202`). The operation's result reports **per-item outcomes**: `results[]` where each is `{ index, id, status: succeeded|failed, result?, error? }` with `error` as a Problem-Details object (§11).
- The overall operation `status` is **`succeeded`** (all items ok), **`partial`** (some failed), or **`failed`** (all/setup failed) — **partial failures are always explicitly reported per item, never silently dropped** (BRULE-1, `PRB-024`).
- Item order in `results[]` matches input `index`; large batches stream results (§16) or paginate (§12).

---

## 16. Streaming APIs — The Canonical Provider-Neutral Streaming Contract

**Governance decision (API-D3):** all model-output streaming is exposed through **one canonical, provider-neutral stream model** over **Server-Sent Events (SSE)** (`text/event-stream`). **No provider-specific event name, field, id, finish-reason, error, or metadata may appear outside the Gateway** (AD-007, `00`). Provider adapters translate each provider's native stream into the canonical events below; the client sees only the canonical model. WebSocket is offered **only** for genuinely bidirectional/interactive cases (same auth/tenant/correlation), never as the default.

### 16.1 Canonical stream event types
Each SSE message uses `event: <type>` and a JSON `data` payload. Every event carries: `id` (gateway stream id), `sequence` (monotonic per stream, starts at 0), `type`, `data`. The correlation id spans the whole stream (header + audit).

| `event:` type | Meaning | `data` (neutral) |
|---|---|---|
| `stream.start` | Stream opened | `{ stream_id, created_at, model_class? }` (neutralized; no provider model id) |
| `content.delta` | Ordered text content chunk | `{ sequence, text }` |
| `content.done` | A content block completed | `{ block_index }` |
| `tool_call.start` | A tool call begins | `{ tool_call_id, index, name }` |
| `tool_call.delta` | Incremental tool-call argument fragment (partial JSON) | `{ tool_call_id, index, arguments_fragment }` |
| `tool_call.done` | Tool call complete, **reconstructed & JSON-Schema-validated** | `{ tool_call_id, index, name, arguments }` (validated object) |
| `usage` | Authoritative token/cost accounting | `{ input_tokens, output_tokens, cost }` (neutral, `05`) |
| `stream.done` | **Terminal success** | `{ finish_reason, usage, complete: true }` |
| `stream.error` | **Terminal error** | RFC 9457 **Problem Details** (§11), neutralized |
| (SSE comment `:ping`) | Heartbeat (not sequenced) | — |

### 16.2 Chunk ordering guarantees
- Events are delivered in **strict `sequence` order**, **exactly once per sequence** — no gaps, no duplicates, no reordering. The Gateway reassembles provider fragments and re-sequences them.
- If the Gateway detects a gap, duplicate, or out-of-order fragment from the provider that it cannot reconcile, it **fails the stream with `stream.error`** — it **never** forwards a corrupted or silently-truncated stream (`07`/`NFR-STRM-001`, BRULE-1).

### 16.3 Tool-call streaming behavior
- Providers stream tool-call arguments incrementally (partial JSON). The Gateway emits `tool_call.start`, then ordered `tool_call.delta` fragments, then **`tool_call.done` only after the full arguments are reconstructed and validated against the declared JSON Schema** (`NFR-SO-003`).
- **Corrupted, incomplete, or non-conforming tool-call arguments are never delivered as `tool_call.done`** — the stream fails with `stream.error` (`gateway.correctness.tool_call_invalid`). Tools are never executed on unvalidated arguments (BRULE-1, AD-018).
- Parallel tool calls are disambiguated by `tool_call_id` + `index`.

### 16.4 Partial JSON reconstruction (structured output)
- When the response is schema-constrained JSON, the Gateway **accumulates** deltas and **never emits partial JSON as if it were a valid result**. It may forward raw `content.delta` text, but the **validated structured object is only produced at completion**, after the full JSON validates against the declared JSON Schema (`NFR-SO-001`, `10 §7`).
- Non-conforming final JSON → `stream.error` (`gateway.correctness.non_conforming_output`) — surfaced, never silently delivered.

### 16.5 Completion events
- A stream **MUST** terminate with **exactly one** of `stream.done` (success) or `stream.error` (failure) — an ambiguous connection close is **not** a valid completion. `finish_reason` is a **neutral enum**: `stop | length | tool_calls | content_filter | cancelled | error` (provider reasons mapped in).
- On a dropped connection without a terminal event, the client treats it as an error; the Gateway records a surfaced failure and completes the audit record (`06 §23.11`).

### 16.6 Cancellation
- **Client disconnect (TCP close) or `POST /v1/requests/{id}:cancel`** cancels upstream provider work **promptly** (resource lifecycle, `11` R-051); the Gateway propagates cancellation to the provider adapter and stops billing further tokens.
- If still open, a `stream.error` with `gateway.gateway.cancelled` may be emitted; accounting reflects partial usage (`usage`).

### 16.7 Heartbeats
- SSE comment lines (`:ping`) are sent at a fixed interval (default **15 s**) to keep intermediaries/load-balancers alive and detect dead streams. Heartbeats carry **no content** and are **not** part of `sequence`.

### 16.8 Retry semantics
- Streams are **not auto-resumed mid-stream** by default (stateful mid-stream resumption is fragile). A failed stream returns `stream.error` with `retryable`; the client **re-issues the whole request with the same `Idempotency-Key`** (§14) to retry safely.
- **Optional resumable streaming** (SSE `Last-Event-ID` + a Gateway resume token) MAY be offered where a provider supports it — documented per endpoint; never the default.

### 16.9 Backpressure behavior
- The Gateway applies **bounded buffering with backpressure** between provider and client: a slow client throttles the provider read; the Gateway **never buffers unboundedly** (`NFR-Q-001`, `11` R-052/R-053).
- If a slow consumer exceeds the bounded buffer, the stream **fails with a surfaced `stream.error`** — never silent truncation, never OOM. If the client is gone, the upstream is cancelled (16.6).

### 16.10 Provider mapping (how each provider maps into the canonical model)
Provider adapters (AD-007) are the **only** place provider-native formats exist. **No provider-native field, event name, id format, finish-reason, error, or metadata leaks past the adapter.**

| Provider | Native stream | → Canonical mapping |
|---|---|---|
| **OpenAI** | Chat Completions SSE (`data:{choices:[{delta:{content|tool_calls}}], finish_reason}`, `[DONE]`) | `delta.content`→`content.delta`; incremental `delta.tool_calls` (function args)→`tool_call.start/delta/done`; `finish_reason`→neutral enum; `[DONE]`→`stream.done`; `usage` (via `stream_options.include_usage`)→`usage` |
| **Claude (Anthropic)** | Event stream: `message_start`, `content_block_start`, `content_block_delta` (`text_delta`/`input_json_delta`), `content_block_stop`, `message_delta`, `message_stop` | `message_start`→`stream.start`; `text_delta`→`content.delta`; `input_json_delta`→`tool_call.delta`; `content_block_stop`(tool)→`tool_call.done` (validated); `message_delta.stop_reason`→neutral; `message_stop`→`stream.done`; usage→`usage` |
| **Gemini (Google)** | `streamGenerateContent` chunks (`candidates[].content.parts[].text`/`functionCall`, `finishReason`, `usageMetadata`) | text parts→`content.delta`; `functionCall`→`tool_call.*`; `finishReason`→neutral; `usageMetadata`→`usage`; final chunk→`stream.done` |
| **Bedrock** | `InvokeModelWithResponseStream` / Converse stream events (`contentBlockDelta`, `messageStop`, `metadata`) — underlying model may be Claude/Titan/Llama | `contentBlockDelta`→`content.delta`/`tool_call.delta`; `messageStop`→`stream.done`; `metadata.usage`→`usage`; the adapter normalizes the underlying model's specifics |
| **OpenRouter** | OpenAI-compatible SSE (+ OpenRouter-specific fields) | mapped as **OpenAI**; adapter **strips** all OpenRouter-specific fields/metadata (none leak) |
| **LiteLLM** | OpenAI-compatible proxy format | mapped as **OpenAI-compatible**; treated as a provider behind the adapter; no LiteLLM-specific fields leak |

**Neutralization rule:** provider ids → Gateway ids; provider `finish_reason`/`stop_reason` → the canonical enum (16.5); provider errors → neutral Problem Details (§11, 502/504); any unmapped provider field is dropped, never forwarded. Adapter contract tests (`11` R-084, `03 §54`) verify no provider field escapes.

---

## 17. File Upload

- **Transport:** `multipart/form-data` **or** direct binary with explicit `Content-Type`; **streamed**, never fully buffered (`11` R-053).
- **Limits:** documented max size per endpoint (default ≤ 25 MB `[CP]`; data-plane uploads discouraged); reject oversize with `413`.
- **Validation:** strict `Content-Type` allow-list; magic-byte sniffing (don't trust the declared type); filename sanitization; reject archives/executables unless explicitly allowed.
- **Malware scan:** uploads are **scanned** before acceptance/use; infected → `422`/`gateway.validation.malware_detected`; scanning is mandatory for anything stored or forwarded.
- **Governance:** uploaded content is classified/redacted per `08 §14` (regulated data); residency-confined; never logged.

---

## 18. Authentication

- **User/interactive:** **OAuth2** (Authorization Code + PKCE) → **JWT** access tokens (short-lived), refresh per policy. **Validate** signature (JWKS), `iss`, `aud`, `exp`, `nbf`, and tenant claim.
- **Service-to-service:** **mTLS** (mutual TLS) and/or **OAuth2 Client Credentials** → JWT; service identities are least-privilege (`06`/AD-012).
- **API keys:** supported for simple SDK/dev auth — **scoped, tenant-bound, revocable, hashed-at-rest, never logged**; treated as bearer credentials over TLS only; **not** sufficient alone for high-assurance/admin ops (require OAuth2/mTLS there).
- **Service accounts:** first-class principals with scopes; rotated credentials (`08` secrets).
- **Scopes:** granular (`completions:write`, `projects:read`, `admin:governance`); tokens carry minimal scopes (least privilege).
- **Rules:** every protected endpoint authenticates (deny-by-default, `11` R-071); `401` with `WWW-Authenticate` on failure; tokens validated on **every** request (stateless, AD-006); no auth material in URLs/logs (§23).

## 19. Authorization

- **Model:** **RBAC + scopes + tenant isolation**, enforced at the PEP (`06`, deny-by-default). Permissions checked **before** any side effect (`11` R-071, AD-018).
- **Tenant isolation:** the token's tenant is authoritative; `X-Tenant-Id` must match; **cross-tenant access is impossible** (AD-021) — mismatches → `403`/`404`.
- **Object-level authz:** verify the caller may act on the **specific** resource, not just the type (OWASP API1 BOLA, §23).
- **403 vs 404 decision matrix (M2):**

| Situation | Return |
|---|---|
| Authenticated, lacks **scope/role** for the operation type | **403** `gateway.authz.forbidden` |
| Authenticated, resource belongs to **another tenant** | **404** (existence hidden — cross-tenant must be invisible, AD-021) |
| Authenticated, resource exists in tenant but caller lacks **object-level** rights, and existence is **non-sensitive** | **403** |
| Authenticated, resource exists but its **existence is sensitive** (e.g., another user's private object, secrets, audit records) | **404** (hide existence) |
| Resource genuinely does not exist | **404** |
| Unauthenticated | **401** |

  **Rule:** default to **404** whenever revealing existence is itself a leak (cross-tenant, sensitive objects); use **403** only when the caller is legitimately allowed to know the resource exists but not to perform the action. Each resource's OpenAPI documents its choice.

---

## 20. Rate Limiting

- **Layers:** **global** (platform protection), **per-tenant** (quota/fairness, `06 §24.5`/`NFR-SCALE-002`), **per-API-key/principal**, and **burst** control. Enforced regionally with async global reconciliation for hard caps (`08 §24.5`).
- **Algorithm:** **sliding-window** (or token-bucket) counters in Valkey; deterministic, low-latency; leases checked node-locally (`08` r3).
- **Headers (M3 — compatibility resolved):** the **canonical** headers are the **IETF `RateLimit` fields** — `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` — plus `Retry-After` on `429`. For ecosystem/tooling compatibility, the Gateway **also emits the legacy `X-RateLimit-Limit`/`X-RateLimit-Remaining`/`X-RateLimit-Reset` mirrors** with identical values during a transition period; SDKs read the canonical `RateLimit-*` first and fall back to `X-RateLimit-*`. The legacy mirrors are deprecation-tracked (§28) and removed once SDK adoption is complete.
- **429 behavior:** return the error envelope (`gateway.rate_limit.exceeded`, `retryable=true`) + `Retry-After`; SDKs back off + jitter; never a hard drop without headers.
- **Fairness:** one tenant cannot starve others (noisy-neighbor prevention).

## 21. Caching

- **Inference/LLM responses:** **`Cache-Control: no-store` by default** — never cached at the edge/proxy (correctness + sensitivity, `NFR-CACHE-001`); caching is an internal, governed optimization only (`08`), never a client-visible cache of model output by default.
- **Config/metadata/read resources:** support **ETag** (strong) + `Cache-Control` (short max-age or `no-cache` with revalidation); **conditional GET** (`If-None-Match` → `304`); **conditional writes** (`If-Match` → `412` on mismatch, optimistic concurrency).
- **`Vary` guidance (M5 — mandatory):** any cacheable response **MUST** set **`Vary: Authorization, Accept, X-Tenant-Id`** (add `Accept-Encoding` when compressed, `X-Region` when residency-routed). Authenticated responses are **`Cache-Control: private`** (never stored by shared caches). This makes it structurally impossible for an intermediary to serve one tenant's/principal's response to another — the caching complement of tenant isolation (AD-021). Inference/model output stays `no-store` (above); tenant-scoped data is **never** cached across tenants.

## 22. Validation

- **REST bodies:** validated by **JSON Schema via OpenAPI 3.1** (fixed, `10 §7`); reject unknown-required violations; `422` + `errors[]` on semantic failures (§11). **Events:** **Avro** (Apicurio) — not REST's concern.
- **AI structured output:** validated against **JSON Schema** (the declared output contract, `10 §7`, `NFR-SO`); non-conforming → `gateway.correctness.non_conforming_output` (surfaced, never silently delivered — BRULE-1).
- **Field naming (M1 — resolved):** **`snake_case` is the canonical wire format** for all JSON fields (public developer-API convention). **Code-generation guidance:** the OpenAPI spec uses `snake_case`; SDK generators are configured to map wire `snake_case` → language-idiomatic member names — **Java/TypeScript → `camelCase`**, **Python/Go → `snake_case`** — via the generator's naming strategy, while serialization always uses the `snake_case` wire names (Jackson `@JsonProperty`/`PropertyNamingStrategy.SNAKE_CASE` per `11` R-008). The wire contract never changes with language; casing is purely a client-ergonomics concern handled by generation. This is a **fixed governance decision**, not open.
- **Dates/times:** **RFC 3339 / ISO-8601 in UTC** (`Z`), string type (`11` R-007). Durations: ISO-8601 or explicit unit fields.
- **Enums:** `UPPER_SNAKE_CASE` string values; **open-enum handling** — clients tolerate unknown values (forward-compat, §27); adding a value is non-breaking, removing is breaking.
- **Nullability:** **absent ≠ null**; absent = "unchanged/not provided" (esp. PATCH), null = "explicitly cleared." Documented per field; no ambiguous tri-state without contract.
- **Precision:** monetary/precise values as **integer minor units** or **decimal strings** — **never JSON floating point** (`05` Money VO); token counts as integers; ids as strings.
- **Input hardening:** max lengths, bounded arrays/objects, depth limits, content-type enforcement (§23).

## 23. Security (API Governance Board grade)

- **OWASP API Security Top 10 (2023) — mapped & enforced:** API1 BOLA (object-level authz, §19); API2 Broken Auth (§18); API3 Broken Object Property authz (field-level authz); API4 Unrestricted Resource Consumption (rate limits/quotas/limits, §20/§29); API5 Broken Function-level authz (scopes, §19); API6 Unrestricted access to sensitive business flows (idempotency + governance); API7 SSRF (no user-controlled URLs to internal; egress control, `08`); API8 Security Misconfiguration (secure defaults, `11` R-061); API9 Improper Inventory (OpenAPI as inventory, §25; no undocumented endpoints); API10 Unsafe Consumption of 3rd-party APIs (provider responses validated/neutralized, `07`).
- **Replay protection:** `Idempotency-Key` + short token lifetimes + (for high-assurance) request timestamp + nonce; reject stale/duplicate.
- **Input validation & output encoding:** validate everything (§22); encode outputs; parameterized queries only (`11` R-067); reject oversized/deeply-nested payloads.
- **Prompt-injection protection:** untrusted content (prompts, retrieved docs, tool outputs) is treated as data, passed through **content-trust controls** (`BR-019`/`07`); the API never lets untrusted content escalate to instructions or bypass governance; residual risk disclosed (BRULE-9).
- **Secret detection:** requests/inputs are scanned; secrets in a request are rejected/redacted and **never** logged/stored (`08 §14`, `07 §17`).
- **PII/regulated-data protection:** classified, redacted where policy requires, residency-confined; never in URLs/logs/errors (§8/§11/§24).
- **Transport:** TLS 1.2+/1.3 only; mTLS for service-to-service; HSTS.

## 24. Observability

- **Trace IDs:** W3C `traceparent`/`tracestate` propagated end-to-end (OTel); spans at API boundary, provider call, DB, consumer (`11` R-058).
- **Correlation IDs:** `X-Correlation-Id` on every request/response, threaded to logs/traces/**events** (envelope, `07 §6`) and to the `RequestFinalized` audit manifest (`06 §23.11`).
- **Metrics:** per-endpoint RED (rate/errors/duration) + saturation via Micrometer; bounded cardinality (`11` R-057); every SLO has a metric (`03 §62`).
- **Logs:** structured, correlated, **no secrets/PII** (`11` R-055/R-068).
- **Audit IDs:** every request produces an audit record (`07`/`08 §10`), correlation-linked; admin/control actions fully audited (`06` C13).

## 25. OpenAPI Standards

- **Version:** **OpenAPI 3.1** (JSON-Schema-aligned) — the **single source of truth** in `apis/`; SDKs and docs generated from it (`10 §7`, AD-015).
- **operationId:** unique, `camelCase`, stable (`createProject`, `streamCompletion`) — used for SDK method names; **never** renamed without a major.
- **Tags:** one per bounded context (`05`/`06`); consistent ordering.
- **Examples:** **required** for every request/response and error on every operation.
- **Error models:** a shared, `$ref`-ed **Problem Details** schema (§11) + the error-code enum; every operation lists its error responses.
- **Reusable components:** shared schemas (pagination envelope, ids, money, timestamps, Problem Details) in `components`; no duplication.
- **Linting:** **Spectral** ruleset enforces all of the above (naming, examples, error models, security defined, no untyped `object`) — build-failing (§33).
- **Security:** every operation declares its `security` (OAuth2/mTLS/apiKey) + scopes; no operation without security unless explicitly public.

## 26. SDK Compatibility

- **Generated from OpenAPI 3.1** for **Java, Python, TypeScript, Go** (`10`/`sdk/`); method names from `operationId`; models from schemas.
- **Consistency:** identical resource/field semantics across languages; language-idiomatic casing (snake_case JSON → camelCase Java/TS, snake_case Python/Go) handled by generation.
- **Independent SemVer** per SDK (AD-015); breaking API change → new SDK major + deprecation.
- **Ergonomics:** built-in retries (on `retryable`), idempotency-key auto-generation, pagination iterators, streaming helpers (SSE), correlation-id propagation.

## 27. Backward Compatibility Rules

- **Additive-only within a major:** add optional fields/endpoints/enum values; **never** remove/rename/re-type/tighten within a major.
- **Consumers tolerate unknown fields** (must-ignore) and **unknown enum values** (open-enum) — forward-compat.
- **Default-safe:** new required inputs are prohibited within a major (would break existing clients); new optional inputs must default safely.
- **Compat gates:** spec-diff + contract tests **fail the build** on a backward-incompatible change without a major bump (§30/§33, `11` R-093).

## 28. Deprecation Lifecycle

- **Announce → Deprecate → Sunset** (AD-015, `10 §16.9`): mark `deprecated: true` in OpenAPI; emit `Deprecation` + `Sunset` (RFC 8594) headers with the removal date; publish a migration guide.
- **Windows:** **≥ 12 months (Enterprise) / ≥ 6 months (Business)**; deprecated + successor run concurrently; migration tracked via telemetry.
- **Removal:** after the window and migration; removed endpoint/version returns **`410 Gone`** with a pointer to the successor.

## 29. Performance Standards

- **Latency budget:** the API adds only the gateway's budgeted overhead (`06 §11`, `NFR-PERF-001`: P50 ≤ 5 ms, P95 ≤ 20 ms, P99 ≤ 50 ms added) — end-to-end is provider-dominated. Streaming first-token overhead per `NFR-PERF-002`.
- **Payload limits:** default max request body **10 MB** (`10`/`NFR-IF-002`); documented per endpoint; oversize → `413`. Bounded response sizes (paginate/stream large sets).
- **Compression:** `gzip`/`br` negotiated via `Accept-Encoding`; responses compressed above a threshold; SSE not compressed (streamed).
- **Connection:** HTTP/2 (multiplexed); keep-alive; timeouts on everything (`11` R-052).

## 30. Testing Requirements

- **Contract tests:** every operation verified against its OpenAPI spec (request/response/error) — `11` R-084.
- **OpenAPI validation:** spec is linted (Spectral) and validated in CI; runtime request/response validation in test environments.
- **Backward-compatibility tests:** spec-diff vs the previous published major fails on breaking change (§27/§33).
- **Consumer-driven contracts:** SDK/consumer expectations (Pact-style) verified against providers; both sides gated in CI.
- **Security tests:** authz/BOLA negative tests, rate-limit tests, input-fuzzing, injection tests (§23).

## 31. Governance

- **API Design Review Board:** reviews new APIs and any breaking change; membership = Architecture + owning context + Security + DX.
- **Review checklist:** URIs/methods/status/headers conform; error envelope + codes; pagination; versioning; idempotency where needed; auth/scopes + tenant isolation; rate limits; OpenAPI complete (examples, error models, security, operationIds); backward-compatible; observability; anti-patterns (§32) absent.
- **Approval workflow:** OpenAPI PR → Spectral + compat gates green → owning-team + Board approval → merge → SDK/doc generation.
- **Breaking-change approval:** requires a **major** bump, an ADR if it changes an architectural contract, a migration guide, deprecation of the old, and **Board sign-off** (§13/§27/§28).

---

## 32. API Anti-Patterns (≥ 50 — build-review-rejected)

**URIs/Methods:** 1) verbs in paths (`/getUser`); 2) mutating `GET`; 3) `POST`-for-reads; 4) tunneling (`_method`); 5) file extensions in URIs; 6) deep nesting (>2); 7) snake/camel in paths (kebab-only); 8) query params for identity; 9) inconsistent singular/plural; 10) action verbs without `:action`.
**Status/Errors:** 11) `200` for errors (with error in body); 12) generic `500` for validation; 13) `400` vs `422` misuse; 14) leaking stack traces/internal messages; 15) leaking provider status/messages; 16) inconsistent error shapes; 17) unstable/undocumented error codes; 18) `403` that leaks existence (should be `404`); 19) missing `Retry-After` on `429`/`503`; 20) non-machine-readable errors.
**Envelopes/Payloads:** 21) heavy request/response `{data,meta}` wrappers everywhere; 22) serializing entities/aggregates directly; 23) JSON floats for money; 24) ambiguous null vs absent; 25) unbounded arrays/objects; 26) deeply-nested payloads (no depth limit); 27) mixed field casing; 28) dates without timezone / non-UTC; 29) closed enums that break on new values; 30) chatty APIs (N calls for one task).
**Versioning/Compat:** 31) breaking change within a major; 32) header/media-type versioning as primary; 33) version sprawl (many concurrent majors); 34) removing/renaming fields silently; 35) tightening validation in a minor; 36) no deprecation signaling.
**Idempotency/State:** 37) non-idempotent POST without idempotency key; 38) idempotency key ignored/not scoped per tenant; 39) server-side sessions (stateful); 40) GET with side effects.
**Pagination/Query:** 41) offset pagination for large/deep sets; 42) unbounded page sizes; 43) arbitrary query DSL in the URL; 44) non-whitelisted sort/filter fields (injection); 45) no stable sort tiebreak.
**Security:** 46) auth in URL/query; 47) API key as sole auth for admin; 48) missing object-level authz (BOLA); 49) trusting client-declared content-type/tenant; 50) logging secrets/PII; 51) SSRF via user-supplied URLs; 52) no rate limiting on expensive ops; 53) undocumented ("shadow") endpoints; 54) permissive CORS (`*`) on authenticated APIs.
**Streaming/Async:** 55) ambiguous stream close (no terminal event); 56) silent mid-stream truncation; 57) no heartbeats/cancellation; 58) long-running work on a synchronous request (no `202`); 59) polling without `Retry-After`; 60) webhooks without signature/idempotency/DLQ.
**OpenAPI/SDK:** 61) missing/renamed `operationId`; 62) no examples; 63) untyped `object`/`additionalProperties:true` everywhere; 64) duplicated schemas (no components); 65) operations without `security`.

## 33. Build-Failing Rules (CI MUST reject)

| Gate | Fails when… |
|---|---|
| **Spectral lint** | any §25/§32 rule violated (naming, examples, error models, security, operationIds, kebab paths, no untyped object) |
| **Spec-diff compat** | backward-incompatible change without a major bump (§27) |
| **Contract tests** | controller ↔ OpenAPI mismatch (request/response/error) (§30) |
| **Error-model check** | any operation missing the Problem-Details error model or a 4xx/5xx w/o `code` |
| **Security-declared** | any operation without a `security` scheme (unless explicitly public) |
| **Field-naming** | non-`snake_case` JSON field, non-UTC/timezone-less date, JSON float for money |
| **Idempotency check** | consequential POST/PATCH without `Idempotency-Key` in the spec (§14) |
| **Versioning** | endpoint without `/v{N}` prefix; header/media-type versioning used as primary |
| **Pagination** | collection endpoint without cursor pagination + max-limit (§12) |
| **Rate-limit** | expensive/inference endpoint without documented rate limiting (§20) |
| **Secret/PII scan** | secret/PII pattern in a spec example or error string |
| **Undocumented endpoint** | a served route absent from the OpenAPI inventory (API9) |

---

## 34. Traceability

| API rule area | BR | NFR | ADR | Coding Std (`11`) |
|---|---|---|---|---|
| Provider-agnostic contract (§4/§7/§11) | BR-006/007 | NFR-IF-001 | AD-007 | R-018/AU-06 |
| Idempotency (§14) | BR-004 | NFR-RTY-001 | AD-016 | R-046 |
| Error envelope (§11) | BR-001 | NFR-REL-003 | AD-016 | R-030/R-031 |
| Versioning/compat (§13/§27/§28) | BR-007/036 | NFR-VER-001/IF-001 | AD-015 | R-092/R-093/R-094 |
| Auth (§18) | BR-021 | NFR-AUTH-001 | AD-012 | R-071 |
| Authz/tenant isolation (§19) | BR-021 | NFR-AUTHZ-001/REL-002 | AD-012/021 | R-040/R-071 |
| Rate limiting (§20) | BR-013/017 | NFR-SCALE-002 | AD-008 | R-052 |
| Streaming (§16) | BR-002 | NFR-STRM-001/PERF-002 | AD-016 | R-051 |
| Validation/correctness (§22) | BR-001 | NFR-SO-001 | AD-018 | R-032 |
| Security (§23) | BR-013/018/019 | NFR-SEC/PRIV | AD-012 | R-067/R-068/R-071 |
| Observability (§24) | BR-010/011 | NFR-OBS/TRC/AUD | AD-005/011 | R-055…R-059 |
| OpenAPI/SDK (§25/§26) | BR-028 | NFR-SDK-001/IF-001 | AD-015 | R-027/R-084 |
| Performance (§29) | BR-005 | NFR-PERF-001/002/IF-002 | AD-020 | R-052/R-054 |
| Caching (§21) | BR-013 | NFR-CACHE-001 | AD-008 | R-008 |
| Format split (§22/§25) | BR-011 | NFR-IF-001 | AD-011 | R-008/R-042 |

## 35. Internal Consistency Review

| Frozen source | Requirement | This document | ✓ |
|---|---|---|---|
| `10 §7` | REST=JSON Schema/OpenAPI 3.1; events=Avro | §22/§25 (REST JSON Schema), events Avro out-of-scope | ✅ |
| `11` R-030 | RFC 9457 Problem Details errors | §11 | ✅ |
| `11` R-046 / NFR-RTY | Idempotency-Key | §14 | ✅ |
| AD-015 | versioning, compat, deprecation | §13/§27/§28 | ✅ |
| AD-012 | zero-trust auth/authz/mTLS | §18/§19/§23 | ✅ |
| AD-021 | tenant isolation absolute | §8/§19 | ✅ |
| AD-007/`00` | provider-agnostic, neutralized errors | §4/§7/§11 (502/504 neutral) | ✅ |
| `07` | correlation/envelope, streaming integrity, audit | §16/§24 | ✅ |
| `08` | residency, secrets/PII non-exposure, no client caching of model output | §21/§23/§17 | ✅ |
| `03` NFR-PERF | latency budget, payload limits | §29 | ✅ |
| `06 §24.5` | rate limiting/quota | §20 | ✅ |
| AD-011/OTel | W3C trace context | §8/§24 | ✅ |
| AD-007/`00` (streaming) | no provider format leaks; neutral canonical stream | §16 (API-D3, mapping table, neutralization rule) | ✅ |
| `07`/`NFR-STRM/SO` | streaming integrity, tool-call/JSON validation, no silent truncation | §16.2–16.5 | ✅ |
| EV-D2/`06` C12, `07 §14`, `08 §14` | webhook delivery, DLQ, secret storage, audit | §15.1 | ✅ |
| API-D2 (envelope) | lightweight envelope governance | §10 (ratified, rationale) | ✅ |
| AD-021 (caching) | tenant-isolation via `Vary`/`private` | §21 (M5) | ✅ |

**No contradictions found.** Every corrective change is additive and traces to a frozen decision.

---

## 36. Adversarial Review — Post-Corrective (Resolutions, Score & Freeze)

> The corrective pass resolved all High and Medium issues from the first review. Status of each finding:

### High — RESOLVED
- **H1 — Streaming contract fully specified (§16).** Canonical event types (16.1), strict ordering/exactly-once (16.2), tool-call streaming + validation (16.3), partial-JSON reconstruction (16.4), completion events (16.5), cancellation (16.6), heartbeats (16.7), retry (16.8), backpressure (16.9), and the **provider mapping table** for OpenAI/Claude/Gemini/Bedrock/OpenRouter/LiteLLM with an explicit **neutralization rule** (16.10) — no provider format leaks. ✅
- **H2 — Envelope decision ratified (§10, API-D2).** Lightweight model made an explicit governance decision (resource-direct + pagination envelope + Problem Details + metadata-via-headers-unless-impossible) with a full rationale for rejecting heavy wrappers. ✅
- **H3 — Webhook security complete (§15.1).** HMAC-SHA256 + timestamp validation + replay protection + signature versioning + signing-secret rotation + retries/backoff + max-retry-duration + DLQ + idempotency + ordering caveat + duplicate detection + full audit logging. ✅

### Medium — RESOLVED
- **M1** snake_case wire + codegen mapping guidance pinned (§22). ✅
- **M2** 403-vs-404 decision matrix (§19). ✅
- **M3** IETF `RateLimit-*` canonical + `X-RateLimit-*` compatibility mirrors (§20). ✅
- **M4** batch per-item partial-failure contract (§15.2). ✅
- **M5** mandatory `Vary`/`private` caching guidance (§21). ✅

### Low — deferred to `13-Security-Standards.md` (as directed)
- **L1** PATCH default confirmed (JSON Merge Patch, §6) — no change needed. **L2** page-size calibration → `32`. **L3** compression thresholds, **L4** explicit CORS rule, **L5** API-key hashing/rotation → **`13-Security-Standards.md`** (cross-referenced). None block freeze.

### Consistency re-review
Re-ran §35 against `00`–`11` and AD-001…AD-023 with the corrective changes: **no contradictions**; every change is additive and traces to a frozen decision (streaming→AD-007/`07`; webhooks→EV-D2/`07`/`08`; envelope→API-D2; caching→AD-021).

### Recalculated Score: **97 / 100**
All High/Medium resolved; the streaming contract and webhook security are now first-class and enforceable; only calibration/low items (deferred to `13`/`32`) remain — none affecting correctness, security, or frozen-doc consistency.

### Remaining issues
**🔴 Critical: none. 🟠 High: none. 🟡 Medium: none.** 🟢 Low: 3 deferred to `13` (compression, CORS, API-key detail) — non-blocking.

### Recommendation: **FREEZE at v1.0.** No Critical or High issues remain; the consistency review is clean.

---

*End of document — 12-API-Standards.md (v1.0, frozen)*
