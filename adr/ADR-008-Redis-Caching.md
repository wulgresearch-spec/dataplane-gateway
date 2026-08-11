# ADR-008 — Distributed In-Memory Tier: Redis-class

**ADR ID:** AD-008 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway

- **Status:** Accepted · **Date:** 2026-07-20 · **Decision Makers:** Architecture Council
- **Context:** The stateless hot path (AD-006) needs an ultra-low-latency shared tier for ephemeral state: rate-limit/quota counters, circuit-breaker/health state, response cache, and short-lived credential/config caches (`04 §46`). `04` defined this as a role-defined port; this ADR selects the implementation class.
- **Problem Statement:** What technology class implements the distributed in-memory tier?
- **Decision:** Adopt a **Redis-class distributed in-memory data store** (e.g., Redis or a compatible/derivative such as Valkey) as the in-memory tier, reached via the in-memory port so it remains swappable.
- **Decision Drivers:** Sub-millisecond latency (fits `NFR-LAT-001`), mature data structures for counters/rate-limiting/TTL, partitioning/clustering for scale, wide operational familiarity, redundancy support.
- **Alternatives Considered:** (a) Memcached — rejected: fewer data structures (weaker for rate-limiting/atomic ops). (b) Embedded per-node cache only — rejected: breaks shared state for stateless nodes. (c) A durable database for counters — rejected: too slow for the hot path. (d) Cloud-native managed cache services — acceptable as deployment options behind the same port.
- **Pros:** Very low latency; rich primitives; proven; clusterable; redundancy.
- **Cons:** Ephemeral (not source of truth); another tier to operate; memory-bound.
- **Trade-offs:** Speed and richness vs. an additional shared dependency (mitigated by fail-safe degradation).
- **Consequences:** Hot-path state depends on this tier; must partition and be redundant (`04 §37/46`).
- **Business Impact:** Enables scale/latency (`02` BR-005). **Engineering Impact:** In-memory data modeling. **Security Impact:** Tenant/policy-scoped entries; no sensitive data cached by default. **Performance Impact:** Primary latency enabler. **Operational Impact:** Cluster ops, memory sizing, redundancy.
- **Risks:** Tier bottleneck/failure. **Mitigations:** partitioning, redundancy, fail-safe (rate limits fail conservative; cache misses fall through) (EP-10).
- **Affected Components:** Reliability Engine (limits), Cache, Secret/Config caches. **Related BR:** BR-013, BR-017. **Related NFR:** NFR-PERF-001, NFR-CACHE-001, NFR-SCALE-001. **Related PRB:** PRB-010, PRB-018.
- **Related ADRs:** AD-006, AD-002 (port), AD-021 (scoping). **Review Criteria:** Revisit if latency/scale needs exceed the class or licensing/operational factors change. **Future Revisions:** Possible move to a specific managed/compatible variant per deployment (behind the port).
