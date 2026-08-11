# ADR-001 — AI Gateway as Enterprise Control Plane

**ADR ID:** AD-001 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway

- **Status:** Accepted · **Date:** 2026-07-20 · **Decision Makers:** Architecture Council
- **Context:** The product's mission (`00`) is to be the trusted control plane between enterprise applications and every provider. The problem analysis (`01`) shows the failures that matter live in the layer around the model, and are cross-cutting concerns solved in the wrong place (root cause C).
- **Problem Statement:** Where should reliability, correctness, governance, security, observability, and cost control for enterprise AI be enforced?
- **Decision:** Build the product as a **control plane positioned on the request path** — a single, enforced boundary through which all enterprise AI traffic flows — rather than as a library, framework, or observe-only sidecar.
- **Decision Drivers:** Cross-cutting concerns belong at a shared boundary; enforcement (not advice) requires being *in* the path; neutrality requires an independent layer (`00`, `02` BR-015).
- **Alternatives Considered:** (a) SDK/library embedded in each app — rejected: cannot enforce centrally, duplicates work. (b) Observe-only sidecar/proxy — rejected: observes but does not enforce correctness/governance. (c) Provider-side solution — rejected: structurally non-neutral (BRULE-4).
- **Pros:** True enforcement; single point of control, observability, and audit; neutrality; eliminates duplication.
- **Cons:** On the request path → must be extremely reliable and low-overhead or it becomes the bottleneck.
- **Trade-offs:** Accepts the burden of being critical-path infrastructure in exchange for the ability to enforce guarantees.
- **Consequences:** Everything downstream (stateless hot path, latency budgets, HA) follows from being on the path.
- **Business Impact:** Enables the entire value proposition and land-and-expand model (`02` BG-1/2). **Engineering Impact:** Sets the reliability bar (`03`). **Security Impact:** Single enforced boundary reduces attack surface fragmentation. **Performance Impact:** Introduces overhead that must be budgeted (`NFR-PERF-001`). **Operational Impact:** Becomes enterprise-critical; demands 24/7 operability.
- **Risks:** Being on the path makes gateway failure a customer outage. **Mitigations:** AD-006, AD-017, HA/DR (`04 §34–36`).
- **Affected Components:** All. **Related BR:** BR-015, BR-010. **Related NFR:** NFR-AV-001, NFR-PERF-001. **Related PRB:** PRB-011, PRB-016, all cross-cutting.
- **Related ADRs:** AD-017, AD-016, AD-018. **Review Criteria:** Revisit only if the enforcement-at-boundary thesis is invalidated. **Future Revisions:** None expected; foundational.
