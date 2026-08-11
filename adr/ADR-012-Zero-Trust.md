# ADR-012 — Zero-Trust Security Model

**ADR ID:** AD-012 · **Status:** Accepted · **Date:** 2026-07-20
**Part of:** ADR Register — see `000-ADR-Index.md` · **Project:** Reliability-First AI Gateway

- **Status:** Accepted · **Date:** 2026-07-20 · **Decision Makers:** Architecture Council
- **Context:** The gateway sits at a sensitive boundary handling regulated data (`02` BR-018/021; `03` NFR-SEC/AUTH/AUTHZ/ENC).
- **Problem Statement:** What trust model governs requests and inter-component communication?
- **Decision:** Adopt **zero trust**: every request and every inter-component call is authenticated, authorized (deny-by-default, least privilege), and encrypted (in transit and at rest); no implicit trust between components or planes; defense-in-depth on critical properties.
- **Decision Drivers:** Regulated-data sensitivity, blast-radius control, secure-by-default (BRULE-3), invariants (isolation, non-exposure).
- **Alternatives Considered:** (a) Perimeter trust (trust inside the boundary) — rejected: a single internal compromise spreads. (b) Trust between co-located components — rejected: weakens isolation guarantees.
- **Pros:** Strong posture; contained blast radius; audit-friendly; meets regulated bar.
- **Cons:** Overhead of pervasive authn/authz/encryption; operational complexity (keys, identities).
- **Trade-offs:** Accepts security overhead for a defensible posture — non-negotiable given the customer base.
- **Consequences:** Underpins AD-019, AD-021, secret/encryption ADRs; every component authenticates.
- **Business Impact:** Enables regulated adoption (`02` BR-022). **Engineering Impact:** Security is pervasive, not perimeter. **Security Impact:** Primary. **Performance Impact:** Overhead budgeted. **Operational Impact:** Identity/key management at scale.
- **Risks:** Complexity-induced misconfiguration. **Mitigations:** secure defaults (AD-013), continuous security testing (`03 §55`).
- **Affected Components:** All. **Related BR:** BR-018, BR-019, BR-020, BR-021. **Related NFR:** NFR-AUTH-001, NFR-AUTHZ-001, NFR-ENC-001, NFR-SEC-*. **Related PRB:** PRB-013, PRB-014, PRB-015, PRB-029.
- **Related ADRs:** AD-019, AD-021, AD-013. **Review Criteria:** Never weaken; strengthen as threats evolve. **Future Revisions:** None to weaken.
