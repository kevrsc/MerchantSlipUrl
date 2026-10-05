---
review: reconcile-spec
spine: ARCHITECTURE-SPINE.md
spec: SPEC-tiny-url-service
companions: [create-pipeline.md]
date: '2026-09-25'
verdict: gaps
---

# Reconcile: Architecture Spine ↔ SPEC

Load-bearing SPEC / create-pipeline requirements checked against spine ADs, Consistency Conventions, Capability map, Structural Seed, and Deferred. Focus: quiet requirements dropped because AD structure favors placement/owner over behavioral contract.

## Verdict

**gaps** — CAP-1..CAP-8 appear in `binds` / Capability map, but several contract clauses did not land as spine rules.

## Landed (summary)

| Area | Where it landed |
| --- | --- |
| Layered single deployable; Web → Service → Persistence | AD-1 |
| Atomic create / sole mutator / return-existing | AD-2 |
| H2-only binding store | AD-3 |
| Liveness inside create; 404 immediate; timeout/5xx ×3 ×30s; store after success | AD-4 |
| Basic Auth on create/vanity; resolve `permitAll` | AD-5 |
| Single local process | AD-6 |
| Configurable inactivity; 5 min initial; last-access on success | AD-7 |
| Store original merchant-pasted URL (not post-redirect final) | Consistency |
| Sanitize before lookup; JSON create response with tiny + original; resolve redirect | Consistency |
| Binding model with `sanitizedUrl` UK + `lastAccessedAt` | Structural Seed ER |

## Gaps (quiet drops)

Load-bearing requirements present in SPEC / create-pipeline that the AD structure did not capture:

1. **Liveness follows redirects** — SPEC Constraints + create-pipeline step 4 require following redirects for the probe while still storing the original string. AD-4 covers retry counts and “original long URL” but omits redirect-following. Consistency covers store-original only.

2. **Liveness success = HTTP 2xx (after redirects)** — SPEC Assumptions. AD-4 says “success” without defining 2xx / redirect-to-success semantics that CAP-4 success requires.

3. **Refuse resolve when missing or inactivity-expired** — create-pipeline Resolve path + CAP-7 success + Constraints (“Expired tiny URLs must not resolve”). AD-7 only states config + last-access reset on successful resolve; the fail-closed / do-not-return-destination rule is absent.

4. **Vanity-assign behavioral contract** — create-pipeline Vanity assign + CAP-8: identify existing binding by tiny URL or long URL; replace short form; keep same original long URL; refuse vanity bound to a different destination. Spine places vanity assign in Service + AD-5 auth only; assign semantics and taken-vanity refuse are not stated (shortCode PK implies uniqueness, not the refuse path or identity keys).

5. **Tiny URL must not encode destination / order content** — CAP-5 success second clause + Non-goals. Capability map covers create response shape only; no opacity / non-encoding rule for short-form generation.

## Not counted as gaps

- Ordered create pipeline as a named AD — pieces land via AD-5 → Consistency sanitize → AD-4 → AD-2; absence of a single ordered-steps AD is structural preference, not a dropped requirement, except where individual clauses above are missing.
- Multi-instance / external DB / analytics — correctly Deferred or non-goals.
- Concurrent create collision rules — covered by AD-2 Prevents/Rule.

## Recommendation

Promote gaps 1–3 into AD-4 / AD-7 (or Consistency) before build; add vanity-assign + short-code opacity as Consistency or a thin AD under CAP-8 / CAP-5 so implementation cannot invent conflicting semantics.
