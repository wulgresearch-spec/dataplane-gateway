# ADR-011 — Observability Instrumentation: OpenTelemetry

**ADR ID:** AD-011 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway

- **Status:** Accepted · **Date:** 2026-07-20 · **Decision Makers:** Architecture Council
- **Context:** Observability must be complete, correlated, consistent, and exportable to the enterprise's existing SIEM/APM (`04 §39`, `02` BR-010, `03` NFR-OBS/TRC-001), while avoiding vendor lock-in on instrumentation.
- **Problem Statement:** What instrumentation standard captures and exports metrics, logs, and traces?
- **Decision:** Adopt **OpenTelemetry** as the instrumentation and telemetry-export standard for metrics, traces, and logs, so signals are vendor-neutral and exportable to any compliant backend (the gateway's own and the enterprise's).
- **Decision Drivers:** Vendor-neutral, industry-standard, correlated signals; broad backend compatibility; avoids lock-in; matches "good enterprise citizen" (`04 §7`).
- **Alternatives Considered:** (a) Proprietary vendor agents — rejected: lock-in, weaker portability. (b) Bespoke telemetry format — rejected: reinvents a solved problem, poor interoperability. (c) Metrics-only or traces-only standards — rejected: need unified metrics+traces+logs correlation.
- **Pros:** Standard, neutral, correlated, exportable to any compliant backend; ecosystem; future-proof.
- **Cons:** Standard still maturing in areas; instrumentation discipline required for full correlation.
- **Trade-offs:** Slight breadth/maturity variance for neutrality and portability — worth it.
- **Consequences:** Telemetry is portable; enterprise export straightforward; backend is swappable.
- **Business Impact:** Fits enterprise observability estates (`02` BR-010). **Engineering Impact:** Consistent instrumentation across stages. **Security Impact:** Governed export prevents leakage (`NFR-LOG-001`). **Performance Impact:** Sampling keeps overhead within budget (`NFR-TRC-001`). **Operational Impact:** Standard tooling; interchangeable backends.
- **Risks:** Instrumentation gaps break correlation. **Mitigations:** by-construction emission (AD-005/`04 §39`), coverage checks (`NFR-OBS-001`).
- **Affected Components:** All stages (emit), Observability backend, export adapters. **Related BR:** BR-010. **Related NFR:** NFR-OBS-001, NFR-TRC-001, NFR-MET-001. **Related PRB:** PRB-011.
- **Related ADRs:** AD-005, AD-009. **Review Criteria:** Track standard maturity; revisit only if a gap blocks a requirement. **Future Revisions:** Adopt standard advances (additive).
