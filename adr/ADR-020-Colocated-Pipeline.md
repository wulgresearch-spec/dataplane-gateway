# ADR-020 — Co-located Request Pipeline vs Microservice Hot Path

**ADR ID:** AD-020 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway
**Note:** Corresponds to the `04 §55` summary label **AD-4**.

- **Status:** Accepted · **Date:** 2026-07-20 · **Decision Makers:** Architecture Council
- **Context:** The request path is a sequence of stages (auth, authz, governance, routing, reliability, correctness, accounting, emission). A reflexive microservice design would make each a separate network service.
- **Problem Statement:** Should hot-path stages be independent network services or co-located within one scalable unit?
- **Decision:** Implement the hot-path stages as a **co-located pipeline within one horizontally-scaled unit** (in-process stage composition), reserving microservice decomposition for the Control Plane where latency is not critical.
- **Decision Drivers:** The latency budget (`NFR-LAT-001`, P99 ≤ 50 ms added) cannot absorb a network hop per stage; fewer failure modes on the path.
- **Alternatives Considered:** (a) Fine-grained microservice hot path — rejected: per-hop latency/failure tax blows the budget. (b) Monolith spanning both planes — rejected: see AD-017.
- **Pros:** Meets latency budget; simpler hot-path failure model; still horizontally scalable (stateless units).
- **Cons:** Stages deploy together (coarser deployment granularity on the path); requires internal modularity discipline.
- **Trade-offs:** Trades fine-grained independent deployability of stages for latency and simplicity — a deliberate departure from naive microservices.
- **Consequences:** Internal modularity (DDD, hexagonal) provides the separation that network boundaries would otherwise give.
- **Business Impact:** Low overhead → adoption (`02` SLA-2). **Engineering Impact:** Strong internal boundaries required without network enforcement. **Security Impact:** Fewer inter-service network surfaces on the path. **Performance Impact:** Primary driver — meets budget. **Operational Impact:** Hot path deploys as a unit.
- **Risks:** Internal coupling if discipline slips. **Mitigations:** DDD/hexagonal boundaries (AD-002/003), contract tests.
- **Affected Components:** Data-plane pipeline. **Related BR:** BR-007. **Related NFR:** NFR-LAT-001, NFR-PERF-001. **Related PRB:** PRB-011 (latency of observing).
- **Related ADRs:** AD-006, AD-002, AD-018. **Review Criteria:** Revisit if a stage's isolation needs (e.g., a heavy policy) justify extraction; `04` AOQ-1. **Future Revisions:** Possible selective extraction of the heaviest stage post-calibration.
