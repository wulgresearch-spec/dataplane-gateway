# 03 — Non-Functional Requirements

**Document:** Non-Functional Requirements (NFR) / Engineering Quality Attributes
**Project:** Reliability-First AI Gateway
**Status:** Living document (v1.0) — **mandatory for every design review**
**Audience:** Architecture, Engineering, SRE, Security, Compliance, Product
**Classification:** Internal — Engineering Standard
**Builds on (approved, frozen):** `00-Project-Vision.md`, `01-Problem-Statement.md`, `02-Business-Requirements.md`

> **Authority.** This document defines the measurable engineering characteristics that every component, service, interface, SDK, plugin, and deployment must satisfy. It is mandatory: no design may be approved that violates a P0/P1 requirement here without an explicit, recorded exception granted by the engineering and reliability leadership. Every architecture decision must demonstrate how it satisfies the relevant requirements below.
>
> **Non-goals.** This document is not architecture, not implementation, not interface design, not code. It states *what quality must be achieved and how it is measured*, and remains technology-neutral. Where a target implies a technique, that technique is illustrative context for the rationale only, never a mandate.
>
> **On numbers.** Targets use accepted industry conventions (Google SRE, AWS Well-Architected, common enterprise infrastructure practice) and are justified individually. They are **tiered** by service tier (Section 64) where customers legitimately need different levels. No target is asserted without rationale. All targets are subject to empirical calibration during load/soak testing (Sections 50–54); where a value is provisional pending calibration it is marked *(calibration target)*.

---

## Table of Contents

1. Executive Summary · 2. Purpose · 3. Scope · 4. Engineering Principles · 5. Availability · 6. Reliability · 7. Performance · 8. Scalability · 9. Elasticity · 10. Capacity Planning · 11. Latency Budgets · 12. Throughput · 13. Concurrency · 14. Streaming Performance · 15. Structured Output Reliability · 16. Provider Failover · 17. Retry Behaviour · 18. Security · 19. Privacy · 20. Compliance · 21. Audit · 22. Authentication · 23. Authorization · 24. Secrets Management · 25. Data Protection · 26. Encryption Standards · 27. Networking · 28. API Reliability · 29. SDK Compatibility · 30. Observability · 31. Logging · 32. Metrics · 33. Distributed Tracing · 34. Alerting · 35. Cost Efficiency · 36. Cache Performance · 37. Datastore Performance · 38. Message/Queue Requirements · 39. Disaster Recovery · 40. Backup Strategy · 41. High Availability · 42. Multi-Region · 43. Upgrade Strategy · 44. Version Compatibility · 45. Configuration Management · 46. Operational · 47. Deployment · 48. Monitoring · 49. Maintenance Windows · 50. Testing · 51. Load Testing · 52. Stress Testing · 53. Chaos Engineering · 54. Fault Injection · 55. Security Testing · 56. Accessibility (Admin UI) · 57. Internationalization · 58. Supportability · 59. Operational KPIs · 60. Engineering KPIs · 61. SLOs · 62. SLIs · 63. Error Budgets · 64. Service Tiers · 65. Risk Analysis · 66. Assumptions · 67. Open Questions · 68. Appendix

---

## 1. Executive Summary

This document establishes the non-functional requirements (NFRs) — the engineering quality attributes — for the Reliability-First AI Gateway. Where `02-Business-Requirements.md` defined *what the product must achieve*, this document defines *how well it must do so*, in measurable, verifiable terms.

The central design tension for an AI gateway is that it sits on the request path between enterprise applications and probabilistic, externally-operated model providers. This gives rise to three governing facts that shape every requirement here:

1. **The gateway's own reliability must exceed that of the systems it fronts.** A reliability layer that is less reliable than the providers it mediates has negative value. The gateway's self-attributable availability, error rate, and correctness targets are therefore stringent (four nines of control-plane availability at Enterprise tier, gateway-attributable error rate below 0.01%).
2. **End-to-end availability is bounded by providers, but the gateway *increases* effective availability through failover.** We separate *gateway-attributable* metrics (which we fully own) from *end-to-end* metrics (which we improve but do not solely control), and we set targets for each accordingly. Conflating the two produces either impossible promises or meaningless ones.
3. **Correctness is a first-class reliability attribute, not just uptime.** Because a successful transport is not a successful result (per `01`), this document sets explicit numeric targets for structured-output conformance, streaming integrity, tool-call integrity, and token-accounting accuracy — quality attributes that traditional infrastructure NFRs omit entirely. These are as important as availability.

Requirements are **tiered** across four service tiers (Developer, Business, Enterprise, Regulated/Mission-Critical) because a startup and a national health system legitimately need different guarantees, and a single target would either over-serve the former at ruinous cost or under-serve the latter fatally. The tier model (Section 64) is referenced throughout.

The document defines targets, measurement methods, monitoring, alert thresholds, acceptance and verification criteria, failure impacts, and priorities for each requirement, and it codifies the SLI/SLO/error-budget framework (Sections 61–63) by which reliability is governed operationally. It closes with risk analysis, assumptions, and open questions.

Every requirement traces to the business requirements and problems it serves. This document is the standard against which all future designs, load tests, chaos experiments, and production operations are judged.

---

## 2. Purpose

The purpose of this document is to:

- Define the measurable quality attributes the platform must meet, so that "reliable" is an engineering commitment with numbers, not an adjective.
- Provide the mandatory acceptance criteria for design reviews, so that architecture is evaluated against explicit, agreed targets.
- Establish the SLI/SLO/error-budget framework that governs day-to-day reliability decisions and the balance between velocity and stability.
- Tier requirements so that commercial service tiers (Section 64, and `02` Section 25) are backed by concrete engineering guarantees.
- Give SRE, security, and compliance functions the thresholds they enforce and alert on.
- Serve as the source from which test plans (load, stress, chaos, fault injection, security) derive their pass/fail criteria.

---

## 3. Scope

**In scope:** the engineering quality attributes of the gateway/control-plane itself and everything it owns on the request and control paths — availability, reliability, correctness, performance, scalability, security, privacy, compliance posture, observability, disaster recovery, operability, and the testing that verifies them. Both *gateway-attributable* and *end-to-end* metrics are in scope, clearly distinguished.

**Out of scope:** the internal quality attributes of the model providers (which we measure and route around but do not control); the quality attributes of customer applications above the gateway; and any architectural or implementation choice (deferred to `04-System-Architecture.md` and later). This document constrains those choices without making them.

**Boundary of responsibility:** consistent with `02` (OOS-6), the gateway is responsible for the correctness of *structure, integrity, accounting, governance, and delivery*, not for the semantic correctness of model reasoning. Correctness targets here concern the former.

---

## 4. Engineering Principles

These principles govern the interpretation and application of every requirement.

- **EP-1 — Reliability is the primary quality attribute.** When quality attributes conflict on the critical path, reliability and correctness win over latency, cost, and feature velocity.
- **EP-2 — Measure what we promise.** Every reliability claim has a defined SLI (Section 62), an SLO (Section 61), and an error budget (Section 63). Unmeasurable requirements are defects.
- **EP-3 — Own our numbers, attribute the rest.** We separate gateway-attributable metrics (we are accountable) from end-to-end and provider-attributable metrics (we improve, measure, and expose). We never take credit or blame for what we do not own, and we never hide behind provider dependency for what we do.
- **EP-4 — Fail explicitly and safely.** Degradation must be legible, bounded, and safe (no silent corruption, no policy bypass, no cross-tenant leakage) — consistent with `02` business rules BRULE-1, BRULE-2, BRULE-7, BRULE-8.
- **EP-5 — Overhead is a budget, not a byproduct.** The latency, CPU, and memory the gateway adds are explicitly budgeted and enforced (Section 11), because a control plane that materially slows the path will not be adopted.
- **EP-6 — Tier honestly.** Higher tiers get stronger guarantees at higher cost; lower tiers get honest, lower guarantees — never a single target that misleads either.
- **EP-7 — Defense in depth for correctness and security.** Critical properties (isolation, data protection, output integrity) are enforced by multiple independent mechanisms, so that a single failure does not breach them.
- **EP-8 — Verify continuously.** Requirements are verified not once but continuously, in production (monitoring) and proactively (load, chaos, fault injection). A target unverified in production is unproven.
- **EP-9 — Conservative on the critical path.** Prefer proven, predictable techniques over novel ones where correctness and availability are at stake (per `00`).
- **EP-10 — Graceful degradation over hard failure.** Where full service cannot be maintained, prefer bounded, prioritized degradation (shed low-priority load, preserve core correctness) over total failure.

---

### Requirement Field Convention

Flagship quantitative requirements below use the full field set: **ID · Title · Description · Business Justification · Engineering Justification · Target Metric · Measurement Method · Monitoring Method · Alert Threshold · Acceptance Criteria · Verification Method · Failure Impact · Risk Level · Priority · Dependencies.** For dense grouped areas, requirements are presented in tabular form with the same fields distributed across a target table plus shared measurement/verification notes, to keep the document usable; the full field semantics still apply. Priority uses P0/P1/P2/P3 consistent with `01`/`02`. Risk Level ∈ {Low, Moderate, High, Critical}.

---

## 5. Availability Requirements

Availability is expressed as **gateway-attributable availability** (the fraction of well-formed requests the gateway successfully processes and returns, excluding failures solely attributable to a provider that the gateway correctly surfaces or fails over) unless stated as **end-to-end**. This separation (EP-3) is essential: the gateway cannot be more available end-to-end than the providers plus its own failover capability, but it can and must be highly available in what it owns.

#### NFR-AV-001 — Gateway-Attributable Availability

- **Description:** The gateway control/data path must be available to accept, process, and return requests, measured excluding failures solely caused by upstream providers that the gateway handles correctly.
- **Business Justification:** The gateway is enterprise-critical infrastructure; its unavailability halts all AI traffic (`02` BR-003, SLA-1). Availability underpins the trust thesis (`00`).
- **Engineering Justification:** A reliability layer must exceed the reliability of what it fronts (EP-1, EP-3). Four nines is the accepted bar for critical, redundant infrastructure; three nines is acceptable for non-critical developer usage.
- **Target Metric (tiered, monthly):** T1 Developer ≥ **99.9%** (≤ 43.2 min/month down); T2 Business ≥ **99.95%** (≤ 21.9 min); T3 Enterprise ≥ **99.99%** (≤ 4.38 min); T4 Regulated/Mission-Critical ≥ **99.99%** control plane with multi-region continuity (Section 42) and additional DR guarantees (Section 39).
- **Measurement Method:** Ratio of successful gateway responses to total valid requests over rolling 28-day and calendar-month windows, computed from request-level telemetry; provider-only failures that are correctly surfaced/failed-over are excluded from the numerator's failures (they count against end-to-end, NFR-AV-002).
- **Monitoring Method:** Real-time availability SLI dashboards per tier and region; synthetic probes from multiple locations at ≤ 60 s interval.
- **Alert Threshold:** Page when the fast-burn error-budget rate implies SLO breach within the window (Section 63): e.g., 2% budget consumed in 1 h (14.4× burn) → page; 5% in 6 h → page. Ticket at slow burn (10% in 3 days).
- **Acceptance Criteria:** Sustained achievement of the tier target over three consecutive months in production, and demonstrated in pre-production HA/DR tests (Sections 41, 53).
- **Verification Method:** Production SLI measurement + quarterly failover/DR game days + synthetic monitoring audit.
- **Failure Impact:** Complete or partial loss of enterprise AI capability; direct SLA penalty exposure; trust damage (highest-severity per EP-1).
- **Risk Level:** Critical. **Priority:** P0. **Dependencies:** NFR-HA-*, NFR-DR-*, NFR-FO-*, Section 42.

#### NFR-AV-002 — End-to-End Availability Improvement (Effective Availability)

- **Description:** With failover enabled, the gateway must deliver *higher* effective availability than any single provider, by routing around provider degradation.
- **Business Justification:** `02` BR-003 promises provider incidents do not become customer outages.
- **Engineering Justification:** Independent providers with health-aware failover raise effective availability above any single dependency; the gateway's value in availability is precisely this uplift.
- **Target Metric:** Effective end-to-end availability with ≥ 2 healthy eligible providers configured ≥ **99.95%** (Business), ≥ **99.99%** (Enterprise/Regulated) for failover-eligible workloads; and demonstrably **greater than the best single configured provider's measured availability** in the same period.
- **Measurement Method:** End-to-end success ratio including provider outcomes, compared against per-provider availability measured from the same telemetry.
- **Monitoring Method:** Per-provider health/availability SLIs; effective-availability dashboard.
- **Alert Threshold:** Alert when effective availability drops below the single-best-provider baseline (failover not delivering its value) or below tier target.
- **Acceptance Criteria:** In provider-outage game days (Section 53), effective availability remains ≥ tier target while a provider is fully failed.
- **Verification Method:** Chaos experiments injecting provider failure; production measurement during real provider incidents.
- **Failure Impact:** Loss of the core availability value proposition. **Risk Level:** High. **Priority:** P0. **Dependencies:** Section 16, Section 6.

> **Trade-off note.** Higher availability tiers require multi-region redundancy and headroom (Sections 41–42, 10) that increase cost and operational complexity. Tiering (EP-6) ensures customers pay for the availability they need rather than a single expensive default.

---

## 6. Reliability Requirements

Reliability here means correctness and dependability of outcomes, beyond mere uptime. These are the requirements that distinguish this product (per `01` root causes A and B).

#### NFR-REL-001 — Gateway-Attributable Error Rate

- **Description:** The rate of failed requests caused by the gateway itself (not by valid provider errors correctly surfaced) must be very low.
- **Business Justification:** `02` BR-004, SLA-3; the gateway must not itself be a source of failure.
- **Engineering Justification:** Four-to-five nines of success is the accepted bar for well-run critical infrastructure; the gateway's own defects must be rare.
- **Target Metric:** Gateway-attributable error rate ≤ **0.01%** (Enterprise/Regulated) / ≤ **0.05%** (Business) / ≤ **0.1%** (Developer) of valid requests, rolling 28 days.
- **Measurement Method:** Classify each non-success by attribution (gateway vs provider vs client) via telemetry; compute gateway-attributable ratio.
- **Monitoring Method:** Error-rate SLI by attribution class, per tier/region.
- **Alert Threshold:** Fast-burn page at 14.4× budget burn; ticket at slow burn.
- **Acceptance Criteria:** Sustained over three months; no undiagnosed gateway-attributable error class exceeding budget.
- **Verification Method:** Production SLI; fault-injection tests confirming correct attribution.
- **Failure Impact:** Direct request failures; trust damage. **Risk Level:** Critical. **Priority:** P0. **Dependencies:** NFR-OBS-*, error-attribution correctness.

#### NFR-REL-002 — Correctness Under Concurrency (No Cross-Request/Tenant Leakage)

- **Description:** Under any concurrency level up to rated capacity, no request's data may appear in another request's or tenant's response, and no state may corrupt across requests.
- **Business Justification:** `02` BR-021, BRULE-7 (tenant boundaries absolute); a leakage incident is catastrophic for regulated customers.
- **Engineering Justification:** Addresses `01` PRB-019; correctness under concurrency is a hard invariant, not a probabilistic target.
- **Target Metric:** **Zero** cross-request/cross-tenant data-crossover events. (This is an invariant; the target is absolute, not a rate.)
- **Measurement Method:** Isolation assertions in telemetry (request-scoped identifiers validated end to end); continuous isolation testing under load.
- **Monitoring Method:** Automated isolation-violation detectors; any detection is a Sev1.
- **Alert Threshold:** Any single detected violation → immediate Sev1 page and traffic safeguard.
- **Acceptance Criteria:** Zero violations across sustained high-concurrency soak and chaos tests and in production.
- **Verification Method:** Dedicated concurrency/isolation test suites (Section 50), fuzzing, and production detectors.
- **Failure Impact:** Catastrophic — data breach, compliance violation, loss of trust. **Risk Level:** Critical. **Priority:** P0. **Dependencies:** NFR-CONC-*, NFR-SEC-*.

#### NFR-REL-003 — No Silent Incorrect Delivery

- **Description:** The gateway must never deliver known-incorrect or known-non-conforming output downstream without explicit signaling and recording (BRULE-1).
- **Target Metric:** **Zero** silent-incorrect-delivery events; 100% of detected non-conformance is signaled and recorded.
- **Measurement Method / Monitoring:** Every correction/rejection produces an audit record (Section 21); reconciliation confirms no detected issue was passed silently.
- **Alert Threshold:** Any evidence of silent delivery → Sev1.
- **Acceptance Criteria / Verification:** Fault-injection of malformed provider output confirms signaling+recording in 100% of cases.
- **Failure Impact:** Undetected bad data in enterprise systems. **Risk Level:** Critical. **Priority:** P0. **Dependencies:** Section 15, Section 21.

---

## 7. Performance Requirements

Performance is governed by **gateway overhead** — the latency and resource cost the gateway *adds* beyond the provider's own processing — because that is what the gateway controls (EP-5). Absolute end-to-end latency is dominated by provider/model time and by design is not something the gateway can bound.

#### NFR-PERF-001 — Added Latency (Non-Streaming, Standard Path)

- **Description:** For a standard request with baseline policy (auth, routing, accounting, standard validation), the latency the gateway adds beyond provider round-trip must be small and predictable.
- **Business Justification:** `02` SLA-2 (bounded, predictable overhead); adoption depends on the gateway not materially slowing applications.
- **Engineering Justification:** Mature reverse-proxy/gateway layers add low-single-digit to low-double-digit milliseconds; we budget generously for policy while staying imperceptible relative to model latency (typically hundreds of ms to seconds).
- **Target Metric (added overhead, excluding provider time and excluding heavy optional policy such as full-content DLP):** P50 ≤ **5 ms**, P95 ≤ **20 ms**, P99 ≤ **50 ms** *(calibration targets)*. Heavy optional policy (e.g., deep content inspection) is separately budgeted per NFR-PERF-003.
- **Measurement Method:** Instrument gateway-internal time (ingress to egress minus upstream wait) per request; report percentiles.
- **Monitoring Method:** Overhead-latency histograms per tier/region/policy profile.
- **Alert Threshold:** P99 overhead > 50 ms sustained 10 min → ticket; > 100 ms → page.
- **Acceptance Criteria:** Targets met at rated throughput (Section 12) in load tests and production.
- **Verification Method:** Load testing (Section 51) at representative and peak load.
- **Failure Impact:** Degraded application performance; adoption resistance. **Risk Level:** High. **Priority:** P0. **Dependencies:** Section 11, 12, 13.

#### NFR-PERF-002 — Added Latency (Streaming First Token / Time-To-First-Byte)

- **Description:** For streaming, the gateway must add minimal delay before the first token/byte reaches the client and must not batch or stall the stream.
- **Target Metric:** Added time-to-first-token overhead P95 ≤ **15 ms**, P99 ≤ **40 ms** *(calibration targets)*; inter-token added jitter P99 ≤ **10 ms**; the gateway must not increase end-to-end streaming completion time by more than **2%** versus direct provider streaming.
- **Measurement / Monitoring:** First-token overhead and inter-token jitter histograms; streaming completion-time comparison against control samples.
- **Alert Threshold:** First-token overhead P99 > 40 ms or streaming completion overhead > 5% → ticket/page per severity.
- **Acceptance Criteria / Verification:** Streaming load tests (Section 14, 51) confirm targets; no unnecessary buffering.
- **Failure Impact:** Sluggish real-time UX; loss of streaming value. **Risk Level:** High. **Priority:** P0. **Dependencies:** Section 14.

#### NFR-PERF-003 — Optional Heavy-Policy Overhead Budget

- **Description:** Optional deep processing (full-content inspection, redaction, grounding checks) has its own overhead budget, separately disclosed, so customers can trade cost/latency for assurance knowingly (BRULE-9).
- **Target Metric:** Each optional policy stage publishes a measured overhead budget; default heavy-policy profile adds P95 ≤ **75 ms** *(calibration target)*; customers can see the overhead each enabled policy contributes.
- **Measurement/Verification:** Per-stage overhead attribution; documented in policy profiles.
- **Risk Level:** Moderate. **Priority:** P1. **Dependencies:** Sections 18, 25.

---

## 8. Scalability Requirements

#### NFR-SCALE-001 — Horizontal Scalability

- **Description:** Throughput must scale near-linearly with added capacity, with no hard single-node ceiling on the request path.
- **Business Justification:** `02` BR-005; must support growth from a team to enterprise-wide standardization without re-architecture (ADOPT-6).
- **Engineering Justification:** Linear horizontal scaling is the accepted property for stateless request-path infrastructure; shared bottlenecks (state, coordination) must not force super-linear cost.
- **Target Metric:** Throughput scales ≥ **0.9×** linearly up to at least **50 nodes/units** of the request path *(calibration target)*; no single component becomes a hard cap below the rated peak (Section 12).
- **Measurement/Monitoring:** Scaling efficiency measured in load tests (throughput vs capacity); saturation indicators per component.
- **Alert Threshold:** Scaling efficiency < 0.8× or any component > 80% saturation at target load → capacity review.
- **Acceptance Criteria/Verification:** Demonstrated in scale-out load tests (Section 51).
- **Failure Impact:** Inability to serve growth; cost blowout. **Risk Level:** High. **Priority:** P0. **Dependencies:** Section 10, 12, 37, 38.

#### NFR-SCALE-002 — Multi-Tenancy Scalability

- **Description:** The platform must scale in number of tenants and per-tenant workloads without cross-tenant performance interference (noisy-neighbor), consistent with `01` PRB-018.
- **Target Metric:** A single tenant cannot consume more than its allocated share; p99 latency of a compliant tenant must not degrade more than **10%** due to another tenant's load spikes *(calibration target)*.
- **Measurement/Verification:** Noisy-neighbor tests injecting single-tenant spikes; fairness metrics (Section 17 quotas).
- **Risk Level:** High. **Priority:** P1. **Dependencies:** NFR-CONC-*, Section 17.

---

## 9. Elasticity Requirements

#### NFR-ELAS-001 — Responsive Autoscaling

- **Description:** Capacity must expand and contract with demand quickly enough to absorb realistic traffic surges without breaching latency/error SLOs, and contract to control cost.
- **Business Justification:** `02` BR-005, BR-013 (cost efficiency); AI traffic is bursty.
- **Engineering Justification:** Elastic scaling with adequate warm headroom prevents SLO breach during surges while avoiding permanent over-provisioning.
- **Target Metric:** Scale-out reaction to a sustained load increase within ≤ **60 s** to begin adding capacity and ≤ **3 min** to reach adequate capacity for a 2× surge *(calibration target)*; maintain warm headroom of **20–30%** above current load at Enterprise tier to cover the reaction gap; scale-in without SLO impact.
- **Measurement/Monitoring:** Scaling reaction-time metrics; headroom vs load; SLO adherence during surges.
- **Alert Threshold:** SLO breach during a surge within rated surge envelope → incident + capacity policy review.
- **Acceptance Criteria/Verification:** Surge load tests (2×, 5× within envelope) hold SLOs; documented surge envelope per tier.
- **Failure Impact:** SLO breach under load spikes; or cost waste. **Risk Level:** Moderate. **Priority:** P1. **Dependencies:** Section 10, 12.

---

## 10. Capacity Planning

#### NFR-CAP-001 — Headroom and Utilization Ceilings

- **Description:** Steady-state resource utilization must stay below ceilings that preserve headroom for spikes and failover, and capacity must be planned against forecast peak plus buffer.
- **Business/Engineering Justification:** Running critical infrastructure near saturation causes cascading failure; SRE practice keeps steady-state utilization well below 100% and plans N+ redundancy so that losing capacity (a node, a zone) does not breach SLOs.
- **Target Metric:** Steady-state target utilization ceilings: **CPU ≤ 65%**, **memory ≤ 75%** at the busy-hour average; provisioned peak capacity ≥ **forecast peak × 1.3** (Enterprise/Regulated) / **× 1.2** (Business); capacity survives loss of **one zone (N+1 at zone level)** without SLO breach (Enterprise/Regulated).
- **Measurement/Monitoring:** Utilization dashboards; capacity forecast vs actual; headroom tracking.
- **Alert Threshold:** Sustained CPU > 80% or memory > 85% → capacity action; headroom < 15% → page.
- **Acceptance Criteria/Verification:** Quarterly capacity review; zone-loss game day holds SLOs.
- **Failure Impact:** Saturation-induced outages; failover failure. **Risk Level:** High. **Priority:** P0. **Dependencies:** Sections 8, 9, 41, 42.

---

## 11. Latency Budgets

Latency budgets decompose the gateway's added overhead (NFR-PERF-001/002) into stage-level budgets so that no single internal concern silently consumes the budget. Budgets are for *added* overhead only.

| Stage (added overhead) | P50 budget | P95 budget | P99 budget | Priority |
|---|---|---|---|---|
| Ingress + auth/identity (NFR-AUTH) | ≤ 1 ms | ≤ 3 ms | ≤ 8 ms | P0 |
| Authorization + policy evaluation | ≤ 1 ms | ≤ 4 ms | ≤ 10 ms | P0 |
| Routing decision (provider/model select) | ≤ 0.5 ms | ≤ 2 ms | ≤ 5 ms | P0 |
| Accounting + telemetry capture | ≤ 0.5 ms | ≤ 2 ms | ≤ 5 ms | P0 |
| Standard output validation (structured) | ≤ 1 ms | ≤ 5 ms | ≤ 15 ms | P0 |
| **Standard path total (NFR-PERF-001)** | **≤ 5 ms** | **≤ 20 ms** | **≤ 50 ms** | **P0** |
| Optional heavy policy (per NFR-PERF-003) | budgeted separately, disclosed per profile | | | P1 |

**NFR-LAT-001 — Budget Enforcement:** The sum of enabled stage overheads must not exceed the standard-path total at the stated percentiles. **Measurement:** per-stage timing attribution. **Alert:** any stage exceeding 150% of its P99 budget for 10 min → ticket. **Verification:** load test stage-attribution report. **Risk:** High. **Priority:** P0.

*Values are calibration targets to be confirmed by load testing; the decomposition and enforcement discipline are the requirement.*

---

## 12. Throughput Requirements

#### NFR-TP-001 — Rated Sustained and Peak Throughput

- **Description:** Each tier must sustain a rated request throughput and a rated peak surge without SLO breach.
- **Business Justification:** `02` BR-005; throughput must match customer scale.
- **Engineering Justification:** Rated throughput with defined surge envelope is standard capacity contracting; targets are set per deployment size, not as universal absolutes, because absolute numbers depend on deployment footprint.
- **Target Metric:** Throughput is rated **per deployment unit and documented per tier** rather than as a single global number. Requirement: each deployment must publish its rated sustained requests/second and streaming concurrency, sustain **≥ rated** for 24 h soak within SLO, and absorb a **peak surge of ≥ 3× rated for ≥ 5 min** (Enterprise/Regulated) / **≥ 2×** (Business) within SLO via elasticity (Section 9).
- **Measurement/Monitoring:** Throughput SLIs; soak-test sustained rates; surge tests.
- **Alert Threshold:** Sustained load > 85% of rated for 15 min → capacity action.
- **Acceptance Criteria/Verification:** 24 h soak at rated load and surge tests pass SLOs (Sections 51–52).
- **Failure Impact:** Throttling, SLO breach at scale. **Risk Level:** High. **Priority:** P0. **Dependencies:** Sections 8, 9, 10.

> **Rationale for not inventing a single global RPS number.** A universal "N requests/sec" target would be meaningless without a fixed deployment size and workload mix, and inventing one would violate the "no arbitrary numbers" rule. The requirement instead mandates *rating, publishing, and proving* throughput per deployment, which is the correct engineering discipline.

---

## 13. Concurrency Requirements

#### NFR-CONC-001 — Concurrent Connection and Stream Handling

- **Description:** The gateway must handle high concurrency, including many simultaneous long-lived streaming connections, without correctness or stability loss.
- **Business/Engineering Justification:** Streaming and agentic workloads are concurrency-heavy (`01` PRB-019); long-lived connections stress resource lifecycle (PRB-020).
- **Target Metric:** Support the tier's rated concurrent connections and concurrent streams (published per deployment); correctness invariant NFR-REL-002 holds at 100% of rated concurrency and under a 1.5× overload test (with graceful shedding, EP-10); no resource leak over a **72 h** sustained-streaming soak (Section 14).
- **Measurement/Monitoring:** Concurrency gauges; per-connection resource tracking; leak detection over soak.
- **Alert Threshold:** Concurrency > 85% of rated → capacity action; any leak signal → ticket/page.
- **Acceptance Criteria/Verification:** 72 h streaming soak with flat resource profile; overload test sheds gracefully without corruption.
- **Failure Impact:** Instability, leaks, correctness loss under load. **Risk Level:** High. **Priority:** P0. **Dependencies:** Sections 14, 41, NFR-REL-002.

---

## 14. Streaming Performance Requirements

#### NFR-STRM-001 — Streaming Integrity

- **Description:** Streamed content and interleaved tool interactions must be delivered correctly and completely; mid-stream failures must be surfaced, never silently truncated (`01` PRB-004, `02` BR-002).
- **Target Metric:** **Unrecovered/silent stream-corruption rate ≤ 0.05%** of streamed requests (Enterprise/Regulated) / ≤ 0.1% (Business); **100%** of mid-stream failures explicitly surfaced and recorded (no silent truncation); reassembly correctness verified.
- **Business/Engineering Justification:** Corrupted streams are hard to detect and damage flagship real-time features; surfacing failures is an absolute (BRULE-1).
- **Measurement Method:** Integrity checks on reassembled streams (completeness, ordering, tool-call reconstruction); classification of terminations as clean/surfaced-error/silent.
- **Monitoring Method:** Stream-integrity SLI; silent-truncation detector (target zero).
- **Alert Threshold:** Any silent truncation detected → Sev2+; corruption rate above budget → page per burn.
- **Acceptance Criteria/Verification:** Fault-injection mid-stream (Section 54) yields 100% surfaced errors; integrity holds in streaming load tests.
- **Failure Impact:** Corrupted/incomplete user-facing output. **Risk Level:** High. **Priority:** P0. **Dependencies:** Sections 13, 15, 54.

#### NFR-STRM-002 — Streaming Latency (see NFR-PERF-002)

- Cross-reference: first-token and inter-token overhead governed by NFR-PERF-002. **Priority:** P0.

---

## 15. Structured Output Reliability Targets

These targets quantify `02` BR-001 and address `01` PRB-001/002/005. We distinguish **detection** (catching non-conformance), **escape** (bad output reaching downstream undetected), and **post-enforcement success** (conforming output ultimately delivered, which is partly bounded by model behavior and is therefore expressed with that dependency explicit).

#### NFR-SO-001 — Non-Conformance Detection and Escape Rate

- **Description:** Structured output that does not conform to the declared structure must be detected at the boundary; undetected escape to downstream systems must be near-zero.
- **Target Metric:** **Detection rate ≥ 99.99%** of non-conforming structured outputs; **escape rate ≤ 0.01%** (1 in 10,000) of structured requests (Enterprise/Regulated) / ≤ 0.05% (Business). Invalid-JSON escape specifically ≤ **0.005%**.
- **Business/Engineering Justification:** Escape of malformed/non-conforming data to enterprise systems is the core failure of `01` PRB-001/002; near-zero escape is the product's reason to exist for structured workloads.
- **Measurement Method:** Independent conformance re-check on a continuous sample and on 100% where feasible; escape measured via downstream conformance auditing on sampled traffic.
- **Monitoring Method:** Conformance/escape SLIs; escape events are high-severity.
- **Alert Threshold:** Escape rate above budget → page; any systematic escape class → Sev2.
- **Acceptance Criteria/Verification:** Adversarial malformed-output injection (Section 54) yields detection ≥ target; production sampling confirms escape ≤ target.
- **Failure Impact:** Bad data in enterprise systems; compliance risk. **Risk Level:** Critical. **Priority:** P0. **Dependencies:** Sections 14, 21, NFR-REL-003.

#### NFR-SO-002 — Post-Enforcement Conformance Success

- **Description:** After the gateway's enforcement (validation plus permitted, recorded repair/retry within policy), the fraction of structured requests that ultimately deliver conforming output should be high — while acknowledging an irreducible floor set by model behavior (OOS-6).
- **Target Metric:** Post-enforcement conformance success ≥ **99.5%** of structured requests *where the customer permits repair/retry*, with the residual explicitly surfaced (not silent). Where repair/retry is disallowed, the gateway guarantees detection and explicit failure (per NFR-SO-001), not success.
- **Measurement/Monitoring:** Success ratio post-enforcement; repair/retry attribution; residual-failure surfacing rate = 100%.
- **Alert Threshold:** Post-enforcement success below target sustained → investigation (may indicate provider/model regression, surfaced transparently).
- **Acceptance Criteria/Verification:** Measured across representative workloads; repair fidelity verified (repairs never change semantics beyond recorded rules, BRULE-1/BRULE-9).
- **Failure Impact:** Higher explicit failure rate for structured features. **Risk Level:** High. **Priority:** P0. **Dependencies:** NFR-SO-001; provider behavior (attributed, EP-3).

#### NFR-SO-003 — Tool/Function Call Integrity

- **Description:** Tool/function calls must be reconstructed faithfully and validated before any action; corrupted tool calls must not reach execution.
- **Target Metric:** **Corrupted-tool-call escape rate ≤ 0.01%**; **100%** of tool-call arguments validated against declared structure before execution is permitted.
- **Business/Engineering Justification:** `01` PRB-005; corrupted tool calls cause wrong real-world actions — a critical-severity failure.
- **Measurement/Monitoring:** Pre-execution validation coverage (target 100%); corrupted-escape detector.
- **Alert Threshold:** Any corrupted-tool-call escape → Sev2+.
- **Acceptance Criteria/Verification:** Fault-injection of malformed tool calls (Section 54) → 100% blocked pre-execution.
- **Failure Impact:** Incorrect/unauthorized automated actions. **Risk Level:** Critical. **Priority:** P0. **Dependencies:** Sections 14, 23.

---

## 16. Provider Failover Requirements

#### NFR-FO-001 — Failover Detection and Switching Time

- **Description:** When a provider degrades or fails, the gateway must detect it and switch eligible traffic to a healthy alternative quickly, respecting correctness and compliance constraints (`02` BR-003, BRULE-5, BRULE-8).
- **Target Metric:** **Unhealthy-provider detection** within ≤ **5 s** (rolling health signal) or immediately on hard error; **provider switching time** for a new request ≤ **500 ms** added decision overhead; **in-flight impact**: failed in-flight requests retried on an alternate within the retry policy (Section 17) such that **≥ 99%** of failover-eligible requests succeed within **≤ 2 s** additional latency. Compliance-unsafe failover targets are **never** taken (residency/policy enforced during failover).
- **Business/Engineering Justification:** Rapid, safe failover is the mechanism behind NFR-AV-002; slow failover negates the availability value; unsafe failover creates compliance violations (worse than the outage).
- **Measurement Method:** Detection latency, switching overhead, failover success ratio, and residency-safe-failover ratio (target 100%) from telemetry.
- **Monitoring Method:** Failover SLIs per provider; residency-violation-on-failover detector (target zero).
- **Alert Threshold:** Detection > 10 s, failover success < 99%, or any residency-unsafe failover → page.
- **Acceptance Criteria/Verification:** Provider-failure chaos experiments (Section 53) meet targets with zero compliance violations.
- **Failure Impact:** Outage propagation or compliance breach. **Risk Level:** Critical. **Priority:** P0. **Dependencies:** Sections 6, 17, 23, 42.

---

## 17. Retry Behaviour Requirements

#### NFR-RTY-001 — Bounded, Safe, Budgeted Retries

- **Description:** Retries must be bounded, use backoff with jitter, respect idempotency, coordinate with rate limits and cost, and never amplify overload (`01` PRB-009, `02` BR-004, BRULE-8).
- **Target Metric:** **Retry budget ≤ 10%** of request volume per rolling window (Google SRE client-retry-budget convention) — retries exceeding budget are shed, not issued; **maximum retry attempts** default ≤ 2 additional attempts on the standard path (configurable, bounded); **exponential backoff with jitter** mandatory; **zero** retries of non-idempotent/consequential operations without explicit idempotency guarantees; retry-induced duplicate consequential actions = **zero**.
- **Business/Engineering Justification:** The 10% retry-budget cap prevents retry storms (a documented cause of cascading outage); backoff+jitter prevents synchronization; idempotency protection prevents duplicate actions and double-charging.
- **Measurement Method:** Retry-rate SLI (retries/requests), retry-budget consumption, duplicate-action detector.
- **Monitoring Method:** Retry-amplification dashboards; storm detector.
- **Alert Threshold:** Retry rate > 10% sustained → page (storm risk); any retried consequential duplicate → Sev2.
- **Acceptance Criteria/Verification:** Fault-injection of transient failures shows bounded retries, no storm, no duplicate consequential actions.
- **Failure Impact:** Retry storms, cascading overload, duplicate actions, cost inflation. **Risk Level:** High. **Priority:** P0. **Dependencies:** Sections 16, 35, provider rate-limit signals.

---

## 18. Security Requirements

Security requirements are invariants and response-time targets; where absolute, they are stated as such (EP-4, EP-7).

#### NFR-SEC-001 — Vulnerability Remediation Time

- **Description:** Security vulnerabilities must be remediated within severity-based timeframes.
- **Target Metric (time-to-remediate from confirmed):** Critical (CVSS ≥ 9.0) ≤ **24 h** (mitigation) / ≤ **72 h** (full fix); High (7.0–8.9) ≤ **7 days**; Medium ≤ **30 days**; Low ≤ **90 days**. Known critical exploited-in-wild → emergency response immediately.
- **Business/Engineering Justification:** Aligns with common enterprise vulnerability-management SLAs; the gateway's boundary position makes prompt remediation essential.
- **Measurement/Monitoring:** Vulnerability tracking with age/SLA dashboards.
- **Alert Threshold:** Any critical vuln approaching SLA → escalation.
- **Acceptance Criteria/Verification:** Continuous scanning (Section 55); SLA adherence reporting.
- **Failure Impact:** Exploitable exposure at the boundary. **Risk Level:** Critical. **Priority:** P0. **Dependencies:** Section 55.

#### NFR-SEC-002 — Security Incident Response Time

- **Description:** Security incidents must be detected, triaged, and responded to within defined times.
- **Target Metric:** **Time to acknowledge** a Sev1 security incident ≤ **15 min** (24/7, Enterprise/Regulated); **time to contain** critical incidents ≤ **1 h** target; customer notification per contractual/regulatory obligations (e.g., breach notification within required legal windows).
- **Measurement/Monitoring:** Incident timelines; MTTA/MTTC for security incidents.
- **Alert Threshold:** Sev1 unacknowledged > 15 min → escalation chain.
- **Acceptance Criteria/Verification:** Security incident game days; post-incident reviews.
- **Failure Impact:** Prolonged breach exposure. **Risk Level:** Critical. **Priority:** P0. **Dependencies:** Sections 30–34, 58.

#### NFR-SEC-003 — Injection/Content-Trust Controls (bounded assurance)

- **Description:** Content-trust controls must reduce manipulation risk from untrusted content (`01` PRB-013, `02` BR-019), with transparent residual risk (BRULE-9).
- **Target Metric:** 100% of designated untrusted-content paths pass through configured content-trust controls; measured reduction in a maintained adversarial test-suite pass-through rate; residual risk documented (no false guarantee of completeness).
- **Measurement/Verification:** Continuous adversarial/red-team test suite (Section 55); control-coverage metric.
- **Risk Level:** High. **Priority:** P1. **Dependencies:** Sections 25, 55.

#### NFR-SEC-004 — Secure Defaults

- **Description:** All defaults must be the secure, compliant option (BRULE-3, `02` BR-027).
- **Target Metric:** 100% of security-relevant settings default to the secure value; insecure configuration requires explicit, recorded override.
- **Verification:** Configuration audit (Section 45); security testing. **Risk Level:** High. **Priority:** P1.

---

## 19. Privacy Requirements

#### NFR-PRIV-001 — Sensitive-Data Handling and Minimization

- **Description:** Personal and sensitive data (PII/PHI) must be handled per classification, minimized in retention, and never exposed beyond policy in records, caches, or outputs (`02` BR-018, `01` PRB-014).
- **Target Metric:** **Zero** policy-violating exposures of classified sensitive data in records/caches/logs; redaction/handling applied to **100%** of designated sensitive fields per policy; data minimization enforced (only necessary data retained, for only the configured duration).
- **Business/Engineering Justification:** Privacy failures are catastrophic and gate regulated adoption; zero-exposure is an invariant, not a rate.
- **Measurement/Monitoring:** Sensitive-data-in-store scanners; redaction coverage metric; retention conformance.
- **Alert Threshold:** Any detected policy-violating exposure → Sev1.
- **Acceptance Criteria/Verification:** Privacy test suite with synthetic sensitive data confirms zero exposure and full redaction coverage; retention audits.
- **Failure Impact:** Privacy breach, regulatory penalty. **Risk Level:** Critical. **Priority:** P0. **Dependencies:** Sections 21, 25, 26.

---

## 20. Compliance Requirements

Compliance NFRs concern the platform's own posture enabling customer compliance (`02` BR-022, COMP-*). Specific certifications are commercial/legal commitments; here we state the engineering posture and controls that must be in place and evidenced.

#### NFR-COMP-001 — Control Coverage and Evidence

- **Description:** The platform must implement and continuously evidence the technical controls required for the compliance regimes of its target industries, and be auditable against recognized frameworks.
- **Target Metric:** 100% of the applicable control set for the offered compliance posture is implemented and evidenced; control-evidence freshness within audit cadence; readiness for recognized frameworks appropriate to healthcare, finance, and government customers (specific certifications named in commercial commitments).
- **Business/Engineering Justification:** Regulated adoption depends on demonstrable controls (`02` BR-022); auditability is a product property (Section 21).
- **Measurement/Monitoring:** Continuous controls monitoring; evidence dashboards; control-drift detection.
- **Alert Threshold:** Any lapsed/failed control → remediation ticket at appropriate severity.
- **Acceptance Criteria/Verification:** Independent audits/assessments against target frameworks; continuous compliance monitoring.
- **Failure Impact:** Loss of regulated customers; audit failure. **Risk Level:** Critical. **Priority:** P0. **Dependencies:** Sections 18–26, 21.

---

## 21. Audit Requirements

#### NFR-AUD-001 — Audit-Grade Record Completeness and Integrity

- **Description:** Every request and material decision produces a complete, faithful, tamper-evident audit record with governed retention (`02` BR-011, BR-024, BRULE-6).
- **Target Metric:** **Audit record coverage = 100%** of requests and material policy/security decisions; **record loss = 0** (audit RPO = 0, see Section 39); **tamper-evidence** on 100% of audit records; retention configurable to regulatory requirement; **time to produce audit evidence** for a defined query ≤ **defined SLA** (e.g., standard audit export within hours, not days).
- **Business/Engineering Justification:** Audit completeness is often a precondition for regulated AI use; zero record loss is required for audit integrity.
- **Measurement/Monitoring:** Audit coverage reconciliation (records vs requests); integrity verification; retention conformance.
- **Alert Threshold:** Any coverage gap or integrity failure → Sev2+.
- **Acceptance Criteria/Verification:** Reconciliation shows 100% coverage; tamper-evidence verified; audit-export drills meet SLA.
- **Failure Impact:** Failed audits; compliance violation. **Risk Level:** Critical. **Priority:** P0. **Dependencies:** Sections 30–33, 39, 40.

---

## 22. Authentication Requirements

#### NFR-AUTH-001 — Strong, Attributable Authentication

- **Description:** Every request must be authenticated and attributable to an identity; authentication must meet enterprise standards (`02` BR-021).
- **Target Metric:** **100%** of requests authenticated (no anonymous access to protected functions); support for enterprise identity standards and strong/multi-factor administrative authentication; authentication decision overhead within the latency budget (Section 11); credential/token lifetimes bounded and revocable.
- **Business/Engineering Justification:** Attribution underpins governance, audit, and isolation; unauthenticated access is disallowed by BRULE-2/BR-021.
- **Measurement/Monitoring:** Auth coverage (target 100%); failed-auth anomaly detection.
- **Alert Threshold:** Any unauthenticated protected access → Sev1; auth-failure spikes → security alert.
- **Acceptance Criteria/Verification:** Security testing confirms no unauthenticated path; standards conformance verified.
- **Failure Impact:** Unauthorized access. **Risk Level:** Critical. **Priority:** P0. **Dependencies:** Sections 23, 24.

---

## 23. Authorization Requirements

#### NFR-AUTHZ-001 — Least-Privilege, Policy-Consistent Authorization

- **Description:** Access to models, tools, data, and administrative functions must be authorized per policy on every request, enforcing least privilege and tenant isolation (`02` BR-021, BR-015, BRULE-2, BRULE-7).
- **Target Metric:** **100%** of protected actions authorized before execution; **zero** authorization bypass; least-privilege default (deny-by-default); authorization overhead within latency budget (Section 11); consistent enforcement across all traffic (no bypass path).
- **Business/Engineering Justification:** Authorization consistency is the mechanism of governance and isolation; a single bypass is a critical failure.
- **Measurement/Monitoring:** Authorization coverage; bypass detector (target zero); deny/allow telemetry.
- **Alert Threshold:** Any authorization bypass → Sev1.
- **Acceptance Criteria/Verification:** Authorization test suite (including negative tests) shows zero bypass; deny-by-default verified.
- **Failure Impact:** Unauthorized access, cross-tenant exposure. **Risk Level:** Critical. **Priority:** P0. **Dependencies:** Sections 22, 18.

---

## 24. Secrets Management Requirements

#### NFR-SEC-SM-001 — Secret Governance, Rotation, and Non-Exposure

- **Description:** Secrets (including provider credentials) must be stored securely, least-privilege, rotatable, revocable, attributable, and never exposed in logs/records (`02` BR-020, `01` PRB-015).
- **Target Metric:** **Zero** secrets in plaintext logs/records/config artifacts; secrets encrypted at rest (Section 26); **rotation** supported without downtime with maximum secret age policy (e.g., ≤ 90 days default, configurable); **revocation** effective within ≤ **5 min**; 100% secret access attributable and audited.
- **Business/Engineering Justification:** Central credential governance reduces breach blast radius; leaked secrets are a leading breach cause.
- **Measurement/Monitoring:** Secret-scanning of outputs (target zero); rotation/age conformance; revocation-time measurement.
- **Alert Threshold:** Any secret exposure detected → Sev1; overdue rotation → ticket.
- **Acceptance Criteria/Verification:** Secret-leak scans clean; rotation/revocation drills meet targets.
- **Failure Impact:** Credential compromise. **Risk Level:** Critical. **Priority:** P0. **Dependencies:** Sections 26, 21.

---

## 25. Data Protection Requirements

#### NFR-DP-001 — Data Classification-Driven Protection

- **Description:** Data must be protected according to its classification throughout its lifecycle at the boundary — in transit, at rest, in caches, in records — with policy-driven redaction and residency (`02` BR-018, BR-023).
- **Target Metric:** 100% of data protected per classification; sensitive data never persisted beyond policy; residency enforced (Section 42) including during failover (Section 16); cache contents governed (no policy-violating sensitive data cached).
- **Measurement/Monitoring:** Classification-coverage metrics; residency-conformance; cache-content governance checks.
- **Alert Threshold:** Any classification/residency violation → Sev1/Sev2.
- **Acceptance Criteria/Verification:** Data-protection test suite; residency tests; cache-governance tests.
- **Failure Impact:** Data exposure, residency breach. **Risk Level:** Critical. **Priority:** P0. **Dependencies:** Sections 19, 26, 36, 42.

---

## 26. Encryption Standards

#### NFR-ENC-001 — Encryption In Transit and At Rest

- **Description:** All data in transit and at rest must be encrypted using current, industry-accepted standards; deprecated algorithms/protocols are prohibited.
- **Target Metric:** **In transit:** TLS **1.2 minimum, 1.3 preferred**; deprecated protocols (SSL, TLS ≤ 1.1) and weak ciphers disabled; strong cipher suites only; certificate management with bounded lifetimes and automated renewal. **At rest:** **AES-256** (or equivalent industry-accepted) for stored data, records, backups, and secrets; **key management** with rotation, access control, and separation of duties; support for customer-managed keys at Enterprise/Regulated tiers. **In use / sensitive fields:** field-level protection where required by classification.
- **Business/Engineering Justification:** These are the accepted baseline standards for enterprise/regulated data protection; using anything weaker fails audits and creates exposure.
- **Measurement/Monitoring:** TLS/cipher configuration scanning; encryption-coverage audits; key-rotation conformance.
- **Alert Threshold:** Any deprecated protocol/cipher or unencrypted sensitive store detected → Sev2+.
- **Acceptance Criteria/Verification:** Configuration and penetration testing confirm standards; encryption-coverage = 100% for in-scope data.
- **Failure Impact:** Data exposure; compliance failure. **Risk Level:** Critical. **Priority:** P0. **Dependencies:** Sections 24, 25, 55.

---

## 27. Networking Requirements

#### NFR-NET-001 — Enterprise Networking, Egress Control, Residency-Aware Routing

- **Description:** The platform must operate within enterprise networking constraints — private connectivity, controlled egress, regional routing — and enforce residency-aware routing (`02` BR-026, BR-023, `01` PRB-017).
- **Target Metric:** Support private/controlled connectivity and egress control; **100%** residency-constrained traffic routed only to compliant regions/providers (including failover, Section 16); network-path overhead within latency budgets; support enterprise proxy/egress requirements.
- **Business/Engineering Justification:** Networking constraints are a common regulated-adoption blocker; residency-aware routing prevents compliance violations.
- **Measurement/Monitoring:** Egress-conformance metrics; residency-routing conformance (target 100%); network latency attribution.
- **Alert Threshold:** Any egress/residency violation → Sev1/Sev2.
- **Acceptance Criteria/Verification:** Networking conformance tests; residency-routing tests including failover.
- **Failure Impact:** Blocked deployment or residency breach. **Risk Level:** High. **Priority:** P1. **Dependencies:** Sections 16, 25, 42.

---

## 28. API Reliability Requirements

*(Reliability of the programmatic surface as an engineering attribute; interface design itself is out of scope.)*

#### NFR-IF-001 — Interface Reliability and Stability

- **Description:** The programmatic surface exposed to customers must be reliable, consistent, versioned, and backward-compatible within a stability commitment (`02` BR-007, BR-036, `01` PRB-021/022).
- **Target Metric:** Interface success (excluding provider/client faults) governed by NFR-REL-001; **backward compatibility** maintained within a major version — no breaking change without version increment and deprecation window ≥ **12 months** (Enterprise) / ≥ 6 months (Business); consistent behavior across providers (`02` BR-006); documented, enforced request/response limits (Section below).
- **Business/Engineering Justification:** Stability insulates customers from ecosystem churn (a core value, `01` PRB-022); breaking changes destroy trust.
- **Measurement/Monitoring:** Compatibility test suites across versions; deprecation tracking; interface-reliability SLI.
- **Alert Threshold:** Any undeclared breaking change reaching customers → Sev2.
- **Acceptance Criteria/Verification:** Contract/compatibility tests pass across supported versions.
- **Failure Impact:** Customer breakage, trust loss. **Risk Level:** High. **Priority:** P1. **Dependencies:** Sections 29, 43, 44.

#### NFR-IF-002 — Request/Response Limits

- **Description:** Enforce sane, documented, configurable size and rate limits to protect stability.
- **Target Metric:** Default maximum request payload (e.g., **10 MB** default, configurable per tier) and response handling limits published and enforced; oversized requests rejected cleanly with clear errors; limits protect against resource exhaustion without impeding legitimate use.
- **Verification:** Boundary/limit tests. **Risk Level:** Moderate. **Priority:** P1. **Dependencies:** Sections 13, 17.

---

## 29. SDK Compatibility Requirements

#### NFR-SDK-001 — SDK Stability and Compatibility

- **Description:** Client SDKs must be stable, consistent across supported languages, backward-compatible, and clearly versioned (`02` BR-028, `01` PRB-021).
- **Target Metric:** SDKs maintain backward compatibility within a major version; support a defined matrix of language/runtime versions with published support windows; SDK behavior consistent across providers; SDK defects on the reliability path held to the same standard as the platform (NFR-REL-001).
- **Business/Engineering Justification:** SDK churn is a documented pain (`01` PRB-021); stability drives developer adoption (`02` BR-028).
- **Measurement/Monitoring:** SDK compatibility test matrix; SDK error telemetry.
- **Alert Threshold:** Compatibility regression → release block.
- **Acceptance Criteria/Verification:** Cross-version, cross-language compatibility tests pass; documented support matrix.
- **Failure Impact:** Developer breakage, adoption resistance. **Risk Level:** Moderate. **Priority:** P1. **Dependencies:** Sections 28, 44.

---

## 30. Observability Requirements

#### NFR-OBS-001 — Full-Fidelity, Consistent Observability

- **Description:** Every request and material decision must be observable with consistent, complete, correlated telemetry across providers, retries, failovers, and streams (`02` BR-010, `01` PRB-011).
- **Target Metric:** **Telemetry coverage = 100%** of requests; correlation across retries/failover/stream segments = 100%; telemetry captured within latency budget (Section 11); observability data available in near real time (freshness ≤ seconds for operational signals); consistent schema across providers.
- **Business/Engineering Justification:** Observability underpins reliability, cost, audit, and debugging; gaps make incidents unresolvable and audits impossible.
- **Measurement/Monitoring:** Coverage reconciliation; correlation completeness; telemetry freshness.
- **Alert Threshold:** Coverage < 100% or correlation gaps → ticket; telemetry pipeline failure → page.
- **Acceptance Criteria/Verification:** Coverage reconciliation = 100%; correlation verified across injected retries/failovers.
- **Failure Impact:** Blind operations, failed audits. **Risk Level:** High. **Priority:** P0. **Dependencies:** Sections 31–34, 21.

---

## 31. Logging Requirements

#### NFR-LOG-001 — Structured, Governed, Non-Leaking Logs

- **Description:** Logs must be structured, correlated, governed for sensitive data, retained per policy, and never leak secrets or classified data.
- **Target Metric:** 100% structured and correlated (trace/request identifiers); **zero** secrets/classified-data leakage in logs (see NFR-SEC-SM-001, NFR-PRIV-001); retention configurable per policy/regulation; log integrity for audit-relevant logs (Section 21); log delivery reliability ≥ 99.9% (no material log loss for operational logs; audit logs = zero loss per Section 21).
- **Measurement/Monitoring:** Log-leak scanning; structure/correlation conformance; delivery reliability.
- **Alert Threshold:** Any leak → Sev1; log-pipeline loss above threshold → page.
- **Acceptance Criteria/Verification:** Leak scans clean; correlation verified; retention audited.
- **Failure Impact:** Data leakage or lost audit trail. **Risk Level:** High. **Priority:** P0. **Dependencies:** Sections 21, 24, 25.

---

## 32. Metrics Requirements

#### NFR-MET-001 — Complete, Timely Metrics for All SLIs

- **Description:** All SLIs (Section 62) must be backed by accurate, timely metrics at appropriate granularity (per tier, region, provider, tenant where relevant).
- **Target Metric:** 100% of defined SLIs have live metrics; metric freshness ≤ **10 s** for operational metrics; cardinality managed to remain queryable; accounting metrics accurate per NFR-COST-001.
- **Measurement/Monitoring:** Metric-availability checks; freshness monitoring; SLI-to-metric coverage.
- **Alert Threshold:** Missing SLI metric or staleness > threshold → ticket/page.
- **Acceptance Criteria/Verification:** Every SLO has a corresponding live metric and dashboard.
- **Failure Impact:** Unmeasurable SLOs (violates EP-2). **Risk Level:** High. **Priority:** P0. **Dependencies:** Sections 61–63.

---

## 33. Distributed Tracing Requirements

#### NFR-TRC-001 — End-to-End Trace Correlation

- **Description:** Requests must be traceable end to end across internal stages, retries, failovers, and stream segments, with sampling that preserves diagnostic value.
- **Target Metric:** Trace correlation across all internal stages = 100% (identifiers propagated); intelligent sampling with **100% trace capture for errors and slow requests**, representative sampling otherwise; trace overhead within latency budget.
- **Measurement/Monitoring:** Trace-completeness checks; error/slow-request capture rate.
- **Alert Threshold:** Trace-correlation gaps on error paths → ticket.
- **Acceptance Criteria/Verification:** Injected failures produce complete, correlated traces.
- **Failure Impact:** Slow incident diagnosis (raises MTTR). **Risk Level:** Moderate. **Priority:** P1. **Dependencies:** Sections 30, 34.

---

## 34. Alerting Requirements

#### NFR-ALRT-001 — Actionable, Burn-Rate-Based Alerting

- **Description:** Alerting must be based on SLO burn rates and symptom-based signals, be actionable, and minimize noise (per SRE practice).
- **Target Metric:** Multi-window, multi-burn-rate alerting for each SLO (e.g., fast-burn: 2% budget/1 h → page; slow-burn: 10% budget/3 days → ticket); **actionability**: every paging alert maps to a runbook; **alert precision** target — page-level false-positive rate ≤ **10%** *(calibration target)* to prevent fatigue; time-to-detect (MTTD) for SLO-threatening conditions ≤ **5 min**.
- **Business/Engineering Justification:** Burn-rate alerting is the SRE standard; noisy alerting causes fatigue and missed incidents.
- **Measurement/Monitoring:** Alert precision/recall tracking; MTTD; runbook-coverage of pages.
- **Alert Threshold:** (Meta) Alerting-system failure or blind spot → page.
- **Acceptance Criteria/Verification:** Game days confirm timely, actionable alerts; alert-quality review each quarter.
- **Failure Impact:** Missed or ignored incidents. **Risk Level:** High. **Priority:** P0. **Dependencies:** Sections 61–63, 58.

---

## 35. Cost Efficiency Requirements

#### NFR-COST-001 — Token/Cost Accounting Accuracy

- **Description:** Token and cost accounting must be accurate and reconcilable to provider ground truth (`02` BR-012, `01` PRB-012).
- **Target Metric:** **Token/cost accounting error ≤ 1%** vs provider-reported ground truth (Business), **≤ 0.5%** (Enterprise/Regulated), measured over reconciliation windows; near-real-time accounting freshness ≤ seconds–minutes; attribution correctness (per team/tenant/use case) = 100%.
- **Business/Engineering Justification:** Inaccurate accounting breaks budgeting, chargeback, and usage-based billing; ≤1% is a defensible reconciliation tolerance given provider reporting variance.
- **Measurement/Monitoring:** Reconciliation against provider invoices; error-rate SLI; attribution checks.
- **Alert Threshold:** Accounting error above tolerance → investigation.
- **Acceptance Criteria/Verification:** Reconciliation drills within tolerance; attribution verified.
- **Failure Impact:** Budget/billing inaccuracy, disputes. **Risk Level:** High. **Priority:** P0. **Dependencies:** Sections 30, 32.

#### NFR-COST-002 — Platform Cost Efficiency

- **Description:** The platform's own resource cost per unit of traffic must be efficient and trend down with scale.
- **Target Metric:** Cost-per-request (platform overhead, excluding provider spend) tracked and improving with scale; no more than a documented, bounded overhead percentage per tier *(calibration target)*.
- **Verification:** Cost-efficiency dashboards; periodic review. **Risk Level:** Moderate. **Priority:** P2. **Dependencies:** Sections 8–10.

---

## 36. Cache Performance Requirements

Caching is an optimization; **correctness and governance dominate hit-rate** (EP-1, BRULE-1/BRULE-5).

#### NFR-CACHE-001 — Cache Correctness and Governance

- **Description:** Cached responses must never violate correctness, freshness, or data-governance policy; caching is disabled by default for sensitive data unless explicitly and safely permitted.
- **Target Metric:** **Zero** policy-violating or stale-beyond-policy cache serves; cache never serves across tenant/policy boundaries (NFR-REL-002, BRULE-7); cached sensitive data governed per Section 25.
- **Measurement/Monitoring:** Cache-correctness detectors; stale-serve detector (target zero); cache-governance checks.
- **Alert Threshold:** Any incorrect/stale/cross-boundary serve → Sev2/Sev1.
- **Acceptance Criteria/Verification:** Cache-correctness and governance test suites; zero violations.
- **Failure Impact:** Wrong/leaked cached data. **Risk Level:** High. **Priority:** P1. **Dependencies:** Sections 25, 37.

#### NFR-CACHE-002 — Cache Effectiveness (Cost KPI, not reliability SLO)

- **Description:** Where caching is enabled, it should deliver meaningful cost/latency benefit — but hit-rate is a cost KPI, not a reliability SLO.
- **Target Metric:** Cache hit-rate is workload-dependent and reported as a cost KPI; **no reliability SLO depends on cache hit-rate** (a cache miss must always be served correctly by the origin path within latency SLOs). Target hit-rates are set per workload during optimization, not fixed here.
- **Rationale:** Setting a universal "maximum cache miss rate" as a reliability target would be an arbitrary number and would wrongly couple reliability to an optimization; we explicitly decline to do so.
- **Verification:** Cache-effectiveness reporting. **Risk Level:** Low. **Priority:** P2. **Dependencies:** Section 35.

---

## 37. Datastore Performance Requirements

*(Technology-neutral: "datastore" denotes any persistence the platform relies on, without prescribing a technology.)*

#### NFR-DS-001 — Persistence Performance and Integrity

- **Description:** Persistence supporting the request path (e.g., policy, accounting, audit, state) must meet latency, throughput, durability, and integrity targets without becoming a request-path bottleneck.
- **Target Metric:** Persistence access on the critical path must fit within the latency budget (Section 11) — critical-path persistence reads P99 ≤ **10 ms** *(calibration target)*; durability sufficient for audit RPO = 0 (Section 39) for audit data; integrity guarantees (no corruption/loss for audit-critical data); persistence scales with traffic (Section 8) without becoming a hard cap.
- **Measurement/Monitoring:** Persistence latency/throughput/error SLIs; durability/integrity verification.
- **Alert Threshold:** Critical-path persistence latency or error above budget → page.
- **Acceptance Criteria/Verification:** Load tests confirm persistence is not the bottleneck; durability drills confirm no loss.
- **Failure Impact:** Request-path slowdown or data loss. **Risk Level:** High. **Priority:** P1. **Dependencies:** Sections 11, 39, 40.

---

## 38. Message/Queue Requirements

*(Technology-neutral: "queue" denotes any asynchronous transport the platform relies on.)*

#### NFR-Q-001 — Asynchronous Transport Reliability and Backpressure

- **Description:** Any asynchronous processing (e.g., telemetry, batch, async workflows) must be reliable, bounded, and apply backpressure rather than unbounded growth (`01` PRB-020, PRB-024).
- **Target Metric:** **Bounded queue depth** with backpressure — no unbounded growth; **no silent message loss** for at-least-once-required flows (audit/accounting = no loss); maximum queue latency and depth thresholds defined and enforced; backpressure engages before resource exhaustion; processing keeps up with rated ingest at steady state.
- **Measurement/Monitoring:** Queue depth/latency/loss SLIs; backpressure activation metrics.
- **Alert Threshold:** Queue depth > threshold or growing unbounded → page; any loss on no-loss flow → Sev2.
- **Acceptance Criteria/Verification:** Overload tests show bounded queues, backpressure, no loss on critical flows.
- **Failure Impact:** Backlog, loss, or exhaustion. **Risk Level:** High. **Priority:** P1. **Dependencies:** Sections 13, 30.

---

## 39. Disaster Recovery

#### NFR-DR-001 — RTO/RPO by Tier and Data Class

- **Description:** The platform must recover from disasters (region loss, major failure) within defined recovery-time and recovery-point objectives, differentiated by data class.
- **Target Metric:**
  - **RPO (data loss):** **Audit and accounting data RPO = 0** (no loss tolerated — replicated synchronously or equivalently durable). Configuration/state RPO ≤ **1 min** (Enterprise/Regulated) / ≤ 5 min (Business). Transient/in-flight request data may be lost on hard failure but must fail safe (no silent incorrect delivery).
  - **RTO (recovery time):** Regional failover RTO ≤ **5 min** (Regulated/Mission-Critical), ≤ **15 min** (Enterprise), ≤ **1 h** (Business), ≤ **4 h** (Developer).
- **Business/Engineering Justification:** Audit RPO=0 is required for compliance integrity (Section 21); RTO tiers reflect the cost/complexity trade-off of multi-region continuity (Section 42).
- **Measurement/Monitoring:** DR drill measurements of actual RTO/RPO; replication lag monitoring (for RPO).
- **Alert Threshold:** Replication lag threatening RPO → page; DR readiness degraded → ticket.
- **Acceptance Criteria/Verification:** **Quarterly DR drills/game days** meet RTO/RPO targets; documented DR runbooks.
- **Failure Impact:** Extended outage or data loss. **Risk Level:** Critical. **Priority:** P0. **Dependencies:** Sections 40, 41, 42.

---

## 40. Backup Strategy

#### NFR-BAK-001 — Backup Coverage, Integrity, and Restore Verification

- **Description:** All durable, recovery-critical data must be backed up, encrypted, integrity-verified, and regularly test-restored.
- **Target Metric:** 100% of recovery-critical data classes backed up per schedule aligned to RPO (Section 39); backups encrypted (Section 26); **restore tested at least quarterly** with verified integrity; backup retention per policy/regulation; immutable/tamper-evident backups for audit data.
- **Measurement/Monitoring:** Backup success/coverage; restore-test results; backup integrity checks.
- **Alert Threshold:** Backup failure or missed schedule → ticket/page per criticality.
- **Acceptance Criteria/Verification:** Successful, integrity-verified test restores each quarter.
- **Failure Impact:** Unrecoverable data loss. **Risk Level:** Critical. **Priority:** P0. **Dependencies:** Sections 26, 39.

---

## 41. High Availability

#### NFR-HA-001 — Redundancy and No Single Point of Failure

- **Description:** The request and control paths must have no single point of failure and must tolerate component/zone loss without breaching availability SLOs.
- **Target Metric:** **No single point of failure** on the critical path; tolerate loss of any single node without impact and any single zone within SLO (Enterprise/Regulated: zone-redundant, N+1 minimum); health-checked, automated recovery of failed components; failover within capacity headroom (Section 10).
- **Business/Engineering Justification:** HA is the foundation of availability targets (Section 5); NP+1 zone redundancy is the accepted bar for four-nines infrastructure.
- **Measurement/Monitoring:** Redundancy posture checks; component/zone failure recovery metrics.
- **Alert Threshold:** Loss of redundancy (running without N+1) → page.
- **Acceptance Criteria/Verification:** Zone/node-failure game days hold SLOs (Section 53).
- **Failure Impact:** Outage on single failure. **Risk Level:** Critical. **Priority:** P0. **Dependencies:** Sections 5, 10, 42.

---

## 42. Multi-Region Strategy

#### NFR-MR-001 — Multi-Region Continuity and Residency

- **Description:** For higher tiers and residency-constrained customers, the platform must operate across regions to provide continuity and to enforce data residency.
- **Target Metric:** Enterprise/Regulated tiers deployable multi-region with regional failover meeting Section 39 RTO/RPO; **100%** residency-constrained data confined to permitted regions (including under failover, Section 16); regional isolation such that one region's failure does not cascade; consistent policy/governance across regions.
- **Business/Engineering Justification:** Multi-region enables both continuity (DR) and residency compliance (`02` BR-023); residency confinement is an invariant.
- **Measurement/Monitoring:** Regional health; cross-region residency-conformance (target 100%); failover drills.
- **Alert Threshold:** Any residency-region violation → Sev1; regional cascade risk → page.
- **Acceptance Criteria/Verification:** Multi-region failover drills; residency-confinement tests including failover.
- **Failure Impact:** Continuity loss or residency breach. **Risk Level:** Critical. **Priority:** P0 (for tiers requiring it). **Dependencies:** Sections 16, 25, 27, 39.

---

## 43. Upgrade Strategy

#### NFR-UPG-001 — Zero/Minimal-Downtime, Reversible Upgrades

- **Description:** Platform upgrades must not breach availability SLOs and must be reversible (`02` BR-036, UPG-*).
- **Target Metric:** **Maximum deployment downtime = 0** for the request path at Enterprise/Regulated tiers (rolling/non-disruptive upgrades); progressive rollout with automated health checks and **automated rollback** on regression within ≤ **5 min**; upgrades preserve version compatibility (Section 44); customer-controllable upgrade timing where applicable.
- **Business/Engineering Justification:** Downtime-free upgrades are required for critical infrastructure; reversibility limits blast radius.
- **Measurement/Monitoring:** Upgrade downtime measurement; rollout health; rollback time.
- **Alert Threshold:** Any request-path downtime during upgrade (Enterprise/Regulated) → incident; failed rollback → Sev1.
- **Acceptance Criteria/Verification:** Upgrade drills demonstrate zero-downtime and successful automated rollback.
- **Failure Impact:** Upgrade-induced outage. **Risk Level:** High. **Priority:** P0. **Dependencies:** Sections 44, 47.

---

## 44. Version Compatibility

#### NFR-VER-001 — Backward Compatibility and Deprecation Discipline

- **Description:** The platform, interfaces, and SDKs must maintain backward compatibility within a major version and manage deprecation predictably (`01` PRB-022, `02` BR-007).
- **Target Metric:** No breaking change within a major version; **deprecation window ≥ 12 months** (Enterprise) / ≥ 6 months (Business) with advance notice; multiple concurrent supported versions with published support windows; compatibility verified by test suites (Section 50).
- **Measurement/Monitoring:** Compatibility test results; deprecation tracking; version-usage telemetry.
- **Alert Threshold:** Undeclared breaking change → release block/incident.
- **Acceptance Criteria/Verification:** Cross-version compatibility tests pass; deprecation notices honored.
- **Failure Impact:** Customer breakage; churn. **Risk Level:** High. **Priority:** P1. **Dependencies:** Sections 28, 29, 43.

---

## 45. Configuration Management

#### NFR-CFG-001 — Safe, Versioned, Auditable Configuration

- **Description:** Configuration must be validated, versioned, auditable, reversible, and safe-by-default (`02` BR-027, BRULE-3).
- **Target Metric:** 100% of configuration changes validated before apply; changes versioned, attributable, and reversible; **secure/compliant defaults** for 100% of security-relevant settings; misconfiguration prevented by validation (target: zero outages from invalid config reaching production); configuration drift detected.
- **Measurement/Monitoring:** Config-validation coverage; drift detection; change audit.
- **Alert Threshold:** Invalid config blocked (expected); drift detected → ticket.
- **Acceptance Criteria/Verification:** Config-validation tests; drift-detection tests; secure-default audit.
- **Failure Impact:** Misconfiguration outage/exposure (`01` PRB-030). **Risk Level:** High. **Priority:** P1. **Dependencies:** Sections 18, 46.

---

## 46. Operational Requirements

#### NFR-OPS-001 — Operability and Runbook Coverage

- **Description:** The platform must be operable with predictable, safe operations and complete runbook coverage for known failure modes.
- **Target Metric:** 100% of paging alerts have runbooks; common operational tasks are safe, reversible, and documented; operational health fully visible (Section 48); operations achievable within staffing model for the tier.
- **Measurement/Monitoring:** Runbook-coverage; operational-task success; toil metrics.
- **Alert Threshold:** Paging alert without runbook → gap ticket.
- **Acceptance Criteria/Verification:** Game days exercise runbooks; operability review.
- **Failure Impact:** Slow/unsafe operations, higher MTTR. **Risk Level:** Moderate. **Priority:** P1. **Dependencies:** Sections 34, 48, 58.

---

## 47. Deployment Requirements

#### NFR-DEP-001 — Repeatable, Safe, Constraint-Compatible Deployment

- **Description:** Deployments must be repeatable, automated, safe, and compatible with the deployment models and constraints of target customers (`02` BR-031, DEP-*).
- **Target Metric:** Fully automated, repeatable deployments; progressive rollout with health-gated promotion and automated rollback (Section 43); support for the required deployment models per tier; deployment does not breach availability SLOs; deployment success rate ≥ **99%** with safe failure handling.
- **Measurement/Monitoring:** Deployment success/rollback metrics; deployment-induced SLO impact.
- **Alert Threshold:** Deployment failure/rollback → tracked; SLO impact during deploy → incident.
- **Acceptance Criteria/Verification:** Deployment drills across supported models.
- **Failure Impact:** Deployment-induced incidents. **Risk Level:** Moderate. **Priority:** P1. **Dependencies:** Sections 43, 45.

---

## 48. Monitoring Requirements

#### NFR-MON-001 — Comprehensive Health and SLI Monitoring

- **Description:** All critical components, dependencies (including providers), and SLIs must be monitored continuously with appropriate granularity.
- **Target Metric:** 100% of critical components and SLIs monitored; provider health monitored for routing/failover (Section 16); synthetic monitoring from multiple locations; monitoring freshness ≤ 10 s operational; monitoring itself is redundant (no single blind spot).
- **Measurement/Monitoring:** Monitoring-coverage audit; synthetic-probe coverage.
- **Alert Threshold:** Monitoring gap or monitoring-system failure → page.
- **Acceptance Criteria/Verification:** Coverage audit = 100%; synthetic coverage verified.
- **Failure Impact:** Undetected degradation. **Risk Level:** High. **Priority:** P0. **Dependencies:** Sections 30–34, 42.

---

## 49. Maintenance Windows

#### NFR-MW-001 — Minimal, Non-Disruptive Maintenance

- **Description:** Routine maintenance must be non-disruptive; where windows are unavoidable they must be bounded, announced, and tier-appropriate.
- **Target Metric:** Routine maintenance causes **zero** request-path downtime (Enterprise/Regulated) via non-disruptive methods (Section 43); any unavoidable window announced ≥ **defined notice period** (e.g., ≥ 7 days for planned, tier-dependent) and bounded in duration; emergency maintenance follows expedited, recorded process.
- **Measurement/Monitoring:** Maintenance impact tracking; notice-compliance.
- **Alert Threshold:** Maintenance exceeding window or causing unplanned impact → incident.
- **Acceptance Criteria/Verification:** Maintenance history shows adherence.
- **Failure Impact:** Unexpected downtime, trust impact. **Risk Level:** Moderate. **Priority:** P1. **Dependencies:** Sections 43, 25 (SLA).

---

## 50. Testing Requirements

#### NFR-TEST-001 — Quality Gates and Coverage

- **Description:** All changes must pass defined quality gates before production; NFRs must be continuously verified by automated tests.
- **Target Metric:** Automated verification exists for every P0/P1 NFR (each has a test that fails if the requirement is violated); correctness-critical paths (isolation, output integrity, tool-call integrity, auth/authz) have dedicated test suites including negative and adversarial cases; no P0/P1 NFR ships without passing verification; regression suites protect against reintroduction.
- **Business/Engineering Justification:** Requirements unverified by tests are unenforced (EP-2, EP-8).
- **Measurement/Monitoring:** NFR-to-test coverage matrix; gate pass rates.
- **Alert Threshold:** NFR without a verifying test → gap ticket blocking related work.
- **Acceptance Criteria/Verification:** Coverage matrix shows every P0/P1 NFR mapped to ≥ 1 verifying test.
- **Failure Impact:** Unverified requirements regress silently. **Risk Level:** High. **Priority:** P0. **Dependencies:** Sections 51–55.

---

## 51. Load Testing Standards

#### NFR-LT-001 — Representative Load Verification

- **Description:** The platform must be load-tested against representative and peak workloads to verify latency, throughput, scaling, and stability targets.
- **Target Metric:** Load tests cover rated sustained load and rated peak surge (Section 12); representative workload mix (non-streaming, streaming, tool-calling, structured); tests verify NFR-PERF, NFR-TP, NFR-SCALE, NFR-CONC targets; run pre-release and periodically.
- **Measurement/Verification:** Load-test reports vs targets; sign-off required for release affecting the request path.
- **Failure Impact:** Unproven performance at scale. **Risk Level:** High. **Priority:** P0. **Dependencies:** Sections 7, 8, 12, 13.

---

## 52. Stress Testing Standards

#### NFR-ST-001 — Beyond-Capacity Behavior

- **Description:** The platform must be stress-tested beyond rated capacity to verify graceful degradation, shedding, and no data corruption under overload (EP-10).
- **Target Metric:** At 1.5×–2× rated load, the platform sheds low-priority load gracefully, preserves correctness invariants (NFR-REL-002), surfaces errors explicitly (no silent corruption), and recovers cleanly when load subsides; no cascading failure.
- **Measurement/Verification:** Stress-test reports; corruption/isolation checks under overload; recovery verification.
- **Failure Impact:** Catastrophic failure under overload. **Risk Level:** High. **Priority:** P0. **Dependencies:** Sections 9, 13, 38.

---

## 53. Chaos Engineering Requirements

#### NFR-CHAOS-001 — Continuous Resilience Verification

- **Description:** Resilience to component, zone, region, and provider failures must be verified by regular chaos experiments and game days (per Netflix/SRE practice).
- **Target Metric:** Regular (≥ quarterly) game days covering: node loss, zone loss, region loss (Section 42), provider degradation/failure (Section 16), dependency failure; each experiment defines steady-state hypotheses and verifies SLOs hold or degrade gracefully; findings tracked to resolution.
- **Measurement/Verification:** Chaos experiment results; SLO adherence during experiments; action-item closure.
- **Failure Impact:** Untested resilience fails in real incidents. **Risk Level:** High. **Priority:** P1. **Dependencies:** Sections 16, 39, 41, 42.

---

## 54. Fault Injection Requirements

#### NFR-FI-001 — Correctness-Fault Injection

- **Description:** Correctness handling (malformed output, invalid JSON, corrupted/partial streams, corrupted tool calls, mid-stream failures) must be verified by systematic fault injection.
- **Target Metric:** Fault-injection suite covers: malformed/invalid structured output, mid-stream truncation/corruption, corrupted/partial tool calls, provider errors and timeouts, slow providers; verifies detection (NFR-SO-001), integrity (NFR-STRM-001, NFR-SO-003), and explicit surfacing (NFR-REL-003) at ≥ target rates; run continuously in CI and periodically in staging.
- **Measurement/Verification:** Fault-injection pass rates vs correctness targets.
- **Failure Impact:** Correctness handling unverified — the product's core value. **Risk Level:** Critical. **Priority:** P0. **Dependencies:** Sections 14, 15, 16.

---

## 55. Security Testing Requirements

#### NFR-SECT-001 — Continuous Security Verification

- **Description:** Security must be verified continuously via scanning, testing, and adversarial exercises.
- **Target Metric:** Automated dependency/vulnerability scanning in CI; regular **penetration testing** (≥ annually and on major change) by qualified parties; **adversarial/red-team** testing of content-trust/injection controls (NFR-SEC-003); auth/authz negative testing (Sections 22–23); secret-leak scanning (Section 24); findings remediated per NFR-SEC-001 timelines.
- **Measurement/Verification:** Scan/test coverage and results; pen-test reports; finding-remediation SLA adherence.
- **Failure Impact:** Undetected exploitable weakness at the boundary. **Risk Level:** Critical. **Priority:** P0. **Dependencies:** Sections 18–26.

---

## 56. Accessibility Requirements (Admin UI)

#### NFR-A11Y-001 — Administrative Interface Accessibility

- **Description:** Any administrative/operator interface must meet recognized accessibility standards.
- **Target Metric:** Conformance to **WCAG 2.1/2.2 Level AA** for administrative interfaces; keyboard navigability; screen-reader compatibility; sufficient contrast; accessibility regressions blocked.
- **Business/Engineering Justification:** WCAG AA is the accepted enterprise/public-sector accessibility bar (often legally required for government customers).
- **Measurement/Verification:** Automated + manual accessibility audits; conformance report.
- **Failure Impact:** Excludes users; fails public-sector requirements. **Risk Level:** Moderate. **Priority:** P2 (P1 for government-facing tiers). **Dependencies:** Section 57.

---

## 57. Internationalization Requirements

#### NFR-I18N-001 — Internationalization and Localization Readiness

- **Description:** Operator interfaces and customer-facing text/data handling must support internationalization; content handling must be encoding-safe and locale-aware where relevant.
- **Target Metric:** Full support for universal character encoding (e.g., UTF-8) end to end with no corruption; operator interfaces localizable; locale-aware formatting where applicable; time handling in a consistent, unambiguous standard (e.g., UTC internally) with correct localization at presentation.
- **Measurement/Verification:** Encoding-integrity tests (multilingual content round-trips without corruption); localization coverage.
- **Failure Impact:** Corrupted content, excluded markets. **Risk Level:** Moderate. **Priority:** P2. **Dependencies:** Section 56.

---

## 58. Supportability Requirements

#### NFR-SUP-001 — Diagnosability and Support Enablement

- **Description:** The platform must be diagnosable and support enterprise-grade support commitments (`02` Section 26, SUP-*).
- **Target Metric:** Sufficient telemetry/traces to diagnose issues without reproduction in most cases; support tooling to inspect (governed, privacy-respecting) request records; **MTTA** and **MTTR** targets aligned to tier (e.g., Sev1 MTTA ≤ 15 min, target **MTTR**: Sev1 ≤ **4 h**, Sev2 ≤ **1 business day** *(calibration targets)*); customer-impacting incident communication within defined windows.
- **Measurement/Monitoring:** MTTA/MTTR tracking; diagnosability (share of incidents resolved without reproduction).
- **Alert Threshold:** MTTR trending above target → process review.
- **Acceptance Criteria/Verification:** Incident post-mortems; support-metric reporting.
- **Failure Impact:** Slow resolution, dissatisfaction. **Risk Level:** Moderate. **Priority:** P1. **Dependencies:** Sections 30–34, 46.

---

## 59. Operational KPIs

Operational KPIs track how well the platform is run. Targets are tiered where relevant.

| KPI | Definition | Target |
|---|---|---|
| MTTD | Mean time to detect SLO-threatening issues | ≤ 5 min |
| MTTA | Mean time to acknowledge Sev1 | ≤ 15 min |
| MTTR | Mean time to resolve (Sev1 / Sev2) | ≤ 4 h / ≤ 1 business day *(calibration)* |
| Change failure rate | % of changes causing incidents/rollback | ≤ 5% *(calibration)* |
| Deployment frequency | Cadence of safe deployments | High, with zero-downtime (Section 43) |
| Failed-provider-call handling | % provider failures correctly handled (surfaced/failed-over) | 100% |
| Alert precision | % pages that are actionable | ≥ 90% *(calibration)* |
| Toil ratio | % operator time on manual repetitive work | Trending down; SRE-style cap |

**NFR-OKPI-001:** These KPIs are tracked, reviewed regularly, and drive operational improvement. **Priority:** P1.

---

## 60. Engineering KPIs

| KPI | Definition | Target |
|---|---|---|
| NFR test coverage | % of P0/P1 NFRs with verifying tests | 100% |
| Correctness escape rate | Structured-output + tool-call escape (Sections 15) | Within NFR-SO targets |
| Reliability regression rate | Regressions in reliability SLOs per release | Trending to zero |
| Backward-compat adherence | Breaking changes without version increment | Zero |
| Security finding age | Findings past remediation SLA | Zero |
| DR/chaos readiness | Passing DR drills + chaos game days | 100% scheduled, passing |
| Accounting accuracy | Token/cost error vs ground truth | Within NFR-COST-001 |

**NFR-EKPI-001:** Tracked and reviewed; gate release-quality decisions. **Priority:** P1.

---

## 61. SLOs (Service Level Objectives)

SLOs are the internal reliability objectives that govern engineering decisions; they are set tighter than external SLA commitments to provide a safety margin. Primary SLOs (tiered per Section 64):

| SLO | Objective (Enterprise/Regulated) | Business | Developer | Backing SLI (Section 62) |
|---|---|---|---|---|
| Gateway availability | 99.99% | 99.95% | 99.9% | Availability SLI |
| Effective availability (w/ failover) | ≥ 99.99% and > best single provider | ≥ 99.95% | best-effort | End-to-end SLI |
| Gateway error rate | ≤ 0.01% | ≤ 0.05% | ≤ 0.1% | Error-rate SLI |
| Added latency (standard path) | P99 ≤ 50 ms | P99 ≤ 75 ms | P99 ≤ 100 ms | Overhead-latency SLI |
| Streaming first-token overhead | P95 ≤ 15 ms | P95 ≤ 25 ms | P95 ≤ 40 ms | Streaming SLI |
| Structured-output escape | ≤ 0.01% | ≤ 0.05% | ≤ 0.1% | Conformance/escape SLI |
| Tool-call corrupted escape | ≤ 0.01% | ≤ 0.05% | — | Tool-call integrity SLI |
| Streaming silent corruption | ≤ 0.05% | ≤ 0.1% | — | Stream-integrity SLI |
| Failover success (eligible) | ≥ 99% within 2 s | ≥ 99% | best-effort | Failover SLI |
| Accounting accuracy | error ≤ 0.5% | ≤ 1% | ≤ 1% | Accounting SLI |
| Audit coverage / record loss | 100% / 0 | 100% / 0 | 100% / 0 | Audit-coverage SLI |
| Isolation violations | 0 (invariant) | 0 | 0 | Isolation SLI |

**NFR-SLO-001:** Every SLO has a defined SLI, error budget (Section 63), measurement window, and owner. SLOs are reviewed quarterly and adjusted with recorded rationale (never weakened silently). **Priority:** P0.

*Values marked as calibration targets elsewhere apply here equally; latency SLOs will be finalized after load-test calibration.*

---

## 62. SLIs (Service Level Indicators)

Each SLO is backed by a precisely defined SLI (the measured signal). SLIs follow the SRE convention of *good events / valid events*.

- **Availability SLI:** successful gateway responses / valid requests (attribution-classified per EP-3).
- **End-to-end SLI:** successful end-to-end outcomes / valid requests (includes provider outcomes; excludes client faults).
- **Error-rate SLI:** gateway-attributable failures / valid requests.
- **Overhead-latency SLI:** distribution of gateway-added latency (internal time minus upstream wait), reported P50/P95/P99.
- **Streaming SLI:** first-token overhead distribution; inter-token jitter; streaming completion overhead.
- **Conformance/escape SLI:** detected non-conformances and escapes / structured requests.
- **Tool-call integrity SLI:** corrupted-tool-call escapes / tool-call requests; pre-execution validation coverage.
- **Stream-integrity SLI:** silent-corruption events / streamed requests; surfaced-failure coverage.
- **Failover SLI:** successful eligible failovers within target / failover-eligible failures; residency-safe-failover ratio.
- **Accounting SLI:** |measured − ground-truth| / ground-truth over reconciliation windows.
- **Audit-coverage SLI:** audit records produced / (requests + material decisions); record-loss count.
- **Isolation SLI:** detected isolation violations (target 0).

**NFR-SLI-001:** SLIs must be well-defined, measurable in production (Section 32), and stable across releases; changes to SLI definitions are versioned and recorded. **Priority:** P0.

---

## 63. Error Budgets

Error budgets operationalize SLOs (per Google SRE): the budget is the allowable unreliability (1 − SLO) over the window. They govern the balance between reliability and change velocity.

- **NFR-EB-001 — Budget policy.** Each SLO has an error budget over a rolling window (default 28 days). While budget remains, feature velocity proceeds; when budget is exhausted, change on the affected path is restricted to reliability work until the budget recovers (recorded, enforced policy).
- **Burn-rate alerting (Section 34):** fast-burn (e.g., 2% of budget in 1 h → page) and slow-burn (e.g., 10% in 3 days → ticket), using multi-window multi-burn-rate detection.
- **Invariant SLOs have zero budget:** isolation violations (NFR-REL-002), silent incorrect delivery (NFR-REL-003), audit record loss (NFR-AUD-001), secret/sensitive-data exposure (NFR-SEC-SM-001, NFR-PRIV-001), and residency violations (NFR-MR-001) have **zero error budget** — any occurrence triggers incident response and change freeze on the affected area, not budget accounting. These are safety invariants, not reliability targets.
- **NFR-EB-002 — Budget governance.** Budget policy is owned by reliability leadership; exceptions are recorded. Budgets are not silently increased to avoid freezes.

**Priority:** P0. **Dependencies:** Sections 34, 61, 62.

---

## 64. Service Tiers

Tiers align engineering guarantees with commercial tiers (`02` Section 25) so that customers receive — and pay for — the reliability they need (EP-6). Tiers differ in guarantees, not in correctness/security invariants: **all safety and correctness invariants (isolation, no-silent-delivery, audit integrity, data protection, secure defaults) apply to every tier equally.**

| Attribute | T1 Developer | T2 Business | T3 Enterprise | T4 Regulated / Mission-Critical |
|---|---|---|---|---|
| Target customers | Startups, dev/test | SaaS, mid-market | Large eng orgs, enterprise SaaS | Healthcare, finance, insurance, government |
| Gateway availability SLO | 99.9% | 99.95% | 99.99% | 99.99% + multi-region continuity |
| Added latency P99 (standard) | ≤ 100 ms | ≤ 75 ms | ≤ 50 ms | ≤ 50 ms |
| Multi-region | optional | optional | available | required |
| RTO / RPO (config) | ≤ 4 h / ≤ 5 min | ≤ 1 h / ≤ 5 min | ≤ 15 min / ≤ 1 min | ≤ 5 min / ≤ 1 min |
| Audit RPO | 0 | 0 | 0 | 0 |
| Residency enforcement | basic | available | available | required, guaranteed |
| Customer-managed keys | — | optional | available | available/required |
| Support (Sev1 MTTA) | best-effort | business-hours | 24/7 ≤ 15 min | 24/7 ≤ 15 min |
| Compatibility deprecation window | 6 mo | 6 mo | 12 mo | 12 mo+ |
| Zero-downtime upgrades | best-effort | yes | yes | yes |
| Correctness & security invariants | **all apply** | **all apply** | **all apply** | **all apply** |

**NFR-TIER-001:** Tier guarantees are published, contracted (`02` COMM/LIC), and independently measured. Tiering never weakens invariants. **Priority:** P0.

---

## 65. Risk Analysis

- **RSK-1 — Latency-budget infeasibility.** Aggressive overhead budgets may prove hard to meet with full policy enabled. *Mitigation:* calibration targets, stage decomposition (Section 11), separate heavy-policy budget (NFR-PERF-003), tiered relaxation for lower tiers. *Risk:* Moderate.
- **RSK-2 — Provider dependency dominates end-to-end metrics.** Customers may attribute provider failures to the gateway. *Mitigation:* strict attribution (EP-3), transparent provider-vs-gateway reporting, effective-availability metric (NFR-AV-002). *Risk:* Moderate.
- **RSK-3 — Correctness targets bounded by model behavior.** Post-enforcement conformance depends partly on models (OOS-6). *Mitigation:* separate detection/escape (owned) from post-enforcement success (shared, attributed); transparency (BRULE-9). *Risk:* Moderate.
- **RSK-4 — Cost of high tiers.** Four-nines + multi-region + zero-downtime is expensive. *Mitigation:* tiering (EP-6); customers buy what they need. *Risk:* Moderate.
- **RSK-5 — Invariant absolutism vs availability.** Enforcing zero-budget invariants (freeze on violation) could conflict with availability pressure. *Mitigation:* invariants are safety-critical and take precedence (EP-1, EP-4); this is deliberate. *Risk:* Low (accepted trade-off).
- **RSK-6 — Unverifiable requirements.** Some targets are hard to measure precisely (e.g., escape rate). *Mitigation:* sampling + adversarial injection (Sections 54–55); continuous refinement. *Risk:* Moderate.
- **RSK-7 — Calibration drift.** Calibration targets may need revision after real load data. *Mitigation:* explicit calibration markers; quarterly SLO review (Section 61). *Risk:* Low.
- **RSK-8 — Tier complexity.** Many tiered numbers increase operational and contractual complexity. *Mitigation:* invariants common across tiers; only guarantees vary. *Risk:* Low.

---

## 66. Assumptions

- **AS-1** — Multiple independent providers with sufficient reliability exist to make failover meaningful (supports NFR-AV-002, Section 16).
- **AS-2** — Provider usage/accounting signals are available with sufficient fidelity to reconcile within NFR-COST-001 tolerance.
- **AS-3** — Deployment environments provide multi-zone/region capability for HA/DR at higher tiers (Sections 41–42).
- **AS-4** — Load/soak/chaos testing infrastructure is available to calibrate and verify targets (Sections 50–54).
- **AS-5** — Customers accept honest tiering (different guarantees at different tiers) rather than demanding top-tier guarantees universally.
- **AS-6** — Regulatory control expectations are stable enough to design controls against, with configurability for variance (Section 20).
- **AS-7** — The organization will staff 24/7 operations for tiers requiring it (Section 58).

If any assumption fails, dependent requirements are revisited.

---

## 67. Open Questions

- **OQ-1** — Final calibrated values for latency budgets (Section 11), throughput ratings (Section 12), and elasticity reaction times (Section 9) pending load testing.
- **OQ-2** — Exact escape-rate measurement methodology (sampling rate, adversarial-suite composition) for NFR-SO-001 to make the target robustly verifiable.
- **OQ-3** — Precise MTTR/change-failure/alert-precision targets (Sections 58–59) pending operational baseline.
- **OQ-4** — Which specific compliance frameworks/certifications back NFR-COMP-001 per segment (commercial decision feeding this NFR).
- **OQ-5** — Default retention periods per data class and regime (Sections 19, 21, 31) — regulatory-dependent.
- **OQ-6** — Boundary of "post-enforcement conformance success" (NFR-SO-002) attribution between gateway and model, and how to present it to customers.
- **OQ-7** — Customer-managed-key scope and per-tier requirements (Sections 26, 64).
- **OQ-8** — Multi-region residency granularity required by target government/health customers (Section 42).
- **OQ-9** — Overhead percentage ceilings for cost efficiency (NFR-COST-002) once real cost data exists.

---

## 68. Appendix

**A. Requirement Index (by ID).** NFR-AV-001/002 · NFR-REL-001/002/003 · NFR-PERF-001/002/003 · NFR-SCALE-001/002 · NFR-ELAS-001 · NFR-CAP-001 · NFR-LAT-001 · NFR-TP-001 · NFR-CONC-001 · NFR-STRM-001/002 · NFR-SO-001/002/003 · NFR-FO-001 · NFR-RTY-001 · NFR-SEC-001/002/003/004 · NFR-PRIV-001 · NFR-COMP-001 · NFR-AUD-001 · NFR-AUTH-001 · NFR-AUTHZ-001 · NFR-SEC-SM-001 · NFR-DP-001 · NFR-ENC-001 · NFR-NET-001 · NFR-IF-001/002 · NFR-SDK-001 · NFR-OBS-001 · NFR-LOG-001 · NFR-MET-001 · NFR-TRC-001 · NFR-ALRT-001 · NFR-COST-001/002 · NFR-CACHE-001/002 · NFR-DS-001 · NFR-Q-001 · NFR-DR-001 · NFR-BAK-001 · NFR-HA-001 · NFR-MR-001 · NFR-UPG-001 · NFR-VER-001 · NFR-CFG-001 · NFR-OPS-001 · NFR-DEP-001 · NFR-MON-001 · NFR-MW-001 · NFR-TEST-001 · NFR-LT-001 · NFR-ST-001 · NFR-CHAOS-001 · NFR-FI-001 · NFR-SECT-001 · NFR-A11Y-001 · NFR-I18N-001 · NFR-SUP-001 · NFR-OKPI-001 · NFR-EKPI-001 · NFR-SLO-001 · NFR-SLI-001 · NFR-EB-001/002 · NFR-TIER-001.

**B. Availability reference (downtime per target).**

| Availability | Monthly downtime | Annual downtime |
|---|---|---|
| 99.9% | ≤ 43.2 min | ≤ 8.76 h |
| 99.95% | ≤ 21.9 min | ≤ 4.38 h |
| 99.99% | ≤ 4.38 min | ≤ 52.6 min |
| 99.999% | ≤ 26 s | ≤ 5.26 min |

*Note: five-nines is deliberately **not** set as a target for gateway availability; it is impractical and unnecessary given that end-to-end reliability is bounded by providers and delivered via failover (NFR-AV-002). Setting it would be an arbitrary, unachievable number.*

**C. Attribution model (EP-3).** Every outcome is classified as **gateway-attributable**, **provider-attributable**, or **client-attributable**. Gateway metrics count only gateway-attributable failures; end-to-end metrics include provider outcomes; client faults are excluded from both. This model is foundational to every reliability SLI and is itself a correctness requirement (misattribution is a defect).

**D. Invariants (zero error budget).** Cross-tenant/cross-request isolation (NFR-REL-002); no silent incorrect delivery (NFR-REL-003); audit record integrity/coverage and zero loss (NFR-AUD-001); no secret exposure (NFR-SEC-SM-001); no policy-violating sensitive-data exposure (NFR-PRIV-001); residency confinement (NFR-MR-001); no authz bypass (NFR-AUTHZ-001). These apply at **all tiers** and never trade off against availability, latency, or velocity.

**E. Relationship to other documents.** This document derives its *what* from `02-Business-Requirements.md` and its *why* from `00`/`01`. It constrains but does not make architectural choices; `04-System-Architecture.md` and later documents must demonstrate conformance to every applicable requirement here. Traceability: NFRs cite the BRs and PRBs they serve throughout.

**F. Maintenance.** Living document. Calibration targets are finalized after load/chaos testing and recorded with rationale. SLOs are reviewed quarterly and never weakened silently. New NFRs must carry the full field set and be added to the index (A), and where they define a reliability objective, to the SLO/SLI/error-budget framework (Sections 61–63). Invariants (D) are stable and may not be downgraded to targets.

---

*End of document — 03-Non-Functional-Requirements.md*
