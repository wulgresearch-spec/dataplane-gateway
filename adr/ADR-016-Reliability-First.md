# ADR-016 — Reliability-First Design Principle (Meta-ADR)

**ADR ID:** AD-016 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway

- **Status:** Accepted · **Date:** 2026-07-20 · **Decision Makers:** Architecture Council
- **Context:** The company's identity (`00`) and every prior document place reliability and correctness above all other attributes.
- **Problem Statement:** When quality attributes conflict on the critical path, what wins?
- **Decision:** **Reliability and correctness take precedence** over latency, cost, and feature velocity on the critical path (EP-1). This is a meta-ADR: it is the tie-breaker that governs all other decisions and future ADRs. Safety/correctness invariants are absolute and never traded off.
- **Decision Drivers:** The entire value proposition; trust as the core asset; regulated-customer requirements.
- **Alternatives Considered:** (a) Balanced/no-priority — rejected: ambiguous under conflict, risks eroding the core value. (b) Performance-first — rejected: contradicts the mission.
- **Pros:** Clear tie-breaker; protects the core value; guides all trade-offs.
- **Cons:** May forgo latency/cost/feature wins that would compromise reliability.
- **Trade-offs:** Explicitly accepts slower/costlier where needed for reliability.
- **Consequences:** Governs AD-018 (no bypass), invariants, conservative critical-path choices; every future ADR must conform.
- **Business Impact:** Protects trust and differentiation (`00`, `02` BG-1/6). **Engineering Impact:** Sets the decision hierarchy. **Security Impact:** Reinforces invariant absolutism. **Performance Impact:** Bounds performance optimizations that would risk correctness. **Operational Impact:** Error-budget and invariant discipline (`03 §63`).
- **Risks:** Over-conservatism slowing delivery. **Mitigations:** tiering (lower tiers get honest lower guarantees); error budgets balance velocity where invariants aren't at stake.
- **Affected Components:** All. **Related BR:** all (BG-1). **Related NFR:** all (EP-1, invariants). **Related PRB:** all.
- **Related ADRs:** Governs all. **Review Criteria:** Never weaken; foundational to the company. **Future Revisions:** None to weaken.
