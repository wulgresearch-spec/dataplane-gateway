# ADR-018 — Non-Bypassable Correctness & Governance Pipeline

**ADR ID:** AD-018 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway
**Note:** Corresponds to the `04 §55` summary label **AD-8**.

- **Status:** Accepted · **Date:** 2026-07-20 · **Decision Makers:** Architecture Council
- **Context:** The product's differentiation is *enforced* correctness and governance (`01` root cause B; `02` BRULE-1/2). Optional/advisory checks would allow the exact failures we exist to prevent.
- **Problem Statement:** Should correctness and governance be optional middleware or mandatory pipeline stages?
- **Decision:** Correctness (structured output, streaming, tool-call integrity) and governance (authz, data-handling, residency, accounting, audit) are **mandatory, non-bypassable stages** for every protected request. There is no supported path around them.
- **Decision Drivers:** Enforced guarantees require structural non-bypass (BRULE-2, OOS-8); invariants have zero error budget (`03` App. D).
- **Alternatives Considered:** (a) Optional/advisory checks — rejected: undermines the core value and invariants. (b) Post-hoc verification — rejected: too late to prevent bad delivery.
- **Pros:** Guarantees are real and structural; invariants enforced; audit complete.
- **Cons:** Adds mandatory hot-path work (latency/cost); no "fast unsafe" mode.
- **Trade-offs:** Accepts unavoidable overhead for enforced correctness — consistent with reliability-first (AD-016).
- **Consequences:** Latency budgets must accommodate these stages; extension points are additive-only (AD-004).
- **Business Impact:** The reason regulated customers can adopt (`02` BR-022). **Engineering Impact:** Stages must be efficient and correct. **Security Impact:** No bypass = strong posture. **Performance Impact:** Mandatory overhead (budgeted). **Operational Impact:** Every request produces enforcement records.
- **Risks:** Overhead pressure to add a bypass. **Mitigations:** budget stages; disallow bypass by policy and design (BRULE-2).
- **Affected Components:** Governance, Correctness, Accounting, Audit stages. **Related BR:** BR-001, BR-015, BR-018. **Related NFR:** NFR-SO-001/003, NFR-STRM-001, NFR-AUTHZ-001. **Related PRB:** PRB-001, PRB-005, PRB-016.
- **Related ADRs:** AD-001, AD-016, AD-004, AD-019. **Review Criteria:** Never weaken; revisit only to strengthen. **Future Revisions:** None to weaken.
