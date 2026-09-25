# Adversarial Finalize Review — Architecture Spine

| Field | Value |
| --- | --- |
| Lens | Adversary: two units one level down, each AD-letter-compliant, still incompatible |
| Target | `ARCHITECTURE-SPINE.md` (ShortyUrlBmad Tiny URL Service) |
| Spec cross-check | `SPEC-tiny-url-service` + `create-pipeline.md` |
| Date | 2026-09-25 |
| Verdict | **FAIL** — multiple holes where story pairs can obey every AD and still clash on shared data, entity ownership, or mutation paths |

## Method

For each finding: construct two story-level implementers that (1) cite only adopted ADs + the letter of Consistency Conventions where an AD is silent, (2) never violate an AD Rule, yet (3) produce incompatible shared-data shapes, dual owners of one entity, or conflicting state-mutation paths. Each pair is a hole to close with a new or tightened AD.

Conventions tables and ER sketches that are not promoted into ADs are treated as non-binding soft guidance — an adversary following ADs to the letter may diverge there.

---

## Findings

### F1 — CRITICAL: CAP-2 Resolve vs CAP-7 Expiry — two owners of binding liveness / `lastAccessedAt`

**Incompatible pair:** Story *Resolve redirect* (CAP-2) × Story *Inactivity expiry* (CAP-7)

**How each obeys every AD:**

| Story | AD compliance claim |
| --- | --- |
| Resolve | AD-1 (web redirects, service resolves); AD-5 (`permitAll` on resolve); AD-7 (“successful resolve updates last-access”). **AD-2 does not bind CAP-2.** |
| Expiry | AD-2 (sole mutator for CAP-7); AD-7 (config-driven inactivity); AD-3 (H2 only). |

**Clash:**

- Resolve implementer updates `lastAccessedAt` from the web adapter or a non-`@Transactional` repository call (AD-2’s sole-mutator rule never names CAP-2), and treats “row exists ⇒ redirect.”
- Expiry implementer owns active/inactive via a scheduled hard-delete, a soft-delete flag, or a read-time clock check inside a different service method that Resolve never calls.
- Result: one path mutates access timestamps without expiry evaluation; the other mutates or removes bindings without a single mandated resolve gate. Spec CAP-7 success (“after inactivity, resolve fails”) is not enforceable by any AD that both stories must share.

**Hole to close:** Tighten **AD-2** to bind CAP-2 (all binding mutations, including last-access). Add **AD-n** (or extend AD-7): resolve is the only successful-access path; it must (a) evaluate inactivity against config before redirect, (b) update `lastAccessedAt` only after that pass, (c) refuse destination when expired — all inside one transactional service method. Forbid a second expiry sweeper as a substitute for the resolve gate (sweeper optional, never the sole enforcer).

---

### F2 — CRITICAL: CAP-1/CAP-3 Create vs CAP-8 Vanity assign — dual identity for one Binding

**Incompatible pair:** Story *Default create + idempotent bind* (CAP-1/CAP-3) × Story *Vanity assign / replace short form* (CAP-8)

**How each obeys every AD:**

| Story | AD compliance claim |
| --- | --- |
| Create | AD-1–AD-3: service transactional persist of Binding; ER sketch with `shortCode` PK + `sanitizedUrl` UK. |
| Vanity | AD-2 + AD-5: service mutates binding under auth; CAP-8 “replaces the short form” while keeping original URL. |

**Clash:**

- Create treats `shortCode` as immutable primary identity (PK in the seed ER) and looks up by `sanitizedUrl`.
- Vanity must change the short form. Letter-compliant options diverge:
  - **A:** `UPDATE` the PK (or delete+insert) — breaks FK/identity assumptions Create and Resolve baked in; fate of the old code undefined.
  - **B:** introduce a second table (`Alias` / `VanityMapping`) as “not the binding store” while Binding keeps a stable id — Create still keys the world by `shortCode` PK; Resolve may query the wrong table.
- Two owners of “what identifies a binding” and two persistence shapes for the same entity. AD-3 forbids dual *stores* for the same binding but does not forbid dual *schemas* or define stable vs mutable identity fields.

**Hole to close:** New **AD — Binding identity model**: stable internal id (or immutable surrogate); short-code is a unique mutable attribute (or versioned alias set) with explicit rules for old-code after vanity replace (must 404 / must not resolve). Single Binding aggregate in `persistence`; vanity assign updates short-code uniqueness in the same transactional service path as create. Promote ER to AD-level mandatory columns + uniqueness.

---

### F3 — HIGH: CAP-3 Idempotent create vs CAP-8 Vanity-at-create — conflicting create mutation paths

**Incompatible pair:** Story *Idempotent create* (CAP-3 / pipeline step 5) × Story *Vanity supplied on create* (CAP-8)

**How each obeys every AD:**

Both sit in service-layer create (AD-2, AD-4, AD-5). Pipeline companion says: existing binding ⇒ return tiny URL and **write nothing**; vanity upgrade is a **separate** path — but that ordering is not an AD.

**Clash:**

- CAP-3 story: on sanitized hit, return existing short code; ignore optional vanity in the request body (no write).
- CAP-8 story: on sanitized hit with a vanity present, treat create as “upgrade short form” inside the same create use case / transaction.
- Same endpoint and AD-2 “check-then-bind or return existing” rule; opposite mutation semantics for the existing-row case. Merchants get non-deterministic behavior depending on which story’s code wins the merge.

**Hole to close:** New **AD — Create vs vanity-assign separation** (ratify pipeline): create never mutates an existing binding’s short-code; existing sanitized hit always returns current tiny URL with no write; vanity replace only via the vanity-assign use case. Vanity-at-create applies only on the new-bind branch when the vanity is available.

---

### F4 — HIGH: CAP-1 Create vs CAP-3 Idempotency — clashing sanitization / lookup key shape

**Incompatible pair:** Story *Persist original + mint* (CAP-1) × Story *Existence check / idempotent return* (CAP-3)

**How each obeys every AD:**

AD-2 requires check-then-bind under a transaction; Consistency says “sanitize before lookup” and “store original merchant-pasted string”; ER shows `sanitizedUrl` UK — **none of that is an AD Rule**. Spec constraints require sanitize-before-existence-check, but the spine does not elevate it.

**Clash:**

- Create story stores `originalUrl` as pasted and uses a local trim-only “sanitize,” or skips a dedicated column and unique-indexes `originalUrl`.
- Idempotency story implements a richer normalizer (host case, default port, trailing slash) and looks up by that form.
- Same long URL messy variants → one story sees a hit, the other inserts a second binding. Shared-data shape for the idempotency key diverges; CAP-3 success fails while every AD still holds.

**Hole to close:** New **AD — Sanitize & lookup key**: one service-owned sanitize function used for all existence checks and uniqueness; persist both original (resolve target) and sanitized (UK); create/vanity/idempotent paths must call the same function. Pin minimum normalization rules or “algorithm is single module X” so stories cannot invent private sanitizers.

---

### F5 — HIGH: CAP-4 Liveness-in-create vs CAP-2/CAP-3 Atomic bind — conflicting transaction / mutation boundaries

**Incompatible pair:** Story *Liveness gate* (CAP-4) × Story *Atomic idempotent bind* (CAP-3) *(also collides with Resolve’s last-access TX expectations from F1)*

**How each obeys every AD:**

| Story | AD compliance claim |
| --- | --- |
| Liveness | AD-4: RestClient liveness inside service create; store only after success; web does not probe. |
| Bind | AD-2: check-then-bind or return existing **under that transaction**. |

**Clash:**

- Liveness story wraps the whole create use case (including up to 3×30s HTTP) in `@Transactional` — letter-matches “inside the create use case” + “under that transaction.”
- Bind story keeps a short transaction around lookup+insert only; liveness runs before the TX opens — also letter-matches AD-4 “store only after success” and AD-2’s atomic write.
- Concurrent creates for the same sanitized URL then diverge: long-held TX vs check-outside-TX races; connection pool / timeout behavior differs; CAP-3 convergence is not uniquely determined by the ADs.

**Hole to close:** Tighten **AD-2 + AD-4** together: liveness MUST run outside the binding transaction; binding transaction covers only sanitize-key lookup + insert-or-return-existing (+ vanity availability check); no HTTP I/O inside the binding TX. State mutation path for create becomes one mandated sequence.

---

## Additional holes (medium / lower)

| ID | Pair | Sketch | Suggested close |
| --- | --- | --- | --- |
| F6 | CAP-5 Create response × CAP-2 Resolve | No AD for JSON field names, path templates, or redirect status (301 vs 302 vs 303). DTOs and resolve URL parsing diverge. | AD: HTTP API contract — paths, DTO fields (`originalUrl` / `tinyUrl`), redirect status. |
| F7 | CAP-1 Default codegen × CAP-8 Vanity syntax | No alphabet/length/charset for system codes vs vanity validation; uniqueness race “service check vs DB constraint” unspecified. | AD: short-code charset/length + DB unique constraint as source of truth; vanity validation rules shared. |
| F8 | CAP-7 Expiry × Deferred multi-instance | AD-6 defers multi-instance, but CAP-7 “clock” source (app clock vs DB) and delete-vs-filter semantics remain free — even single-process stories clash (F1). | Fold into AD-7 tighten from F1; keep multi-instance deferred. |

---

## Summary of AD work (for parent Finalize apply)

| Priority | Action |
| --- | --- |
| P0 | Bind CAP-2 under AD-2; mandate single resolve gate for expiry + last-access (F1) |
| P0 | AD: Binding identity — stable id, mutable unique short-code, old-code after vanity (F2) |
| P1 | AD: Create never mutates existing short-code; vanity-assign is separate (F3) |
| P1 | AD: Single sanitize + `sanitizedUrl` UK mandatory (F4) |
| P1 | AD-2/AD-4: liveness outside binding TX; TX = lookup+bind only (F5) |
| P2 | API DTO/path/redirect AD (F6); short-code charset AD (F7) |

Until P0–P1 land, story implementers can each claim full AD compliance and still ship incompatible persistence and mutation paths.

---

## Verdict

**FAIL** for finalize handoff. The spine’s ADs cover layering, sole mutator (partially), H2, liveness placement, security paths, single-process runtime, and configurable inactivity — but leave binding identity under vanity, resolve/expiry mutation ownership, create-vs-assign semantics, sanitize key shape, and create transaction boundaries unspecified. Those are exactly the seams where two AD-obedient stories diverge.
