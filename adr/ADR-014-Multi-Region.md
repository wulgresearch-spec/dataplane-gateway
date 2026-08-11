# ADR-014 — Multi-Region Deployment Strategy

**ADR ID:** AD-014 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway

- **Status:** Accepted · **Date:** 2026-07-20 · **Decision Makers:** Architecture Council
- **Context:** Higher tiers and regulated customers need regional continuity and data-residency enforcement (`03` NFR-MR/DR-001, `02` BR-023/031).
- **Problem Statement:** How do we provide continuity and residency across regions without violating residency or creating cross-region fragility?
- **Decision:** **Regionally-isolated operation with globally-consistent policy**: each region operates self-sufficiently; policy/governance is globally consistent (single logical control plane, regionally enforced); residency-constrained data is confined to permitted regions, **including during failover**; failover eligibility is filtered by residency before routing.
- **Decision Drivers:** Continuity + residency compliance + consistency + no cross-region cascade.
- **Alternatives Considered:** (a) Single-region — rejected: no continuity, can't meet residency variety. (b) Global active-active shared state — rejected: complicates residency and consistency, risks residency violation. (c) Active-passive only — acceptable per regime; chosen topology varies by residency need (`04` AOQ-6).
- **Pros:** Continuity; residency guaranteed; no cross-region cascade; consistent policy.
- **Cons:** Regional operational footprint; replication design per data class.
- **Trade-offs:** Regional isolation (less global consistency of state) for residency and blast-radius control (EP-1).
- **Consequences:** Residency filtered before routing/failover (`04 §20/35`); audit replicated within permitted regions (RPO=0).
- **Business Impact:** Regulated/government adoption (`02` BR-023). **Engineering Impact:** Regional topology, replication. **Security Impact:** Regional isolation limits blast radius. **Performance Impact:** Regional handling reduces latency. **Operational Impact:** Multi-region operations and DR drills.
- **Risks:** Residency violation on failover. **Mitigations:** residency-first eligibility, invariant detectors (`NFR-MR-001`).
- **Affected Components:** Router, Reliability Engine, data tier, DR. **Related BR:** BR-023, BR-031, BR-003. **Related NFR:** NFR-MR-001, NFR-DR-001, NFR-BAK-001. **Related PRB:** PRB-017, PRB-008.
- **Related ADRs:** AD-007, AD-017, AD-010. **Review Criteria:** Topology per regime (`04` AOQ-6). **Future Revisions:** Region/topology additions.
