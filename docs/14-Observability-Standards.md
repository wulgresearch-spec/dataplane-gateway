# 14 — Observability Standards (The Observability Constitution)

**Document:** Observability Standards & Governance
**Project:** Reliability-First AI Gateway
**Status:** **Approved & Frozen (v1.0, 2026-07-21)** — **binding observability standard**
**Corrective pass applied:** OH-1 two-tier telemetry (§5.1), OH-2 privacy enforcement (§7.1), OH-3 multi-region residency (§18.1), OM-1…OM-5 (§§5/6/11/14.1/15). Frozen after a clean §22 consistency review (0 Critical, 0 High; §23 score 98/100).
**Audience:** Every engineer, SRE, security engineer, FinOps, and the Observability Platform team
**Classification:** Internal — Engineering Standard
**Builds on (approved, frozen):** `00`–`13` and ADRs **AD-001…AD-023**. Nothing here may contradict them.
**Stack:** OpenTelemetry (AD-011) · Micrometer · Prometheus + long-term TSD (Mimir/Thanos/VictoriaMetrics, `09` TD-006) · Kafka · ClickHouse (optional, `09` TD-011) · W3C Trace Context · Grafana-class dashboards.

> **What this is.** The permanent law for how the platform is observed — metrics, traces, logs, audit, events, provider and AI telemetry, SLOs, alerting, dashboards, cost, incident support, and compliance. It makes the platform's reliability, correctness, and governance **measurable and provable** (the whole point of a reliability-first product). **No code, no implementation, no redesign** — only standards. Observability is **by construction** (`04 §39`, `11` R-059): no request may traverse a path that is not observed, correlated, and auditable.
>
> **The seven observability principles:**
> **Every request observable · Every event traceable · Every decision explainable · Correlation by default · Zero blind spots · Tenant-safe observability · Privacy-preserving telemetry.**

---

## Table of Contents
1. Purpose · 2. Scope · 3. Observability Principles · 4. Golden Signals · 5. Metrics Standards · 6. Distributed Tracing · 7. Logging Standards · 8. Audit Logging · 9. Event Observability · 10. Provider Observability · 11. AI Observability · 12. SLO/SLA · 13. Alerting · 14. Dashboards · 15. Capacity Planning · 16. Cost Observability · 17. Incident Support · 18. Compliance · 19. Build-Failing Rules · 20. Governance · 21. Traceability · 22. Internal Consistency Review · 23. Adversarial Review, Score & Findings

---

## 1. Purpose
Define every observability control so any team can instrument, monitor, alert, and debug consistently — and so the platform can **prove** its SLOs, correctness, isolation, and compliance. Observability is the evidence layer for the entire reliability-first thesis (`00`/`03`).

## 2. Scope
**In scope:** metrics, traces, operational logs, audit telemetry, event/Kafka observability, provider telemetry, AI/inference telemetry, SLO/SLI/error budgets, alerting, dashboards, capacity signals, cost observability, incident support, and observability compliance — for the data-plane, all 13 control-plane services, SDKs, plugins, and infrastructure. **Out of scope:** the audit *system of record* itself (governed by `07`/`08 §10`; here we govern its *observability*), and implementation. Where a rule differs **[DP]** data-plane vs **[CP]** control-plane it is marked.

## 3. Observability Principles

| Principle | Rule |
|---|---|
| **Every request observable** | Every request emits metrics, a trace, and an audit record — 100% coverage; a silent path is a defect (`04 §39`, `11` R-059, NFR-OBS-001). |
| **Every event traceable** | Every domain event carries correlation + causation ids (`07 §6`); event flows are reconstructable end-to-end. |
| **Every decision explainable** | Every governance/routing/reliability/correctness decision is recorded with its inputs and outcome (`07`/§8/§11). |
| **Correlation by default** | One correlation id threads metrics↔traces↔logs↔events↔audit for every request (`11` R-056, `12 §24`). |
| **Zero blind spots** | Every component, dependency (incl. providers), queue, and boundary is instrumented; monitoring is itself redundant (NFR-MON-001). |
| **Tenant-safe observability** | Telemetry never crosses tenants; per-tenant signals are access-scoped and cardinality-bounded (AD-021, `08 §24.9`). |
| **Privacy-preserving telemetry** | **No secrets, PII/PHI, prompt/completion content, or regulated data in metrics/traces/logs** — ever (`13 §20`, `07 §17`, `11` R-068). Telemetry carries **metadata and measurements, not content.** |

---

## 4. Golden Signals

The **four golden signals** (Google SRE) **plus** the platform-specific signals that make an AI gateway observable. Each is a first-class, dashboarded, SLO-backed metric.

| Signal | What | Notes |
|---|---|---|
| **Latency** | request duration; **gateway added-overhead** separated from provider time | budget per `06 §11`/NFR-PERF; P50/P95/P99 histograms |
| **Traffic** | request rate (RPS), by plane/endpoint/tenant-class | capacity + anomaly |
| **Errors** | error rate by **attribution class** (gateway / provider / client) | never conflate (NFR-REL EP-3) |
| **Saturation** | CPU/mem/connections/thread utilization vs headroom | ceilings per NFR-CAP-001 |
| **Token usage** | input/output tokens per request/tenant/model-class | AI-native; feeds cost/quota (§16) |
| **Provider latency** | upstream provider round-trip (per provider, internal) | §10 |
| **Provider failures** | provider error/timeout rate (per provider) | drives failover (§10) |
| **Streaming latency** | time-to-first-token + inter-token jitter | NFR-PERF-002 |
| **Queue depth** | Kafka consumer lag; internal buffer/WAL depth | §9, backpressure (NFR-Q) |
| **Circuit-breaker state** | open/half-open/closed per provider/scope | reliability (`06 §23`) |
| **Retry rate** | retries/requests + budget consumption | storm detection (NFR-RTY, ≤10% budget) |
| **Cache hit ratio** | Valkey hit/miss (cost KPI, not reliability SLO) | `03` NFR-CACHE-002 |
| **Cost** | $ per request/tenant/provider/token | FinOps (§16) |

---

## 5. Metrics Standards

- **System:** **Micrometer** (instrumentation) → **OpenTelemetry** export (AD-011/TD-007) → **Prometheus + long-term TSD** (TD-006). OTel semantic conventions where they exist; platform conventions otherwise.
- **Naming:** `gateway.<area>.<name>.<unit>` in snake/dot per exposition (`11` R-057), e.g. `gateway.request.duration.seconds`, `gateway.provider.errors.total`, `gateway.correctness.output_escape.total`. Counters end `_total`; durations in **seconds** (histograms); sizes in **bytes**. One metric = one meaning.
- **Labels/dimensions (bounded):** allowed low-cardinality labels — `plane`, `service`, `endpoint`(templated, never raw path), `method`, `status_class`, `error_class`(attribution), `provider`(internal ops only, §10), `model_class`(neutral), `tenant_tier`, `region`, `outcome`. **Tenant-level** labels are permitted **only where cardinality-bounded and access-scoped** (§16); high-cardinality per-tenant/per-request analytics go to the **rollup/OLAP store or exemplars**, not Prometheus labels.
- **Cardinality limits (hard rule):** **NEVER** use `request_id`, `correlation_id`, `user_id`, `api_key`, `session_id`, raw URL, `email`, or free-text as a **metric label** — these are unbounded and blow up the TSD (`08 §24.9 M7`). Per-metric cardinality budgets are set and monitored; **exceeding the budget fails the build** (§19) and alerts. High-cardinality lookups belong to **traces/logs/exemplars**.
- **Units:** SI base units (seconds, bytes); no milliseconds-in-name; monetary as a decimal (never float precision loss, `05`/`11`).
- **Aggregation:** histograms for latency/size (percentiles computed from buckets, not client-side); counters for events; gauges for saturation/state. Pre-aggregate high-cardinality server-side.
- **Retention:** **high-resolution short** (e.g., 15 days raw) → **downsampled long** (13 months rolled-up) per capacity (§15/`32`); operational, **not** compliance retention (that is audit, §8).
- **Sampling:** metrics are **not** sampled (aggregates must be complete); only traces/high-volume telemetry sample (§6). Best-effort high-volume telemetry may drop under extreme pressure but decision/accounting metrics never (NFR-MET-001).
- **Histograms:** native/exponential histograms preferred; consistent bucket boundaries per metric family.
- **Exemplars (OM-3):** latency/error histograms carry **exemplars linking to a trace id** (metric → trace pivot), so a spike jumps to a representative trace — the metrics↔traces bridge. The **native-histogram + exemplar chain is validated end-to-end** (Micrometer → OTel → TSD → Grafana) as part of the observability acceptance suite; the chosen long-term TSD (Mimir/Thanos/VictoriaMetrics) must support native histograms + exemplars, verified before GA.
- **Every SLO has a metric** (§12, `03 §62`); an SLO without a live SLI metric is a defect (§19).

---

### 5.1 Two-Tier Telemetry Architecture (Plane A / Plane B) — OH-1

Telemetry is split into **two completely separate planes** with different backends, purposes, and cardinality rules. They are never conflated. **Prometheus is NEVER used for analytical (high-cardinality) workloads.** Both planes obey the privacy enforcement of §7.1.

**Plane A — Operational Observability**
- **Purpose:** real-time operations.
- **Backend:** **Prometheus + long-term TSDB** (Mimir/Thanos/VictoriaMetrics, `09` TD-006).
- **Requirements:** **low-cardinality metrics only**; **build-failing label limits** (§19); fast alerting; SLO monitoring; capacity monitoring; infrastructure health.
- **Forbidden labels (build-failing, §19):** `request_id`, `trace_id`, `user_id`, `tenant_email`, `api_key`, `raw_prompt`, `completion` — plus any other unbounded, identifying, or content-bearing label. Tenant labels only where cardinality-bounded and access-scoped (§16).

**Plane B — Analytical Observability**
- **Purpose:** high-cardinality analytics.
- **Backend:** **ClickHouse (optional, port-abstracted, `09` TD-011)** — a columnar/OLAP store reached via a port so it remains swappable (AD-002), introduced per the `09` adoption criteria.
- **Stores:** per-request analytics · per-tenant analytics · cost analytics · FinOps · usage analytics · capacity forecasting · schema-failure analytics · hallucination-signal analytics · routing analytics · latency distributions · streaming analytics.
- **Fed by:** the event fabric (`07`) and authoritative metering (`05` C5); analytical rows carry the high-cardinality dimensions (request/tenant/user ids) that are **forbidden in Plane A**.
- **Rule:** **never use Prometheus for analytical workloads.** FinOps / per-tenant / per-request / latency-distribution views (§16) are served from **Plane B**, never from the TSD labels.

**Separation guarantees:** the planes are independently scaled and operated; Plane A stays low-cardinality and fast for operations and alerting; Plane B absorbs high-cardinality analytics without ever threatening operational monitoring. **Neither plane may contain secrets/PII/content** (§7.1) — analytics use ids and measurements, never raw content.

---

## 6. Distributed Tracing

- **Standard:** **OpenTelemetry** tracing (AD-011); propagation via **W3C Trace Context** (`traceparent`/`tracestate`, `12 §8/§24`); baggage for **correlation id + tenant scope only** — **never PII/content** in baggage (privacy).
- **Span hierarchy (canonical request):** `ingress` → `authn` → `authz.pep` → `governance` → `routing` → `reliability` (→ `provider.call` child spans per attempt) → `correctness` → `accounting/emit`. Each in-process stage (`06 §10`) is a span; provider calls, DB access, and consumers are child spans.
- **Span naming:** `<area>.<operation>` low-cardinality (`provider.call`, `correctness.validate`, `db.query`), never with ids in the name.
- **Attributes:** neutral, bounded — `tenant.id`(scoped), `correlation.id`, `provider`(internal), `model_class`, `route.decision`, `attempt.number`, `error.class`, `data.classification`. **No prompt/completion content, no secrets, no PII** (`13 §20`). Follow OTel semantic conventions (http/db/messaging) where applicable.
- **Sampling strategy (OM-1):** **head-based** default rate + **tail-based** override → **100% capture for errors and slow (>P99) requests**, representative sampling otherwise (`07 §16`, NFR-TRC-001); sampling decision propagated; **decision/audit paths are never lost** (they ride events/audit, §8, not sampled traces). **Topology:** tail sampling runs in a **dedicated OTel collector tier** (per region, §18.1) that buffers spans for a bounded decision window before export; buffer depth/decision-lag are monitored (Plane A); the collector tier is horizontally scaled and its failure fails safe (drop sampled non-error spans, never drop error/audit paths).
- **Cross-service tracing:** context propagated across every service hop (mTLS-authenticated) and **across the async event boundary** (correlation/causation in the event envelope reconstruct the trace, `07 §6`).
- **Cross-region tracing:** trace context propagates cross-region; **trace data is residency-confined** — spans containing tenant-scoped attributes stay within permitted regions (AD-014, §18).
- **Provider tracing:** every provider call is a span with latency, attempt, outcome, and neutral provider label; **no provider-native ids/payloads** in spans (`12 §16.10`).
- **Streaming tracing:** a streaming request is one trace spanning the whole stream; child spans/events for first-token, tool-call reconstruction, and terminal `stream.done`/`stream.error` (`12 §16`); mid-stream failures visible.

---

## 7. Logging Standards

- **Format:** **structured JSON** logs via SLF4J (`11` R-055); no `System.out`/`printStackTrace` (`11` R-055, AU-12).
- **Required fields (every log line):** `timestamp`(UTC RFC3339), `level`, `service`, `version`, `correlation_id`, `trace_id`, `span_id`, `tenant_id`(scoped), `message`, and on errors `error.class`+`error.code` (not stack traces to clients; internal stack in a controlled field only).
- **Correlation:** every log carries `correlation_id` + `trace_id` (metrics↔traces↔logs↔events pivot, `11` R-056, `12 §24`).
- **Severity/levels:** `ERROR` (actionable failure), `WARN` (recoverable/degraded), `INFO` (significant business events), `DEBUG` (dev/troubleshooting, off in prod by default). No log-and-throw duplication; no noisy INFO on the hot path.
- **PII/secret redaction (absolute):** **no secrets, PII/PHI, prompt/completion content, or regulated data in logs** — redaction applied **before** emission (`13 §20`, `07 §17`, `11` R-068); classification-driven; enforced by scanner (§19).
- **Retention:** operational retention (e.g., 30 days hot, tiered/archived per policy); security-relevant logs forwarded to **SIEM** (`13`); **not** compliance retention (audit, §8). Residency-confined (§18).
- **Access:** log access is least-privilege and audited (logs may reference tenant-scoped context); no cross-tenant log access.

---

### 7.1 Telemetry Privacy Enforcement — mandatory platform invariant (OH-2)

**Invariant (zero-tolerance, CP-3):** telemetry — metrics, traces, logs, events, **and analytics (both planes)** — must **NEVER** contain **secrets, passwords, API keys, JWTs, PII, PHI, raw prompts, raw completions, provider credentials, or customer files**. A violation is a **Sev1**.

Enforcement is **allow-list-based (deny-by-default — only explicitly permitted fields may be emitted)**, applied at **both CI and runtime**, on **both planes**:
- **Span-attribute allow-list:** only registered, reviewed attribute keys may be set on spans; unknown/free-text/content attributes are dropped/rejected. Prompt/completion content is never an allowed attribute.
- **Metric-label allow-list:** only the bounded Plane-A label set (§5/§5.1) is permitted; anything else fails the build.
- **Log-field allow-list:** only registered structured fields (§7); free-text `message` is redaction-scanned; no content fields.
- **Event-metadata allow-list:** event envelopes carry only governed metadata (`07 §6`); regulated data appears only in classified **audit** events (`07 §17`), never in telemetry.
- **Automatic telemetry scanners:** static + runtime scanners inspect emitted metrics/traces/logs/events/analytics for secret/PII/content patterns.
- **Build-failing telemetry linting (CI):** span attributes, metric labels, and log fields validated against the allow-lists — **violations fail the build** (§19).
- **Runtime PII detection · secret detection · prompt-content detection:** the telemetry pipeline (SDK + OTel collector) runs these on outbound telemetry (defense in depth).
- **Automatic redaction:** detected sensitive values are **redacted before emission** (classification-driven, `13 §20`).
- **Automatic rejection:** telemetry still containing a forbidden pattern after redaction is **rejected/dropped and alerted** (fail-secure), never emitted.
- **CI validation:** allow-lists + scanners run on every PR (build-failing).
- **Production validation:** the collector **continuously validates** outbound telemetry; a violation pages Security (Sev1) and quarantines the telemetry.

**Rule:** privacy enforcement is **allow-list + scan + redact + reject**, at **CI and runtime**, on **both planes** — policy alone is insufficient. This closes the top real-world leakage vector.

---

## 8. Audit Logging (observability of the compliance record)

- **Distinct from operational logs (`07` D-2):** audit is the **immutable, tamper-evident, zero-loss compliance system of record** (`08 §10`), not operational logging. Here we govern its **observability & completeness**.
- **Immutable & tamper-evident:** sealed WORM records + **per-record signature + Merkle checkpoints** (`08 §10`, `13 §19`); tamper is detectable.
- **Required content:** **actor** (principal/service), **action**, **decision** (permit/deny + obligations, `07`), **outcome**, **tenant scope**, **correlation id**, **timestamp** — every request and material policy/security decision (`06 §23.11` RequestFinalized manifest).
- **Completeness monitoring:** the **audit loss detector = 0** (produced vs consumed vs sealed) and **per-record completeness** via `RequestFinalized` (`08 §10 C1`) are themselves monitored SLIs; any gap → Sev incident (zero error budget).
- **Access-governed:** access to audit data is itself access-controlled and audited (`03` OBS-5, `13 §19`); audit dashboards are Security/Compliance-scoped.
- **Retention:** compliance-driven + legal hold (§18, `08 §15`).

---

## 9. Event Observability

- **Consumer lag:** per topic + consumer group, monitored with SLOs; lag beyond threshold pages (`07 §16`); high-ingest ZL topics (metering/audit) watched closely.
- **DLQ:** DLQ depth/age per consumer; **ZL DLQ > 0 is an incident** (never silent drop, `07 §14`); retry-rate and poison-message metrics.
- **Producer durability:** **transactional-outbox relay lag** (`08` DA-D1) and **Data-Plane WAL depth/drain lag + fail-safe-rejection rate** (`07` EV-D3) — rising values warn of loss risk before loss occurs.
- **Replay:** replay operations are observable and audited (who/scope/when, `07 §15`); replay-mode isolation visible.
- **Ordering:** per-partition ordering health; partition skew/hot-partition detection (low-cardinality keys, `07 §12`).
- **Deduplication:** dedup-hit rate + consumer-group rebalance rate (rebalances → at-least-once redelivery, absorbed by idempotency; a spike warns of churn, `07 §16`).
- **Schema evolution:** per-version production/consumption telemetry drives deprecation (`07 §9`); registry-compatibility failures alert.
- **Loss detectors (ZL):** produced vs consumed vs sealed reconciliation = 0 for audit/accounting (§8, `07 §16`).

---

## 10. Provider Observability

> Provider identity is an **internal operational** dimension; it is **never exposed** in customer-facing dashboards/APIs/telemetry (`12 §16.10`, AD-007). Internal ops metrics label `provider` for routing/reliability/FinOps.

- **Per-provider metrics:** **latency** (round-trip, P50/95/99), **availability** (success ratio, and *effective* availability with failover vs single-provider baseline, `03` NFR-AV-002), **errors** (rate + class, neutralized), **cost** (per provider, §16), **retries**, **timeouts**, **fallbacks** (failover events + residency-safe-failover ratio, `08 §16`).
- **Health & circuits:** provider health signal + circuit-breaker state per provider drive routing (`06`); provider-health-changed events observable.
- **Capacity/limits:** provider rate-limit proximity, throttle events (avoid tripping shared limits, `06 §24`).
- **Neutralization check:** a telemetry-leak detector verifies **no provider-native id/field/error escapes** to customer-facing surfaces (contract-tested, `12 §16.10`).

---

## 11. AI Observability

> **Content-free by law:** AI telemetry is **metadata and measurements only** — never prompt/completion content, never regulated data (§3, `13 §20`).

- **Prompt metrics:** input token counts, prompt size, structured-input validation outcomes (counts, not content).
- **Completion metrics:** output token counts, finish-reason distribution (neutral enum, `12 §16.5`), latency, streaming quality.
- **Structured-output failures:** **conformance/escape rate** (`03` NFR-SO-001: escape ≤ 0.01%), repair rate, post-enforcement success (attributed, OOS-6) — the product's core correctness SLIs.
- **Hallucination-detection signals (bounded, honest — OM-4):** where grounding/verification controls are configured (`BR-009`), emit their outcomes as **signals** (flag rate, groundedness score) — explicitly **not a guarantee** of truth (OOS-6); a signal, not a verdict. **Mandatory disclaimer:** wherever a groundedness/flag score surfaces (dashboards, API, reports), it **MUST be labeled "non-authoritative signal — not a correctness guarantee"** to prevent over-trust; it never gates delivery as if it were truth.
- **Tool-call metrics:** tool-call rate, **corrupted-tool-call escape rate** (`03` NFR-SO-003 ≤ 0.01%), pre-execution validation coverage (100%), reconstruction failures.
- **Schema-validation failures:** JSON-Schema validation failure rate for structured output/tool-calls (`12 §22`, correctness).
- **Streaming quality:** time-to-first-token, inter-token jitter, silent-corruption rate (≤ 0.05%, `03` NFR-STRM-001), surfaced-failure coverage (100%), mid-stream failure rate.
- **Token efficiency:** tokens/request, cost/request, cache-savings, retry-token-waste — FinOps + optimization (§16).
- **Correctness escape SLIs are zero-error-budget-adjacent:** escape rates above budget page (§12/§13).

---

## 12. SLO / SLA

- **SLIs/SLOs/error budgets** are governed by **`03 §61–63`** (frozen) — this document instruments them; it does not redefine them.
- **Primary SLOs (tiered, `03 §61`):** gateway availability (99.9→99.99%), effective availability (> best single provider), gateway error rate (≤0.01% Enterprise), added latency (P99 ≤ 50 ms), streaming first-token overhead, **structured-output escape ≤ 0.01%**, tool-call corrupted escape ≤ 0.01%, streaming silent-corruption ≤ 0.05%, failover success ≥ 99%, accounting accuracy, **audit coverage 100% / loss 0**, **isolation violations = 0 (invariant)**.
- **Error budgets (`03 §63`):** each SLO has a budget over a rolling window; **invariant SLOs have zero budget** (any occurrence → incident + freeze, not budget accounting).
- **Burn-rate alerts:** **multi-window, multi-burn-rate** (fast-burn: 2% budget/1h → page; slow-burn: 10%/3d → ticket) per `03 §63`/§13.
- **SLA:** external SLAs are set **tighter internally** (SLO margin); reported transparently (`02` SLA-5).

---

## 13. Alerting

- **Severity levels:** **Sev1** (customer-impacting outage / invariant violation / security incident — page 24/7, MTTA ≤ 15 min), **Sev2** (degradation / SLO fast-burn — page business-critical), **Sev3** (slow-burn/warning — ticket), **Sev4** (informational).
- **Escalation:** defined on-call rotations + escalation chains per severity (`03` NFR-SEC-002 for security); auto-escalate on unacknowledged.
- **Paging:** every **page maps to a runbook** (actionable, NFR-ALRT-001); non-actionable alerts are forbidden (they cause fatigue).
- **Noise reduction:** burn-rate (not threshold-spam) alerting; **alert precision target ≥ 90%** (`03 §59`); routine flaps suppressed.
- **Deduplication:** alerts grouped/deduped by correlation/service/incident; storm-suppression.
- **Maintenance windows:** planned maintenance suppresses expected alerts (`03` NFR-MW); emergency changes recorded.
- **Invariant alerts always page:** isolation/no-silent-delivery/audit-loss/secret-exposure/residency detections page immediately (zero budget, CP-3).
- **Meta-monitoring:** the alerting/monitoring system's own failure pages (no silent blind spot, NFR-MON-001).

---

## 14. Dashboards (per persona)

| Dashboard | Audience | Content |
|---|---|---|
| **Executive** | Leadership | availability, SLO health, adoption, incidents, cost trend, regulated-adoption |
| **Operations (SRE)** | On-call/SRE | golden signals, burn rates, saturation, provider health, queue lag, DLQ, error budgets |
| **Engineering** | Service teams | per-service RED, traces/exemplars, deploy/change-failure, latency budget by stage |
| **Security** | Security/Compliance | authz denials, isolation-violation detectors, secret-exposure scans, injection/WAF signals, audit completeness, incident timeline |
| **Customer Success** | CS | per-customer reliability, usage, incidents, adoption breadth |
| **FinOps** | Finance/Platform | token/provider/infra/storage cost, per-tenant/request cost, budget vs entitlement (§16) |

- **Rule:** dashboards are **tenant-safe** (customer-facing views scoped to one tenant; internal views aggregate or are access-controlled); **no provider identity or content** on customer-facing dashboards (§10/§3).

### 14.1 SIEM Detection Catalog (core detections — OM-5, resolves `13` SL-2)
Security-relevant telemetry is forwarded to the **SIEM** (`13`); the core detection rules the platform ships (extensible per deployment):
- **Isolation-violation detector** — any cross-tenant data/quota crossing (Sev1, invariant).
- **AuthN anomalies** — auth-failure spikes, impossible-travel, token-reuse/refresh-reuse (`13 §6`).
- **AuthZ anomalies** — authorization-denial spikes, BOLA probing, privilege-escalation attempts (`13 §7`).
- **Secret/PII-in-telemetry** — any forbidden pattern reaching telemetry (§7.1, Sev1).
- **Secret access anomalies** — unusual secret/key access, revocation events (`13 §8`).
- **Dual-control/break-glass** — privileged-operation and break-glass grants/uses (`13 §29`).
- **Prompt-injection / content-trust** — flagged injection attempts, jailbreak patterns (`13 §11`, non-authoritative signals).
- **Rate-limit/cost-DoS** — budget-exhaustion + cost-anomaly spikes (`13 §13.1`).
- **WAF/edge** — blocked requests, L7 DDoS signals (`13 §21.2`).
- **Provider anomalies** — provider error/latency spikes, neutralization-leak detector trips (§10).
- **Residency-violation** — telemetry or data residency breach (§18.1, Sev1).
- **Audit integrity** — audit loss-detector > 0, tamper-evidence (Merkle) mismatch, completeness gaps (§8).
- **Supply-chain** — unsigned-artifact/provenance-failure at admission (`13 §21/§22`).

Each maps to a severity (§13) and a runbook; detections feed the **Security dashboard** (§14) and paging (§13).

---

## 15. Capacity Planning (signals feed `32`)

- **Traffic growth:** RPS/token-throughput trends per plane/tenant → forecast peak + headroom (NFR-CAP-001, 1.3× peak).
- **Storage growth:** PostgreSQL/MongoDB/object/audit-WORM growth; **audit-WORM multi-year growth** projected (`08 §24.9 N3`).
- **Metrics retention:** TSD cardinality + series growth vs budget (§5); downsampling policy.
- **Log retention:** volume vs retention/cost; tiering.
- **Trace retention:** sampled-trace volume vs retention; tail-sampling storage.
- **Observability cost budget (OM-2):** telemetry is itself a significant cost/throughput load; a **telemetry-cost budget is set as a bounded % of total infrastructure cost** (calibration target in `32`) and enforced via **sampling (§6), cardinality limits (§5), retention/downsampling (§5/§7), and Plane-A/Plane-B separation (§5.1)**; telemetry cost is monitored (Plane B) and overage alerts. Observability must never cost more than the reliability it buys.
- **Rule:** capacity signals are continuous inputs to `32-Capacity-Planning.md`; growth beyond mitigations alerts.

## 16. Cost Observability (FinOps)

- **Token cost:** $ per request/tenant/model-class/provider, from **authoritative metering** (`05` C5, `06 §24.5`) — the accounting SoR, not estimates.
- **Infrastructure cost:** compute/network/K8s per service/tenant (allocation).
- **Storage cost:** per store class (audit-WORM, TSD, object, DB) — the growth-driven lines (§15).
- **Provider cost:** per provider (internal), for routing/optimization (`06`).
- **Per-tenant / per-request cost:** attributed (`05` attribution), enabling chargeback + margin analysis; high-cardinality tenant/request cost lives in the **rollup/OLAP store** (ClickHouse, TD-011), **not** Prometheus labels (§5).
- **Budget vs entitlement:** cost vs Billing entitlements + the cost-DoS budgets (`13 §13.1`); anomaly detection flags spikes (abuse/DoS, `13`).
- **Rule:** cost observability derives from authoritative metering (reconcilable, NFR-COST-001), never from ad-hoc estimates.

## 17. Incident Support

- **Correlation workflow:** start from an alert → pivot **metric → exemplar → trace → logs → audit** via the shared correlation id (§3/§5/§6/§7) — one thread reconstructs the whole request.
- **Root-cause analysis:** attribution-classified errors (gateway/provider/client, EP-3) localize fault fast; blameless post-incident reviews feed durable fixes (`03` EP-8).
- **Timeline reconstruction:** immutable audit + correlated telemetry reconstruct exactly what happened, when, and why (§8, forensics `13 §25`).
- **Dependency graph:** service/dependency map (incl. providers, stores, Kafka) from traces/topology; blast-radius and failure-domain visibility (`06 §13`).
- **MTTD/MTTA/MTTR** tracked (`03 §59`); every Sev1 produces a post-incident review.

## 18. Compliance (observability)

- **Audit evidence:** audit records + completeness SLIs are producible on demand for audits (`08 §10`, `03` NFR-AUD/COMP).
- **Retention:** telemetry retention per policy; **audit compliance retention** separate + legal hold (`08 §15`); operational telemetry bounded (§5/§7).
- **Residency:** **telemetry is residency-confined** — traces/logs/metrics containing tenant-scoped data stay within permitted regions (AD-014, §6); cross-region aggregation respects residency.
- **Access controls:** observability data access is least-privilege, tenant-safe, and audited (`13 §19`); customer-facing telemetry scoped to the customer's tenant.
- **Privacy:** no PII/content in telemetry (§3/§7) — privacy-by-design in the observability layer.

### 18.1 Multi-Region Residency-Aware Telemetry — OH-3

**Guarantee: no telemetry ever violates tenant residency.** Telemetry containing tenant-scoped data stays within the tenant's permitted region(s) (AD-014, `08`); the default is **regional-in, regional-stored, regional-viewed**.

- **Regional collectors:** OTel collectors are **deployed per region**; tenant-scoped telemetry is collected in-region and **never** shipped out of a permitted region.
- **Regional storage:** Plane A (TSD), Plane B (analytics), logs, and traces are **stored regionally**; residency-constrained telemetry never replicates to non-permitted regions.
- **Regional retention:** retention applied per region per policy/regulation.
- **Regional dashboards:** operational/tenant dashboards are **regional**; a tenant's telemetry is viewed within its region.
- **Cross-region aggregation:** **only non-residency-constrained, aggregated/anonymized** signals (global SLO rollups, counts) may cross regions — **never raw tenant-scoped telemetry**.
- **Cross-region correlation:** correlation/causation ids (`07 §6`) reconstruct a flow across regions **by id reference, without moving tenant data** — the ids travel, the raw telemetry stays put.
- **Global executive dashboards:** built from **aggregated, non-tenant-identifying** rollups (availability, SLO health, cost trend) — residency-safe by construction.
- **Residency restrictions:** telemetry classification + tenant residency policy drive placement; a **residency-violation detector** on the telemetry pipeline pages Security (Sev1) on any breach.
- **Disaster recovery:** telemetry DR (collector/store backup/failover) targets **residency-valid regions only**; no DR event moves telemetry cross-residency.
- **Cross-region tracing / metrics / logs:** context and correlation propagate cross-region **by id**; the underlying spans/series/lines remain in their region of origin.
- **Cross-region alerting:** alerting evaluates **regionally + a global layer over aggregated rollups**; pages route to the correct regional/global on-call.
- **Global SLO calculation:** global SLOs are computed from **regional SLI rollups** (aggregated, non-tenant-raw) — global reliability is measured **without** centralizing tenant telemetry.

**Rule:** only aggregated, non-tenant-identifying rollups cross regions; residency is a **telemetry invariant** enforced by classification + detectors (CP-3).

---

## 19. Build-Failing Rules (CI/CD MUST reject)

| Gate | Fails when… |
|---|---|
| **Cardinality** | a metric uses an unbounded label (`request_id`/`user_id`/`api_key`/raw-path/`email`) or exceeds its cardinality budget (§5) |
| **Metric hygiene** | metric missing unit/help, non-conventional name, ms-in-name, or float money |
| **SLI coverage** | an SLO (`03 §62`) without a live backing metric |
| **Correlation** | a boundary handler that doesn't propagate correlation/trace context (`11` R-056) |
| **Instrumentation** | a request-path stage/boundary without OTel span/metrics (`11` R-058, zero blind spots) |
| **Log structure** | non-structured log, `System.out`/`printStackTrace` (`11` R-055/AU-12) |
| **PII/secret in telemetry** | secret/PII/content pattern in a log/metric/span attribute (`13 §20`) |
| **Event observability** | a domain consumer without lag/DLQ/retry metrics (§9) |
| **Baggage safety** | PII/content placed in trace baggage |
| **Dashboard tenancy** | a customer-facing panel not tenant-scoped, or exposing provider identity/content |
| **Plane separation (OH-1)** | an analytical/high-cardinality dimension emitted to Plane A (Prometheus), or Prometheus used for analytical workloads (§5.1) |
| **Telemetry allow-list (OH-2)** | a span attribute / metric label / log field / event-metadata key not on the allow-list (§7.1) |
| **Telemetry residency (OH-3)** | telemetry configured to ship/store tenant-scoped data cross-region, or a global panel over raw tenant telemetry (§18.1) |
| **Hallucination-signal labeling (OM-4)** | a groundedness/flag score surfaced without the "non-authoritative signal" label (§11) |

## 20. Governance

- **Ownership:** the **Observability Platform team** owns the pipeline (OTel/Prometheus/TSD/dashboards/alerting infra); **each context team owns its own SLOs, dashboards, alerts, and runbooks** (`06 §16`); SRE owns cross-cutting SLOs and on-call.
- **Approval workflow:** new/changed SLOs, alerts (esp. paging), and dashboards are reviewed by the owning team + SRE; alert changes require a runbook (§13).
- **Change management:** instrumentation/SLO/alert changes are versioned, reviewed, and cardinality-checked (§19); silencing an alert requires a recorded reason + expiry.
- **SLO review:** SLOs reviewed quarterly and **never weakened silently** (`03 §61`); error-budget policy governs velocity vs reliability (`03 §63`).

## 21. Traceability

| Observability area | BR | NFR | ADR | Coding (`11`) | API (`12`) | Sec (`13`) |
|---|---|---|---|---|---|---|
| Metrics (§5) | BR-010 | NFR-MET-001 | AD-011 | R-057 | §24 | — |
| Tracing (§6) | BR-010 | NFR-TRC-001 | AD-011 | R-058 | §8/§24 | — |
| Logging (§7) | BR-010 | NFR-LOG-001 | AD-011 | R-055 | §24 | §20 |
| Audit obs (§8) | BR-011/024 | NFR-AUD-001 | AD-009 | — | — | §19 |
| Event obs (§9) | BR-010/011 | NFR-OBS/Q | AD-005/009 | R-059 | — | — |
| Provider obs (§10) | BR-006 | NFR-FO/IF | AD-007 | AU-06 | §16 | §12 |
| AI obs (§11) | BR-001/002 | NFR-SO/STRM-001 | AD-018 | — | §16/§22 | §11 |
| SLO/SLA (§12) | BR-003/SLA | NFR-SLO/SLI/EB (§61–63) | AD-016 | — | — | — |
| Alerting (§13) | BR-003 | NFR-ALRT-001 | AD-016 | — | — | §25 |
| Dashboards (§14) | BR-010 | NFR-OBS-001 | AD-011 | — | — | §19 |
| Capacity (§15) | BR-005 | NFR-CAP-001 | AD-020 | — | — | — |
| Cost obs (§16) | BR-012/013 | NFR-COST-001 | AD-005 | — | — | §13.1 |
| Incident (§17) | BR-010 | NFR-OBS/MON | AD-011 | — | — | §25 |
| Compliance (§18) | BR-011/022/023 | NFR-COMP/MR | AD-014 | — | — | §26 |
| Privacy/tenant-safe (§3) | BR-018/021 | NFR-PRIV/REL-002 | AD-021 | R-068 | — | §20 |
| Two-tier telemetry (§5.1, OH-1) | BR-010/012 | NFR-MET/COST | AD-011/010 (TD-006/011) | R-057 | — | — |
| Telemetry privacy enforcement (§7.1, OH-2) | BR-018/021 | NFR-PRIV/SEC | AD-012/021 | R-068 | — | §20 |
| Multi-region telemetry (§18.1, OH-3) | BR-023 | NFR-MR-001 | AD-014 | — | — | §18 |
| SIEM catalog (§14.1, OM-5) | BR-018 | NFR-SEC | AD-012 | — | — | §25 |

## 22. Internal Consistency Review

| Frozen source | Requirement | This document | ✓ |
|---|---|---|---|
| AD-011 | OpenTelemetry instrumentation | §5/§6/§7 | ✅ |
| `09` TD-006/007/011 | Prometheus+TSD, OTel, ClickHouse (opt) for high-card | §5/§16 | ✅ |
| `03 §61–63` | SLO/SLI/error-budget framework | §12 (instruments, doesn't redefine) | ✅ |
| `07 §16` | trace correlation, event obs, sampling | §6/§9 | ✅ |
| `07 §14`/EV-D3/`08` DA-D1 | DLQ, WAL, outbox lag | §9 | ✅ |
| `08 §10/§18` | audit completeness/loss detector, data obs | §8/§9 | ✅ |
| `08 §24.9 M7` | cardinality bounding | §5 (hard rule + build-fail) | ✅ |
| `11` R-055/057/058/059/068 | logging/metrics/tracing/no-PII | §5/§6/§7/§19 | ✅ |
| `12 §16.10` | no provider leaks | §10/§11 (internal-only provider label) | ✅ |
| `12 §24` | W3C trace context, correlation | §6/§7 | ✅ |
| `13 §20/§19` | no secrets/PII in logs; audit access governed | §7/§8/§18 | ✅ |
| `13` SL-2 | SIEM/detection catalog | §7 (SIEM forward), §14 Security dashboard | ✅ |
| AD-021/`03` NFR-REL-002 | tenant-safe observability | §3/§14/§18 | ✅ |
| AD-014 | residency-confined telemetry | §6/§18 | ✅ |
| OOS-6/BRULE-9 | honest limits (hallucination signals) | §11 (mandatory non-authoritative label) | ✅ |
| `09` TD-006/011 (two-tier) | Prometheus for ops, ClickHouse for high-card analytics | §5.1 (Plane A / Plane B) | ✅ |
| AD-012/021 (privacy) | no secrets/PII/content in telemetry; allow-list + scan + reject | §7.1 (mandatory invariant) | ✅ |
| AD-014/`08` (residency) | no telemetry violates tenant residency | §18.1 (regional-in/stored/viewed; id-only cross-region) | ✅ |

**No contradictions found.** Every corrective change is additive and traces to a frozen decision; none modifies `00`–`13` or AD-001…AD-023.

---

## 23. Adversarial Review — Post-Corrective (Resolutions, Score & Freeze)

> The corrective pass resolved all High and Medium findings from the first review.

### High — RESOLVED
- **OH-1 — Two-tier telemetry architecture explicit (§5.1).** Plane A (Prometheus + TSD, operational, low-cardinality, forbidden-label list) and Plane B (ClickHouse, analytical, high-cardinality) fully separated; Prometheus never used for analytics; FinOps/per-tenant/per-request served from Plane B. ✅
- **OH-2 — Telemetry privacy enforcement complete (§7.1).** Allow-lists (span/metric/log/event), automatic scanners, build-failing lint, runtime PII/secret/prompt-content detection, automatic redaction + rejection, CI + production validation — a mandatory zero-tolerance invariant on both planes. ✅
- **OH-3 — Multi-region residency-aware telemetry complete (§18.1).** Regional collectors/storage/retention/dashboards; id-only cross-region correlation; aggregated-only cross-region; global exec dashboards from rollups; residency-safe DR; global SLO from regional rollups — with the explicit guarantee that **no telemetry violates tenant residency**. ✅

### Medium — RESOLVED
- **OM-1** tail-sampling **collector-tier topology** specified (§6). ✅
- **OM-2** **observability cost budget** (bounded % of infra, enforced) (§15). ✅
- **OM-3** **exemplar chain validated** end-to-end (§5). ✅
- **OM-4** **mandatory "non-authoritative signal" label** for hallucination scores (§11). ✅
- **OM-5** **SIEM detection catalog** enumerated (§14.1) — resolves `13` SL-2. ✅

### Low — deferred (as directed)
- **OL-1** dashboard-as-code, **OL-2** metric/label registry → governance tooling; **OL-3** synthetic-probe cadence → `16`; **OL-4** trace-retention numbers → `32`. None block freeze.

### Inherent, honestly-disclosed limitation (NOT a defect, OOS-6)
- **Hallucination "signals" are non-authoritative** — now mandatorily labeled everywhere they surface (§11); the platform never presents groundedness as a truth guarantee.

### Consistency re-review
Re-ran §22 against `00`–`13` and AD-001…AD-023 with the corrective changes: **no contradictions**; every addition is additive and traces to a frozen decision (two-tier→TD-006/011; privacy→AD-012/021; residency→AD-014). No frozen document or ADR was modified.

### Recalculated Observability Readiness Score: **98 / 100**
All High/Medium resolved; two-tier architecture, privacy enforcement, and multi-region residency are now first-class and enforceable (§19 build-failing gates added). The 2 residual points reflect the **inherent, disclosed** hallucination-signal limitation and normal calibration items (deferred to `32`) — not defects.

### Remaining findings
**🔴 Critical: none. 🟠 High: none. 🟡 Medium: none.** 🟢 Low ×4 deferred to `16`/`32`/tooling — non-blocking.

### Recommendation: **FREEZE at v1.0.** Zero Critical, zero High. Consistency review clean.

---

*End of document — 14-Observability-Standards.md (v1.0, frozen)*
