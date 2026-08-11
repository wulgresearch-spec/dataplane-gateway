# ADR-021 — Multi-Tenancy Isolation Model

**ADR ID:** AD-021 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway
**Note:** Corresponds to the `04 §55` summary label **AD-11**.

- **Status:** Accepted · **Date:** 2026-07-20 · **Decision Makers:** Architecture Council
- **Context:** Tenant isolation is an absolute invariant (`NFR-REL-002`, BRULE-7); efficiency favors sharing infrastructure.
- **Problem Statement:** How do we isolate tenants absolutely while remaining efficient, and serve customers who require stronger isolation?
- **Decision:** **Logical multi-tenancy** with tenant context enforced end-to-end (identity, data, cache, quota, config, telemetry), backed by always-on isolation detectors and defense-in-depth; **optional physical/dedicated isolation** for the Regulated/Mission-Critical tier where required.
- **Decision Drivers:** Absolute isolation invariant, efficiency, tiered assurance (`03 §64`).
- **Alternatives Considered:** (a) Physical isolation only (tenant-per-deployment) — rejected: costly, poor efficiency. (b) Weak logical isolation — rejected: violates the invariant.
- **Pros:** Efficient at scale; absolute isolation via enforcement + detection; strong option for those who need it.
- **Cons:** Requires rigorous, pervasive enforcement; detectors add work.
- **Trade-offs:** Logical efficiency with defense-in-depth vs. blunt physical isolation; physical available at higher cost.
- **Consequences:** Tenant scope threads through every stage/store; noisy-neighbor controls required (`NFR-SCALE-002`).
- **Business Impact:** Multi-tenant SaaS + regulated deals (`02` BR-021). **Engineering Impact:** Pervasive tenant enforcement. **Security Impact:** Cross-tenant leakage = Sev1 invariant. **Performance Impact:** Detector overhead budgeted. **Operational Impact:** Two isolation modes to operate.
- **Risks:** Enforcement gap → leakage. **Mitigations:** defense-in-depth, detectors, isolation test suites (`03` NFR-REL-002).
- **Affected Components:** All; stores; cache. **Related BR:** BR-021, BR-017. **Related NFR:** NFR-REL-002, NFR-SCALE-002. **Related PRB:** PRB-029, PRB-018, PRB-019.
- **Related ADRs:** AD-012, AD-006. **Review Criteria:** Revisit physical-isolation scope per regulated demand (`04` AOQ-4). **Future Revisions:** Isolation-tier options may expand.
