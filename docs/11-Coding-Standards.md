# 11 — Coding Standards (The Engineering Constitution)

**Document:** Engineering Constitution & Coding Standards
**Project:** Reliability-First AI Gateway
**Status:** **Approved & Frozen (v1.0, 2026-07-20)** — **binding engineering standard**
**Frozen after** the §M internal consistency review passed with no contradictions against `00`–`10` and AD-001…023.
**Audience:** Every engineer, reviewer, and tech lead
**Classification:** Internal — Engineering Standard
**Builds on (approved, frozen):** `00`–`10` and ADRs **AD-001…AD-023**. Nothing here may contradict them.
**Stack (AD-023/`09`/`10`):** Java 21 · Spring Boot · Spring Cloud Gateway · Maven · Virtual Threads · Kafka (Avro/Apicurio) · PostgreSQL · MongoDB · Valkey · OpenTelemetry · Micrometer · JUnit 5 · Mockito · Testcontainers · ArchUnit · Docker · Kubernetes. Namespace: `io.reliabilityai.gateway`.

> **What this is.** The permanent engineering law of the platform. A new engineer should be able to write production code without asking "how should this be done" — the answer is here. Every rule carries: **Problem · Rule · Why · Good · Bad · Exceptions · Enforcement · Build-Fail.** Examples are **tiny illustrations of the standard**, not product code. Rules are challenged, not accreted: where a rule adds complexity without buying reliability, it is cut.
>
> **The three constitutional principles (everything derives from these):**
> - **CP-1 Reliability & correctness first (AD-016).** When in doubt, choose the option that fails safe, loud, and legibly. No silent failure (BRULE-1).
> - **CP-2 Boundaries are law (AD-002/020, `06`/`08`).** Module, port, layer, and tenant boundaries are enforced by the build, not by good intentions.
> - **CP-3 Invariants are absolute (`03` App. D).** Tenant isolation, no-silent-delivery, audit integrity, secret non-exposure, residency, no-bypass — no code, flag, or version may weaken them.

---

## Table of Contents
- **A. Language & Style** (R-001…R-012)
- **B. Structure & Architecture** (R-013…R-024)
- **C. Spring & Web** (R-025…R-033)
- **D. Data & Persistence** (R-034…R-041)
- **E. Events & Messaging** (R-042…R-047)
- **F. Concurrency & Runtime** (R-048…R-054)
- **G. Cross-Cutting** (R-055…R-066)
- **H. Security & Cryptography** (R-067…R-071)
- **I. Quality Gates & Static Analysis** (R-072…R-079)
- **J. Architecture Enforcement — ArchUnit Rules** (AU-01…AU-12)
- **K. Testing** (R-080…R-086)
- **L. Governance, Docs & Compatibility** (R-087…R-095)
- **M. Consistency Review, Scores & Recommendation**

> **Field convention.** Each rule: **Problem** (what goes wrong without it) · **Rule** (the law) · **Why** (rationale/trade-off) · **Good/Bad** (illustration) · **Exceptions** (the only permitted deviations) · **Enforcement** (how it's checked) · **Build-Fail** (the condition that fails CI). Where a rule is self-evident, Good/Bad may be omitted.

---

## A. Language & Style

### R-001 — Java 21, modern idioms
- **Problem:** legacy Java patterns bloat and obscure code. **Rule:** target **Java 21**; use records, sealed types, pattern matching, switch expressions, `var` for obvious locals, text blocks, and **Virtual Threads** (R-048). No pre-11 idioms (raw types, `new` boxed constructors, manual `Optional`-abuse). **Why:** the modern language is safer and clearer; Loom (R-048) is a first-class dependency (AD-023). **Good:** `record Money(long minorUnits, Currency ccy) {}`. **Bad:** a 40-line mutable POJO with getters/setters for a value type. **Exceptions:** none. **Enforcement:** Error Prone + Checkstyle + review. **Build-Fail:** deprecated/forbidden API usage (Error Prone `@DoNotCall`, forbidden-apis plugin).

### R-002 — `Optional` for absence, never for fields/params/collections
- **Rule:** use `Optional<T>` **only** as a return type signaling absence; **never** as a field, constructor/method parameter, or in collections (use empty collections). **Why:** `Optional` fields add allocation and ambiguity; empty collections are the idiom for "none." **Good:** `Optional<Tenant> findById(TenantId id)`. **Bad:** `record X(Optional<String> name)`. **Enforcement:** Checkstyle/PMD rule. **Build-Fail:** `Optional` field/param detected.

### R-003 — `null` discipline
- **Problem:** NPEs and defensive null-checks everywhere. **Rule:** **non-null by default.** Public APIs never return `null` (return empty collection / `Optional` / throw). Validate constructor args (`Objects.requireNonNull`). Use JSpecify/`@NonNull`/`@Nullable` annotations at API boundaries. **Why:** eliminates a whole defect class (CP-1). **Enforcement:** Error Prone `NullAway` (or equivalent). **Build-Fail:** NullAway violation on annotated code.

### R-004 — Records for data, classes for behavior
- **Problem:** anemic classes and mutable data carriers. **Rule:** **DTOs, Value Objects, event payloads, and immutable data → `record`.** Entities/aggregates with identity+behavior and services → `class` (see R-035/R-037). **Why:** records are immutable, concise, and value-semantic — perfect for the platform's heavy VO/DTO/event surface (`05`). **Good:** `record UsageFact(RequestId id, TokenCount tokens, CostAmount cost) {}`. **Bad:** a mutable `UsageFactDto` with setters. **Exceptions:** frameworks requiring no-arg mutability (isolate to adapters). **Enforcement:** review + ArchUnit (DTO/VO packages must be records). **Build-Fail:** non-record class in `*.dto`/`*.vo` packages.

### R-005 — Immutability by default
- **Rule:** fields `final`; collections defensively copied and returned unmodifiable; no setters on domain types. Mutability is opt-in and justified. **Why:** immutable objects are thread-safe (critical for Virtual Threads, R-049), cacheable, and reason-about-able (CP-1/CP-2). **Enforcement:** SpotBugs/Error Prone `Immutable` checks; ArchUnit for domain packages. **Build-Fail:** non-final mutable field in a `@Immutable`/domain type.

### R-006 — Value Objects wrap primitives (no primitive obsession)
- **Problem:** passing raw `String`/`long` for ids, money, tokens invites mix-ups and missing validation. **Rule:** model identifiers and quantities as **Value Objects** (`TenantId`, `RequestId`, `Money`, `TokenCount`) with validation in the constructor. **Why:** type-safety prevents cross-wiring (e.g., a `TenantId` can't be passed where a `PrincipalId` is expected) and centralizes invariants (`05`). **Good:** `route(TenantId t, ProviderId p)`. **Bad:** `route(String t, String p)`. **Exceptions:** at serialization edges (adapters) where primitives are unavoidable — convert immediately. **Enforcement:** review + ArchUnit (domain method signatures forbid bare `String` ids). **Build-Fail:** domain public method with `String`/`UUID` id parameter (named `*Id`).

### R-007 — Time is UTC `Instant`; injectable `Clock`
- **Problem:** local-time bugs, untestable time, DST errors. **Rule:** all timestamps are **`Instant` in UTC**; obtain "now" from an injected **`Clock`**, never `Instant.now()`/`System.currentTimeMillis()` directly; persist/serialize as UTC ISO-8601. **Why:** correctness across regions (`03` NFR-I18N, `08` residency) and deterministic tests. **Good:** `clock.instant()`. **Bad:** `Instant.now()` in business logic. **Exceptions:** the `Clock` bean definition. **Enforcement:** ArchUnit/Error Prone ban on `now()` in non-adapter code. **Build-Fail:** direct `Instant.now()`/`System.currentTimeMillis()` in domain/application layers.

### R-008 — Serialization is explicit; Jackson centrally configured
- **Rule:** one central `ObjectMapper` config (module-registered, `JavaTimeModule`, `WRITE_DATES_AS_TIMESTAMPS=false`, fail-on-unknown per contract policy, no auto-detection of fields — annotate). Event payloads use **Avro** (R-042), not Jackson. REST uses JSON via the shared mapper. **Why:** predictable, secure, versionable serialization (`07`/`10 §7`); prevents polymorphic-deserialization RCE. **Bad:** enabling default typing / `ObjectMapper.enableDefaultTyping()`. **Exceptions:** none for default typing (banned). **Enforcement:** SpotBugs (unsafe deserialization), review. **Build-Fail:** `enableDefaultTyping`/`activateDefaultTyping` detected.

### R-009 — No mutable static state
- **Problem:** hidden shared state breaks tenant isolation and concurrency. **Rule:** no mutable `static` fields; constants are `static final` immutable. **Why:** CP-2/CP-3 (isolation), thread-safety under Virtual Threads. **Enforcement:** SpotBugs `MS_*`, ArchUnit. **Build-Fail:** mutable static field detected.

### R-010 — Explicit visibility; smallest scope
- **Rule:** package-private by default; `public` only for a module's `api` surface (R-013/R-014); no `public` on `internal` types. **Why:** boundaries (CP-2). **Enforcement:** ArchUnit (AU-02). **Build-Fail:** `internal` type referenced across module boundary (AU-02).

### R-011 — No magic numbers/strings; no dead code
- **Rule:** named constants; no commented-out code; no unreachable/unused code. **Why:** clarity, 10-year maintainability. **Enforcement:** Checkstyle/PMD/Sonar. **Build-Fail:** Sonar "dead code"/"commented-out code" above zero on new code.

### R-012 — Formatting is automated, not debated
- **Rule:** one formatter (Spotless + a fixed Google-Java-Format-derived profile); imports ordered; no wildcard imports; 120-col soft limit. **Why:** zero style debate; clean diffs. **Enforcement:** Spotless check. **Build-Fail:** `spotless:check` fails (unformatted code).

---

## B. Structure & Architecture

### R-013 — Package organization: `io.reliabilityai.gateway.<area>.<context>.{api|internal|domain|application|adapter}`
- **Rule:** packages are **feature/context-first**, then layered: `...<context>.api` (published surface), `...domain` (aggregates/VOs/ports), `...application` (use cases), `...adapter.{in,out}` (web/kafka/persistence/provider adapters), `...internal` (hidden). No `util`/`helpers`/`common` dumping grounds. **Why:** package = boundary (CP-2, hexagonal AD-002, DDD `05`). **Bad:** `com....service`, `....impl`, `....utils`. **Enforcement:** ArchUnit package rules (AU-01/02/03). **Build-Fail:** disallowed package or layer import (AU-03).

### R-014 — Modular-monolith module rules (Data Plane)
- **Problem:** a modular monolith rots into a big ball of mud without enforcement (`06 §10`, AD-020). **Rule:** each `gateway-dp-*` module exposes a **small `api` package**; all else is `internal`. A module may depend **only** on another module's `api`, on `libs/*`, and on ports — **never** on another module's `internal`. No cycles. **Why:** in-process boundaries substitute for the network boundaries a microservice would give (CP-2). **Enforcement:** ArchUnit (AU-01/AU-02/AU-04), Maven Enforcer. **Build-Fail:** cross-module `internal` access or cycle (AU-02/AU-04).

### R-015 — Control-plane service rules
- **Rule:** each `gateway-svc-*` is a Spring Boot app owning **one store** (`08 §24`); it **never** reads another service's store or code; integration is via **events/published-language** (`06`/`07`) only. Each service is independently deployable. **Why:** store-per-service, loose coupling (CP-2). **Enforcement:** ArchUnit (AU-07 cross-service DB), review. **Build-Fail:** cross-service datasource/repository reference (AU-07).

### R-016 — DDD tactical patterns are mandatory in the domain
- **Rule:** the domain layer uses **Aggregates (with a single root), Entities, Value Objects, Domain Services, Domain Events**; business rules live **inside** aggregates (no anemic domain). **Why:** `05` is the source of truth; rich models prevent logic leaking into services. **Enforcement:** ArchUnit (domain package contents), review. **Build-Fail:** domain aggregate with public setters / logic-free "service" holding aggregate rules (review-gated + ArchUnit on setters).

### R-017 — Hexagonal: dependencies point inward, only through ports
- **Rule:** **domain depends on nothing external**; application depends on domain + **ports**; adapters implement ports. No domain/application import of Spring web, Kafka, JPA/Mongo drivers, provider SDKs, or `jakarta.servlet`. **Why:** provider independence + testability + swappable tech (AD-002/007, `09`). **Enforcement:** ArchUnit (AU-08 domain→infra, AU-05 port bypass). **Build-Fail:** domain/application importing an infrastructure/framework package (AU-08).

### R-018 — Ports live with the domain; adapters live at the edge
- **Rule:** a **port** is a domain-owned interface (e.g., `ProviderPort`, `UsageLedgerPort`) in `libs/gateway-persistence-ports` or the context's `domain`; adapters (`adapter.out.*`) implement it. The domain calls the **port**, never the adapter/SDK. **Why:** hexagonal core (AD-002). **Good:** `class Router { Router(ProviderPort port) }`. **Bad:** `class Router { OpenAiClient client }`. **Enforcement:** ArchUnit (AU-05 port bypass, AU-06 provider SDK). **Build-Fail:** direct provider SDK import outside a provider adapter (AU-06).

### R-019 — SOLID, applied
- **Rule:** single-responsibility classes; open for extension via ports/plugins (AD-004), closed to core edits; Liskov-safe hierarchies (prefer composition + sealed types over deep inheritance); interface segregation (small ports); dependency inversion (depend on ports, R-018). **Why:** 10-year evolvability. **Enforcement:** review + Sonar complexity/coupling metrics + ArchUnit. **Build-Fail:** Sonar cognitive-complexity/coupling thresholds exceeded on new code.

### R-020 — Dependency Injection: constructor-only, no field injection
- **Problem:** field injection hides dependencies, breaks immutability, and makes testing harder. **Rule:** **constructor injection only**; dependencies `final`; no `@Autowired` on fields; no static `ApplicationContext` lookups; prefer explicit beans over classpath scanning for core wiring. **Why:** immutability (R-005), testability, clear graphs. **Good:** `Router(ProviderPort p, Clock c) {…}`. **Bad:** `@Autowired ProviderPort p;`. **Exceptions:** none in product code. **Enforcement:** ArchUnit (no field `@Autowired`), Checkstyle. **Build-Fail:** field/setter `@Autowired`/`@Inject` detected.

### R-021 — No circular dependencies (packages, modules, beans)
- **Rule:** no cyclic dependencies at any level; Spring `spring.main.allow-circular-references=false`. **Why:** cycles are the root of un-maintainability (CP-2). **Enforcement:** ArchUnit `slices().should().beFreeOfCycles()` (AU-04); Spring fails on circular beans. **Build-Fail:** any dependency cycle (AU-04) or circular-bean at startup.

### R-022 — Composition over inheritance; sealed hierarchies
- **Rule:** prefer composition; where polymorphism is needed use **`sealed` interfaces + records** (exhaustive `switch`). No deep/implementation inheritance across modules. **Why:** exhaustiveness + clarity (Java 21). **Enforcement:** review + Sonar. **Build-Fail:** n/a (review), but non-exhaustive switch on sealed type → Error Prone.

### R-023 — One public type per file; no god classes
- **Rule:** one top-level public type per file; hard cap on class/method size (Sonar): method ≤ ~60 lines, class ≤ ~400, cyclomatic ≤ 15 (guidelines, not fetishes). **Why:** readability. **Enforcement:** Checkstyle/Sonar. **Build-Fail:** Sonar size/complexity thresholds on new code.

### R-024 — Feature/context cohesion
- **Rule:** code for a bounded context (`05`) lives together under its `<context>` package and its Maven module; do not scatter a context across layers-first packages. **Why:** cohesion, ownership (`06 §16`). **Enforcement:** ArchUnit package mapping. **Build-Fail:** type placed in the wrong context package (AU-01).

---

## C. Spring & Web

### R-025 — Spring Boot: explicit config, no surprise magic
- **Rule:** explicit `@Configuration` for core wiring; constructor-bound `@ConfigurationProperties` (records) for config (R-062); avoid broad component-scan of `internal`; **Virtual Threads enabled** (`spring.threads.virtual.enabled=true`, R-048). **Why:** predictability, boundaries. **Enforcement:** ArchUnit (no `@Component` on `internal` domain), review. **Build-Fail:** component-scan reaching domain/internal (AU-02).

### R-026 — Controllers are thin adapters (`adapter.in.web`)
- **Problem:** business logic in controllers bypasses the domain and boundaries. **Rule:** controllers **only** translate HTTP↔application commands/queries and map errors; **no business logic, no repository access** (R-033). One controller per resource. **Why:** hexagonal (AD-002), CP-2. **Bad:** controller calling a repository or doing validation logic. **Enforcement:** ArchUnit (AU-11 controller→repository, AU-08). **Build-Fail:** controller referencing a repository/port-out directly (AU-11).

### R-027 — REST API design (OpenAPI 3.1 first)
- **Rule:** API-first — the OpenAPI 3.1 spec in `apis/` is the source; controllers conform. Resource-oriented URIs, correct status codes, cursor pagination, idempotency keys for unsafe ops (R-046), consistent error envelope (R-030). Versioned (`/v1`), backward-compatible within a major (R-093). **Why:** `10 §7`, AD-015. **Enforcement:** spec-diff compat check (R-093), contract tests (R-084). **Build-Fail:** controller diverges from spec (contract test) or breaking spec change without major bump.

### R-028 — DTOs at the edge only; never leak domain types over the wire
- **Rule:** web/event **DTOs are records** in `adapter.*`; map to/from domain explicitly; **never serialize an aggregate/entity directly**. **Why:** decouples contract from model (AD-015), prevents accidental exposure. **Good:** `TenantResponse` record mapped from `Tenant`. **Bad:** returning a JPA `@Entity` from a controller. **Enforcement:** ArchUnit (domain entities not referenced in web-serialization), review. **Build-Fail:** `@Entity`/aggregate referenced in a controller signature (ArchUnit).

### R-029 — Mapping: explicit, tested; MapStruct allowed, reflection mappers banned
- **Rule:** DTO↔domain mapping is explicit — hand-written or **MapStruct** (compile-time). **No** runtime reflection mappers (ModelMapper-style). **Why:** compile-time safety, no hidden mismatches. **Enforcement:** forbidden-deps (ban reflection mappers), review. **Build-Fail:** banned mapping dependency present.

### R-030 — Error response envelope (RFC 9457 Problem Details)
- **Problem:** inconsistent error bodies break clients. **Rule:** all HTTP errors use a **single Problem-Details** envelope (`type`, `title`, `status`, `detail`, `code`, `correlationId`) — **never** stack traces or internal messages to clients; map exceptions centrally (`@RestControllerAdvice`). Include `correlationId` (R-056). **Why:** consistent, secure, debuggable (CP-1). **Bad:** returning `ex.getMessage()` or a stack trace. **Enforcement:** review + a test asserting the envelope; ArchUnit (no `printStackTrace`). **Build-Fail:** `printStackTrace()` present, or controller returning raw exception.

### R-031 — Exception hierarchy (unchecked, typed, mapped)
- **Rule:** a small **sealed** exception hierarchy rooted at `GatewayException` (unchecked): `DomainException` (business rule), `ValidationException`, `AuthzException`, `NotFoundException`, `ConflictException`, `UpstreamProviderException`, `InfrastructureException`. Each maps to a status + `code`. **No** checked exceptions in domain/application; **never** catch-and-swallow. **Why:** typed errors → correct handling + no silent failure (CP-1, BRULE-1). Checked exceptions rejected: they leak infrastructure and encourage swallowing. **Bad:** `catch (Exception e) {}`. **Enforcement:** ArchUnit (no empty catch; domain throws only `GatewayException`), SpotBugs. **Build-Fail:** empty catch block / swallowed exception detected.

### R-032 — Validation at the boundary (Bean Validation) + domain invariants inside
- **Rule:** **syntactic** validation at the edge (`jakarta.validation` on DTOs, `@Valid`); **semantic invariants** enforced **inside** VOs/aggregates (R-006/R-016). Never trust the edge for invariants. **Why:** defense in depth; invariants belong to the domain (`05`). **Enforcement:** review + tests; ArchUnit (aggregates validate in constructor). **Build-Fail:** n/a (test-gated) — but missing `@Valid` on request bodies flagged by Sonar rule.

### R-033 — Controllers/services never touch persistence directly
- **Rule:** only `adapter.out.persistence` implements persistence ports; application/domain call **ports**. **Why:** hexagonal (AD-002), CP-2. **Enforcement:** ArchUnit (AU-11). **Build-Fail:** controller/service importing a repository/`EntityManager`/`MongoTemplate` (AU-11).

---

## D. Data & Persistence

### R-034 — Persistence is an adapter behind a port (per `08`)
- **Rule:** domain defines a **repository *port*** (interface, domain language); `adapter.out.persistence` implements it with Spring Data (JPA/Postgres, Mongo, Valkey). The domain never sees `@Entity`/`Document`/`JpaRepository`. **Why:** AD-002/010; swappable stores. **Good:** `interface UsageLedgerPort { void append(UsageFact f); }`. **Bad:** domain depending on `UsageJpaRepository`. **Enforcement:** ArchUnit (AU-08/AU-05). **Build-Fail:** domain referencing a persistence framework (AU-08).

### R-035 — Entities vs domain models are distinct
- **Rule:** **persistence entities** (`@Entity`/`@Document`) live in the persistence adapter and are **mapped** to/from **domain aggregates**; they are not the same class. **Why:** ORM concerns (lazy loading, mutability) must not pollute the domain (CP-2). **Exceptions:** small services may collapse the two **only** if the persistence type stays inside the adapter and is never exposed — documented per service. **Enforcement:** ArchUnit (no `@Entity` in domain). **Build-Fail:** `@Entity`/`@Document` in a domain package.

### R-036 — Aggregate consistency boundary = one transaction
- **Rule:** a single transaction mutates **one aggregate** (small-aggregate rule, `05`/`08 §8`); cross-aggregate consistency is **eventual via events** (R-042), never a distributed transaction. **Why:** scalable, correct (AD-005/016). **Bad:** one `@Transactional` method updating two aggregates across contexts. **Enforcement:** review + ArchUnit (no cross-context repository calls in one class). **Build-Fail:** n/a (review), cross-service store access → AU-07.

### R-037 — Value Objects immutable records; equality by value
- **Rule:** VOs are records with validated construction and value equality; no identity. **Why:** `05`, immutability (R-005). **Enforcement:** ArchUnit (`*.vo` are records). **Build-Fail:** non-record in VO package (R-004).

### R-038 — Transactions: explicit, narrow, at application boundary
- **Rule:** `@Transactional` on **application service methods** (the use-case boundary), **not** on controllers or repositories; keep transactions short (no remote/provider calls inside a DB transaction); read-only where applicable. **Outbox** writes happen **in the same transaction** as the state change (R-043, DA-D1). **Why:** correctness + no long locks (CP-1). **Bad:** provider HTTP call inside `@Transactional`. **Enforcement:** ArchUnit (`@Transactional` only in application layer; no port-out provider call within), review. **Build-Fail:** `@Transactional` on controller/repository (ArchUnit).

### R-039 — No lazy-loading leakage; fetch explicitly
- **Rule:** avoid ORM lazy-loading across the domain boundary; adapters fetch what the domain needs and map fully. **Why:** predictability, no `LazyInitializationException`, no N+1. **Enforcement:** review + Hibernate statistics in tests. **Build-Fail:** n/a (test-gated: N+1 assertion in integration tests).

### R-040 — Tenant scope on every query (isolation, AD-021)
- **Rule:** **every** persistence operation is tenant-scoped; no query without a `TenantId` predicate; regulated data uses per-tenant/subject keys (`08 §14`). **Why:** absolute isolation invariant (CP-3). **Enforcement:** ArchUnit + a persistence-adapter test harness asserting tenant predicate; isolation detectors. **Build-Fail:** repository method lacking tenant scoping (custom ArchUnit/test rule).

### R-041 — Migrations are versioned & forward-only (expand-then-contract)
- **Rule:** schema changes via versioned migrations (Flyway/Liquibase), **expand-then-contract**, backward-compatible, reviewed by the owning team + DBA reviewer (`08 §17`). **Why:** zero-downtime (AD-015). **Enforcement:** migration lint + review; CI applies migrations in integration tests. **Build-Fail:** out-of-order/edited applied migration; failing migration in CI.

---

## E. Events & Messaging

### R-042 — Events use the standard envelope + Avro (per `07`/`10 §7`)
- **Rule:** every domain event carries the **standard envelope** (Event Id, type, schema version, occurred-at, recorded-at, producer, **correlation id**, **causation id**, **tenant scope**, partition key, data classification) with an **Avro** payload registered in **Apicurio**; topics per `06 §23` (`rfaig...`→`rfaig`? no — `06 §23` topics). **Why:** correlation, replay, security, evolution (`07`). **Bad:** a bespoke JSON event without envelope/registry. **Enforcement:** a shared `gateway-contracts` producer API; registry compat gate (R-093). **Build-Fail:** event published off-contract / schema incompatible.

### R-043 — Producers use the transactional outbox (stateful services)
- **Rule:** state change + event are written in **one DB transaction** (outbox), relayed to Kafka (polling default; Debezium optional) — **DA-D1**. The stateless Data Plane uses the durable emit path (EV-D3). **Why:** no dual-write inconsistency; zero-loss (CP-1). **Bad:** publish to Kafka then commit DB (or vice-versa) in separate steps. **Enforcement:** review + integration test (crash-between). **Build-Fail:** n/a (test-gated); a lint flags direct `KafkaTemplate.send` in a `@Transactional` service without outbox.

### R-044 — Kafka consumers are idempotent and at-least-once
- **Rule:** consumers assume **at-least-once**; processing is **idempotent** keyed by the `06 §23` idempotency key; **replay-critical consumers use natural/version-guarded idempotency**, not TTL dedup alone (`07 §13`). Manual/ack-after-process; no auto-commit-before-process. **Why:** effectively-once (CP-1). **Bad:** relying on exactly-once delivery. **Enforcement:** review + duplicate-delivery test. **Build-Fail:** auto-commit enabled on a domain consumer (config lint).

### R-045 — DLQ + bounded retry; never silent drop on ZL streams
- **Rule:** consumers route poison messages to a **DLQ** after **bounded, backed-off retries** (retry topics); **ZL streams never discard** — DLQ holds + alerts (`07 §14`). **Why:** no silent loss (CP-1/CP-3). **Enforcement:** consumer template mandates DLQ; review. **Build-Fail:** a domain consumer without a DLQ/retry policy (config lint/ArchUnit on consumer base class).

### R-046 — Idempotency for unsafe operations (HTTP + commands)
- **Rule:** unsafe/consequential operations accept an **idempotency key**; the same key returns the same result without repeating side effects (no duplicate charges/actions — `NFR-RTY-001`). **Why:** CP-1, safe retries. **Enforcement:** review + test. **Build-Fail:** n/a (test-gated).

### R-047 — Retry policy is centralized, budgeted, jittered (never per-call hand-rolled)
- **Rule:** retries use the **shared reliability policy** (exponential backoff + jitter, retry budget ≤ 10%, no retry of non-idempotent ops without idempotency) — never ad-hoc loops. **Why:** prevents retry storms (`07 §21`, PRB-009). **Bad:** `for (int i=0;i<5;i++) try{...}catch{...}`. **Enforcement:** ArchUnit/review ban on hand-rolled retry loops around provider/IO calls. **Build-Fail:** hand-rolled retry loop around a port call (ArchUnit heuristic + review).

---

## F. Concurrency & Runtime

### R-048 — Virtual Threads are the default concurrency model
- **Rule:** use **Virtual Threads** for blocking/IO-bound and high-concurrency work (streaming, provider calls); write **simple blocking code**, not reactive chains. Enable platform-wide (`spring.threads.virtual.enabled=true`). **Why:** AD-023 — simple, scalable concurrency for streaming (`NFR-CONC`) without reactive complexity. **Bad:** introducing Reactor/WebFlux for concurrency. **Exceptions:** a *specific* CPU-bound hotspot may use a bounded platform-thread pool (documented). **Enforcement:** forbidden-deps (no WebFlux/Reactor in core), review. **Build-Fail:** reactive stack dependency present in core modules.

### R-049 — Virtual-thread safety: no pinning, no shared mutable state, no thread-locals for scale
- **Rule:** never block inside `synchronized` (pins the carrier) — use `ReentrantLock`; avoid `ThreadLocal` for per-request context at scale (use explicit context/`ScopedValue`); no shared mutable state across requests. **Why:** correctness + performance under Loom; isolation (CP-3). **Bad:** `synchronized` around a provider IO call. **Enforcement:** Error Prone/JFR pinning detection in perf tests; review. **Build-Fail:** perf test flags carrier pinning above threshold.

### R-050 — Thread safety is proven, not assumed
- **Rule:** shared components are immutable (R-005) or explicitly synchronized/lock-guarded and documented `@ThreadSafe`; no data races. **Why:** NFR-REL-002 (no cross-request leakage). **Enforcement:** SpotBugs concurrency detectors; concurrency tests (R-085). **Build-Fail:** SpotBugs concurrency bug pattern (e.g., `IS2_INCONSISTENT_SYNC`).

### R-051 — Resource lifecycle: try-with-resources; nothing leaks
- **Rule:** all `Closeable`/streams/clients use **try-with-resources**; long-lived resources have explicit lifecycle beans; streaming connections are closed on completion/cancel/error (`08`/`07` — prevents leaks, PRB-020). **Why:** no resource exhaustion (CP-1). **Bad:** opening a stream without closing on error path. **Enforcement:** Error Prone `MustBeClosed`, SpotBugs `OBL_*`. **Build-Fail:** unclosed resource detected.

### R-052 — Timeouts and bounds on everything
- **Rule:** every external call has a **timeout**; every queue/buffer/collection that grows with load is **bounded** with backpressure (`NFR-Q-001`); no unbounded caches. **Why:** stability under load (CP-1). **Bad:** a provider call with no timeout; an unbounded in-memory list per request. **Enforcement:** review + config lint (client timeouts required). **Build-Fail:** provider/IO client without a configured timeout (config lint).

### R-053 — Memory: no unbounded accumulation; stream large payloads
- **Rule:** stream large request/response bodies (don't buffer whole); bound per-request memory; avoid holding provider responses longer than needed. **Why:** footprint under load (JVM, AD-023 R-1). **Enforcement:** perf/soak tests (R-086); review. **Build-Fail:** soak test shows memory growth beyond threshold (nightly gate).

### R-054 — Performance is measured, not guessed; no premature optimization
- **Rule:** optimize against **benchmarks** (`benchmarks/`) and the latency budget (`06 §11`), not intuition; correctness first (CP-1); document any perf-driven complexity. **Why:** balanced engineering. **Enforcement:** perf regression gate (R-086). **Build-Fail:** perf regression beyond baseline (nightly).

---

## G. Cross-Cutting

### R-055 — Structured logging via SLF4J; no `System.out`, no secrets/PII
- **Rule:** SLF4J structured logs (key-value/JSON), appropriate levels, **no `System.out`/`printStackTrace`**, **never** log secrets or unredacted regulated data (`07 §17`, `08`); logs carry the correlation id (R-056). **Why:** observability + non-exposure (CP-3). **Bad:** `log.info("token=" + apiKey)`. **Enforcement:** ArchUnit (no `System.out`/`printStackTrace`), secret-in-log scanner. **Build-Fail:** `System.out`/`printStackTrace` present, or secret-pattern in a log call.

### R-056 — Correlation & causation IDs everywhere
- **Rule:** every request/flow has a **correlation id** propagated across threads (via context/`ScopedValue`, not `ThreadLocal`-at-scale), logs, traces, and events (envelope, R-042); causation id links caused events. **Why:** end-to-end reconstruction (`07 §16`, NFR-TRC). **Enforcement:** a mandatory filter/interceptor + ArchUnit that emitters set it. **Build-Fail:** event published without correlation id (contract test).

### R-057 — Metrics via Micrometer; standard names, bounded cardinality
- **Rule:** instrument with **Micrometer**; follow a naming convention (`gateway.<area>.<metric>`); **bound label cardinality** (no unbounded tenant/user labels beyond policy, `08 §24.9 M7`); every SLO has a metric (`03 §62`). **Why:** observability without cardinality blowup. **Bad:** a timer tagged with raw request id. **Enforcement:** review + a cardinality lint in tests. **Build-Fail:** metric with a known-unbounded tag (lint).

### R-058 — Tracing via OpenTelemetry; spans on boundaries
- **Rule:** OTel spans at ingress, provider calls, DB, and consumers; propagate context; 100% capture for errors/slow (`07 §16`). **Why:** NFR-TRC. **Enforcement:** auto-instrumentation + review. **Build-Fail:** n/a (review), missing propagation caught by trace-completeness test.

### R-059 — Observability is by construction, not optional
- **Rule:** every stage **emits** telemetry/audit as part of its work (`04 §39`); you may not add a code path that a request can traverse silently. **Why:** 100% coverage (NFR-OBS/AUD). **Enforcement:** review + coverage reconciliation test. **Build-Fail:** n/a (test-gated).

### R-060 — Feature flags: typed, defaulted-safe, short-lived
- **Rule:** flags are typed, have a **safe default** (secure/off), are evaluated centrally, and are **short-lived** (tracked for removal); flags never gate an invariant (CP-3 cannot be flagged off). **Why:** safe rollout without permanent complexity. **Bad:** a flag that disables audit or isolation. **Enforcement:** flag registry + review; stale-flag lint. **Build-Fail:** a flag older than its max age (lint) or a flag guarding an invariant (review-blocked).

### R-061 — No business logic in config; config is validated
- **Rule:** configuration tunes behavior, never encodes business rules; `@ConfigurationProperties` **records** with Bean Validation; fail-fast on invalid config at startup; secure defaults (`10`, AD-013). **Why:** safe-by-default (CP-3), reproducibility. **Enforcement:** validation on startup; review. **Build-Fail:** startup fails on invalid config (intended).

### R-062 — Configuration precedence & no secrets in config files
- **Rule:** config precedence is explicit (defaults < env < mounted config); **no secrets in `application.yml`/env-in-repo** — secrets come from the Secret service/KMS (R-069, `08 §14`). **Why:** non-exposure (CP-3). **Enforcement:** secret scanner on repo + config. **Build-Fail:** secret pattern in any committed config.

### R-063 — Determinism & no hidden global state
- **Rule:** no reliance on system time (R-007), randomness without injected source, locale/timezone defaults, or ambient global state; inject `Clock`, `RandomGenerator`. **Why:** testability + correctness. **Enforcement:** Error Prone bans (`now()`, `Math.random`), review. **Build-Fail:** `Math.random()`/default-locale formatting in business code.

### R-064 — Nullability & Optional at boundaries only (cross-ref R-002/R-003)
- Consolidated with R-002/R-003; enforced identically.

### R-065 — No reflection/dynamic proxies in the domain
- **Rule:** domain/application avoid reflection, dynamic proxies, and classpath scanning; keep them in adapters/framework config. **Why:** GraalVM-native compatibility (AD-023 optional), clarity, security. **Enforcement:** ArchUnit (no reflection API in domain). **Build-Fail:** `java.lang.reflect`/proxy use in domain (ArchUnit).

### R-066 — Internationalization & encoding
- **Rule:** UTF-8 everywhere; locale-aware formatting only at presentation; no platform-default charset. **Why:** `NFR-I18N`; no corruption. **Enforcement:** forbidden-apis (default-charset methods), Checkstyle. **Build-Fail:** default-charset API usage.

---

## H. Security & Cryptography

### R-067 — Secure coding baseline (zero-trust, AD-012)
- **Rule:** validate/normalize all external input; parameterized queries only (no string-built SQL/NoSQL); output encoding; deny-by-default authorization checks in the application layer (PEP, `06`); no SSRF/deserialization sinks. **Why:** the gateway is the boundary (CP-3). **Bad:** string-concatenated query; disabled auth in a code path. **Enforcement:** SAST + SpotBugs security + review. **Build-Fail:** SAST high/critical finding; unsafe query pattern.

### R-068 — No secrets, keys, or regulated data in logs/events/exceptions
- **Rule:** **absolute** — secrets never in logs/events/traces/exception messages; regulated data only in classified, governed audit records (`07 §17`, `08 §14`). **Why:** CP-3 (non-exposure). **Enforcement:** secret-in-log/event scanner; review. **Build-Fail:** secret pattern detected anywhere in emitted strings.

### R-069 — Secrets access via the Secret port only; short-TTL, never persisted
- **Rule:** obtain provider credentials/keys via the **Secret port** (adapter to KMS/Vault-class), cached short-TTL, never written to disk/config/domain; rotation/revocation honored. **Why:** `08 §14`, `06 §9.6`. **Enforcement:** ArchUnit (no KMS SDK outside the secret adapter), scanner. **Build-Fail:** secret-manager SDK used outside `adapter.out.secret` (ArchUnit).

### R-070 — Cryptography: standard, vetted, no home-grown
- **Rule:** use vetted crypto (JCA/BouncyCastle/KMS); **AES-256** at rest, **TLS 1.2+/1.3** in transit; **no home-grown crypto**, no ECB, no static IVs, no MD5/SHA-1 for security; envelope encryption + per-tenant/subject keys (`08 §14`). **Why:** NFR-ENC. **Bad:** custom XOR "encryption"; `Cipher.getInstance("AES")` (ECB default). **Enforcement:** SpotBugs security (weak crypto), review. **Build-Fail:** weak/broken crypto pattern (SpotBugs `CIPHER_*`, `WEAK_*`).

### R-071 — AuthN/AuthZ enforced, never bypassable
- **Rule:** every protected operation is authenticated + authorized (deny-by-default); no code path bypasses the PEP; tenant scope enforced (R-040). **Why:** CP-3, no-bypass (AD-018). **Enforcement:** ArchUnit (protected endpoints require an authz check annotation/aspect), negative tests. **Build-Fail:** protected controller method without authorization (ArchUnit/test).

---

## I. Quality Gates & Static Analysis

> **One toolchain, all build-failing on new code.** Baselines freeze existing debt; **new/changed code must be clean** (ratchet). Tools are complementary, not redundant (challenged below).

### R-072 — Spotless (formatting) — *fail on unformatted*.
### R-073 — Checkstyle (style/conventions: imports, naming, visibility, size) — *fail on violation (new code)*.
### R-074 — PMD (code smells, best practices, `Optional`/null rules) — *fail on priority ≥ medium (new code)*.
### R-075 — SpotBugs + FindSecBugs (correctness, concurrency, security bug patterns) — *fail on any high-confidence bug*.
### R-076 — Error Prone + NullAway (compile-time bug prevention, nullability, `now()`/`Math.random` bans) — *compile-fail on error-level checks*.
### R-077 — SonarQube (quality gate: coverage, duplication, complexity, security hotspots, maintainability rating) — *fail the Sonar quality gate on the PR*.
### R-078 — Dependency/License/SBOM (OWASP Dependency-Check/Snyk + license policy + CycloneDX) — *fail on known critical CVE or disallowed license (`09` posture)*.
### R-079 — Secret scanning (gitleaks-class) — *fail on any detected secret*.

- **Why this set (challenge):** they overlap slightly but each catches a distinct class — **Error Prone** at compile-time (cheapest), **SpotBugs/FindSecBugs** on bytecode (concurrency/security patterns Sonar misses), **PMD/Checkstyle** for style/smells, **Sonar** for the aggregate quality gate + hotspots, **ArchUnit** for architecture (§J). We **do not** run every possible linter — we run this curated, non-redundant set and **turn duplicate rules off** to avoid noise. **Rule for the set (R-072..079):** all run in CI; **new code must pass**; existing debt is baselined and burned down; no rule is disabled without an ADR-level exception recorded in `security/`.
- **Build-Fail (aggregate):** any of the above failing on changed code fails the PR (gate order per `10 §15`).

---

## J. Architecture Enforcement — ArchUnit Rules (build-failing)

> These are **mandatory** ArchUnit tests in a shared `architecture-tests` module, run in CI. Each **fails the build** on violation. They are the executable form of CP-2 and the frozen architecture.

| ID | Rule | Fails build when… | Source |
|---|---|---|---|
| **AU-01** | **Package/context mapping** — types reside in their bounded-context package (`...<context>...`) | a type is in the wrong context/layer package | `05`/`10`, R-013/R-024 |
| **AU-02** | **Internal-package access** — `internal` types are not referenced across module boundaries | a module imports another module's `internal` | AD-020, R-010/R-014 |
| **AU-03** | **Layer rules** — `domain → (nothing external)`, `application → domain+ports`, `adapter → application`, web/persistence adapters don't call each other | any layered dependency points the wrong way | AD-002, R-017 |
| **AU-04** | **No cycles** — packages, modules, and slices are cycle-free | any dependency cycle exists | R-021 |
| **AU-05** | **No port bypass** — application/domain depend on **ports**, adapters implement them; no domain→adapter | domain/application references a concrete adapter | AD-002, R-018 |
| **AU-06** | **No direct provider SDK** — provider SDKs (OpenAI/Anthropic/etc.) used **only** inside `adapter.out.provider.*` | a provider SDK is imported outside a provider adapter | AD-007, neutrality `00`, R-018 |
| **AU-07** | **No cross-service DB access** — a service references only its own datasource/repositories | a service imports another service's persistence/datasource | `08 §24`, R-015 |
| **AU-08** | **Domain independent of infrastructure** — domain/application import no Spring-web/Kafka/JPA/Mongo/servlet/provider packages | domain/application import an infrastructure/framework package | AD-002, R-017 |
| **AU-09** | **Infrastructure independent of web** — persistence/messaging adapters don't depend on `adapter.in.web` | a persistence/messaging adapter imports web layer | AD-002, R-017 |
| **AU-10** | **Forbidden dependencies** — banned libs (reactive stack in core R-048, reflection mappers R-029, `java.util.logging`, `commons-logging`, default-charset apis) | a banned dependency/API is present | R-029/R-048/R-066 |
| **AU-11** | **Controller ↛ repository** — controllers/`adapter.in.web` never reference repositories/`EntityManager`/`MongoTemplate`/ports-out directly | a controller references persistence/port-out | R-026/R-033 |
| **AU-12** | **No mutable static / field injection / `now()` / `printStackTrace` / `System.out`** — code hygiene invariants | any listed anti-pattern is present | R-007/R-009/R-020/R-055 |

- **Enforcement:** `architecture-tests` module in the Maven reactor (`10 §13`), part of gate 1 (`10 §15`). **Build-Fail:** any AU-0N violation.
- **Exceptions:** an AU rule may be narrowly `@ArchIgnore`-suppressed **only** with a recorded justification in `security/arch-exceptions.md` and owner+architect approval; suppressions are reviewed quarterly.

---

## K. Testing

### R-080 — Test pyramid & the coverage bar
- **Rule:** many fast **unit** tests (domain/application, no Spring), fewer **slice/integration** (Testcontainers: Postgres/Mongo/Valkey/Kafka), fewest **e2e** (`testing/`); coverage **≥ 85% line / ≥ 80% branch on new code** for domain/application (higher for correctness/security/isolation paths); coverage is a **floor, not a goal** (no test-for-coverage). **Why:** confidence without brittleness (`03 §50`). **Enforcement:** JaCoCo + Sonar gate. **Build-Fail:** new-code coverage below floor.

### R-081 — Unit tests: JUnit 5 + Mockito, no Spring context
- **Rule:** domain/application unit tests use **JUnit 5 + Mockito**, no Spring context, deterministic (injected `Clock`/random). Mock **ports**, not concrete infra. **Why:** speed + isolation. **Bad:** `@SpringBootTest` for a pure domain rule. **Enforcement:** ArchUnit test-classification + review. **Build-Fail:** n/a (review); slow-test budget enforced in CI.

### R-082 — Integration tests: Testcontainers, real backends
- **Rule:** persistence/messaging/adapter tests run against **Testcontainers** (real Postgres/Mongo/Valkey/Kafka), never in-memory fakes (no H2 for Postgres behavior). **Why:** real-backend fidelity (`09`). **Enforcement:** convention + CI. **Build-Fail:** failing integration test.

### R-083 — Architecture tests are tests (AU-01…12) — run every build (§J).

### R-084 — Contract tests for every API/event
- **Rule:** provider/consumer **contract tests** verify controllers match OpenAPI (`apis/`) and producers/consumers match Avro schemas (registry); compat gate on change (R-093). **Why:** AD-015, no drift. **Enforcement:** CI. **Build-Fail:** contract mismatch or incompatible schema.

### R-085 — Concurrency & isolation tests (mandatory for shared/hot-path code)
- **Rule:** hot-path and shared components have **concurrency tests** (parallel load) asserting **no cross-request/tenant leakage** (NFR-REL-002) and no data races. **Why:** CP-3. **Enforcement:** CI suite + isolation detectors. **Build-Fail:** leakage/race detected.

### R-086 — Fault-injection, performance & chaos are gates (not per-PR)
- **Rule:** correctness fault-injection (malformed output/streams/tool-calls, `03 §54`) runs in CI; **performance vs baselines** and **chaos/DR** run nightly/scheduled and gate releases (`03 §51–53`, `10 §15`). **Why:** the product's core value must be verified. **Enforcement:** scheduled pipelines. **Build-Fail:** fault-injection failure (per-PR); perf/chaos regression (nightly gate).

---

## L. Governance, Docs & Compatibility

### R-087 — Pull-Request checklist (required)
- [ ] Correct module/package/layer; AU-01…12 green. [ ] Constructor injection, immutable, tenant-scoped. [ ] Ports not bypassed; no provider SDK/cross-service DB. [ ] Errors typed + mapped (envelope); nothing swallowed. [ ] Events on-contract w/ envelope+correlation; idempotent consumers + DLQ. [ ] No secrets/PII in logs/events. [ ] Tests at right levels; coverage floor; contract/concurrency tests where needed. [ ] Docs/ADR updated (frozen docs untouched). [ ] Spotless/Checkstyle/PMD/SpotBugs/Error Prone/Sonar/deps/secrets green. **Enforcement:** PR template + required checks. **Build-Fail:** any required check red.

### R-088 — Code-review checklist (reviewer's duty)
- Reviewer verifies: correctness/failure-mode (does it fail safe & loud?), boundary adherence, invariant safety (CP-3), naming/clarity, test adequacy, and **that no reviewer approves their own boundary-crossing exception**. **Enforcement:** CODEOWNERS (`10 §16`). **Build-Fail:** n/a (process).

### R-089 — Documentation standards
- **Rule:** Javadoc on **public `api` types** (contract, not restating code); package-info for each context package; ADR for architectural decisions (R-090); READMEs per module explaining boundaries/ownership; **no misleading/stale docs**. **Why:** 10-year onboarding. **Enforcement:** Checkstyle (missing Javadoc on public api), review. **Build-Fail:** missing Javadoc on public `api` type.

### R-090 — ADR requirement (cross-ref `10 §16.4`)
- **Rule:** architectural/technology/boundary/invariant decisions require an ADR; code contradicting an Accepted ADR is blocked. **Enforcement:** review + `10 §16.4`. **Build-Fail:** n/a (process/review).

### R-091 — TODO / tech-debt policy
- **Problem:** `TODO`s rot into permanent debt. **Rule:** a `TODO` **must** reference a tracked issue (`// TODO(GATEWAY-123): …`); bare `TODO`/`FIXME`/`XXX` are **forbidden**; debt is tracked, not comment-buried. **Why:** visible, managed debt. **Bad:** `// TODO fix later`. **Enforcement:** Checkstyle/PMD regex gate. **Build-Fail:** `TODO`/`FIXME` without an issue reference.

### R-092 — Deprecation policy (cross-ref `10 §16.9`, AD-015)
- **Rule:** deprecate with `@Deprecated(since, forRemoval)` + Javadoc `@deprecated` pointing to the replacement; honor support windows (≥12mo Enterprise/≥6mo Business); removal only after window + migrated telemetry. **Enforcement:** review + deprecation tracker. **Build-Fail:** use of an internally `forRemoval` API past its removal version.

### R-093 — API compatibility policy (cross-ref `10 §16.5`)
- **Rule:** no breaking change to REST (OpenAPI), events (Avro), SDK, or plugin-API without a **major** bump + deprecation; **compat gates are build-failing** (spec-diff + registry compat). **Why:** AD-015, NFR-VER. **Enforcement:** CI compat gates. **Build-Fail:** incompatible contract change without major bump.

### R-094 — Backward compatibility within a major
- **Rule:** additive-only within a major; consumers tolerate unknown fields; envelope/schema evolution backward-compatible (`07 §9`). **Enforcement:** contract/compat tests (R-084/R-093). **Build-Fail:** backward-incompatible change on a stable contract.

### R-095 — Public API surface is minimal and intentional
- **Rule:** keep `api` packages/`public` surface as small as possible; anything not in a module's `api` is `internal` and unsupported. **Why:** less to keep compatible for 10 years (AD-015). **Enforcement:** ArchUnit (AU-02) + review. **Build-Fail:** unintended `public` on `internal` (AU-02).

---

## M. Consistency Review, Scores & Recommendation

### Internal Consistency Review (vs frozen docs)

| Frozen source | Requirement | This constitution | ✓ |
|---|---|---|---|
| AD-023/`09` | Java 21 + Spring Boot + Virtual Threads; no reactive default | R-001/R-048/R-049; AU-10 bans reactive in core | ✅ |
| AD-002 | Ports & adapters; domain independent | R-017/R-018/R-034; AU-03/05/08/09 | ✅ |
| AD-020/`06 §10` | Modular monolith, enforced module boundaries | R-014; AU-01/02/04 | ✅ |
| `06 §24`/`08` | Store-per-service, no shared DB | R-015/R-034/R-040; AU-07 | ✅ |
| AD-007/`00` | Provider independence, no SDK in core | R-018; AU-06 | ✅ |
| `07` | Envelope+Avro events, outbox, idempotent consumers, DLQ | R-042…R-047 | ✅ |
| `08 §14` | Encryption, per-tenant keys, secret non-exposure | R-068…R-070 | ✅ |
| `03` App. D / BRULE-1 | Invariants absolute; no silent failure | CP-1/CP-3; R-030/R-031/R-045/R-055/R-060/R-071 | ✅ |
| AD-015/`10 §16` | Versioning, compat, deprecation | R-092…R-095 | ✅ |
| `10 §7` | Avro/OpenAPI 3.1/JSON Schema formats | R-008/R-027/R-042 | ✅ |
| `10 §16` | Governance, CODEOWNERS, gates | R-072…R-079, R-087…R-093; §J | ✅ |
| `03` NFR-CONC/REL-002 | Concurrency correctness, isolation | R-048…R-050, R-085 | ✅ |

**No contradictions.** Every rule traces to a frozen decision; the ArchUnit suite (§J) is the executable enforcement of the frozen architecture.

### Complexity challenge (what we deliberately did NOT mandate)
- **No reactive/WebFlux** — Virtual Threads make it unnecessary complexity (AD-023).
- **No checked exceptions** — leak infrastructure, encourage swallowing (R-031).
- **No reflection mappers / broad component-scan / field injection** — hidden coupling.
- **No "every linter"** — a curated, non-redundant tool set (R-072…079) to avoid noise/duplication.
- **No coverage-as-goal** — a floor, not a target (R-080).
- Rules that only restated the compiler or the formatter were cut; each surviving rule earns its place by preventing a real, named failure class.

### Scores
- **Architecture Readiness Score: 95/100** — the constitution is a faithful, *executable* enforcement of the frozen architecture (ArchUnit §J); minor points pending is the calibration of a few thresholds (coverage %, complexity caps) after real code exists.
- **Engineering Governance Score: 94/100** — gates, checklists, ADR discipline, compat/deprecation policy, and CODEOWNERS are complete and build-failing; the residual is tooling-config execution (baselines) at repo bring-up.
- **Maintainability Score: 93/100** — boundaries-as-law + immutability + minimal public surface + no-dead-code + TODO discipline target 10-year maintainability; residual risk is human adherence, mitigated by build-failing enforcement.

### Recommendation
**FREEZE at v1.0.** The consistency review shows **no contradictions** with `00`–`10` or AD-001…023; every rule is traceable, challenged, and (where possible) machine-enforced. Thresholds marked "calibration" (coverage %, complexity caps, perf baselines) are tunable **within** this constitution without changing its law, and are finalized in `15-Testing-Standards.md`/`32`.

---

## Appendix
**A. Rule → enforcement quick map.** Compile-time: Error Prone/NullAway (R-003/007/063/076). Bytecode: SpotBugs/FindSecBugs (R-050/051/067/070/075). Style/smell: Checkstyle/PMD (R-002/011/012/023/073/074/091). Aggregate gate: Sonar (R-077). Architecture: ArchUnit §J. Supply-chain: deps/license/SBOM + secret scan (R-078/079). Tests: JUnit5/Mockito/Testcontainers/contract/concurrency/fault-injection (R-080…086).
**B. Relationship to other docs.** Implements the stack/structure of `09`/`10`, enforces the architecture of `04`/`06`, the events of `07`, the data rules of `08`, and the invariants of `03`. Feeds `12`–`16` (which detail API/security/observability/testing/deployment standards) and every module doc (`17`–`28`).
**C. Maintenance.** Living until frozen on review. New rules carry the full field template + a traceability entry and, where enforceable, a build-failing check. CP-1/CP-2/CP-3 and the AU-01…12 suite are stable law; weakening them requires revisiting the frozen documents.

---

*End of document — 11-Coding-Standards.md*
