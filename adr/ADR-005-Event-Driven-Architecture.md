# ADR-005 — Event-Driven Communication (Off Hot Path)

**ADR ID:** AD-005 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway

- **Status:** Accepted · **Date:** 2026-07-20 · **Decision Makers:** Architecture Council
- **Context:** Complete observability and audit (`03` NFR-OBS/AUD-001) must be achieved without adding synchronous latency to requests (`NFR-LAT-001`).
- **Problem Statement:** How do we capture telemetry, audit, and accounting completely without slowing the request path?
- **Decision:** All off-hot-path concerns (telemetry, audit, accounting, security signals, analytics) are emitted **asynchronously as events** to a streaming backbone; consumers (observability, audit, cost, analytics) process them independently. Audit/accounting streams are durable, at-least-once, idempotent → zero loss.
- **Decision Drivers:** Latency (no synchronous logging), completeness, decoupling, replay.
- **Alternatives Considered:** (a) Synchronous logging/audit on the path — rejected: latency + couples request success to logging availability. (b) Best-effort fire-and-forget only — rejected: cannot guarantee audit zero-loss.
- **Pros:** No synchronous latency; complete, durable audit/accounting; replayable; decoupled consumers.
- **Cons:** Eventual consistency of dashboards; a backbone to operate.
- **Trade-offs:** Accepts eventual consistency for latency and completeness; guarantees zero-loss where it matters via durability.
- **Consequences:** Requires a reliable streaming backbone (AD-009); audit RPO=0 via durability.
- **Business Impact:** Total observability/audit (`02` BR-010/011). **Engineering Impact:** Event schemas and consumers. **Security Impact:** Security signals streamed for detection. **Performance Impact:** Removes logging from the latency budget. **Operational Impact:** Backbone reliability is critical (AD-009).
- **Risks:** Backbone as audit dependency. **Mitigations:** durability, backpressure, quorum storage (AD-009, `04 §47`).
- **Affected Components:** Emitters, Event Bus, Observability/Audit/Cost/Analytics. **Related BR:** BR-010, BR-011, BR-012. **Related NFR:** NFR-OBS-001, NFR-AUD-001, NFR-Q-001, NFR-LAT-001. **Related PRB:** PRB-011, PRB-012, PRB-020.
- **Related ADRs:** AD-009, AD-011, AD-018. **Review Criteria:** Revisit delivery semantics per stream as scale grows. **Future Revisions:** None expected structurally.
