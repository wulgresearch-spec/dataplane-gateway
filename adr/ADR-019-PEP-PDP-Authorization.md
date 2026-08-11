# ADR-019 — PEP/PDP Authorization Split with Cached Policy

**ADR ID:** AD-019 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway
**Note:** Corresponds to the `04 §55` summary label **AD-7**.

- **Status:** Accepted · **Date:** 2026-07-20 · **Decision Makers:** Architecture Council
- **Context:** Authorization must be consistent across all traffic (`NFR-AUTHZ-001`) yet fit the hot-path latency budget (`NFR-PERF-001`).
- **Problem Statement:** How do we enforce consistent, central policy without a per-request network call to a central authorizer?
- **Decision:** Split **Policy Enforcement Point (PEP)** — local, on the hot path — from **Policy Decision distribution** (**PDP**) — central authoring, validated, distributed as **versioned cached snapshots**. The PEP evaluates locally against the current snapshot; deny-by-default.
- **Decision Drivers:** Consistency + low latency + control-plane decoupling.
- **Alternatives Considered:** (a) Central per-request authorization service — rejected: network hop + shared hot-path dependency. (b) Fully local static policy — rejected: no central governance/consistency.
- **Pros:** Consistent enforcement at hot-path speed; resilient to control-plane blips; central authoring.
- **Cons:** Bounded policy-propagation delay; snapshot management.
- **Trade-offs:** Eventual policy propagation for latency and availability.
- **Consequences:** Ties to config snapshots (AD-022); obligations (e.g., redaction) flow to governance stage.
- **Business Impact:** Central governance realized (`02` BR-015). **Engineering Impact:** PEP/PDP separation. **Security Impact:** Deny-by-default everywhere. **Performance Impact:** Local eval fits budget. **Operational Impact:** Policy versioning/propagation.
- **Risks:** Stale policy window. **Mitigations:** high-priority propagation for security-critical policy (AD-022).
- **Affected Components:** Authorization stage, Config/Policy service. **Related BR:** BR-015, BR-021. **Related NFR:** NFR-AUTHZ-001, NFR-PERF-001. **Related PRB:** PRB-016, PRB-029.
- **Related ADRs:** AD-012, AD-022, AD-018. **Review Criteria:** Revisit propagation SLA (`04` AOQ-3). **Future Revisions:** Propagation tuning.
