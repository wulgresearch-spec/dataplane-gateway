# ADR-009 — Event Streaming Backbone: Kafka-class

**ADR ID:** AD-009 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway

- **Status:** Accepted · **Date:** 2026-07-20 · **Decision Makers:** Architecture Council
- **Context:** Event-driven off-hot-path communication (AD-005) requires a durable, ordered, replayable, high-throughput backbone with differentiated delivery semantics (audit/accounting zero-loss; telemetry high-reliability; analytics best-effort) (`04 §47`). `04` defined this as a role-defined port; this ADR selects the class.
- **Problem Statement:** What technology class implements the event streaming backbone?
- **Decision:** Adopt a **Kafka-class partitioned, durable log streaming platform** (e.g., Apache Kafka or a compatible/managed equivalent) as the event backbone, reached via the streaming port so it remains swappable.
- **Decision Drivers:** Durability + ordering + replay + high throughput + partitioned scale; supports at-least-once with idempotent consumers for zero-loss audit; mature ecosystem.
- **Alternatives Considered:** (a) Traditional message queue (non-log) — rejected: weaker replay/retention/ordering at scale. (b) Cloud-native pub/sub — acceptable as a managed equivalent behind the port. (c) Direct-to-store writes (no backbone) — rejected: couples producers to consumers, loses decoupling/replay. (d) Pulsar and similar log platforms — acceptable equivalents behind the port.
- **Pros:** Durable, ordered, replayable; high throughput; decouples producers/consumers; supports zero-loss audit.
- **Cons:** Operationally non-trivial; a critical dependency for audit.
- **Trade-offs:** Operational weight for durability, replay, and decoupling — justified by audit RPO=0.
- **Consequences:** Audit/accounting completeness and replay depend on it; must be durable/redundant with backpressure.
- **Business Impact:** Total observability/audit (`02` BR-010/011). **Engineering Impact:** Stream schemas, consumers, idempotency. **Security Impact:** Security-signal streaming for detection; governed content. **Performance Impact:** Removes logging from the latency budget (via AD-005). **Operational Impact:** Backbone reliability is critical.
- **Risks:** Backbone as audit single dependency. **Mitigations:** durability, replication, quorum, backpressure, idempotent consumers (`04 §47`, `NFR-Q-001`).
- **Affected Components:** Emitters, Event Bus, Observability/Audit/Cost/Analytics. **Related BR:** BR-010, BR-011, BR-012. **Related NFR:** NFR-AUD-001, NFR-OBS-001, NFR-Q-001. **Related PRB:** PRB-011, PRB-012, PRB-020.
- **Related ADRs:** AD-005, AD-011, AD-010. **Review Criteria:** Revisit if scale/latency/operational factors favor another equivalent. **Future Revisions:** Managed-equivalent selection per deployment (behind the port).
