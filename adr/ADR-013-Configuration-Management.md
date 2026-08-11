# ADR-013 — Configuration Management Strategy

**ADR ID:** AD-013 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway

- **Status:** Accepted · **Date:** 2026-07-20 · **Decision Makers:** Architecture Council
- **Context:** Misconfiguration is a leading cause of outages/exposure (`01` PRB-030); the platform must be safe-by-default and reproducible.
- **Problem Statement:** How is configuration authored, validated, versioned, distributed, and rolled back safely?
- **Decision:** **Declarative, validated, immutable, versioned configuration** authored centrally, distributed as snapshots (AD-022), with secure/compliant defaults, drift detection, and reversible rollback by re-pinning a prior snapshot.
- **Decision Drivers:** Safety, reproducibility, reversibility, drift control (`NFR-CFG-001`, immutable infra).
- **Alternatives Considered:** (a) Mutable, imperative config — rejected: drift, irreproducibility, risky rollback. (b) Ad hoc per-service config — rejected: inconsistency, no central governance.
- **Pros:** Safe, reproducible, reversible, auditable; drift detected.
- **Cons:** Snapshot lifecycle to manage; propagation delay (bounded).
- **Trade-offs:** Immutability/versioning overhead for safety and reproducibility.
- **Consequences:** Underpins AD-022, safe upgrades (AD-015).
- **Business Impact:** Fewer incidents; safer operations (`02` BR-027). **Engineering Impact:** Declarative config discipline. **Security Impact:** Secure defaults (BRULE-3). **Performance Impact:** Neutral. **Operational Impact:** Reversible, drift-detected operations.
- **Risks:** Propagation delay for urgent changes. **Mitigations:** high-priority path for security/invariant changes (AD-022).
- **Affected Components:** Config/Policy service, data-plane config cache. **Related BR:** BR-027. **Related NFR:** NFR-CFG-001. **Related PRB:** PRB-030.
- **Related ADRs:** AD-022, AD-015, AD-019. **Review Criteria:** Revisit propagation SLA. **Future Revisions:** Propagation tuning.
