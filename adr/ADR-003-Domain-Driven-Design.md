# ADR-003 — Domain-Driven Design & Bounded Contexts

**ADR ID:** AD-003 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway

- **Status:** Accepted · **Date:** 2026-07-20 · **Decision Makers:** Architecture Council
- **Context:** The platform spans many distinct concerns (identity, governance, routing, reliability, correctness, accounting, observability, secrets, extensibility, admin) with different models and vocabularies (`04 §14–15`).
- **Problem Statement:** How do we decompose a broad platform to keep it cohesive and loosely coupled?
- **Decision:** Use **DDD bounded contexts**, each owning its model, vocabulary, and boundary, with explicit context mapping (shared kernel for identity; upstream governance; downstream correctness gate; conformist observability consumer).
- **Decision Drivers:** High cohesion, loose coupling, clear ownership, model clarity.
- **Alternatives Considered:** (a) Technical-layer-only decomposition — rejected: cross-cutting models blur. (b) Single unified model — rejected: conflates distinct domains.
- **Pros:** Clear ownership and evolution; explicit coupling; scalable team structure.
- **Cons:** Requires discipline in boundaries and context mapping.
- **Trade-offs:** Upfront modeling effort for long-term maintainability.
- **Consequences:** Contexts map to components and future modules; guides team topology.
- **Business Impact:** Faster, safer evolution (`02` ADOPT-6). **Engineering Impact:** Ownership clarity. **Security Impact:** Governance/identity as explicit contexts. **Performance Impact:** Neutral. **Operational Impact:** Contexts evolve/deploy per plane.
- **Risks:** Boundary erosion. **Mitigations:** context mapping, contract discipline (AD-002).
- **Affected Components:** All contexts (`04 §15`). **Related BR:** all. **Related NFR:** NFR-VER-001. **Related PRB:** all (organizational lens).
- **Related ADRs:** AD-002, AD-005, AD-054-family (future modules). **Review Criteria:** Revisit context boundaries as domains mature. **Future Revisions:** Context refinements expected as new modules land (additive).
