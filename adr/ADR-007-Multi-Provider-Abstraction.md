# ADR-007 — Multi-Provider Abstraction Layer

**ADR ID:** AD-007 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway

- **Status:** Accepted · **Date:** 2026-07-20 · **Decision Makers:** Architecture Council
- **Context:** Providers differ in surface *and semantics* (`01` PRB-007); true provider independence requires absorbing semantic differences, not just format.
- **Problem Statement:** How do we present a stable, neutral contract across heterogeneous, evolving providers?
- **Decision:** A **Provider Abstraction Layer** of adapters (the provider port from AD-002) absorbs each provider's request/streaming/tool/error/accounting semantics and presents a normalized, neutral model to the core. New providers are new adapters; provider changes are absorbed in adapters and surfaced through versioned capability.
- **Decision Drivers:** Provider neutrality, semantic (not just surface) normalization, insulation from churn.
- **Alternatives Considered:** (a) Surface-only normalization (format translation) — rejected: leaks semantics, breaks on switch. (b) Standardize on one provider — rejected: forfeits flexibility.
- **Pros:** Real provider independence; switch/combine without app change; churn absorbed.
- **Cons:** Semantic parity is hard; adapters carry real complexity and must track providers.
- **Trade-offs:** Concentrates provider complexity in adapters (correct place) at the cost of adapter maintenance.
- **Consequences:** Central to BR-006/008; enables routing/failover across providers (AD-014).
- **Business Impact:** No lock-in; best-provider-per-task (`02` BG-4). **Engineering Impact:** Adapter maintenance discipline. **Security Impact:** Uniform security-relevant behavior across providers. **Performance Impact:** Adapter overhead budgeted. **Operational Impact:** Provider onboarding = adapter lifecycle.
- **Risks:** Provider semantic drift under adapters. **Mitigations:** contract tests, fault injection (`03 §54`), versioned capability.
- **Affected Components:** Provider Adapter Layer, Router, Correctness. **Related BR:** BR-006, BR-007, BR-008. **Related NFR:** NFR-IF-001, NFR-VER-001. **Related PRB:** PRB-007, PRB-021, PRB-022.
- **Related ADRs:** AD-002, AD-014, AD-018. **Review Criteria:** Revisit normalization model as provider landscape shifts. **Future Revisions:** Ongoing adapter additions (additive).
