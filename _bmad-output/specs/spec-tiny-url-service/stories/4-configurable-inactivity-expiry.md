---
title: 'Configurable inactivity expiry'
type: 'feature'
created: '2026-09-29'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: '2d285c1d2a3cdcc18485f657f2f7ef62b37849c9'
context:
  - '{project-root}/_bmad-output/specs/spec-tiny-url-service/SPEC.md'
  - '{project-root}/_bmad-output/specs/spec-tiny-url-service/create-pipeline.md'
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-ShortyUrlBmad-2026-09-25/ARCHITECTURE-SPINE.md'
  - '{project-root}/_bmad-output/specs/spec-tiny-url-service/stories/2-resolve-tiny-url-to-original-destination.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A tiny URL still resolves after it has sat unused. Resolve always redirects and refreshes `lastAccessedAt`, so CAP-7 never takes effect.

**Approach:** On public resolve, treat the binding as expired when the time since last access (or since creation, if it was never accessed) is at least the configured inactivity period. An expired tiny URL does not redirect and does not refresh last access. A successful resolve still redirects to the stored original URL and resets the window. The period is configuration, 5 minutes by default.

## Boundaries & Constraints

**Always:**
- Layered web → service → persistence. Expiry is decided in the service layer (AD-2, AD-7)
- Inactivity period comes from configuration. Initial value is 5 minutes (`shortyurl.inactivity=5m`)
- The window anchor is `lastAccessedAt` when set, otherwise `createdAt`
- Successful resolve still returns 302 to the stored `originalUrl` and sets `lastAccessedAt` to now
- An expired tiny URL responds with **410 Gone**, does not redirect, and does not update `lastAccessedAt`
- A missing code stays **404**
- Changing the configured period changes the window used
- Resolve stays unauthenticated
- CAP-7 only for this story

**Never:**
- A background sweeper as the thing that enforces expiry
- Hard-coding 5 minutes in the resolve method
- Changing liveness or vanity. Create changes only to reactivate an expired binding for the same sanitized URL
- Controllers deciding expiry

**Decisions:**
- Enforce expiry inside the existing transactional `UrlResolveService.resolve`, before writing `lastAccessedAt`
- Tests control time with a `Clock` (or an equivalent test double) and a short configured period so they do not sleep 5 minutes
- Expired resolve returns **410 Gone**
- After expiry, an authenticated create of the same long URL keeps that binding, returns the same tiny URL, and starts the inactivity window again. A create of a binding that is not expired still writes nothing

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Fresh resolve | Binding younger than the period | 302 to `originalUrl`; `lastAccessedAt` set to now | N/A |
| Access resets window | Resolved once, then again before the period elapses from that access | Second resolve is 302; window measured from the first access | N/A |
| Expired | No access for the full configured period | **410**; no redirect; `lastAccessedAt` unchanged | ProblemDetail 410 |
| Never accessed | `lastAccessedAt` null and `createdAt` older than the period | Same as expired: **410** | ProblemDetail 410 |
| Unknown code | No binding | 404; no redirect | Unchanged from story 2 |
| Config change | Period set shorter than the age of an otherwise fresh binding | **410**; no redirect | ProblemDetail 410 |
| Reactivate | Authenticated create of the same long URL after expiry | Same tiny URL; window starts now; a following resolve is 302 | N/A |

</frozen-after-approval>

## Code Map

- `shorty-url/src/main/java/com/shortyurl/service/UrlResolveService.java` -- today finds by short code, sets `lastAccessedAt`, returns `originalUrl`. Add the inactivity check before that write
- `shorty-url/src/main/java/com/shortyurl/persistence/Binding.java` -- reuse `createdAt` and nullable `lastAccessedAt`. Do not add a new expiry column
- `shorty-url/src/main/java/com/shortyurl/web/ResolveUrlController.java` -- stay a redirect adapter. Expired resolve is 410, not a redirect
- `shorty-url/src/main/java/com/shortyurl/web/ApiExceptionHandler.java` -- map the expired signal to ProblemDetail 410. Leave unknown-code 404 as it is
- `shorty-url/src/main/resources/application.properties` -- add `shortyurl.inactivity=5m`. Test profile may shorten it
- `shorty-url/src/main/java/com/shortyurl/service/UrlCreateService.java` -- when the existing binding is expired, keep it, return its tiny URL, and start the window again. A live existing binding still returns with no write
- `shorty-url/src/test/java/com/shortyurl/web/ResolveUrlIT.java` -- cover the matrix with a controllable clock. Do not sleep 5 minutes
- Do not change `_bmad/` or `.agents/`

## Tasks & Acceptance

**Execution:**
- [x] `application.properties` (+ test props) -- `shortyurl.inactivity=5m`; tests use a short period -- CAP-7 config
- [x] `UrlResolveService.java` -- expire from `lastAccessedAt` or else `createdAt`; update `lastAccessedAt` only after the check passes -- AD-7
- [x] `ResolveUrlController.java` / `ApiExceptionHandler.java` -- expired resolve is **410** and does not redirect -- customer-facing refusal
- [x] `UrlCreateService.java` -- expired existing binding is kept, the same tiny URL is returned, and the window starts again -- reactivate
- [x] `ResolveUrlIT.java` (+ create coverage) -- matrix rows, including reset, 410, never-accessed, unknown 404, shorter period, and reactivate -- testable

**Acceptance Criteria:**
- Given a binding younger than the configured period, when it is resolved, then the response is 302 to `originalUrl` and `lastAccessedAt` is now
- Given a successful resolve, when the same code is resolved again before the period elapses from that access, then the response is still 302
- Given no access for the full configured period, when the code is resolved, then the response is **410**, there is no redirect, and `lastAccessedAt` is not updated
- Given an expired binding, when the same long URL is created with valid Basic Auth, then the response is that same tiny URL, the window starts again, and a following resolve is 302
- Given `lastAccessedAt` is null and `createdAt` is older than the period, when the code is resolved, then the outcome matches an expired binding
- Given the configured period is shortened below a binding’s age, when that code is resolved, then resolve refuses
- Given an unknown code, when it is resolved, then the response is still 404

### Review Findings

- [x] [Review][Patch] Assign expiry is only tested when both timestamps agree [shorty-url/src/main/java/com/shortyurl/service/UrlAssignService.java:57]

#### Rejected

- low — Successful resolve does not read `createdAt` back. Resolve only writes `lastAccessedAt`, and after that write `createdAt` is not the expiry anchor. The 410 tests already keep `createdAt`.
- low — `assignByTinyUrl` and `assignByEquivalentLongUrl` do not re-read every timestamp. The shared assign expiry block is what the timestamp-pair tests cover.
- low — The never-accessed 410 case sits on the period boundary, and the inside-window cases use a one-second margin. Equality is already expired (`now` is not before the anchor plus the period), which is the boundary that matters.
- low — `ResolveUrlShorterPeriodIT` does not move `MutableClock`. The row is already older than 1s and younger than the 5s profile, so the test locks the shorter period. The clock advance is locked in `ResolveUrlIT`.
- false — `Inactivity.isExpired` does not 500 on a normal resolve. `createdAt` is non-null on every insert, and a null last access uses that timestamp.

## Implementation Notes

- Resolve checks inactivity in `UrlResolveService` before writing `lastAccessedAt`. The anchor is `lastAccessedAt` when set, otherwise `createdAt`. The period is `shortyurl.inactivity` (`5m` by default; the test profile uses `5s`). Time comes from an injected `Clock`. An expired code throws `UrlExpiredException`, which `ApiExceptionHandler` maps to ProblemDetail 410. The resolve controller stays a redirect adapter.
- Create of an expired binding for the same sanitized URL keeps that row, returns its tiny URL (HTTP 200), and sets `lastAccessedAt` to now so the window starts again. A live binding is returned with no write. `createdAt` is left as the original mint time.
- `ResolveUrlIT` (and `ResolveUrlShorterPeriodIT` in the same file) drives a `MutableClock` through the matrix, including a 1s period for the config-change row. Create reuse is also covered when `createdAt` is already outside the period and `lastAccessedAt` is still inside it (no write), and when `lastAccessedAt` is null and `createdAt` is outside the period (reactivate, then 302). `gradlew.bat test --rerun-tasks` BUILD SUCCESSFUL.

## Spec Change Log

## Review Triage Log

- **low, rejected** — `shortyurl.inactivity` is not checked for a positive duration. `0s` or a negative value would expire every row, and the shipped value is `5m` (tests use `5s` and `1s`). A startup guard is extra complexity users will not meet with the configured period.
- **low, rejected** — `Binding.onCreate` fills a null `createdAt` from `Instant.now()`, and no test asserts an API create stored the injected `Clock`. `persistNewBinding` already sets `createdAt` from that clock before save, and `@PrePersist` only fills null, so the clock is what gets stored. Production uses `Clock.systemUTC()` either way.
- **low, rejected** — `liveCreate_writesNothing` does not advance the clock between resolve and create, so a same-instant rewrite of `lastAccessedAt` would still pass. `reuseExisting` returns before any write when the binding is live, so that rewrite is not what the code does.
- **false** — A failed liveness probe cannot leave an expired binding half-updated. `UrlCreateService.create` calls `urlLivenessChecker.check` before `findBySanitizedUrl`, and `LivenessFailedException` aborts before `reuseExisting`.
- **low, rejected** — Resolve already covers “`lastAccessedAt` inside the period, `createdAt` outside” in `accessResetsWindow_secondResolveMeasuredFromFirstAccess`. A `lastAccessedAt` older than `createdAt` is not produced by resolve or create. The create-side hole is the patch below.
- **low, rejected** — The 410 response sets no cache directive. This service has no cache in front of it, and the spec does not require one. A header would be new behavior.
- **low, rejected** — `shorterPeriod_refusesBindingInsideTheDefaultWindow` does not assert the body omits the destination or that the title is `Gone`. The handler detail is `Tiny URL has expired: {code}`, which does not include `originalUrl`, and the test already asserts 410 and no `Location`.
- **false** — `URI.create` runs after `resolve` returns, so a throw would leave `lastAccessedAt` updated. Create only stores a trimmed paste that already passed `new URI(...)`, so `URI.create` on that same string does not throw.
- **low, rejected** — `MutableClock.withZone` returns `Clock.fixed`, and `instant` is not volatile. Tests call `setInstant` and `advance` on the injected clock from one thread, and nothing calls `withZone`.
- **false** — `ResolveUrlIT`, `ResolveUrlShorterPeriodIT`, and `CreateUrlIT` share `jdbc:h2:mem:shortyurl`. Gradle and JUnit are not running those classes in parallel, and each test deletes the store in `@BeforeEach`. The suite passed.
- **false** — The staged diff still showed story `status: in-progress` with empty logs. That snapshot predates this review step, which set `in-review`. Fixing the story file is not a code defect.
- **low, rejected** — Overlapping resolves can commit an older `lastAccessedAt` last. The skew is the overlap, milliseconds against a 5-minute window. A pessimistic lock is more than a direct correction.
- **low, rejected** — A resolve that loaded an expired row can still return 410 if reactivation commits before the throw. That needs those two requests to overlap, and the next resolve sees the new window. A lock is extra complexity.
- **low, rejected** — Two reactivations can both pass the inner expiry check, and the earlier instant can commit last. Same millisecond skew. A row lock is extra complexity.
- **medium, patch** — Create reuse is only tested when `createdAt` and `lastAccessedAt` agree. `Inactivity.isExpired` uses `lastAccessedAt` when set, otherwise `createdAt`, but no create test pairs an in-window `lastAccessedAt` with an out-of-window `createdAt`, and none pairs a null `lastAccessedAt` with an out-of-window `createdAt`. Dropping `lastAccessedAt` from the create check, or treating null last-access as not expired, would leave `liveCreate_writesNothing` and `reactivate_expiredBinding_returnsSameTinyUrlAndResolves` green. Add both cases to `ResolveUrlIT`.
- **defer** — The sanitized-URL unique-constraint catch now calls `reuseExisting`, and no test raises `DataIntegrityViolationException`. Restoring the old “return the row unchanged” branch would still pass the suite. Recorded in deferred work; the find-then-reuse reactivation is already covered.

- `low` — `shortyurl.inactivity` is not checked for a positive duration. A `0s` or negative value would expire every row. Shipped value is `5m`. Rejected: everyday config is positive, and the fix is a new guard.
- `low` — `Binding.onCreate` still fills a null `createdAt` from `Instant.now()`, and no test asserts an API create stored the injected `Clock`. `persistNewBinding` does set `createdAt` from the clock, and `@PrePersist` only fills null, so the clock is used. Rejected: production clock and `Instant.now()` are the same source; the gap is test-only.
- `low` — `liveCreate_writesNothing` does not advance the clock, so a rewrite of `lastAccessedAt` to the same instant would pass. `reuseExisting` returns before any write when the binding is live. Rejected: the no-write path is what runs; tightening the test is extra.
- `false` — A failed liveness probe could still reactivate an expired binding. `urlLivenessChecker.check` runs before `findBySanitizedUrl`, and a failure throws, so `reuseExisting` never runs.
- `low` — Tests would still pass if the anchor were the later of `createdAt` and `lastAccessedAt`. Resolve already covers last-access inside the window with creation outside it (`accessResetsWindow`). A last-access older than creation is not produced by create or resolve. The create-side hole is the medium row below. Rejected.
- `low` — A 410 ProblemDetail has no `Cache-Control`, so a heuristic cache could keep serving Gone after reactivation. This service sets no cache and has no intermediary. Rejected: unlikely here, and the fix adds a header the spec does not require.
- `low` — `ResolveUrlShorterPeriodIT` does not assert the body omits the destination or that the title is `Gone`. The handler detail is the exception message, which does not include the URL, and the test already asserts 410 and no `Location`. Rejected.
- `defer` — `UrlResolveService` commits `lastAccessedAt` before `ResolveUrlController` calls `URI.create`. Pre-existing from story 2: the write already happened inside the resolve transaction before the controller built `Location`.
- `low` — `MutableClock.withZone` returns a fixed clock, and `instant` is not volatile. Tests are single-threaded and do not call `withZone`. Rejected.
- `false` — `ResolveUrlIT`, `ResolveUrlShorterPeriodIT`, and `CreateUrlIT` share `jdbc:h2:mem:shortyurl`. Gradle and JUnit are not running these classes in parallel, and each test deletes the store in `@BeforeEach`. The suite passed, so that interference does not occur.
- `false` — The staged diff left story `status` at `in-progress`. Review sets `in-review` before the reviewers run. The fix would also edit this spec, which is rejected.
- `low` — Overlapping resolves can commit an earlier `lastAccessedAt` last. The skew is the overlap, milliseconds against a 5-minute window. Rejected: a pessimistic lock is extra complexity for that skew.
- `low` — A resolve that loaded an expired row can still return 410 after a concurrent reactivation commits. The next resolve sees the new window. Rejected: the overlap is rare, and the fix is a lock the spec does not require.
- `low` — Overlapping reactivations can persist the earlier instant. Same millisecond lost update as the resolve race. Rejected.
- `medium` — Create reuse is only tested when `createdAt` and `lastAccessedAt` agree. Passing null instead of `lastAccessedAt` into create’s expiry check, or treating a null last-access as not expired, would leave `liveCreate_writesNothing`, `reactivate_expiredBinding`, and the back-to-back create test green. Pre-verified by the verification-gap layer. Route: patch — add the two disagreeing-timestamp create cases.
- `defer` — The sanitized-URL unique-constraint catch now calls `reuseExisting`, and no test raises `DataIntegrityViolationException`. Pre-verified. Restoring the old return-unchanged line would still pass the suite. The find-then-reuse reactivation is already asserted.

## Design Notes

Expiry is checked on resolve only. A binding that is never opened expires from `createdAt`. Tests should move a `Clock` or set timestamps directly rather than waiting out 5 minutes.

## Verification

**Commands:**
- `shorty-url/gradlew.bat test --rerun-tasks` -- expected: BUILD SUCCESSFUL

**Manual checks (if no CLI):**
- With the period at 5 minutes, a new tiny URL redirects. After that period with no access, the same path does not redirect.
