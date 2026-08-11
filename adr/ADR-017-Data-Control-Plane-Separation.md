# ADR-017 — Data Plane / Control Plane Separation

**ADR ID:** AD-017 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway
**Note:** Corresponds to the `04 §55` summary label **AD-1**.

- **Status:** Accepted · **Date:** 2026-07-20 · **Decision Makers:** Architecture Council
- **Context:** A control plane on the request path (AD-001) must be both extremely available/fast (request handling) and durable/consistent (management of config, policy, identity, secrets, audit, cost) — competing profiles.
- **Problem Statement:** Should request handling and platform management share one plane, or be separated?
- **Decision:** Separate a **stateless Data Plane** (hot path) from a **stateful Control Plane** (management), communicating asynchronously and via cached versioned snapshots. Control-plane unavailability must not stop the data plane (it degrades to last-known-good).
- **Decision Drivers:** Different availability/latency/durability/scaling profiles; independent upgrade; availability decoupling.
- **Alternatives Considered:** (a) Single-plane monolith — rejected: couples hot-path availability to management functions, harder to scale/upgrade. (b) Fully distributed with per-request control-plane calls — rejected: adds hot-path dependency and latency.
- **Pros:** Independent scale/upgrade; hot path fast and resilient to control-plane issues; clear responsibility split.
- **Cons:** Two planes to build/operate; bounded config-propagation delay.
- **Trade-offs:** Accepts eventual config propagation and operational duality for availability, latency, and evolvability.
- **Consequences:** Enables AD-006, AD-020, AD-022; shapes deployment (`04 §34`).
- **Business Impact:** Higher availability → trust (`02` BG-1). **Engineering Impact:** Clear plane boundaries. **Security Impact:** Control-plane isolation limits blast radius. **Performance Impact:** Hot path unencumbered by management. **Operational Impact:** Planes operated/upgraded independently.
- **Risks:** Config-propagation delay; two operational surfaces. **Mitigations:** last-known-good operation, high-priority propagation path for security/invariant changes (AD-022).
- **Affected Components:** Entire platform. **Related BR:** BR-003, BR-005. **Related NFR:** NFR-AV-001, NFR-UPG-001, NFR-SCALE-001. **Related PRB:** PRB-008, PRB-020.
- **Related ADRs:** AD-001, AD-006, AD-022, AD-014. **Review Criteria:** Revisit if plane duality cost outweighs benefit at scale (unlikely). **Future Revisions:** None expected.
