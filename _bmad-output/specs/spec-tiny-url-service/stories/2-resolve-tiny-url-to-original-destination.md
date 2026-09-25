---
title: 'Resolve tiny URL to original destination'
type: 'feature'
created: '2026-09-25'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: '63e6b6cbca244255232a9f75648ece8e23b1f4f1'
context:
  - '{project-root}/_bmad-output/specs/spec-tiny-url-service/SPEC.md'
  - '{project-root}/_bmad-output/specs/spec-tiny-url-service/create-pipeline.md'
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-ShortyUrlBmad-2026-09-25/ARCHITECTURE-SPINE.md'
  - '{project-root}/_bmad-output/specs/spec-tiny-url-service/stories/1-authenticated-default-create.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Customers with a tiny URL need to open it on a web-connected device and land on the exact original long URL the merchant submitted, without authenticating.

**Approach:** Add a public `GET /{shortCode}` that looks up the binding, updates `lastAccessedAt` in the service layer, and redirects to the stored `originalUrl`. Unknown codes return 404. Inactivity expiry enforcement stays out of this story (story 4).

## Boundaries & Constraints

**Always:**
- Layered web → service → persistence; service sole mutator including `lastAccessedAt` (AD-2)
- Resolve is unauthenticated (`permitAll`) — AD-5
- Redirect target is the stored original merchant-pasted URL, not a post-redirect final URL
- Path matches create’s tiny URL shape: `http://localhost:8080/{shortCode}` → `GET /{shortCode}`
- CAP-2 only for this story

**Never:**
- Enforce CAP-7 inactivity expiry (story 4)
- Require Basic Auth on resolve
- Controllers writing repositories directly
- CAP-4 liveness or CAP-8 vanity work

**Decisions:**
- Successful resolve responds with HTTP **302 Found** and `Location: {originalUrl}`
- Missing/unknown `shortCode` responds with **404** (no redirect)
- Successful resolve sets `lastAccessedAt` to now (prepares story 4; does not gate access yet)

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Happy resolve | Existing binding for shortCode | 302 to that binding’s `originalUrl`; `lastAccessedAt` updated | N/A |
| Missing code | Unknown shortCode | 404; no redirect; store unchanged | Not found |
| No auth required | Resolve without Basic Auth | Same as happy/missing | Must not 401 |
| Create then resolve | Create URL then GET its shortCode | 302 to the same `originalUrl` from create | N/A |

</frozen-after-approval>

## Code Map

- `shorty-url/src/main/java/com/shortyurl/web/SecurityConfig.java` -- already `anyRequest().permitAll()` after create matchers; do not require auth for GET resolve
- `shorty-url/src/main/java/com/shortyurl/persistence/Binding.java` -- has nullable `lastAccessedAt`; reuse
- `shorty-url/src/main/java/com/shortyurl/persistence/BindingRepository.java` -- add `findByShortCode`; keep existing finders
- `shorty-url/src/main/java/com/shortyurl/web/CreateUrlController.java` -- leave create as-is; do not overload with resolve
- `shorty-url/src/main/java/com/shortyurl/service/UrlCreateService.java` -- leave create path unchanged
- `shorty-url/.../service/UrlResolveService.java` _(add)_ -- transactional lookup by shortCode + update lastAccessedAt + return originalUrl or not-found
- `shorty-url/.../web/ResolveUrlController.java` _(add)_ -- `GET /{shortCode}` → 302 or 404
- `shorty-url/.../web/ApiExceptionHandler.java` -- extend or add handler so resolve not-found maps to 404 without breaking create JSON errors
- `shorty-url/src/test/java/.../ResolveUrlIT.java` _(add)_ -- cover I/O matrix
- Do not change BMAD tooling under `_bmad/`, `.agents/`

## Tasks & Acceptance

**Execution:**
- [x] `shorty-url/.../persistence/BindingRepository.java` -- add `Optional<Binding> findByShortCode(String shortCode)` -- resolve lookup
- [x] `shorty-url/.../service/UrlResolveService.java` (+ not-found signal) -- transactional resolve: find by shortCode, set lastAccessedAt, return originalUrl -- CAP-2, AD-2
- [x] `shorty-url/.../web/ResolveUrlController.java` -- `GET /{shortCode}` returns 302 Location or 404 -- public redirect
- [x] `shorty-url/.../web/ApiExceptionHandler.java` (or sibling) -- map resolve not-found to 404 -- consistent errors
- [x] `shorty-url/src/test/java/.../ResolveUrlIT.java` -- tests for matrix rows (happy, missing, no auth, create-then-resolve) -- Ready-for-dev testable

**Acceptance Criteria:**
- Given an existing binding, when `GET /{shortCode}` is called without auth, then the response is 302 with `Location` equal to that binding’s `originalUrl` and `lastAccessedAt` is set
- Given an unknown shortCode, when `GET /{shortCode}` is called, then the response is 404 and no redirect occurs
- Given a newly created tiny URL from `POST /api/v1/urls`, when its shortCode path is requested, then resolve lands on the same original URL returned at create
- Given resolve is called without Basic Auth, when the shortCode exists, then the response is not 401

## Implementation Notes

- Added public `GET /{shortCode:[A-Za-z0-9_-]+}` → 302 Location originalUrl; UrlResolveService updates lastAccessedAt; UrlNotFoundException → 404.
- ResolveUrlIT covers matrix; `gradlew.bat test --rerun-tasks` BUILD SUCCESSFUL.
- Review patches: path pattern 1..64 excluding error/api; stronger IT for original≠sanitized, lastAccessedAt, store-unchanged, JsonPath.

## Spec Change Log

## Review Triage Log

| Finding | Verdict | Evidence / route |
| --- | --- | --- |
| Catch-all GET may steal `/error` | medium | ResolveUrlController pattern matches `error`. **patch** |
| Path pattern lacks max length vs column 64 | medium | Confirmed Binding shortCode length 64. **patch** |
| Missing-code “store unchanged” weakly tested | low | Empty DB only; add seeded binding leave-alone assert. **patch** |
| resolveWithoutAuth duplicates happy path | low | Reject (unlikely everyday extra value) |
| createThenResolve omits lastAccessedAt assert | medium | AC requires lastAccessedAt on successful resolve. **patch** |
| 404 ProblemDetail body untested | low | Reject |
| Brittle tinyUrl JSON parse in IT | low | Direct fix to JsonPath. **patch** |
| No UrlResolveService unit test | low | Reject (IT covers) |
| Browser JSON 404 UX | low | Intent does not require HTML 404. Reject |
| URI.create fails on bad originalUrl | maybe-false | Create path only stores validated http(s) URLs; would need corrupted DB. **defer** |
| lastAccessedAt committed if URI.create fails | maybe-false | Same precondition as above. **defer** (grouped) |
| Location not pinned when originalUrl ≠ sanitizedUrl | medium | verification-gap pre-verified. **patch** |

## Design Notes

Prefer a dedicated `@Controller` for redirect (not `@RestController`) so 302 uses Spring’s redirect view/ResponseEntity cleanly. Constrain `{shortCode}` pattern if needed so it does not steal `/api/**` (create already under `/api/v1/urls`). Avoid enforcing expiry until story 4.

## Verification

**Commands:**
- `cd shorty-url && ./gradlew.bat test` -- expected: all tests green including ResolveUrlIT

**Manual checks (if no CLI):**
- Create with Basic Auth, then open the returned tiny URL path without auth → browser/curl follows or shows 302 to original URL; bogus code → 404
