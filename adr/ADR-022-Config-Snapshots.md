# ADR-022 — Cached Versioned Configuration Snapshots

**ADR ID:** AD-022 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway
**Note:** Corresponds to the `04 §55` summary label **AD-6**.

- **Status:** Accepted · **Date:** 2026-07-20 · **Decision Makers:** Architecture Council
- **Context:** The stateless data plane (AD-006) needs config/policy/identity/secret data on the hot path without per-request control-plane calls, and must survive control-plane unavailability (AD-017).
- **Problem Statement:** How does the hot path obtain configuration without a synchronous control-plane dependency?
- **Decision:** The data plane **pulls immutable, versioned snapshots** and caches them as **last-known-good**; it operates on the cache even if the control plane is unavailable. Security/invariant changes may use a **high-priority propagation path**.
- **Decision Drivers:** Hot-path latency, availability decoupling, safe rollback.
- **Alternatives Considered:** (a) Per-request control-plane fetch — rejected: latency + hard dependency. (b) Push-only with no local cache — rejected: control-plane outage breaks the data plane.
- **Pros:** Fast; resilient to control-plane outage; reproducible; rollback via version.
- **Cons:** Bounded propagation delay; must manage snapshot freshness and priority tiers.
- **Trade-offs:** Eventual propagation for availability and latency; mitigated by priority path for urgent changes.
- **Consequences:** Enables AD-006/017/019/013 to hold together.
- **Business Impact:** Availability + speed (`02` BR-003). **Engineering Impact:** Snapshot distribution/caching. **Security Impact:** Priority path bounds exposure window for urgent policy. **Performance Impact:** Local reads, budget-friendly. **Operational Impact:** Freshness/priority management.
- **Risks:** Stale config window for non-priority changes. **Mitigations:** priority tiers, freshness monitoring (`04` AOQ-3).
- **Affected Components:** Config cache, Authorization, Router, Secret cache. **Related BR:** BR-003, BR-027. **Related NFR:** NFR-AV-001, NFR-PERF-001, NFR-CFG-001. **Related PRB:** PRB-030, PRB-008.
- **Related ADRs:** AD-017, AD-006, AD-013, AD-019. **Review Criteria:** Revisit propagation SLA post-calibration. **Future Revisions:** Propagation tuning.
