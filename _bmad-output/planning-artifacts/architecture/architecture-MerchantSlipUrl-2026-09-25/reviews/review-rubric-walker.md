# Rubric Walker Review — Architecture Spine

**Artifact:** `ARCHITECTURE-SPINE.md`  
**Reviewed:** 2026-09-25  
**Altitude:** feature  
**Verdict:** **CONCERNS**

---

## Checklist scoring

| Criterion | Result | Notes |
| --- | --- | --- |
| Fixes real divergence points; misses none important | **Partial** | Strong coverage of layering, store ownership, liveness, security, single-process, inactivity config. Gaps on short-code generation, URL identity/sanitize rules, resolve/expiry HTTP outcomes, vanity conflict — not decided, deferred, or marked open. |
| Every AD Rule enforceable and prevents stated divergence | **Pass** | AD-1..AD-7 are concrete enough for story review / code structure checks. |
| Nothing under Deferred lets units diverge dangerously | **Pass** | Deferred items are gated by ADOPTED rules (esp. AD-3, AD-6); interim choices are locked. |
| Named tech verified-current | **Pass** | Java 21; Spring Boot 4.1.1 (released 2026-08-20, Maven Central); H2 BOM-managed; Gradle 8.14+ / 9.x per Boot 4.1 — appropriate. |
| Covers SPEC CAP-1..CAP-8 if present in binds | **Pass** | `binds` lists CAP-1..CAP-8; Capability → Architecture Map covers each with layer + governing ADs. |
| Every dimension decided / deferred / open (esp. operational envelope) | **Partial** | Operational envelope decided via AD-6 + Deferred. Several implementer-facing dimensions are silent (neither decided nor listed Open/Deferred). |

---

## Verdict rationale

The spine is a solid feature-altitude substrate: paradigm, package seed, stack, and the main cross-story divergence risks (who mutates, which store, where liveness lives, which paths are auth’d, single-process assumption) are locked with enforceable AD rules. CAP-1..CAP-8 are bound and mapped. Deferred items do not leave a dangerous dual-path.

It falls short of **PASS** because a few high-probability story divergence points are left **undeclared** — not ADOPTED, not Deferred, not Open — so two implementers could still ship incompatible create/resolve/vanity behavior while “following” the spine.

---

## Findings (ordered by severity)

### F1 — Undeclared opens: short-code generation & URL identity (CAP-1 / CAP-3)

**Severity:** Medium (blocks clean PASS)  
**Checklist:** divergence points; dimensions decided/deferred/open  

Conventions say “sanitize before lookup” and the ER seed has `sanitizedUrl UK`, but there is **no AD Rule or Open item** defining:

- How the default short code is generated (alphabet, length, collision retry)
- What “sanitize” means for idempotent identity (scheme/host casing, trailing slash, query order, fragment strip, etc.)

Two units can both obey AD-2/AD-3 and still diverge on create idempotency and code shape. These should be ADOPTED rules or explicit Open/Deferred rows.

### F2 — Resolve / expiry HTTP semantics incomplete (CAP-2 / CAP-7)

**Severity:** Medium  
**Checklist:** divergence points; CAP coverage depth  

AD-7 locks configurable inactivity and last-access updates on successful resolve. Missing as decided/deferred/open:

- Behavior when a binding is past inactivity (hard delete vs soft expire; status on resolve — 404 vs 410 vs other)
- Redirect class for successful resolve (302 vs 301/307)

CAP-2 and CAP-7 are mapped, but story implementers can still diverge on client-visible contract without violating any AD.

### F3 — Vanity conflict / assign edge cases under-specified (CAP-8)

**Severity:** Low–Medium  
**Checklist:** divergence points; AD prevent-statement completeness  

CAP-8 is governed by AD-2 (sole mutator) and AD-5 (auth). AD-2’s “check-then-bind or return existing” is framed for **default create idempotency**, not vanity assign. Missing: conflict when vanity code already bound to another URL; whether vanity reuses create/liveness pipeline; code charset constraints. Risk of two stories inventing incompatible conflict responses.

### F4 — AD rules are generally enforceable (positive)

**Severity:** n/a (strength)  

| AD | Enforceability | Prevents stated divergence? |
| --- | --- | --- |
| AD-1 | Package + deployable boundary | Yes — blocks hexagonal/multi-service ownership split |
| AD-2 | `@Transactional` service-only writes; check-then-bind | Yes — blocks adapter writes / torn concurrent creates |
| AD-3 | JPA/H2 only; BOM version | Yes — blocks dual stores |
| AD-4 | RestClient in service; 404 immediate; 3×30s for timeout/5xx | Yes — blocks web-layer liveness / split retry |
| AD-5 | Single Security filter chain; path rules | Yes — blocks securing resolve / opening create |
| AD-6 | One local process; no multi-instance design | Yes — soft but clear constraint until Deferred lifted |
| AD-7 | Config property; 5 min test default; last-access on resolve | Yes — blocks hard-coded expiry drift |

No AD was found that fails to prevent its stated divergence or is too vague to enforce at feature altitude.

### F5 — Deferred list is safe; operational envelope closed enough

**Severity:** n/a (strength)  

Deferred (external DB, multi-instance, orchestration, deeper observability, shared credential store) are explicitly gated by AD-3/AD-6 and local Security user config. Two story units cannot “partially adopt” Postgres or shared H2 without violating ADOPTED rules. Operational envelope at this altitude is **decided**, not open.

### F6 — Stack verified-current

**Severity:** n/a (strength)  

- **Java 21** — current LTS choice for Boot 4.x  
- **Spring Boot 4.1.1** — verified released 2026-08-20 (spring.io / Maven Central)  
- **H2** — BOM-managed (correct pin strategy)  
- **Gradle** — “8.14+ or 9.x per Boot 4.1” acknowledges Boot’s Gradle requirement without over-pinning  

---

## CAP bind coverage

| CAP | In binds | Mapped | Governed adequately for spine altitude? |
| --- | --- | --- | --- |
| CAP-1 | Yes | Yes | Mostly — missing short-code policy (F1) |
| CAP-2 | Yes | Yes | Mostly — missing redirect/miss semantics (F2) |
| CAP-3 | Yes | Yes | Weak on identity definition (F1) |
| CAP-4 | Yes | Yes | Strong (AD-4) |
| CAP-5 | Yes | Yes | OK via conventions + AD-1 |
| CAP-6 | Yes | Yes | Strong (AD-5) |
| CAP-7 | Yes | Yes | Partial — config locked; expiry outcome not (F2) |
| CAP-8 | Yes | Yes | Partial — auth/mutation locked; conflict not (F3) |

---

## Recommended remediations (for spine author)

1. Add AD or Open rows for **short-code generation** and **URL sanitize/identity** used by idempotent create.  
2. Add AD or Open rows for **expired/missing resolve** HTTP outcomes and **redirect status**.  
3. Clarify **vanity assign** conflict behavior (and whether CAP-4 liveness applies) under CAP-8 / AD-2.  
4. Optionally add an explicit **Open** section so silent gaps cannot be mistaken for intentional silence.

After F1–F3 are either locked or explicitly Open/Deferred, this spine should clear **PASS**.

---

## Verdict

**CONCERNS** — Adopted divergence controls, CAP map, Deferred safety, operational envelope, and verified stack are in good shape; promote to PASS once short-code/identity, resolve-expiry contract, and vanity conflict are decided or explicitly opened.
