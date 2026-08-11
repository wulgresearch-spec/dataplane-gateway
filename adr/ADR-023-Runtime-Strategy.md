# ADR-023 — Runtime Strategy (Java 21 + Spring Boot)

**ADR ID:** AD-023 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway
**Decision Makers:** Architecture Council
**Origin:** promotes the decision recorded in `09-Technology-Decisions.md` TD-013 (TDOQ-1 resolved).

---

## 1. Status
**Accepted.** Immutable once Accepted; changed only by a superseding ADR. This is the permanent runtime decision for the platform.

## 2. Context
The platform comprises a stateless, latency-sensitive **Data Plane** (a co-located request pipeline, AD-020/AD-006) and a decomposed, management-oriented **Control Plane** of thirteen services (`06`). It targets regulated enterprise segments — healthcare, finance, insurance, government (`02`) — and must be built, staffed, operated, and evolved over a **10-year horizon**. The technology *classes* were fixed by earlier ADRs (AD-008/009/010/011) behind ports (AD-002), but **no ADR previously fixed the implementation runtime/language**. `09` TD-013 evaluated the options from first principles; this ADR records the outcome as a permanent decision.

A decisive contextual fact: the gateway's **own** added-latency budget is small (≤ 50 ms P99 added overhead, `06 §11`) relative to LLM **provider** latency (hundreds of ms to seconds). The end-to-end latency is therefore **provider-dominated, not runtime-GC-dominated** — which reframes the runtime choice away from "lowest possible GC pause" toward enterprise maturity, development velocity, ecosystem, security, and long-term maintainability.

## 3. Problem Statement
Which single primary runtime/language best balances **enterprise maturity, development velocity, concurrency/streaming, observability, security ecosystem, Kubernetes fit, hiring availability, and 10-year maintainability** against a latency budget that is not GC-bound — while preserving a sanctioned escape hatch for the rare case where a specific hot-path component needs more?

## 4. Decision
- **Primary runtime: Java 21.**
- **Framework: Spring Boot.**
- **Virtual Threads (Project Loom)** are the **default concurrency model** where appropriate — enabling massive, simple, blocking-style concurrency for streaming and high-connection-count workloads without reactive complexity.
- **Garbage collector:** Generational ZGC (sub-millisecond pauses) is the default posture for meeting the latency budget. **GraalVM Native Image is OPTIONAL** for specific deployment scenarios (fast startup, reduced footprint) and is **NOT the default**.
- **Rust** may be introduced **only** for an **isolated hot-path service/component** (e.g., token/stream reassembly, a crypto path) **and only if production profiling demonstrates that Java cannot satisfy a specific latency SLO** for that component. Rust is never speculative, never a default, never a second general-purpose language.
- **Go is NOT adopted** for the initial product.
- **No dual-language architecture** at launch. The platform is **single-runtime (Java 21 + Spring Boot)** until (and unless) the Rust escape hatch is triggered by measured evidence.

## 5. Alternatives Considered
- **A — Java 21 + Spring Boot (SELECTED):** unmatched enterprise maturity in the target regulated segments; highest development velocity across the broad control-plane surface; Virtual Threads for streaming concurrency; Generational ZGC gives sub-ms pauses (immaterial to a provider-dominated budget); best-in-class OpenTelemetry/security ecosystems; excellent Kubernetes fit; the largest 10-year hiring pool and lowest continuity risk. Trade-off: higher JVM memory footprint and framework-CVE patch discipline (managed).
- **B — Go:** excellent for infrastructure and cloud-native tooling, memory-safe, simple ops; **rejected for the initial product** because the broad enterprise control-plane surface benefits more from Spring's ecosystem, its GC/latency edge is immaterial to the provider-dominated budget, and its enterprise-backend hiring breadth is smaller than Java's.
- **C — Rust:** best raw performance and no GC; memory/thread safety as compile-time guarantees; **rejected as the primary runtime** because the no-GC benefit does not bind against the real latency budget, while development velocity and hiring availability are materially worse for a 10-year, multi-service enterprise platform. **Retained solely as a profiling-gated hot-path optimization.**
- **D — Java + Rust (dual-language):** **rejected** — combines Java's velocity with Rust's hot-path performance but doubles toolchain/hiring/operational cost for a benefit the budget does not require at launch; the two ecosystems share little.
- **E — Go + Rust (dual-language):** **rejected** — both modern, but still two languages/toolchains with Rust's hiring risk, and it forgoes Java's enterprise-ecosystem and hiring advantages; premature complexity for the initial product.

## 6. Decision Drivers
- **Latency budget is provider-dominated, not GC-bound** — sub-ms ZGC pauses meet the ≤ 50 ms P99 added-overhead budget (`06 §11`, `03` NFR-LAT/PERF).
- **Concurrency/streaming** — Virtual Threads scale high-connection streaming simply (`03` NFR-CONC, `07`/streaming).
- **Enterprise maturity & regulated-market fit** (`02` target segments).
- **Development velocity across 14 services** — Spring ecosystem.
- **Observability & security ecosystems** (`03` NFR-OBS/TRC/SEC; AD-011/012).
- **Kubernetes fit** (AD deployment, `03` NFR-SCALE/HA/UPG).
- **Hiring availability & long-term maintainability** over 10 years (`03` NFR-VER; AD-016 reliability-first favors proven over novel — Java is the more proven runtime).
- **Preserve a measured escape hatch** for isolated hot-path needs (module boundaries, `06 §10`).

## 7. Consequences
- **Single-runtime organization:** one toolchain, one hiring profile, one operational model — the simplest, lowest-risk posture; simplifies CI/CD, security scanning, and staffing.
- Control-plane services and the Data-Plane pipeline are all Java 21 + Spring Boot; the Data-Plane hot path uses Virtual Threads + Generational ZGC and (optionally) GraalVM Native Image where footprint/startup matter.
- Correctness/isolation invariants (`03` NFR-REL-002) are enforced by design + testing rather than by a compile-time-borrow-checker; the testing bar (`03 §50–54`) carries that weight.
- The Rust escape hatch remains available per-component behind `06 §10` module boundaries, exercised only on profiled evidence.
- Downstream standards (`10`–`16`) and module docs (`17`–`28`) assume Java 21 + Spring Boot.

## 8. Risks
- **R-1 JVM memory footprint** (vs Go/Rust). *Mitigation:* Generational ZGC tuning; GraalVM Native Image / jlink slim images where footprint matters.
- **R-2 Java/Spring framework CVEs** (e.g., Log4Shell, Spring4Shell). *Mitigation:* strict dependency scanning, SBOM, and rapid patching (`03` NFR-SEC-001/SECT-001); this is patch discipline, not a runtime-reliability risk.
- **R-3 Single-runtime concentration.** *Mitigation:* the `06 §10` module boundaries and AD-002 ports preserve a profiling-gated Rust escape hatch and keep the runtime replaceable per component.
- **R-4 Hot-path tail latency under extreme load.** *Mitigation:* ZGC sub-ms pauses + load/soak testing (`03 §51–52`); if a specific component fails its SLO, the Rust escape hatch is triggered by evidence, not speculation.

## 9. Migration Strategy
- **Greenfield** — no legacy runtime to migrate from.
- **Rust escape-hatch procedure (future, conditional):** (1) profiling in production demonstrates a specific hot-path component cannot meet its latency SLO on Java 21; (2) the component is isolated behind a `06 §10` module/port boundary; (3) a scoped ADR records the exception; (4) the Rust component is introduced for that component only, with contracts unchanged. No platform-wide migration; no dual-language default.
- **GraalVM Native Image** may be adopted per deployment scenario without an ADR (it is an optional JVM packaging choice, not a runtime change).

## 10. Review Criteria
- Revisit **only** if: (a) production profiling shows Java 21 systematically cannot meet the latency SLOs across components (not just one), or (b) a fundamental shift in the JVM/Java ecosystem or hiring market materially changes the trade-off. Absent such evidence, this decision stands.
- A single component needing Rust is **not** grounds to revisit this ADR — it is the sanctioned escape hatch (§9), recorded per-component.
- Consistency with AD-016 (reliability-first) is re-affirmed at each review: prefer proven over novel.

## 11. Related ADRs
- **AD-016** (Reliability-First, meta) — governs; favors proven over novel, supporting Java.
- **AD-006** (Stateless Data Plane) — the runtime runs stateless hot-path units.
- **AD-020** (Co-located Pipeline) — the Data-Plane modular monolith is implemented in this runtime; the Rust escape hatch uses its `06 §10` module boundaries.
- **AD-002** (Hexagonal Ports & Adapters) — keeps the runtime replaceable per component.
- **AD-013** (Configuration Management) — runtime configuration follows the config strategy.
- Technology-class ADRs (AD-008/009/010/011) — this runtime integrates with those selected technologies (via their Java clients/SDKs).

## 12. References to NFRs and Technology Decisions
- **Technology Decision:** `09-Technology-Decisions.md` **TD-013** (this ADR promotes it); related TD-004 (Kafka), TD-001/002/003 (data stores), TD-007 (OpenTelemetry), TD-012 (Kubernetes).
- **NFRs:** `03` NFR-LAT-001 / NFR-PERF-001 (latency budget), NFR-CONC-001 (concurrency/streaming), NFR-REL-002 (isolation — enforced by design + testing), NFR-OBS/TRC-001 (observability), NFR-SEC-001 / NFR-SECT-001 (security & security testing), NFR-SCALE/HA/UPG (Kubernetes fit), NFR-VER-001 (long-term maintainability).
- **Architecture:** `04` (planes), `06 §10–§11` (module boundaries, latency budget), `07`/streaming (concurrency).

---

*This ADR is immutable once Accepted; it is changed only by a superseding ADR.*
