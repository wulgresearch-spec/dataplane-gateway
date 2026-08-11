# ADR-015 — Versioning & Backward Compatibility

**ADR ID:** AD-015 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway

- **Status:** Accepted · **Date:** 2026-07-20 · **Decision Makers:** Architecture Council
- **Context:** A core value is insulating customers from ecosystem churn (`01` PRB-022, `02` BR-007/036); the product itself must not become a source of instability.
- **Problem Statement:** How do we evolve contracts, SDKs, plugins, and config without breaking customers?
- **Decision:** **Explicit versioning with strict backward compatibility within a major version**; no breaking change without a new major version and a deprecation window (≥ 12 months Enterprise, ≥ 6 months Business); multiple supported versions concurrently; provider/ecosystem changes absorbed in adapters and surfaced via versioned capability.
- **Decision Drivers:** Stability, trust, insulation from churn, predictable evolution.
- **Alternatives Considered:** (a) Move-fast/break-often — rejected: destroys trust, contradicts the value proposition. (b) Never change (freeze) — rejected: cannot evolve. (c) No formal deprecation policy — rejected: unpredictable for customers.
- **Pros:** Stability and trust; predictable migrations; churn insulation.
- **Cons:** Maintaining multiple versions; deprecation discipline cost.
- **Trade-offs:** Slower breaking evolution for stability — aligned with the product's promise.
- **Consequences:** Compatibility test suites gate releases; deprecation tracked.
- **Business Impact:** Trust and low TCO (`02` BR-007/036). **Engineering Impact:** Version maintenance; contract tests. **Security Impact:** Managed upgrades keep security current without forced breakage. **Performance Impact:** Neutral. **Operational Impact:** Multiple supported versions.
- **Risks:** Undeclared breaking change. **Mitigations:** contract/compatibility tests, release gates (`NFR-VER-001`, `NFR-IF-001`).
- **Affected Components:** Contracts, SDKs, plugins, config schemas. **Related BR:** BR-007, BR-036, BR-028. **Related NFR:** NFR-VER-001, NFR-IF-001, NFR-SDK-001. **Related PRB:** PRB-021, PRB-022.
- **Related ADRs:** AD-013, AD-007, AD-002. **Review Criteria:** Revisit deprecation windows per customer feedback. **Future Revisions:** Window tuning (never to weaken below stated minimums without notice).
