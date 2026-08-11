# ADR-004 — Plugin Architecture (Sandboxed, Additive-Only)

**ADR ID:** AD-004 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway

- **Status:** Accepted · **Date:** 2026-07-20 · **Decision Makers:** Architecture Council
- **Context:** Extensibility broadens use cases and grows an ecosystem (`02` BR-029), but must never weaken core guarantees (BRULE-2).
- **Problem Statement:** How do we allow extension without permitting bypass of reliability/security/governance?
- **Decision:** A **sandboxed Plugin Runtime** executes vetted plugins at **defined, additive-only extension points** (`04 §53`). Plugins add behavior (extra classification, routing signals, validation, exports, attribution) but cannot bypass authentication, authorization, governance, correctness, accounting, isolation, or audit, and cannot cross tenant boundaries.
- **Decision Drivers:** Open/closed extensibility; guarantee preservation; ecosystem growth without lock-in.
- **Alternatives Considered:** (a) Permissive plugins (full access) — rejected: can weaken guarantees. (b) No plugins (core-only) — rejected: limits use cases and ecosystem.
- **Pros:** Extensible within a safety envelope; ecosystem; guarantees preserved.
- **Cons:** Sandboxing overhead; constrained plugin power; vetting burden.
- **Trade-offs:** Chooses safety over flexibility (EP-1) — plugins extend within, never outside, the envelope.
- **Consequences:** Extension points are additive-only; plugin overhead budgeted (`NFR-PERF-003`).
- **Business Impact:** Broader fit, stickiness via value not lock-in (`02` BR-029). **Engineering Impact:** Sandbox + registry to build/operate. **Security Impact:** Plugins are least-privilege, governed, observable. **Performance Impact:** Budgeted plugin overhead. **Operational Impact:** Plugin vetting/versioning lifecycle.
- **Risks:** Plugin instability or attempted bypass. **Mitigations:** sandbox, additive-only, vetting, resource limits.
- **Affected Components:** Plugin Runtime, Registry, pipeline extension points. **Related BR:** BR-029, BR-030. **Related NFR:** NFR-PERF-003. **Related PRB:** PRB-025, PRB-026.
- **Related ADRs:** AD-018, AD-002. **Review Criteria:** Revisit capability model granularity (`04` AOQ-5). **Future Revisions:** Extension-point additions (additive).
