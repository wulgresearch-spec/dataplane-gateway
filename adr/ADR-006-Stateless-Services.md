# ADR-006 — Stateless Data-Plane Services

**ADR ID:** AD-006 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway

- **Status:** Accepted · **Date:** 2026-07-20 · **Decision Makers:** Architecture Council
- **Context:** The hot path must scale horizontally near-linearly, fail over cleanly, and elastically absorb bursts (`03` NFR-SCALE/HA/ELAS).
- **Problem Statement:** Should request-processing components hold per-request durable state?
- **Decision:** The Data Plane is **stateless**; ephemeral shared state (rate-limit counters, cache, health) lives in the in-memory tier, and durable state lives in control-plane stores. Any unit can serve any request.
- **Decision Drivers:** Horizontal scale, shared-nothing, clean failover, elasticity.
- **Alternatives Considered:** (a) Stateful hot-path nodes (sticky sessions) — rejected: impedes scale/failover, complicates isolation. (b) Local durable state — rejected: node loss = data loss, breaks HA.
- **Pros:** Near-linear scale; trivial add/remove of capacity; resilient to node loss.
- **Cons:** Requires a fast shared state tier (dependency on AD-008).
- **Trade-offs:** Shifts state to a shared tier that must be low-latency and resilient.
- **Consequences:** Enables scale/HA (`04 §37`); creates dependency on the in-memory tier.
- **Business Impact:** Supports growth to enterprise-wide scale (`02` ADOPT-6). **Engineering Impact:** Simpler failure model. **Security Impact:** No residual per-node sensitive state. **Performance Impact:** Depends on fast shared tier. **Operational Impact:** Nodes are cattle, not pets.
- **Risks:** Shared-tier bottleneck/failure. **Mitigations:** partitioning, redundancy, fail-safe degradation (AD-008, `04 §46`).
- **Affected Components:** All data-plane stages. **Related BR:** BR-005. **Related NFR:** NFR-SCALE-001, NFR-HA-001, NFR-ELAS-001. **Related PRB:** PRB-019, PRB-020.
- **Related ADRs:** AD-017, AD-008, AD-020. **Review Criteria:** Revisit if a stateful design proves necessary for a capability. **Future Revisions:** None expected.
