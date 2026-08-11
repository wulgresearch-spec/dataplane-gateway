# 16 — Deployment & Operations Standards (The Deployment Constitution)

**Document:** Deployment & Operations Standards & Governance
**Project:** Reliability-First AI Gateway
**Status:** **FROZEN**
**Version:** 1.0 (frozen 2026-07-21)
**Audience:** Every platform/SRE/DevOps engineer, release manager, security engineer, compliance officer, on-call responder, auditor
**Classification:** Internal — Engineering Standard
**Builds on (approved, frozen):** `00`–`15` and ADRs **AD-001…AD-023**. Nothing here may contradict them.
**Deployment targets:** Regulated multi-tenant SaaS · Single-tenant / dedicated · On-premises (customer-managed) · Multi-cloud.
**Stack (from `09`):** Kubernetes · Docker/OCI · Helm · Terraform · GitHub Actions · Argo CD/Flux (GitOps) · SPIFFE/SPIRE · Cloud KMS/HSM + Vault/OpenBao · Cosign/Sigstore · Syft/Grype (SBOM/scan) · Prometheus + OTel (`14`) · PostgreSQL/MongoDB/Valkey/Kafka/S3-WORM (`08`).

> **What this is.** The permanent law for how the platform is **built into artifacts, promoted across environments, released into production, operated, and recovered** — without ever violating a reliability, isolation, residency, correctness, or compliance guarantee established in `00`–`15`. Deployment is where architecture meets reality; a reliability-first platform that deploys carelessly is not reliable. Every major standard carries **Problem · Rule · Why · Good · Bad · Exceptions · Enforcement · Build-Fail**; examples illustrate the standard, never product infrastructure. Where a rule adds ceremony without buying safety, it is cut.
>
> **The three deployment principles (everything derives from these):**
> - **DP-1 — A deployment must never be able to break an invariant.** Isolation, no-silent-delivery, audit integrity, residency confinement, secret/data non-exposure, and no-bypass (03 App. D) survive every rollout, rollback, scale event, and region failover — by construction, not by discipline.
> - **DP-2 — Every change to production is progressive, observable, and reversible.** No big-bang. No un-instrumented rollout. No forward-only change without a tested path back (or a deliberately-governed exception).
> - **DP-3 — If a deployment safety property cannot fail the pipeline, it is not guaranteed.** Signing, SBOM, admission policy, migration compatibility, residency pinning, and health gating are machine-enforced gates, not review checklists.

---

## Table of Contents
**A. Philosophy & Principles** (D-001…D-004) · **B. Kubernetes & Topology** (D-005…D-012) · **C. Service Deployment Model** (D-013…D-015) · **D. Packaging & Supply Chain** (D-016…D-022) · **E. GitOps, Pipeline & Progressive Delivery** (D-023…D-031) · **F. Data, Config & Identity** (D-032…D-039) · **G. Health, Probes & Scaling** (D-040…D-046) · **H. DR, Backup & Residency** (D-047…D-051) · **I. IaC, Environments & Release** (D-052…D-057) · **J. Operations & Governance** (D-058…D-065) · **K. Reviews**

---

## A. Philosophy & Principles

### D-001 — Deployment philosophy
- **Problem:** most outages are self-inflicted — introduced by a deploy, config change, or migration, not by organic load. **Rule:** deployment exists to move change into production **safely, progressively, observably, and reversibly**, and to make an unsafe change **impossible to ship** rather than merely discouraged. Infrastructure is code (`09`/Terraform), reviewed and tested like product code. **Why:** in a reliability-first product the deploy path is itself a reliability surface (AD-016, `03`). **Good:** every prod change flows through GitOps + progressive delivery + automated gates. **Bad:** `kubectl apply` by hand to fix prod. **Exceptions:** break-glass only, governed (D-065, `13 §29.2`). **Enforcement:** GitOps as sole prod mutator (D-023); admission control. **Build-Fail:** any prod-mutating path outside the governed pipeline.

### D-002 — Reliability-first deployment principles
- **Rule:** every deployment mechanism is designed so that **invariants hold across the whole lifecycle** — during rollout, mid-canary, during rollback, under partial failure, during region failover, and during scale in/out. The **data plane keeps serving on last-known-good** when the control plane is unavailable (AD-022); a control-plane deploy **never** takes the request path down (AD-020/AD-017). **Why:** the guarantees are continuous, not steady-state-only (CP-1, DP-1). **Enforcement:** progressive delivery + health gates + isolation/residency admission. **Build-Fail:** a rollout strategy that can drop the request path or bypass a guarantee (validated in `15` T-049/T-051).

### D-003 — Separation of build, release, and run (12-factor discipline)
- **Rule:** **build** (immutable signed artifact), **release** (artifact + environment config, versioned), and **run** are strictly separated; the **same image** promotes dev→staging→prod (build once, deploy many); environment differences live only in config/secrets (D-034/D-035), never in the image. **Why:** promotion-parity kills "works in staging" drift (`15` T-058). **Good:** `gateway-dataplane:1.4.2` promoted unchanged with per-env config overlays. **Bad:** rebuilding the image per environment. **Enforcement:** digest-pinned promotion (D-051/E). **Build-Fail:** an environment running an image digest not promoted from the prior stage.

### D-004 — Everything as code, nothing by hand
- **Rule:** clusters, namespaces, network policy, RBAC, deployments, config, secrets wiring, DNS, and scaling policy are **declarative and version-controlled** (Terraform + Helm/Kustomize + GitOps); manual mutation of production is prohibited outside break-glass. **Why:** auditability, reproducibility, rollback (`13 §19`, `10 §16`). **Enforcement:** drift detection reconciles/alerts; manual change reverted by GitOps. **Build-Fail:** detected out-of-band drift on a protected resource.

---

## B. Kubernetes & Topology

### D-005 — Kubernetes deployment standards
- **Problem:** ungoverned K8s usage becomes an inconsistent, insecure sprawl. **Rule:** all workloads run on **Kubernetes** (`09`) under a hardened baseline: **Pod Security Standards = restricted**, non-root, read-only root filesystem, dropped capabilities, seccomp `RuntimeDefault`, no host network/PID/IPC, no privileged containers; every workload declares resources (D-044), probes (D-041–043), and a `PodDisruptionBudget`. **Why:** the platform hosts regulated multi-tenant data (`13`, AD-021). **Good:** PSS-restricted namespace with enforced admission. **Bad:** a privileged sidecar "for convenience." **Exceptions:** a documented, time-boxed exception per D-065 (never for tenant-data workloads). **Enforcement:** admission policy (Kyverno/Gatekeeper) — deny at admission; verified in `15` T-051. **Build-Fail:** any manifest violating the restricted baseline (policy test in CI).

### D-006 — Namespace strategy
- **Problem:** flat namespaces blur blast radius and isolation. **Rule:** namespaces segment by **plane and function**, not by tenant on shared SaaS (tenancy is enforced in-app per AD-021, not by namespace): e.g. `gw-dataplane`, `gw-control-<service>`, `gw-platform` (SPIRE, ingress), `gw-observability`, `gw-data` (operators). Each namespace carries **default-deny NetworkPolicy**, scoped RBAC, resource quotas, and PSS labels. **Dedicated/on-prem single-tenant** deployments MAY map one tenant to one namespace/cluster. **Why:** blast-radius containment + least privilege (`13`). **Good:** default-deny + explicit allow between `gw-dataplane` and only its required control services. **Bad:** one `default` namespace for everything. **Exceptions:** governed. **Enforcement:** namespace-provisioning module (Terraform) + policy. **Build-Fail:** a namespace without default-deny NetworkPolicy, quota, and PSS labels.

### D-007 — Cluster topology
- **Rule:** clusters are **regional**, each spanning **≥3 availability zones** (D-008); control-plane services and the data plane are co-scheduled per region; node pools are **segmented by workload class** (data-plane / control-plane / data-store-adjacent / system) with taints/tolerations; cluster autoscaler enabled. Management/tooling (GitOps controller, SPIRE root, CI runners) runs in a **separate management cluster/plane** from tenant-serving workloads. **Why:** fault isolation + least privilege + clean blast radius (AD-017, `04`). **Enforcement:** Terraform cluster modules; conformance test. **Build-Fail:** a production cluster with < 3 AZs or without node-pool segmentation.

### D-008 — Multi-AZ deployment (in-region HA)
- **Problem:** single-AZ loss should be a non-event. **Rule:** every production workload is spread across **≥3 AZs** via `topologySpreadConstraints` (and anti-affinity for singletons); stateful backends (`08`) run with **synchronous quorum across AZs** where the store's durability class requires it (audit/relational SoR); a single-AZ failure must not breach SLOs. **Why:** AZ loss is routine; `03` availability targets assume it (NFR-AV). **Good:** `maxSkew: 1` spread across 3 AZs + PDB. **Bad:** all replicas landing on one AZ/node. **Exceptions:** dev/test only. **Enforcement:** admission (spread required) + chaos AZ-kill (`15` T-019). **Build-Fail:** a prod workload without multi-AZ spread constraints.

### D-009 — Multi-region deployment
- **Rule:** production is deployed to **multiple regions**, each region **independently serviceable** and **residency-scoped** (AD-014): a region serves only tenants permitted to it; **no request-path cross-region dependency** on the hot path (a region can serve with its peers unreachable). Cross-region is limited to **id-only correlation** (`14 §18.1`), asynchronous replication of permitted data, and control/config distribution. **Why:** regional isolation + residency + availability (AD-014, `08 §16`, `04`). **Good:** EU tenants served wholly within EU regions; failover only to EU peers. **Bad:** a US region synchronously calling an EU service on the request path. **Exceptions:** none for residency-constrained data. **Enforcement:** residency admission (D-051) + topology tests (`15 §I.1`). **Build-Fail:** a hot-path cross-region call, or a region configured to serve a non-permitted residency scope.

### D-010 — High availability (no single point of failure)
- **Rule:** every request-path component runs **≥ N+1 redundant** across AZs with a `PodDisruptionBudget` guaranteeing minimum availability during voluntary disruption; **no singletons on the request path**; leader-elected control components tolerate leader loss without request-path impact (AD-022 last-known-good). Ingress/load balancing is redundant and health-aware. **Why:** availability is a product SLA (`02`, `03` NFR-AV). **Good:** data plane min 3 replicas, PDB `minAvailable: 2`. **Bad:** a request-path deployment with `replicas: 1`. **Exceptions:** dev/test. **Enforcement:** admission (min-replicas/PDB on request-path labels) + chaos. **Build-Fail:** a request-path workload with replicas < N+1 or no PDB.

### D-011 — Deployment isolation & blast radius
- **Rule:** deployments are **independently deployable per control service** (AD-017) and the **data plane deploys as one unit** (AD-020); a failed deploy of one control service must not cascade (bulkheads, independent rollout, circuit breaking); tenant isolation (AD-021) is never a function of deployment topology — it is enforced in-app and cannot be weakened by any rollout. **Why:** independent evolution + contained failure (`06`). **Enforcement:** per-service pipelines (`10` release trains) + isolation tests (`15` T-044). **Build-Fail:** a deploy coupling that forces lock-step release of independent services (contradicts `06`).

### D-012 — On-prem & air-gapped topology
- **Rule:** the platform supports **customer-managed / air-gapped** installation: all images from a **customer-controlled registry**, no outbound calls required for core operation, offline SBOM/signature verification, and Terraform/Helm profiles for constrained environments; residency/isolation guarantees hold identically. **Why:** regulated/on-prem buyers (deployment targets). **Enforcement:** air-gap install test in `15` (K8s install, offline). **Build-Fail:** a core component requiring public egress to start.

---

## C. Service Deployment Model

### D-013 — Service deployment model (the frozen shape — not re-decided here)
- **Rule:** the deployable topology is exactly as frozen in `06`/`04`: **1 Data Plane deployable** (co-located modular monolith, AD-020/AD-006) **+ 13 independent Control-Plane services** (AD-017) = 14 deployables; this document specifies **how** they deploy, never **what** they are. Each deployable has its own release train (`10`), pipeline, and rollback unit. **Why:** honor the architecture (CP-2). **Enforcement:** deployable inventory checked against `06`. **Build-Fail:** a new deployable or a merge/split of frozen deployables introduced via deployment config (that is an architecture change, out of scope here).

### D-014 — Data Plane deployment
- **Problem:** the data plane is the request-critical hot path; its deploy risk is the platform's risk. **Rule:** the Data Plane is **stateless** (AD-006/AD-020) and deploys via **rolling or canary** (D-025/D-030) with **zero downtime** (D-031); it holds **cached config/policy snapshots** and **serves on last-known-good** if the control plane is unavailable during/after deploy (AD-022); it starts fast (startup probe D-041), drains gracefully (`preStop` + connection draining, honoring in-flight streams `12 §16`), and never blocks shutdown on a stuck upstream. **Why:** the request path must never blink (AD-018, `03` NFR-AV/PERF). **Good:** canary 5%→25%→100% gated by SLO/error-budget (`14`, `03 §61`). **Bad:** recreate strategy that drops in-flight streams. **Exceptions:** none in prod. **Enforcement:** strategy admission + `15` T-049 zero-downtime test + stream-drain test. **Build-Fail:** data-plane manifest with `Recreate`, no `preStop` drain, or no startup probe.

### D-015 — Control Plane deployment
- **Rule:** each of the 13 control services deploys **independently** (rolling default; blue/green or canary where it has external consumers, `12`); a control-plane outage or bad deploy **degrades gracefully** (data plane on last-known-good, AD-022) and **never** breaks an invariant; stateful control services (owning stores per `08`) deploy with migration discipline (D-032) and the **quorum-safe choreography of §C.1**. **Why:** independent evolution without request-path coupling (AD-017/AD-022). **Good:** Policy service canary with config-snapshot backward-compat verified. **Bad:** a control-service deploy that invalidates active data-plane snapshots incompatibly. **Exceptions:** governed. **Enforcement:** per-service pipeline + snapshot-compat check (`07`/`08` contracts). **Build-Fail:** a control-plane release that breaks snapshot/API/event backward-compat within a major (`12 §27`, `07 §9`).

### §C.1 — Stateful Control-Service Rollout Choreography *(resolves DH-2; expands D-015/D-032 — additive, no architecture change)*

Control services that own a store (`08` store-per-service) are the highest-risk deploy case: rollout must couple **quorum preservation** + **expand-then-contract migration** + **snapshot/API/event backward-compat** without ever dropping availability or breaking N-1. The following **nine-phase sequence is mandatory** for any stateful control-service release; **every phase has an explicit pass/fail gate and a rollback checkpoint**, and every phase is testable in the staging pipeline (`15` T-049/T-050) before prod.

| # | Phase | Actions | Pass gate (testable) | Rollback checkpoint |
|---|---|---|---|---|
| **S-1** | **Pre-deployment validation** | Verify artifact (signed+SBOM D-018/19), config schema (D-034), and that the release is **N-1 backward-compatible** at API (`12 §27`), event (`07 §9`), and config-snapshot (AD-022) contracts | All compat gates green; artifact admitted | *None yet* — abort is free (nothing changed) |
| **S-2** | **Snapshot/backup verification** | Confirm a **fresh, integrity-verified backup** exists for the owned store (D-048) and a **restore has been validated** (D-049); capture a labeled **pre-migration recovery point** | Backup present + integrity-verified + recovery point recorded | **CP-A: restore-to-recovery-point** is the ground-truth fallback |
| **S-3** | **Schema compatibility verification (expand)** | Apply **expand-only** migration (additive, D-032) as a **decoupled job** (app never blocks on DDL); verify old schema readers still pass | Expand migration idempotent + reversible; N-1 code green against new schema (`15` T-050) | **CP-B: contract-expand** (drop the additive change — safe because nothing depends on it yet) |
| **S-4** | **Quorum preservation check** | Confirm the store cluster is at **full quorum/health** *before* touching any replica; deploy proceeds **only** with quorum headroom to tolerate one replica down | Quorum healthy; `maxUnavailable` bounded so quorum never lost | **CP-B** still valid |
| **S-5** | **Rolling update order (one replica at a time, followers→leader)** | Update **followers/non-leaders first**, one at a time; for each: drain, update, rejoin, **resync to quorum** before the next; update the **leader last** via controlled step-down (leader election hands off, AD-022 keeps data plane on last-known-good throughout) | Each replica rejoins quorum healthy before next; leader step-down non-disruptive; **zero request-path impact** (D-031) | **CP-C: per-replica** — a replica failing to rejoin halts the rollout with quorum still intact; revert that replica to N-1 |
| **S-6** | **Health verification (per step + aggregate)** | After each replica and after leader handoff, verify readiness (D-042), quorum, replication lag within bound, and golden signals (`14`) | All green at each step; replication caught up | **CP-C** per step |
| **S-7** | **Post-deployment validation** | Run smoke + contract (`15` T-008/T-011) + isolation (`15` T-044) + snapshot-compat against the fully-updated service | Full post-deploy suite green | **CP-D: full rollback** to N-1 artifact+config (git-revert, D-029) — still N-1-compatible because contract (S-8) has not run |
| **S-8** | **Contract migration (only after bake)** | **After a defined bake window** confirming N is healthy and N-1 is fully retired, apply the **contract** migration (remove old columns/fields) as a separate, later release — **never in the same rollout as expand** | Bake window elapsed + no N-1 consumers remain + contract migration reversible-by-restore | **CP-A** (restore) becomes the fallback once contract runs; this is the **one-way gate**, entered only deliberately (D-032 exceptions) |
| **S-9** | **Post-contract validation & recovery-point refresh** | Verify service health post-contract; capture a **new clean backup/recovery point**; close the deploy audit record (D-061) | Healthy + new recovery point recorded | New recovery point is the go-forward baseline |

**Invariants of the choreography (all testable in staging, `15`):**
- **N-1 compatibility holds through S-1…S-7** — at every moment during rollout, mixed N/N-1 replicas and mixed N/N-1 readers interoperate; the contract step (S-8) runs **only after N-1 is gone**.
- **Quorum is never lost** (S-4/S-5): `maxUnavailable` and update order guarantee the store keeps quorum throughout.
- **Data plane never blinks** (AD-022): last-known-good snapshots serve across the entire sequence, including leader handoff.
- **Every phase before S-8 is fully reversible** without data loss; S-8 is the sole deliberate one-way gate, guarded by the S-2 recovery point.
- **Enforcement:** the sequence is encoded as the stateful-service rollout template + verified by `15` T-049 (zero-downtime) and T-050 (expand-then-contract) in staging before prod. **Build-Fail:** a stateful control-service rollout that runs expand+contract in one release, updates the leader before followers, proceeds without a verified backup/recovery point (S-2), or breaks quorum/N-1 at any step.

---

## D. Packaging & Supply Chain

### D-016 — Container standards
- **Problem:** fat, root, mutable images are an attack and reliability surface. **Rule:** images are **minimal** (distroless/UBI-micro or equivalent), **non-root** (numeric UID), **read-only root FS**, single concern per image, **multi-stage build**, pinned base by **digest**, no secrets/build creds baked in, OCI labels (source commit, build id, version). Java images use a JRE-only/`jlink` runtime for the Java 21 workloads (AD-023). **Why:** small trusted surface (`13`). **Good:** distroless non-root, digest-pinned base. **Bad:** `FROM ubuntu:latest` running as root with `curl | sh`. **Exceptions:** governed, scanned. **Enforcement:** Dockerfile lint + image scan (Grype) + admission (non-root/read-only). **Build-Fail:** root user, `:latest`/untagged base, secret detected in layers, or image scan critical (`13 §28`).

### D-017 — Image build policy
- **Rule:** images are built **only by CI** (GitHub Actions, `10 §15`) from a clean checkout — **never** from a developer laptop for any promotable environment; builds are **reproducible/hermetic** where feasible, tagged with **immutable semantic version + git SHA + digest**; `:latest` is banned for deploy references. **Why:** provenance + reproducibility (`10`, supply chain). **Good:** `gateway-dataplane:1.4.2@sha256:…`. **Bad:** locally-built image pushed to prod registry. **Enforcement:** registry write restricted to CI identity; provenance attestation (SLSA-style). **Build-Fail:** an image without CI provenance attestation deployed to staging/prod.

### D-018 — Image signing and verification
- **Rule:** every image is **cryptographically signed** (Cosign/Sigstore) in CI; clusters **admit only signed images** from trusted CI identities with **verified provenance** (keyless OIDC or KMS-backed keys); verification works **offline** for air-gapped installs (D-012). **Why:** prevent unauthorized/tampered images (`13 §21`, `15 T-051`). **Good:** admission controller rejects unsigned/untrusted-signer images. **Bad:** pulling an unsigned third-party image onto a tenant-serving node. **Exceptions:** none in staging/prod. **Enforcement:** signature-verifying admission policy. **Build-Fail:** an unsigned or untrusted-signature image reaching any shared/prod cluster.

### D-019 — SBOM requirements
- **Rule:** CI generates a **complete SBOM** (SPDX/CycloneDX via Syft) per image, **signed and attested** (attached to the image); SBOMs are **retained and queryable** for the artifact's supported life to answer "are we affected by CVE-X?" in minutes; deploy requires an SBOM present. **Why:** supply-chain transparency + rapid CVE response (`13 §28`). **Good:** signed CycloneDX attestation queried on zero-day disclosure. **Bad:** no bill of materials for a running image. **Exceptions:** none. **Enforcement:** pipeline gate + admission (SBOM attestation required). **Build-Fail:** image without a signed SBOM attestation.

### D-020 — Vulnerability & license gating
- **Rule:** images and dependencies are scanned (Grype/Trivy + `15` T-029) at build and **continuously** against new CVE feeds; **critical/high CVEs block deploy**; disallowed licenses block build (`10 §16`); running images are re-scanned so a newly-disclosed critical triggers remediation SLA (`13 §24`). **Why:** the exploit window is measured in hours. **Enforcement:** CI gate + continuous scan + admission. **Build-Fail:** critical CVE (or disallowed license) on a promoted image. *(Offline/air-gapped equivalents: §D.1.)*

### §D.1 — Air-Gapped / Offline Supply-Chain Path *(resolves DH-3; expands D-012/D-016–020/D-035–038 — additive, keeps on-prem fully supported)*

Regulated and on-premises buyers require the **full supply-chain guarantee with zero egress**. Every online mechanism in Part D/F has a **specified offline equivalent**; the guarantee is identical, only the distribution mechanism differs. An air-gapped install that satisfies §D.1 is a **first-class, fully-supported deployment target** (D-012), not a degraded mode.

| # | Online mechanism | **Offline equivalent (required)** |
|---|---|---|
| AG-1 | **Offline artifact repository** — public image/chart registries | A **customer-controlled mirror registry** (images, Helm charts, Terraform modules, dependency artifacts) populated from a **signed, verified release bundle** transferred via approved media; deploys pull **only** from the internal mirror; no public pull path exists |
| AG-2 | **Offline image-signature verification** — keyless Sigstore/Fulcio (needs egress) | **KMS/HSM-key Cosign signatures** (or an air-gap Sigstore with a **mirrored Rekor/trust root** in the release bundle); admission verifies against the **locally-distributed trust bundle** (AG-7) — verification is fully offline |
| AG-3 | **Offline SBOM verification** — remote attestation lookup | SBOM (SPDX/CycloneDX) + its signature **shipped inside the release bundle**, attached to the mirrored image; verified locally against the local trust bundle; deploy still requires SBOM present (D-019) |
| AG-4 | **Offline CVE database** — live vuln feeds | A **mirrored vulnerability database** (Grype/Trivy DB) delivered as a **signed, dated feed bundle** on a defined cadence; scans run against the local DB; **feed staleness is monitored and alarmed** (an over-age vuln DB is itself a finding) — the guarantee (critical CVE blocks deploy) holds offline |
| AG-5 | **Offline license validation** — online license/policy services | License data and allow/deny policy **bundled and versioned** with the release; validation runs locally in CI/admission; disallowed license still blocks (D-020) |
| AG-6 | **Offline admission policy** — policy engine with external data | Admission policies (PSS-restricted, signed-only, non-root, network-policy, residency D-051) are **self-contained bundles** evaluated by the in-cluster policy engine with **no external data dependency**; policy bundles are signed and versioned |
| AG-7 | **Offline trust-bundle distribution** — public CA / OIDC | The **root trust bundle** (signing roots, internal CA roots, SPIRE trust root) is distributed via **signed offline bundles** and installed into clusters out-of-band; rotation of the bundle is a governed, audited offline procedure |
| AG-8 | **Offline certificate rotation** — public ACME / online CA | An **internal CA (cert-manager + SPIRE, D-037/D-038)** issues/renews all certs **in-cluster**; short-lived SVIDs and TLS certs rotate with **no external CA dependency**; expiry monitoring (D-037) runs locally with lead-time alarms |

**Rules & guarantees:**
- **No-egress operation (upholds D-012):** a compliant air-gapped install starts, verifies, scans, admits, and rotates **without any outbound network call**; a core component requiring public egress to operate is a Build-Fail (D-012).
- **Release-bundle integrity:** the transferred bundle (images + charts + SBOMs + signatures + vuln DB + policies + trust roots) is itself **signed and integrity-verified** on ingest before anything is admitted.
- **Freshness governance:** offline CVE DB, license data, and trust bundles carry versions/dates; **staleness beyond policy is alarmed** and gates promotion (an air-gapped site cannot silently run on a year-old vuln DB).
- **Parity of guarantees:** signing, SBOM, CVE-gating, license-gating, admission, residency, and mTLS guarantees are **identical** to the connected profile — regulated/on-prem deployments remain fully supported (deployment targets).
- **Enforcement:** an **air-gap install-and-verify test** (`15` T-051, offline profile) proves AG-1…AG-8 with egress disabled. **Build-Fail:** any core component requiring egress; a bundle admitted without integrity verification; an over-age vuln-DB/trust-bundle promoted past its staleness policy; an offline admission/signature/SBOM check absent.

### D-021 — Helm standards
- **Problem:** unstructured Helm becomes unmaintainable and unsafe. **Rule:** each deployable ships a **versioned, linted Helm chart** (`gateway-*`, umbrella `gateway-platform` per `10`) with: `values.schema.json` (schema-validated values), sane secure defaults (PSS-restricted, probes, resources, PDB, NetworkPolicy), **no secrets in values** (D-034), pinned image **digests**, chart version = app version discipline, and `helm template` policy-tested in CI. Charts are **rendered and diffed** before apply (GitOps). **Why:** reproducible, reviewable, safe releases (`09`/`10`). **Good:** `values.schema.json`-validated chart, `helm lint` + policy test green. **Bad:** a chart templating a plaintext secret or `:latest`. **Exceptions:** governed. **Enforcement:** `helm lint` + schema validation + policy (`15` T-051). **Build-Fail:** chart lint/schema failure, secret in values, or unpinned image.

### D-022 — Kustomize policy (overlays, not forks)
- **Rule:** environment/region variation is expressed as **Kustomize overlays** (or Helm value overlays) over a **single base** — dev/test/staging/prod and per-region overlays patch only config, replicas, resources, residency scope, and endpoints; **no divergent base per environment** (upholds build-once/promote D-003). Overlays are reviewed; prod overlays are protected. **Why:** parity + auditable difference (`15` T-058). **Good:** one base + `overlays/prod-eu` patching residency + scale. **Bad:** a hand-maintained separate prod manifest tree. **Exceptions:** governed. **Enforcement:** structure lint + render-diff review. **Build-Fail:** an environment deployed from a base not shared with other environments.

---

## E. GitOps, Pipeline & Progressive Delivery

### D-023 — GitOps workflow (git is the source of truth)
- **Problem:** imperative changes are unauditable and drift. **Rule:** **desired state lives in git**; a GitOps controller is the **sole mutator of production** — it reconciles cluster state to the signed, reviewed repo state; humans change prod **only** by merged PR (`10 §16` protected branches, CODEOWNERS, required checks). Drift is auto-corrected or alerted. **Why:** auditable, reviewable, reversible, self-healing (DP-1/DP-2, `13 §19`). **Good:** prod change = approved PR → controller syncs → recorded. **Bad:** `kubectl edit` on prod. **Exceptions:** break-glass (D-065), auto-reverted after. **Enforcement:** cluster RBAC denies human write; controller owns apply. **Build-Fail:** a human-writable prod credential path outside break-glass; unreconciled drift on protected resources.

### §E.1 — GitOps & Progressive Delivery: Capability Standard, Not Tool Mandate *(resolves DH-1; expands D-023/D-025–028 — additive, introduces no ADR)*

**§E.1.0 — Architecture-neutrality (consistency with AD-002).** GitOps and progressive delivery are specified here as **required capabilities**, not products. Any implementation satisfying the capability contracts below is compliant; named products are **examples**, freely replaceable. This is fully consistent with **AD-002 (hexagonal ports & adapters / replaceable implementations)** — the delivery controller is an *adapter* behind a capability contract, exactly like a provider SDK or a persistence engine. **Selecting a specific controller is a tooling decision recorded in the operations/tooling standard, not an architecture decision — this section introduces no new ADR and modifies none.**

**§E.1.1 — GitOps capability contract (any implementation MUST provide):**
| # | Required capability | Compliant examples |
|---|---|---|
| GC-1 | Declarative desired-state reconciliation from a signed git source | Argo CD, Flux, *equivalent* |
| GC-2 | Continuous drift detection + auto-correct/alert on protected resources | Argo CD, Flux, *equivalent* |
| GC-3 | Sole-mutator enforcement (human prod-write denied by RBAC) | any controller + cluster RBAC |
| GC-4 | Signed-commit / verified-source gating before sync | Argo CD (GPG), Flux (verify), *equivalent* |
| GC-5 | Audit trail of every sync (who/what/when/digest) → `08`/`13 §19` | any + audit sink |
| GC-6 | Multi-cluster / multi-region reconciliation (D-009) | Argo CD, Flux, *equivalent* |

**§E.1.2 — Progressive-delivery capability contract (any implementation MUST provide):**
| # | Required capability | Compliant examples |
|---|---|---|
| PD-1 | Canary and/or blue-green traffic shaping (D-026/D-027) | Argo Rollouts, Flagger, service-mesh-native, *equivalent* |
| PD-2 | Automated metric analysis gating each promotion step (SLOs + correctness signals, §E.2) | Argo Rollouts AnalysisTemplate, Flagger MetricTemplate, *equivalent* |
| PD-3 | Automatic abort + rollback on breach (D-029) | any implementation of PD-1/PD-2 |
| PD-4 | Isolation- and residency-safe cohorting (canary stays in-region/in-scope, D-027/D-051) | any + scoped routing |
| PD-5 | Emission of rollout events/markers for deploy observability (D-060) | any + `14` integration |

**§E.1.3 — Fallback when progressive-delivery tooling is unavailable.** Progressive delivery is a *safety requirement*, not a tool; if a PD-1…PD-5 implementation is absent (e.g., minimal on-prem/air-gapped install, D-012), the platform **degrades safely, never to big-bang**:
1. **Preferred fallback — manual-gated rolling (D-028):** `maxUnavailable: 0` surge-then-drain rolling update executed in **explicit staged increments** (e.g., cohort-by-cohort) with a **mandatory human gate + health/SLO verification (`14`) between increments**, recorded in the deploy audit (D-061). This preserves DP-2 (progressive, observable, reversible) without an automation controller.
2. **Hard rule:** a **single-step 100% cutover on the request path is prohibited** regardless of tooling (D-025/D-028 Build-Fail still applies). Absence of automation lowers *speed*, never *safety*.
3. **Rollback** remains the git-revert path (D-029) and the tested emergency path (D-030), which require no PD tooling.
- **Enforcement:** capability-conformance check in the environment's release-acceptance (the environment declares which GC-/PD- capabilities it provides; a request-path prod rollout without at least the manual-gated fallback fails). **Build-Fail:** a request-path prod rollout with neither automated progressive delivery nor the manual-gated staged fallback.

### D-024 — CI/CD deployment pipeline (extends `10 §15`, `15 §J.1`)
- **Rule:** the delivery pipeline is fixed and gated: **build+sign+SBOM+scan** → **publish immutable artifact** → **deploy to ephemeral/dev** → **automated verification (`15` gates)** → **promote to staging** → **progressive prod rollout (D-025)** with SLO gating → **post-deploy verification**. Promotion is **digest-pinned** (D-003) and **gated** (D-059); every stage emits deployment telemetry (D-062) and an audit record (D-063). **Why:** one governed path, many gates (DP-3). **Enforcement:** pipeline-as-code + required checks (`10 §16`). **Build-Fail:** any promotion skipping a required gate, or promoting a non-identical digest.

### D-025 — Progressive delivery (default = never big-bang)
- **Problem:** all-at-once releases turn a latent bug into a total outage. **Rule:** production rollouts are **progressive** — canary or blue/green for consumer-facing/high-risk, rolling for the rest — **automatically gated on SLOs and error budgets** (`03 §61`, `14`): traffic advances only while golden signals stay in bounds and **auto-halts/auto-rolls-back** on breach. **Why:** contain blast radius; catch regressions at 5%, not 100% (DP-2). **Good:** Argo Rollouts canary with Prometheus analysis gates. **Bad:** `kubectl set image` to 100% at once. **Exceptions:** governed emergency fix (still progressive where possible). **Enforcement:** rollout controller + analysis templates. **Build-Fail:** a prod Deployment strategy without progressive gating on a request-path/consumer-facing workload.

### D-026 — Blue/Green deployments
- **Rule:** blue/green is used where **instant, atomic cutover + instant rollback** matter (e.g. a control service with schema-coupled consumers): stand up green, verify (smoke + health + contract), **shift traffic atomically**, keep blue warm for immediate rollback for a defined bake window, then retire. Data compatibility across blue/green is guaranteed by expand-then-contract (D-032). **Why:** fast, reversible cutover. **Good:** green verified, cut over, blue retained 1 bake cycle. **Bad:** deleting blue before the bake window ends. **Exceptions:** governed. **Enforcement:** rollout template + bake-window gate. **Build-Fail:** blue torn down before the required bake/verify window.

### D-027 — Canary deployments
- **Rule:** canary shifts a **small traffic slice** to the new version and **auto-analyzes** golden signals + correctness/streaming/error-budget metrics (`14`, `15`) before each promotion step; **automatic rollback** on any breach; canary honors tenant isolation (a canary cohort never mixes tenants unsafely) and residency (a canary in a region stays in-region). **Why:** statistically detect regressions cheaply (DP-2). **Good:** 5%→25%→50%→100% with per-step analysis pauses. **Bad:** a "canary" with no automated analysis or abort. **Exceptions:** governed. **Enforcement:** analysis templates wired to Prometheus/SLOs. **Build-Fail:** a canary without automated analysis + auto-abort. *(Objective correctness-signal gating: §E.2.)*

### §E.2 — Correctness-Signal Canary Gating *(resolves DM-1; expands D-025/D-027 — additive)*

Latency and error-rate alone do **not** catch a *correctness* regression (a canary can be fast and 200-OK while silently degrading structured-output or streaming integrity — the platform's core value, AD-018). Canary analysis therefore gates on **objective correctness signals** (sourced from `14` telemetry + `15` guarantees), each with an automatic-abort threshold expressed **relative to the stable baseline** and configurable per the operational baseline (§I.1):

| Signal (from `14`) | Gate condition (abort + auto-rollback if…) | Ties to |
|---|---|---|
| **Structured-output conformance rate** | canary conformance drops below baseline by more than the configured delta | `03` NFR-SO, `15` T-013 |
| **Silent-incorrect-delivery / escape count** | **any** increase above baseline (zero-tolerance signal) | AD-018, `15` T-018 |
| **Streaming integrity** (silent-truncation / invalid `tool_call.done` / partial-JSON) | **any** occurrence in the canary cohort | `12 §16`, `15` T-012 |
| **Error-normalization leak rate** (provider-native leak) | any increase above baseline | `12 §16.10` |
| **Retry/idempotency anomaly** (duplicate side effect, retry-storm) | any increase above baseline | `15` T-017 |
| **Golden signals** (P99 latency, 5xx, saturation) | breach of SLO/error-budget burn thresholds (`03 §61`) | `14 §15` |
| **Isolation signal** (cross-tenant indicator in canary telemetry) | **any** occurrence → immediate abort (invariant) | AD-021, `15` T-044 |

- **Objective + automatic:** each threshold is a machine-evaluated analysis metric; **breach triggers automatic abort and rollback (D-029)** with no human in the loop; zero-tolerance signals (escape, silent truncation, isolation) abort on the **first** occurrence, not on a rate.
- **Baseline-relative:** thresholds compare canary vs. the concurrently-running stable version (not absolute magic numbers), so they self-calibrate to real traffic; the exact deltas/burn-rates live in the operational baseline (§I.1), not hard-coded here.
- **Enforcement:** canary AnalysisTemplate/MetricTemplate (PD-2, §E.1.2) MUST include the correctness signals above, not only latency/error. **Build-Fail:** a request-path canary whose analysis omits the structured-output/streaming/escape/isolation correctness signals.

### D-028 — Rolling deployments
- **Rule:** rolling is the **baseline** for stateless workloads (data plane, stateless control services): `maxUnavailable: 0` / `maxSurge ≥ 1` (surge-then-drain) to preserve capacity, readiness-gated (D-042), PDB-bounded, AZ-spread-preserving. **Why:** zero-downtime default for stateless (AD-006). **Good:** surge new pod, pass readiness, drain old. **Bad:** `maxUnavailable: 50%` on the request path. **Exceptions:** none on request path. **Enforcement:** strategy admission. **Build-Fail:** a request-path rolling update permitting unavailability (`maxUnavailable > 0`).

### D-029 — Rollback policy
- **Problem:** if rollback is slow or untested, forward-only becomes forced-outage. **Rule:** every release is **reversible**; rollback = redeploy the **prior signed artifact + prior config revision** via GitOps (git revert), **not** a hand hotfix; rollback is **tested** (`15` T-049) and completes **≤ 5 minutes** (`03` NFR-UPG); rollback safety is guaranteed by **backward-compatible data/schema** (D-032/D-033) so the prior version always runs against current data. **Why:** the escape hatch must always work (DP-2). **Good:** `git revert` → controller restores N-1 in < 5 min. **Bad:** a migration that makes N-1 un-runnable (forward-only trap). **Exceptions:** genuinely irreversible changes require explicit governance + a compensating plan (D-032/D-065). **Enforcement:** rollback drill in CI/staging; compat gate. **Build-Fail:** a release whose N-1 cannot run against the post-deploy schema (backward-compat check).

### D-030 — Emergency rollback (fast path)
- **Rule:** a **one-command / one-click** emergency rollback exists per deployable, pre-authorized for on-call, that restores the last-known-good artifact+config and is **fully audited** (D-063); it is **exercised in game-days** (`15` T-019/T-048) so it works under pressure; emergency rollback still cannot violate an invariant (it restores a known-good, invariant-holding state). **Why:** MTTR under incident (`03`). **Good:** `deploy rollback <service>` restores N-1, auto-opens an incident record. **Bad:** improvising rollback steps during a Sev1. **Exceptions:** none. **Enforcement:** runbook + tooling + drill. **Build-Fail:** a deployable without a tested emergency-rollback path (release-acceptance gate, `15` T-053).

### D-031 — Zero-downtime deployment
- **Rule:** production deploys cause **no request-path downtime and no dropped in-flight work**: readiness-gated rollout, connection draining + `preStop`, **graceful stream termination** (finish or cleanly checkpoint active streams `12 §16`, never silent truncation `03` NFR-STRM), and pre-stop deregistration from load balancing. Long-lived streaming connections are drained within a bounded window. **Why:** availability + correctness during deploy (AD-015/AD-018, `15` T-049). **Good:** old pod stops receiving new streams, finishes/《checkpoints》 in-flight, then exits. **Bad:** SIGKILL mid-stream. **Exceptions:** none in prod. **Enforcement:** `15` T-049 zero-downtime + stream-drain tests. **Build-Fail:** deploy causes request-path 5xx spike or silent stream truncation in the pre-prod zero-downtime test.

---

## F. Data, Config & Identity

### D-032 — Database migration strategy (deploy-time)
- **Problem:** schema changes are the classic deploy outage and rollback trap. **Rule:** all migrations are **expand-then-contract**, **backward-compatible**, **online**, and **reversible** (`08 §17`, `15` T-050): expand (additive, deploy code that tolerates old+new) → migrate data → contract (remove old) only after the old code is fully retired; migrations are **decoupled from app start** (run as a governed job, app never blocks/racing on DDL), **idempotent**, **ordered/immutable** (no editing applied migrations), and validated in CI against real backends (Testcontainers). **Per-tenant/per-service store ownership** (`08`) is respected — no cross-service schema reach. **Why:** rollback-safe, zero-downtime evolution (D-029). **Good:** add nullable column → backfill → dual-write → cut → drop, across releases. **Bad:** a `NOT NULL` add + drop in one release (breaks N-1). **Exceptions:** irreversible migrations require governance + backup checkpoint + compensating plan (D-065). **Enforcement:** migration linter + `15` T-050 + backward-compat gate. **Build-Fail:** a breaking/in-place/irreversible migration, an app that blocks startup on DDL, or an edited applied migration.

### D-033 — Event schema migration (deploy-time)
- **Rule:** event/schema changes deploy under **Apicurio compatibility gates** (`07 §9`): **backward/forward compatible within a major**; producers and consumers roll out so that **any version combination in flight interoperates** (mixed-version safety during rollout); breaking changes require a **new major topic/versioned event + dual-run + consumer migration**, never an in-place break. Compacted snapshot topics keep last-known-good semantics across deploys (EV-D1). **Why:** zero-loss, versioned events survive rolling deploys (`07`, DP-1). **Good:** additive Avro field with default, consumers upgraded first. **Bad:** removing a required field while old consumers run. **Exceptions:** governed major migration. **Enforcement:** registry compat gate (`15` T-011). **Build-Fail:** an incompatible schema change within a major deployed to any environment.

### D-034 — Configuration deployment
- **Rule:** configuration deploys as **versioned, validated, environment-overlaid** artifacts (JSON-Schema-validated per `10 §7`); the data plane consumes config as **cached snapshots with last-known-good fallback** (AD-022) — a bad config push **cannot** take the request path down and is itself **progressively rolled + reversible**; config changes are audited (D-063) and never contain secrets (D-035). **Why:** config is a top outage cause; it must be as safe as code (AD-022, DP-2). **Good:** schema-validated config canaried, snapshot-versioned, revertible. **Bad:** editing live config in a UI with no validation/rollback. **Exceptions:** governed. **Enforcement:** schema validation + progressive config rollout + snapshot compat. **Build-Fail:** unvalidated config, a secret in config, or a config change without a rollback revision.

### D-035 — Secret management
- **Problem:** leaked secrets are catastrophic and irreversible. **Rule:** secrets live **only** in **KMS/HSM + Vault/OpenBao** (`13`, `09`); **never** in images, git, Helm values, config maps, env baked into artifacts, logs, or telemetry (`13 §20`, `14 §7.1`); workloads obtain secrets at runtime via **short-lived, identity-bound** injection (SPIFFE-authenticated, D-038) or a secrets operator with encrypted-at-rest references; **per-tenant/per-subject key hierarchy** (`08`) is honored. **Why:** non-exposure is an invariant (03 App. D). **Good:** workload fetches a short-TTL secret via SPIFFE identity at start. **Bad:** a base64 secret in a committed values file. **Exceptions:** none. **Enforcement:** secret scanning (git/images/manifests) + admission (no plaintext Secret refs) + `15` T-057. **Build-Fail:** any secret detected in code/config/image/manifest, or a plaintext secret mounted.

### D-036 — Secret rotation
- **Rule:** all secrets/keys **rotate on a defined schedule and on-demand** (compromise) **without downtime**: overlapping validity (new active before old retired), automated propagation, and **crypto-shred alignment** with the `08` key hierarchy; rotation is **tested** (restore-with-old-key, `15` T-047) and audited. Provider API keys, DB creds, signing keys, and tenant KEKs all have rotation runbooks (D-058). **Why:** limit blast radius of any leak; meet compliance (`13`, `08`). **Good:** dual-key overlap rotation, zero failed requests. **Bad:** a hard cutover that invalidates in-flight sessions. **Exceptions:** governed. **Enforcement:** rotation automation + expiry monitoring + drill. **Build-Fail:** a secret/key without a rotation policy + expiry alarm (release-acceptance).

### D-037 — Certificate lifecycle
- **Rule:** all TLS/mTLS certificates are **automatically issued, renewed, and revoked** (cert-manager + the internal CA / SPIRE, D-038); **short-lived** certs preferred; **expiry is monitored with lead-time alerts**; no manual cert handling in prod; rotation is non-disruptive. **Why:** expired certs are a classic avoidable outage; mTLS everywhere (SEC-D1). **Good:** auto-renewed short-lived SPIFFE SVIDs, expiry dashboards. **Bad:** a hand-issued 1-year cert nobody tracks. **Exceptions:** governed. **Enforcement:** cert-manager + expiry monitoring (D-062). **Build-Fail:** a manually-provisioned cert on the request path, or any cert without an expiry alarm.

### D-038 — SPIFFE/SPIRE deployment
- **Rule:** workload identity is **SPIFFE/SPIRE** (SEC-D1, `13 §21.1`): every workload gets a short-lived **SVID**; **all internal traffic is mTLS** with SPIFFE identities; SPIRE is deployed HA (redundant server, per-node agent), its **trust root protected** (management plane, D-007), and identity issuance is **attested** (node + workload attestation). Authorization (PEP/PDP, AD-019) consumes these identities. **Why:** zero-trust internal fabric (`13`). **Good:** service-to-service call authenticated by SVID, authorized by policy. **Bad:** a workload skipping mTLS "temporarily." **Exceptions:** none for internal service traffic. **Enforcement:** mesh/mTLS admission + `15` isolation/security tests. **Build-Fail:** an internal service reachable without SPIFFE-mTLS.

### D-039 — Service discovery
- **Rule:** discovery is **platform-native** (Kubernetes DNS/Services + mesh) — **no hardcoded IPs/endpoints**; cross-service calls resolve via stable names, are **identity-authenticated** (D-038), **residency-scoped** (never resolve to a non-permitted region on the hot path, D-009), and **health-aware** (unready endpoints removed). **Why:** portable, safe, region-correct routing (AD-014/AD-017). **Good:** name-based, mesh-routed, in-region resolution. **Bad:** a config with a literal pod IP or a cross-region endpoint on the request path. **Exceptions:** governed. **Enforcement:** config lint + residency admission. **Build-Fail:** a hardcoded endpoint or a hot-path cross-region resolution.

---

## G. Health, Probes & Scaling

### D-040 — Health checks (semantic, not superficial)
- **Problem:** shallow "process up" checks mask broken instances. **Rule:** every workload exposes **meaningful** health endpoints distinguishing **liveness** (should I be killed?), **readiness** (can I serve?), and **startup** (am I initialized?); readiness reflects **real dependency + snapshot availability** (data plane ready only with a valid config snapshot, AD-022), not just port-open; health never leaks tenant/secret detail (`14 §7.1`). **Why:** correct traffic gating (D-042). **Good:** readiness fails when the local snapshot is stale beyond tolerance. **Bad:** `return 200` regardless of state. **Exceptions:** none. **Enforcement:** probe presence admission + health-contract test. **Build-Fail:** a workload without all three probe classes where applicable.

### D-041 — Startup probes
- **Rule:** workloads with non-trivial init (JVM warm-up, snapshot load — AD-023/AD-022) define a **startup probe** with adequate `failureThreshold`/`period` so slow starts aren't killed prematurely, **decoupled** from liveness; startup completes only when the instance can actually serve. **Why:** avoid crash-loops on cold start (Java 21 warm-up). **Good:** startup probe covers JVM+snapshot warm-up, then liveness takes over. **Bad:** aggressive liveness killing a still-warming JVM (crash loop). **Exceptions:** trivial workloads. **Enforcement:** admission (startup probe on JVM/data-plane workloads). **Build-Fail:** a data-plane/JVM workload with liveness but no startup probe.

### D-042 — Readiness probes
- **Rule:** readiness gates traffic: a pod receives traffic **only** when truly ready (dependencies reachable, valid config snapshot, warm); readiness **fails fast** on dependency/snapshot loss to shed from rotation, and **rolling/canary steps gate on readiness** (D-028); readiness must not flap. **Why:** never route to an unready instance (zero-downtime, D-031). **Good:** readiness false → removed from endpoints → drained. **Bad:** readiness == liveness (routes to warming pods). **Exceptions:** none on request path. **Enforcement:** admission + rollout readiness gating. **Build-Fail:** a request-path workload without a distinct readiness probe.

### D-043 — Liveness probes
- **Rule:** liveness detects **unrecoverable** states (deadlock, wedged event loop) and triggers restart — **conservative** thresholds to avoid killing healthy-but-busy pods; liveness is **independent** of transient dependency outages (a downstream outage must **not** cause mass liveness restarts — that turns a partial outage into a total one). **Why:** self-healing without cascading restarts. **Good:** liveness checks internal loop health, not downstream reachability. **Bad:** liveness tied to DB reachability → DB blip restarts the fleet. **Exceptions:** none. **Enforcement:** probe-config review + chaos (dependency-loss must not mass-restart). **Build-Fail:** liveness coupled to external dependency reachability.

### D-044 — Resource requests and limits
- **Problem:** unset/incorrect resources cause noisy-neighbor, OOMKills, and throttling. **Rule:** **every** container sets **CPU/memory requests** and **memory limits** sized from profiling (`15` T-021/benchmarks); **memory limit ≥ request** with JVM heap (`-XX:MaxRAMPercentage`) tuned within the limit (AD-023/ZGC); CPU limits used judiciously (avoid throttling latency-critical paths); requests reflect real usage for correct scheduling/bin-packing. **Why:** predictable performance + fair multi-tenant sharing (`03` NFR-PERF, AD-021). **Good:** requests from load-test profiles; JVM sized to limit. **Bad:** no limits (OOM risk) or heap > container limit (OOMKill). **Exceptions:** governed for special workloads. **Enforcement:** admission (requests/limits required) + LimitRange. **Build-Fail:** a container without requests + memory limit, or JVM heap exceeding container memory limit.

### D-045 — Autoscaling policy
- **Rule:** stateless workloads (data plane) autoscale via **HPA** on **meaningful signals** (CPU + latency/RPS/queue-depth custom metrics from `14`), within `min`/`max` bounds that hold SLOs during scale-up lag; scale-down is **gradual** (stabilization windows) to avoid thrash; stateful/quorum services (`08`) **do not** naïvely HPA (scaling is deliberate/operator-driven); cluster autoscaler adds nodes. Autoscaling **never** compromises isolation or residency (scaled pods stay in-region/in-scope). **Why:** elasticity within SLO + cost (`03` NFR-ELAS/CAP). **Good:** HPA on P95 latency + RPS, 3→30, gradual scale-down. **Bad:** HPA on a stateful quorum store. **Exceptions:** governed. **Enforcement:** HPA policy lint + spike/elasticity tests (`15` T-024). **Build-Fail:** HPA on a stateful/quorum workload, or a request-path workload with no autoscaling policy in prod.

### D-046 — Capacity planning hooks
- **Rule:** the platform exposes **capacity signals** (utilization, saturation, headroom, per-tenant demand, provider-quota consumption) as first-class metrics (`14`) feeding capacity reviews and **pre-emptive scaling**; deployments declare expected capacity envelopes; **quota/limit exhaustion is alarmed with lead time** (before hard limits bite). **Why:** avoid capacity-driven incidents; plan growth (`03` NFR-CAP). **Good:** headroom dashboard + forecast alerts at 70% sustained. **Bad:** discovering capacity limits during an incident. **Exceptions:** none. **Enforcement:** required capacity metrics (`14 §19`) + saturation alerts. **Build-Fail:** a request-path service without saturation/headroom metrics wired.

---

## H. DR, Backup & Residency

### D-047 — Disaster recovery deployment
- **Problem:** DR that only exists on paper fails when needed. **Rule:** DR is **deployed and continuously exercised**, meeting the **tiered RTO/RPO** of `08 §16` (**audit RPO = 0**); recovery is **fully automated/IaC-reproducible** (rebuild a region from code + backups), **coordinated across services** to a **consistent recovery point** (`08 §16 H4`), and **residency-safe** (D-051). DR runbooks (D-058) are drilled **quarterly** (`15` T-048). **Why:** survive region/provider loss within promised bounds (`02`/`03`). **Good:** IaC rebuilds a region + restores to a consistent point within RTO in a drill. **Bad:** a DR plan never tested. **Exceptions:** none. **Enforcement:** `15` T-048 DR game-day; overdue drill blocks release. **Build-Fail (release-acceptance):** DR drill missing or last drill failed/overdue.

### D-048 — Backup standards
- **Rule:** backups are **automated, encrypted (envelope, per-tenant keys `08`), immutable (WORM/Object-Lock), residency-confined, and tiered by data class**; audit stores are **continuously/synchronously** protected (RPO=0); backup **success and integrity are monitored** (a silent backup failure is a defect); retention meets compliance (`13 §26`). **Why:** durability + recoverability + compliance (`08`, DP-1). **Good:** encrypted immutable backups, integrity-verified, region-pinned. **Bad:** unencrypted backups in a non-permitted region. **Exceptions:** none for regulated data. **Enforcement:** backup automation + integrity monitor + residency check. **Build-Fail:** a data store without an automated, encrypted, residency-confined backup policy.

### D-049 — Restore validation
- **Rule:** backups are **proven by regular test-restores** (`15` T-047): **quarterly** (or tighter for critical stores) automated restore + integrity verification; **key-version retention** verified (restore with historical key; crypto-shredded data stays unrecoverable, `08`); restore time is measured against RTO. A backup is not trusted until a restore has validated it. **Why:** untested backups routinely fail to restore. **Good:** automated restore drill → integrity pass → RTO recorded. **Bad:** assuming backups work without restoring. **Exceptions:** none. **Enforcement:** `15` T-047 scheduled restore + integrity gate. **Build-Fail (scheduled):** a failed/integrity-invalid test-restore.

### D-050 — Multi-region failover
- **Rule:** failover to a **permitted peer region** is **automated or one-command**, **residency-preserving** (EU→EU only, D-009/AD-014), meets RTO/RPO, and is **non-corrupting** (coordinated, idempotent, no split-brain — fencing/leader-election); failback is equally governed; failover is **drilled** (`15 §I.1` Tier-3). Cross-region promotion of async-replicated data respects the consistency/RPO model (`08`). **Why:** regional resilience without residency/consistency violation (AD-014, `08 §16`). **Good:** EU-1 loss → automated failover to EU-2 within RTO, no data crossing to non-EU. **Bad:** failover routing EU tenants to a US region. **Exceptions:** none for residency. **Enforcement:** residency-scoped failover config + Tier-3 drill. **Build-Fail:** a failover target outside the permitted residency scope, or an untested failover path (release-acceptance).

### D-051 — Residency-aware deployment
- **Rule:** deployment is **residency-first**: every workload, store, backup, cache, telemetry sink, and failover target is **tagged with a residency scope** and **admission-verified** to stay within permitted regions (AD-014, `08` DA-D2, `14 §18.1`); region overlays (D-022) encode scope; a deploy that would place residency-constrained data/telemetry outside its scope is **rejected at admission**. **Why:** residency confinement is an invariant (03 App. D). **Good:** admission blocks an EU-tenant store scheduled to a US region. **Bad:** a telemetry sink shipping EU request data to a US backend. **Exceptions:** none. **Enforcement:** residency admission policy + `15 §I.1` Tier-1 logic + Tier-2 env tests. **Build-Fail:** any residency-scope violation at admission or in the residency test tier.

---

## I. IaC, Environments & Release

### D-052 — Infrastructure as Code standards
- **Problem:** click-ops infrastructure is unauditable and unreproducible. **Rule:** **all** infrastructure (clusters, networks, node pools, IAM, DNS, KMS, buckets, databases, policies) is **declarative IaC**, version-controlled, peer-reviewed (CODEOWNERS `10 §16`), **plan-reviewed before apply**, and applied **only via CI/GitOps** — no console changes; state is **remote, locked, encrypted**; modules are reusable and versioned. **Why:** reproducibility, review, audit, DR-rebuild (D-047). **Good:** every infra change = reviewed PR + plan + automated apply. **Bad:** creating a prod bucket in the cloud console. **Exceptions:** break-glass (D-065), reconciled after. **Enforcement:** drift detection + restricted apply identity + policy-as-code. **Build-Fail:** out-of-band infra drift, or apply outside CI.

### D-053 — Terraform standards
- **Rule:** Terraform (`09`) follows: **remote encrypted locked state** (per env/region, isolated), **no secrets in state/code** (secrets via KMS/Vault refs, D-035), **pinned provider/module versions**, **mandatory `plan` review** on PR (with policy checks — OPA/Conftest/tfsec), **modules for reuse**, environment isolation (separate state per env/region), and **no manual state edits**. Destroy on protected resources is guarded. **Why:** safe, reviewable, secure infra changes (`13`, `10`). **Good:** `terraform plan` + tfsec + policy gate on PR, applied by CI. **Bad:** a secret in `terraform.tfstate` committed to git. **Exceptions:** governed. **Enforcement:** CI plan/policy gate + state-scanning. **Build-Fail:** secret in state/code, unpinned provider, or apply without a reviewed plan.

### D-054 — Environment strategy (dev / test / staging / prod)
- **Rule:** four promotion tiers with **increasing protection and parity**: **dev** (ephemeral, fast), **test/CI** (ephemeral, Testcontainers/kind `15`), **staging** (prod-like topology, prod-like data *synthetic/masked* `15` T-057, full gate suite), **prod** (progressive delivery, max protection). **Same artifact promotes across all** (D-003); **no prod data flows downward** (`13 §20`); staging mirrors prod (multi-AZ/region shape) enough to validate deploys. **Why:** catch issues before prod with parity (`15` T-058). **Good:** identical digest dev→prod, synthetic data in staging. **Bad:** copying prod PII into staging. **Exceptions:** none for prod data. **Enforcement:** promotion pipeline + data-provenance scan. **Build-Fail:** prod data in a lower environment, or a prod artifact not promoted from staging.

### D-055 — Release trains
- **Rule:** each deployable follows its **independent release train** (`10` monorepo + independent trains, AD-017): versioned (SemVer), changelogged, with backward-compat guarantees (`12`/`07`) so trains release on their own cadence **without lock-step**; the data plane is one train, each control service its own. Coordinated multi-service changes use **compatibility (expand/contract), not synchronized deploys**. **Why:** independent evolution (CP-2, `06`). **Good:** Policy service ships v2.3 while data plane stays v1.4, both compatible. **Bad:** a "release everything together" freeze forced by a breaking change. **Exceptions:** governed. **Enforcement:** per-train pipeline + compat gates. **Build-Fail:** a change forcing lock-step release of independent trains (contradicts `06`/AD-017).

### D-056 — Version promotion
- **Rule:** promotion is **artifact-identical and gated** (D-003/D-024): a version advances dev→staging→prod **only** by passing each tier's gates; **prod pins immutable digests**; every promotion is **audited** (who/what/when/approvals, D-063) and **reversible** (D-029); regulated promotions require the recorded approvals (`13 §26`, `10 §16.10`). **Why:** traceable, safe, reversible releases (DP-2/DP-3). **Good:** digest-pinned promotion with recorded approvals. **Bad:** promoting a staging tag that rebuilds for prod. **Enforcement:** promotion gates + approval records. **Build-Fail:** a promotion of a non-identical digest or skipping a tier gate.

### D-057 — Feature flags (deploy ≠ release)
- **Problem:** coupling code deploy to feature exposure forces risky big-bang launches. **Rule:** risky/user-visible changes ship **dark** behind **feature flags** (`15`/config system, AD-022 snapshotted config), enabling **decoupled deploy vs release**, progressive per-tenant/per-cohort enablement, and **instant kill-switch** without redeploy; flags are **evaluated within isolation** (a tenant's flag never affects another, AD-021), **audited**, and **short-lived** (retired after rollout — no permanent flag debt). Flags **cannot** be used to bypass an invariant (correctness/governance/isolation are never flag-gated off). **Why:** safe, reversible, gradual exposure (DP-2). **Good:** new routing behind a flag, enabled 1 tenant → cohort → all, instant off. **Bad:** a flag that can disable the correctness pipeline (AD-018 violation). **Exceptions:** governed. **Enforcement:** flag registry + expiry + invariant-flag prohibition check. **Build-Fail:** a flag gating an invariant/non-bypassable control off; a stale flag past its retirement deadline.

### §I.1 — Operational Baselines (configurable, not hard-coded) *(resolves DM-3; expands D-014/D-025–031/D-044–049 — additive)*
- **Problem:** timing and resource numbers scattered as literals (canary steps, bake windows, drain timeouts, rollback SLA, restore cadence, resource sizing) become stale and inconsistent. **Rule:** every deployment **timing/resource/threshold value is a named, versioned entry in a per-environment `operational-baseline` config** (schema-validated `10 §7`, deployed as config D-034, region/env-overlaid D-022), **not** a hard-coded literal. The numeric examples throughout this document (e.g. canary `5%/25%/100%`, rollback `≤5 min`, quarterly restore, bake windows, drain timeouts, HPA min/max, correctness-signal deltas §E.2) are **illustrative defaults**; the authoritative values live in the baseline and are tuned per environment against `03`/`08`/`15` targets.
- **Bounds are governed:** the baseline may tighten freely; **loosening a value derived from a frozen guarantee** (e.g. rollback SLA from `03` NFR-UPG, RPO/RTO from `08 §16`, audit RPO=0) requires a recorded exception (D-065) and **may never cross the frozen floor** — the baseline **configures within** frozen guarantees, never weakens them.
- **Enforcement:** baseline is schema-validated + change-audited (D-061); gates read thresholds from the baseline. **Build-Fail:** a hard-coded deployment threshold bypassing the baseline; a baseline value set beyond a frozen `03`/`08` guarantee bound.

### §I.2 — Multi-Cloud Abstraction Seams & Portability Boundaries *(resolves DM-4; expands D-005/D-012/D-052 — additive)*
- **Problem:** "multi-cloud" is hollow unless the cloud-specific seams are named. **Rule:** the platform is **Kubernetes-portable core + explicit provider-abstracted seams**; every cloud-specific dependency sits **behind a named abstraction** (consistent with AD-002 replaceable adapters), so a new cloud is a new adapter, not a rewrite:

| Seam | Portable contract | Per-cloud adapter (examples) |
|---|---|---|
| **Compute/orchestration** | Kubernetes API (PSS-restricted, D-005) | EKS / AKS / GKE / on-prem K8s |
| **Secrets/KMS** | envelope-encryption + secret-fetch interface (D-035) | Cloud KMS/HSM / Vault / OpenBao |
| **Object storage (WORM)** | S3-compatible + Object-Lock (`08`) | S3 / GCS / Azure Blob / MinIO |
| **Block/DB hosting** | store-per-service engines (`08`) via operators | managed RDS/Cloud SQL or self-hosted operators |
| **Load balancing / ingress** | K8s Service/Ingress + health-aware LB (D-010/D-039) | cloud LB / MetalLB / on-prem |
| **DNS / service discovery** | K8s DNS + name-based discovery (D-039) | external-dns providers |
| **Identity/mTLS** | SPIFFE/SPIRE (SEC-D1, D-038) | cloud-agnostic (portable by design) |

- **Rule:** **no cloud-proprietary service on the request path without a portable abstraction**; residency/isolation/secret guarantees are identical across clouds and on-prem. Terraform modules (D-053) isolate provider specifics behind stable module interfaces. **Why:** genuine multi-cloud + on-prem portability (deployment targets) without architecture divergence. **Enforcement:** dependency review flags un-abstracted cloud-proprietary use on the core/request path. **Build-Fail:** a request-path/core component bound to a cloud-proprietary API without a portable seam.

### §I.3 — Deployment-Time Cost Guardrails & FinOps Validation *(resolves DM-5; expands D-045/D-046 — additive)*
- **Problem:** autoscaling + progressive rollout can silently blow up cost (runaway HPA, oversized requests, a canary that scales both versions); cost is a reliability concern (budget exhaustion → forced degradation). **Rule:** promotion includes a **FinOps validation gate**: (a) **cost-impact estimate** of the change (resource-request deltas, replica bounds, new infra from Terraform `plan`) surfaced on the PR; (b) **HPA `max` and resource requests bounded by a per-environment cost budget** (from the operational baseline §I.1); (c) **cost-anomaly alarms** on deploy (spend rate vs. forecast, tied to `14` capacity metrics D-046); (d) a rollout that would breach a hard cost ceiling **pauses for approval** rather than scaling unbounded. This mirrors the platform's cost-based DoS-budget posture (`13 §13.1`) applied to *self-inflicted* deploy cost. **Why:** prevent scale-driven cost incidents; FinOps discipline for regulated/enterprise buyers. **Good:** PR shows +$X/mo estimate; HPA capped at budgeted max; deploy alarms on 2× forecast spend. **Bad:** an unbounded HPA + oversized requests shipped with no cost visibility. **Exceptions:** governed (a deliberate capacity increase is an approved baseline change). **Enforcement:** FinOps gate in promotion (D-024/D-056) + cost-anomaly alerts. **Build-Fail:** a prod workload with an unbounded HPA `max` or no cost budget wired; a promotion missing the cost-impact estimate for a resource/scale change.

---

## J. Operations & Governance

### D-058 — Operational runbooks
- **Rule:** every deployable and critical operation (deploy, rollback, failover, restore, rotation, scale, break-glass) has a **tested, version-controlled runbook** with clear triggers, steps, verification, and rollback; runbooks are **exercised in game-days** (`15` T-019/T-048) and kept current; on-call can execute them under pressure. **Why:** MTTR + human reliability during incidents (`03`, `14`). **Good:** a drilled failover runbook executed in a game-day. **Bad:** an outdated wiki page nobody has run. **Exceptions:** none for critical ops. **Enforcement:** runbook presence in release-acceptance (`15` T-053) + drill cadence. **Build-Fail:** a critical operation without a current, tested runbook.

### D-059 — Monitoring requirements (deploy-gating)
- **Rule:** before/without adequate monitoring, a workload does not ship: every deployable exposes the **required golden signals + SLIs** (`14`), **deploy markers/annotations**, and **saturation/capacity** metrics; **SLOs + alerts exist before prod** (`03 §61`, `14 §19`); progressive delivery **gates on these metrics** (D-025). **Why:** you cannot safely deploy what you cannot observe (DP-3). **Good:** SLOs, alerts, and deploy annotations wired before first prod rollout. **Bad:** shipping a service with no dashboards/alerts. **Enforcement:** release-acceptance monitoring gate (`14 §19`, `15` T-052). **Build-Fail:** a prod workload lacking required SLIs/SLOs/alerts.

### D-060 — Deployment observability
- **Rule:** the **deployment process itself is observable**: every rollout emits structured events (start/step/promote/halt/rollback), **deploy markers on dashboards** (correlate deploys ↔ metric shifts), rollout-health analysis is recorded, and **change-related incidents are traceable to the deploy** (`14`). Deploy telemetry obeys the same **privacy rules** (no secrets/PII/tenant content, `14 §7.1`). **Why:** most incidents are change-induced; correlation is essential (`14`). **Good:** a latency spike auto-correlated to the canary step that caused it. **Bad:** no record of what deployed when. **Enforcement:** rollout controller emits events + markers. **Build-Fail:** a prod rollout path not emitting deploy events/markers.

### D-061 — Audit requirements (deployment)
- **Rule:** **every** production change (deploy, promotion, rollback, config/secret/infra change, break-glass, flag change) produces a **tamper-evident, immutable audit record** (who, what, when, approvals, artifact digest, git SHA) into the audit store (`08 §10` WORM+Merkle, `13 §19`); deployment audit has the same **integrity/completeness** guarantees as request audit (RPO=0, no gaps). **Why:** compliance + forensics + accountability (`13`, `08`, invariant). **Good:** signed, immutable deploy record chained into the audit Merkle log. **Bad:** an untracked manual prod change. **Enforcement:** GitOps + pipeline emit audit records; completeness monitor. **Build-Fail:** a prod-mutating action path that does not emit an audit record.

### D-062 — Deployment security requirements
- **Rule:** the deployment path enforces platform security end-to-end: **signed images + provenance + SBOM** (D-018/19), **admission policy** (PSS-restricted, signed-only, non-root, network-policy), **SPIFFE-mTLS** (D-038), **secrets only from KMS/Vault** (D-035), **least-privilege CI/deploy identities** (no standing prod admin; scoped, short-lived), **protected branches + required reviews** (`10 §16`), and **dual-control for the 16 sensitive operations** (`13 §29.1`) where deployment triggers them. **Why:** the supply chain and deploy path are prime attack targets (`13`). **Good:** keyless-signed images, OIDC-scoped deploy identity, dual-control on key rotation. **Bad:** a long-lived cluster-admin token in CI. **Enforcement:** admission + IAM policy + `15` T-051/T-029. **Build-Fail:** unsigned image, over-privileged deploy identity, or a dual-control op executed single-handed.

### D-063 — Compliance requirements (deployment)
- **Rule:** deployment upholds compliance obligations (`13 §26`): **change-management records** (approvals, segregation of duties), **residency evidence** (D-051), **retention** of deploy/audit artifacts, **SBOM/provenance retention**, and **regulated-release sign-off**; on-prem/regulated profiles provide the same evidence locally (D-012). **Why:** regulated SaaS/on-prem buyers require demonstrable control (`02`, `13`). **Good:** an auditor reconstructs any release's approvals, artifacts, and residency from records. **Bad:** no evidence trail for a prod change. **Enforcement:** audit completeness (D-061) + compliance report generation. **Build-Fail:** a regulated release without recorded segregation-of-duties approvals.

### D-064 — Build-failing deployment validation (summary gate table)
| Gate | Fails when… |
|---|---|
| Image supply chain | unsigned image, no SBOM/provenance, critical CVE, disallowed license, root/`:latest` (D-016–020) |
| Admission baseline | PSS-restricted / non-root / network-policy / probes / resources violated (D-005, 044, 040–043) |
| Progressive delivery | request-path/consumer workload without gated canary/blue-green/rolling + auto-rollback (D-025–028) |
| Zero-downtime | pre-prod deploy causes 5xx spike or silent stream truncation (D-031, `15` T-049) |
| Migration compat | breaking/in-place/irreversible schema, or N-1 can't run post-deploy (D-032, `15` T-050) |
| Event schema compat | incompatible Avro change within a major (D-033, `15` T-011) |
| Config safety | unvalidated config, secret in config, or no rollback revision (D-034) |
| Secrets | any secret in code/config/image/manifest/state (D-035, D-053, `15` T-057) |
| Identity/mTLS | internal service reachable without SPIFFE-mTLS; cert without expiry alarm (D-037/38) |
| Residency | any residency-scope violation at admission or test tier (D-051, `15 §I.1`) |
| HA/topology | prod workload without multi-AZ spread, PDB, or N+1 (D-008/10) |
| Rollback | deployable without a tested emergency-rollback path (D-030) |
| Observability | prod workload without required SLIs/SLOs/alerts/deploy markers (D-059/60, `14 §19`) |
| Audit | prod-mutating path emitting no audit record (D-061) |
| IaC | out-of-band drift / apply outside CI / unreviewed plan (D-052/53) |
| Governance | a gate disabled without a recorded exception (D-065) |

### D-065 — Governance & exception process
- **Rule:** these standards are owned by **Platform/SRE + Security**; changes to deployment gates/policies are reviewed; **exceptions** (e.g. a temporary admission waiver, a break-glass manual action) require a **recorded, time-boxed, approved exception** (`10 §16`/`13 §29`) — never silent; **break-glass** actions are pre-defined, dual-controlled where sensitive (`13 §29.1/§29.2`), fully audited (D-061), and **reconciled back into git/IaC** immediately after; **invariant-protecting gates** (isolation, residency, secret non-exposure, no-bypass, signed-images, audit) are **never exception-waivable**. **Why:** gates erode silently otherwise; emergencies still need a safe, audited path (DP-1). **Good:** a break-glass hotfix, audited, then codified in git within the window. **Bad:** a permanent admission exception with no expiry. **Enforcement:** exception registry + expiry + post-break-glass reconciliation check. **Build-Fail:** an invariant gate waived; an expired/unreconciled exception; a break-glass action not reconciled into IaC within its window.

**§J.1 — Break-glass that preserves non-waivable invariants *(resolves DM-2 — additive).*** Break-glass exists so operators can act fast in emergencies **without ever creating an escape hatch around an invariant**. The two are reconciled by separating *what* may be expedited from *what* is inviolable:

- **Two classes of gate:**
  - **Operational gates (may be expedited under break-glass):** e.g. skipping the normal PR/review latency, applying a pre-approved emergency artifact, expediting a rollback, temporarily relaxing a non-invariant admission rule (e.g. a resource-limit ceiling) — each dual-controlled, time-boxed, audited, auto-reverting.
  - **Invariant gates (NEVER waivable, even in break-glass):** tenant isolation (AD-021), residency confinement (AD-014), secret/data non-exposure, no-bypass of correctness/governance (AD-018), signed-image admission, and audit integrity. **There is no break-glass code path that disables these** — the emergency tooling is *built without the capability* to turn them off, so "never waive" is enforced by construction, not by policy alone.
- **Compliant emergency path for the hard case (region loss vs. residency):** when a region is lost, the operator does **not** get to route residency-constrained data to a non-permitted region. Instead the pre-defined runbook (D-058) offers only **invariant-preserving** options: fail over to a **permitted peer region** (D-050), or, if none is available, **degrade/deny** the affected residency scope with an explicit, audited service-impact decision — never a silent residency breach. The emergency is survivable because the failover topology (D-009/D-050) always provisions a permitted peer for every residency scope; a scope with no permitted peer is a design-time Build-Fail (D-050), not an incident-time dilemma.
- **Every break-glass action:** dual-controlled where sensitive (`13 §29.1`), fully audited (D-061), time-boxed, and **reconciled into git/IaC within its window** (D-052); the exception registry entry is mandatory.
- **Enforcement / Build-Fail:** an emergency/break-glass tool path that is *capable* of disabling an invariant gate; a residency scope provisioned without a permitted failover peer; a break-glass action without dual-control (where required), audit record, or timely reconciliation.

---

## K. Reviews

### Traceability

| Deployment area | BR | NFR | ADR | Repo (`10`) | Sec (`13`) | Obs (`14`) | Test (`15`) | Data/Event (`07`/`08`) |
|---|---|---|---|---|---|---|---|---|
| Philosophy/principles (D-001–004) | BR-003 | NFR-AV/UPG | AD-016 | §15/§16 | §19 | — | TP-1..3 | — |
| K8s baseline/namespace (D-005–006) | BR-021 | NFR-SEC | AD-021 | — | §21 | — | T-051 | — |
| Topology/AZ/region/HA (D-007–012) | BR-003 | NFR-AV/MR/DR | AD-014/017 | — | §18 | §18.1 | T-019/§I.1 | `08 §16` |
| Service model/DP/CP (D-013–015) | BR-006 | NFR-AV/PERF | AD-006/017/020/022 | — | — | — | T-049 | — |
| Container/build/sign/SBOM (D-016–020) | BR-019 | NFR-SEC | AD-012 | §15/§16 | §21/§28 | — | T-029/T-051 | — |
| Helm/Kustomize (D-021–022) | — | NFR-OPS | AD-015 | §10/§15 | §21 | — | T-051/T-058 | — |
| GitOps/pipeline/progressive (D-023–028) | BR-003 | NFR-UPG/AV | AD-015 | §15/§16 | §19 | §15 | T-049/T-053/§J.1 | — |
| Rollback/emergency/zero-downtime (D-029–031) | BR-003 | NFR-UPG-001 | AD-015/018 | §15 | — | — | T-049 | — |
| DB migration (D-032) | BR-011 | NFR-DS | AD-015 | — | — | — | T-050 | `08 §17` |
| Event schema migration (D-033) | BR-011 | NFR-Q | AD-005/009 | §7 | §14 | §9 | T-011 | `07 §9` |
| Config deploy (D-034) | BR-010 | NFR-AV | AD-022 | §7 | — | — | T-051 | — |
| Secrets/rotation/certs/SPIRE (D-035–038) | BR-018/019 | NFR-SEC | AD-012/019 | — | §20/§21.1/§29 | §7.1 | T-030/T-057 | `08 §14` |
| Discovery (D-039) | BR-006 | NFR-MR | AD-014/017 | — | §21 | — | §I.1 | — |
| Health/probes (D-040–043) | BR-003 | NFR-AV | AD-006/022 | — | — | §5 | T-049 | — |
| Resources/autoscale/capacity (D-044–046) | BR-005 | NFR-PERF/ELAS/CAP | AD-020/023 | — | — | §15/§19 | T-021/T-024 | — |
| DR/backup/restore/failover/residency (D-047–051) | BR-003/023/031 | NFR-DR/BAK/MR | AD-014 | — | §18 | §18.1 | T-047/T-048/§I.1 | `08 §16` |
| IaC/Terraform (D-052–053) | BR-019 | NFR-SEC/OPS | AD-012 | §16 | §21 | — | T-051 | — |
| Environments/trains/promotion/flags (D-054–057) | BR-028 | NFR-VER/UPG | AD-015/017/022 | §15/§16 | §26 | — | T-058/T-053 | — |
| Runbooks/monitoring/deploy-obs (D-058–060) | BR-010 | NFR-OBS/MTTR | AD-011 | — | — | §14/§19 | T-052 | — |
| Audit/compliance/security (D-061–063) | BR-024 | NFR-AUD/SEC | AD-009/012/019/021 | §16 | §19/§26/§29 | §8 | T-046/T-029 | `08 §10` |
| Build-fail/governance (D-064–065) | — | NFR-OPS | AD-016 | §16 | §29 | §19 | T-053/T-064 | — |
| GitOps/PD capability standard (§E.1) | BR-003 | NFR-UPG/AV | AD-002/015 | §15/§16 | §19 | §15 | T-049/T-053 | — |
| Correctness-signal canary gating (§E.2) | BR-001/002 | NFR-SO/STRM | AD-018/021 | — | — | §11/§15 | T-012/T-018/T-044 | — |
| Stateful rollout choreography (§C.1) | BR-011 | NFR-AV/DS/DR | AD-017/022 | — | — | §5 | T-049/T-050 | `08 §16/§17` |
| Air-gapped supply chain (§D.1) | BR-019/031 | NFR-SEC | AD-002/012 | §16 | §21/§28 | — | T-051 | `08 §14` |
| Break-glass & invariants (§J.1) | BR-021/023 | NFR-SEC | AD-014/018/021 | §16 | §29 | — | T-044 | — |
| Operational baselines (§I.1) | — | NFR-UPG/DR/PERF | AD-022 | §7 | — | §19 | T-049 | `08 §16` |
| Multi-cloud seams (§I.2) | BR-019 | NFR-SEC/OPS | AD-002 | §16 | §21 | — | T-051 | `08` |
| Cost guardrails / FinOps (§I.3) | BR-005 | NFR-CAP/ELAS | AD-020 | — | §13.1 | §15/§19 | T-021/T-024 | — |

### Internal Consistency Review

| Frozen source | Requirement | This document | ✓ |
|---|---|---|---|
| AD-020/AD-006 | data plane = one stateless co-located deployable | D-013/D-014 (rolling/canary, stateless, last-known-good) | ✅ |
| AD-017 | 13 independent control services | D-013/D-015/D-055 (independent trains, no lock-step) | ✅ |
| AD-022 | cached config snapshots / last-known-good | D-002/D-014/D-034/D-040 (serve during control-plane outage) | ✅ |
| AD-021 | absolute tenant isolation | D-006/D-011/D-027/D-045/D-057 (topology never carries isolation; flags/scale can't breach) | ✅ |
| AD-014 | residency confinement | D-009/D-050/D-051 (residency-first admission, in-region failover) | ✅ |
| AD-015 | versioning/compat/zero-downtime | D-025–031/D-055/D-056 | ✅ |
| AD-018 | non-bypassable correctness/governance | D-014/D-031/D-057 (no deploy/flag bypass; no silent truncation) | ✅ |
| AD-019 | PEP/PDP authz | D-038 (SPIFFE identities feed authz) | ✅ |
| AD-023 | Java 21 + Virtual Threads/ZGC | D-016/D-041/D-044 (JRE image, startup probe, heap sizing) | ✅ |
| SEC-D1 (`13 §21.1`) | universal internal mTLS SPIFFE/SPIRE | D-037/D-038/D-062 | ✅ |
| `08 §16/§17` | tiered RPO/RTO, expand-then-contract | D-032/D-047–050 | ✅ |
| `07 §9`, EV-D1 | Avro compat, compacted snapshots | D-033 | ✅ |
| `09` | K8s/Docker/Terraform/GitHub Actions/Vault | throughout | ✅ |
| `10 §15/§16` | CI order, monorepo trains, governance | D-023/D-024/D-055/D-065 | ✅ |
| `13 §20/21/26/28/29` | secrets, admission, compliance, scan, dual-control | D-035/D-005/D-062/D-063 | ✅ |
| `14 §7.1/§18.1/§19` | telemetry privacy, residency telemetry, SLO gating | D-051/D-059/D-060 | ✅ |
| `15` T-047–053, §I.1, §J.1 | backup/DR/upgrade/migration/K8s/multi-region/shift-left | D-029/D-032/D-047–051/D-059 | ✅ |
| AD-002 | hexagonal / replaceable implementations | §E.1 (GitOps/PD as capability contracts + adapters), §I.2 (cloud seams) | ✅ |

**No contradictions found.** The additive corrective pass (§E.1, §E.2, §C.1, §D.1, §J.1, §I.1–§I.3) introduced **no new architecture decision, no ownership change, no consistency-model change, no deployment-model change, and no security-model change**, and **weakened no invariant** — every addition is a deployment capability contract, sequence, offline equivalent, threshold-governance rule, or guardrail that *operationalizes* an already-frozen guarantee. Explicitly consistent with **AD-002** (GitOps/progressive-delivery/cloud-services are replaceable adapters behind capability contracts). Verified against **00–15** and **AD-001…AD-023**: consistent; no change to Service Boundaries (`06`), Event Architecture (`07`), Data Architecture (`08`), Security (`13`), Observability (`14`), Testing (`15`), or Repository Structure (`10`).

### Architecture Validation
- **Data/Control plane split (AD-020/AD-017):** deployment honors 1 data-plane + 13 control-plane deployables; §C.1 specifies *how* stateful control services roll (quorum + expand/contract), never redefining them. ✅
- **Last-known-good continuity (AD-022):** every deploy/rollback/config path — including the §C.1 leader handoff and §I.1 baseline changes — preserves data-plane serving. ✅
- **Isolation & residency invariants:** enforced at admission and never a function of topology; §E.2 aborts a canary on any isolation signal; §J.1 makes invariant gates *incapable* of break-glass waiver; §I.1 forbids baselines crossing frozen floors. ✅
- **Non-bypass (AD-018):** §E.2 gates canaries on structured-output/streaming/escape correctness signals; no mechanism or flag disables correctness/governance. ✅
- **Replaceable implementations (AD-002):** §E.1 GitOps/PD capability contracts and §I.2 cloud seams keep every tool/cloud an adapter — this pass *reinforces* AD-002 rather than adding a decision. ✅
- **Zero-trust supply chain & fabric (`13`, SEC-D1):** §D.1 gives the offline profile identical signing/SBOM/CVE/admission/mTLS guarantees. ✅
- **Independent evolution (CP-2, `06`):** unchanged; release trains stay independent. ✅
- No architectural contradiction; the corrective pass remains a faithful operational projection of the frozen architecture — **no new architecture decision**.

### Adversarial Review (re-run after corrective pass)

**Prior High findings — resolution**
- **DH-1 → RESOLVED (§E.1).** GitOps and progressive delivery are now **capability contracts (GC-1…GC-6, PD-1…PD-5)**, not tool mandates; supported options listed as examples (Argo CD/Flux; Argo Rollouts/Flagger/equivalent); a **safe fallback** (manual-gated staged rolling, never big-bang) defined when PD tooling is absent; explicitly consistent with **AD-002** and **introduces no ADR**.
- **DH-2 → RESOLVED (§C.1).** A complete **nine-phase stateful rollout choreography** (S-1 pre-deploy validation → S-2 backup/recovery-point → S-3 expand → S-4 quorum check → S-5 followers-then-leader rolling → S-6 health → S-7 post-deploy → S-8 contract-after-bake → S-9 revalidation), each with a pass gate and rollback checkpoint (CP-A…CP-D), N-1 compatibility preserved throughout, expand-then-contract enforced, every step testable in staging (`15` T-049/T-050).
- **DH-3 → RESOLVED (§D.1).** Full **offline supply-chain path AG-1…AG-8**: offline mirror registry, KMS-key/air-gap-Sigstore signature verification, bundled+signed SBOM verification, mirrored+dated CVE DB with staleness alarms, bundled license validation, self-contained admission policy, offline trust-bundle distribution, internal-CA certificate rotation — regulated/on-prem remains first-class.

**Prior Medium findings — resolution**
- **DM-1 → RESOLVED (§E.2):** canary gates on objective correctness signals (structured-output conformance, escape/silent-incorrect-delivery, streaming integrity, error-normalization leak, retry/idempotency anomaly, isolation) with automatic, baseline-relative, first-occurrence abort for zero-tolerance signals.
- **DM-2 → RESOLVED (§J.1):** operational vs. invariant gates separated; invariant gates *incapable* of waiver by construction; a compliant region-loss emergency path (permitted-peer failover, else audited degrade/deny — never a silent residency breach), guaranteed survivable because every residency scope must provision a permitted peer (D-050).
- **DM-3 → RESOLVED (§I.1):** all timing/resource/threshold values moved to a schema-validated, per-environment `operational-baseline` config; numerics in the doc are illustrative defaults; baselines configure *within* frozen floors, never across them.
- **DM-4 → RESOLVED (§I.2):** multi-cloud seams named (compute, secrets/KMS, object storage, DB, LB/ingress, DNS, identity) with portable contracts + per-cloud adapters; no un-abstracted proprietary service on the request path.
- **DM-5 → RESOLVED (§I.3):** FinOps validation gate (cost-impact estimate, budget-bounded HPA/requests, cost-anomaly alarms, ceiling-pause) mirroring the `13 §13.1` cost-budget posture for self-inflicted deploy cost.

**New findings from the re-run**

**🔴 Critical:** none.
**🟠 High:** none.
**🟡 Medium:** none blocking. (All numeric values remain explicit, governed **operational-baseline** entries under §I.1 — a stated design choice within frozen floors, not an open defect.)
**🟢 Low (deferred to later standards, non-blocking):**
- **DL-1 — Admission-policy engine choice** (Kyverno vs Gatekeeper) → tooling standard.
- **DL-2 — Mesh choice** (if any) vs library-based SPIFFE → tooling standard.
- **DL-3 — Registry/retention specifics** (image GC, SBOM retention window, offline-bundle cadence numbers) → ops standard.
- **DL-4 — Exact runbook templates** → operations handbook.
- **DL-5 — Concrete cost-budget figures & baseline default values** → set per environment at first-build calibration (§I.1).

### Readiness Score: **97 / 100**
All three High findings and all five Medium findings resolved additively, with no architecture, ownership, consistency-model, deployment-model, or security-model change and no invariant weakened. GitOps/progressive-delivery capability contracts, stateful rollout choreography, the offline supply-chain path, correctness-signal canary gating, break-glass invariant preservation, configurable operational baselines, multi-cloud seams, and FinOps guardrails are now fully specified and machine-enforceable, across regulated SaaS, on-prem/air-gapped, and multi-cloud. The residual 3 points reflect only **honest, governed calibration items** (baseline defaults / cost figures pending real environments) and deferred **Low** tooling choices — none blocking.

### Recommendation: **FREEZE.** Critical = 0, High = 0, Medium = 0 (blocking); remaining Low items are explicitly deferred and non-blocking. No contradictions with `00`–`15` or AD-001…AD-023; no architectural, ownership, consistency-model, deployment-model, or security-model change; no invariant weakened; consistent with AD-002.

---

## Appendix
**A. Deployment → tool quick map.** Orchestration: Kubernetes. Packaging: Helm + Kustomize. Images: Docker/OCI, distroless, Cosign (sign), Syft (SBOM), Grype/Trivy (scan). GitOps: Argo CD/Flux. Progressive delivery: Argo Rollouts/Flagger (canary/blue-green) + Prometheus analysis. Identity: SPIFFE/SPIRE + cert-manager. Secrets: Vault/OpenBao + Cloud KMS/HSM. IaC: Terraform (+ tfsec/OPA/Conftest). Admission: Kyverno/Gatekeeper. CI: GitHub Actions. Observability: Prometheus + OTel (`14`). Stores: PostgreSQL/MongoDB/Valkey/Kafka/S3-WORM (`08`).
**B. Relationship to other docs.** Operationalizes `09` (tech) + `10` (repo/CI) + `13` (security) + `14` (observability) + `15` (testing gates); deploys the `06`/`04` service model under `07`/`08` data/event contracts; honors AD-001…AD-023. Introduces **no new architecture decision** — deployment mechanics only.
**C. Maintenance.** Frozen at v1.0. New standards carry the full template + a traceability row +, where possible, a build-failing admission/pipeline gate. DP-1/DP-2/DP-3, the GitOps/progressive-delivery capability contracts (§E.1), and the invariant-protecting gates (isolation, residency, secret non-exposure, no-bypass, signed-images, audit) are stable law and never exception-waivable; changes go through the D-065 governance/exception process.

---

*End of document — 16-Deployment-Standards.md · Status: FROZEN · Version: 1.0 (2026-07-21)*
