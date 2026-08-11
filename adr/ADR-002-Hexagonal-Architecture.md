# ADR-002 — Hexagonal (Ports & Adapters) Architecture

**ADR ID:** AD-002 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway

- **Status:** Accepted · **Date:** 2026-07-20 · **Decision Makers:** Architecture Council
- **Context:** Provider independence (`02` BR-006) and technology-neutrality require insulating the core from providers, datastores, identity systems, and sinks.
- **Problem Statement:** How do we keep the domain independent of volatile externals (providers, stores, frameworks)?
- **Decision:** Adopt **hexagonal architecture**: a provider-agnostic domain core exposes **ports**; all externals (providers, persistence, identity, telemetry sinks, secrets, notifications) are **adapters** implementing those ports. Dependencies point inward.
- **Decision Drivers:** Provider neutrality, evolvability, testability, SOLID dependency inversion.
- **Alternatives Considered:** (a) Provider-coupled core with per-provider code paths — rejected: violates neutrality, breaks on provider change. (b) Layered-only (no ports) — rejected: still couples core to externals.
- **Pros:** Providers/stores swap without touching core; core testable in isolation; neutrality structural.
- **Cons:** More abstraction; adapter maintenance per external.
- **Trade-offs:** Some indirection cost for durable independence and evolvability.
- **Consequences:** Enables AD-007, AD-008/009/010 (adapters/stores swap), future modules (`04 §54`).
- **Business Impact:** Provider independence value (`02` BG-4). **Engineering Impact:** Clean boundaries; adapter surface to maintain. **Security Impact:** Externals mediated through controlled ports. **Performance Impact:** Negligible indirection. **Operational Impact:** Adapters evolve independently.
- **Risks:** Adapter sprawl. **Mitigations:** shared adapter patterns; contract tests.
- **Affected Components:** Core domain; all adapters. **Related BR:** BR-006, BR-007. **Related NFR:** NFR-VER-001, NFR-IF-001. **Related PRB:** PRB-007, PRB-021, PRB-022.
- **Related ADRs:** AD-007, AD-003, AD-010. **Review Criteria:** Revisit only if abstraction cost proves prohibitive (unlikely). **Future Revisions:** None expected.
