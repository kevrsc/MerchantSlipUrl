---
title: 'Vanity create and assign'
type: 'feature'
created: '2026-09-29'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 'beb639452e2c4539392b50fd64244cf668892d42'
context:
  - '{project-root}/_bmad-output/specs/spec-tiny-url-service/SPEC.md'
  - '{project-root}/_bmad-output/specs/spec-tiny-url-service/create-pipeline.md'
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-ShortyUrlBmad-2026-09-25/ARCHITECTURE-SPINE.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Create always mints an opaque short code. A merchant cannot choose the short form, and cannot later replace an existing code with an available vanity while keeping the same long URL (CAP-8).

**Approach:** An optional vanity on authenticated create is stored as `shortCode` when that code is free and the long URL is new. A separate authenticated assign replaces the `shortCode` of an existing binding. If that binding is expired, last access is set to now so the new vanity resolves. The previous code stops resolving. Resolve and liveness stay as they are.

## Boundaries & Constraints

**Always:**
- Layered web → service → persistence. Short-code changes go through the service layer inside a transaction (AD-2, AD-8)
- Create stays `POST /api/v1/urls` with Basic Auth. Body gains an optional `vanity`. Null or blank means the current opaque generator
- Assign is `POST /api/v1/urls/vanity` with Basic Auth. Body is `{ "url", "vanity" }`. `url` is either the current tiny URL (configured public base plus short code) or the long URL
- A vanity is 1–64 characters of `A–Z`, `a–z`, `0–9`, `_`, or `-`, and is not exactly `api` or `error`. Comparison is case-sensitive. That is the same set resolve already accepts
- An available vanity on a **new** long URL is the stored `shortCode`. Response is 201 with that tiny URL and the pasted `originalUrl`
- If the sanitized long URL already has a binding, create returns that binding’s **current** tiny URL and does not apply a requested vanity. Live bindings still write nothing. Expired bindings still reactivate as in story 4
- A vanity already stored on a **different** destination is refused with **409 Conflict** ProblemDetail. Create stores nothing and does not run liveness. Assign leaves the row unchanged
- Invalid vanity syntax is 400. Nothing is stored and liveness does not run
- Assign updates `shortCode`. `originalUrl`, `sanitizedUrl`, and `createdAt` stay. Assign does not create a row and does not run liveness
- When the binding being assigned is expired, set `lastAccessedAt` to now so the window starts again and a following resolve of the new vanity is 302. A binding that is not expired keeps its `lastAccessedAt`
- After a successful assign, the previous short code is unknown (404)
- Assigning the binding’s current short code returns 200 and changes no fields
- Unknown long URL or tiny URL on assign is 404
- Unauthenticated create or assign stores nothing

**Never:**
- A second binding for a long URL that already exists
- Encoding the destination into the vanity
- Changing liveness, resolve status rules, or the inactivity period
- Regenerating a caller-supplied vanity when the short-code unique constraint trips (that retry is only for generated codes)
- Letting `api` or `error` through; resolve will not serve them

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Omit vanity | Authenticated create with only `url` | 201 and an opaque short code, same as today | N/A |
| Vanity at create | New long URL and a free vanity | 201; tiny URL ends with that vanity; a following resolve is 302 to `originalUrl` | N/A |
| Vanity taken | Vanity already stored for another destination | Refused; no new row; liveness does not run | ProblemDetail 409 |
| Existing long URL | Create again, with a different vanity | 200; current tiny URL unchanged; vanity not applied | N/A |
| Bad vanity | `api`, `error`, a slash, or longer than 64 | 400; no store; no probe | ProblemDetail 400 |
| Assign | Binding still inside the window, and a free vanity | 200; tiny URL uses the vanity; `originalUrl` and `lastAccessedAt` unchanged; old code is 404 | N/A |
| Assign expired | Expired binding and a free vanity | 200; tiny URL uses the vanity; `lastAccessedAt` is now; a following resolve is 302; old code is 404 | N/A |
| Assign taken | Vanity belongs to another destination | Refused; short code unchanged | ProblemDetail 409 |
| Assign missing | Unknown long URL or tiny URL | 404; store unchanged | ProblemDetail 404 |
| Assign same code | Vanity equals this binding’s short code | 200; no field changes | N/A |
| No auth | Create or assign without Basic Auth | 401; store unchanged | Unchanged |

</frozen-after-approval>

## Code Map

- `shorty-url/src/main/java/com/shortyurl/web/CreateUrlRequest.java` -- today only `url`. Add optional `vanity`
- `shorty-url/src/main/java/com/shortyurl/web/CreateUrlController.java` -- `POST /api/v1/urls` calls `UrlCreateService.create`. Pass vanity through. 201 vs 200 stays `CreateUrlResult.created()`
- `shorty-url/src/main/java/com/shortyurl/web/SecurityConfig.java` -- `POST /api/v1/urls` and `POST /api/v1/urls/**` are already authenticated. Reuse that for assign. Do not authenticate resolve
- `shorty-url/src/main/java/com/shortyurl/service/UrlCreateService.java` -- pipeline is trim, sanitize, liveness, then lookup or insert. Validate vanity syntax, then availability, **before** liveness. On insert, use the vanity instead of `ShortCodeGenerator` when one was supplied. `DataIntegrityViolationException` may regenerate only a **generated** code; a supplied vanity that loses the race is refused, not replaced. Existing sanitized URL still goes through `reuseExisting` and ignores vanity
- `shorty-url/src/main/java/com/shortyurl/service/ShortCodeGenerator.java` -- keep for the no-vanity path. Do not use it to “fix” a taken vanity
- `shorty-url/src/main/java/com/shortyurl/persistence/Binding.java` -- `shortCode` is already unique and mutable, length 64. Do not add a column
- `shorty-url/src/main/java/com/shortyurl/persistence/BindingRepository.java` -- reuse `findByShortCode` and `findBySanitizedUrl`. `existsByShortCode` is already there
- `shorty-url/src/main/java/com/shortyurl/web/ResolveUrlController.java` -- `GET /{shortCode:(?!(?:error|api)$)[A-Za-z0-9_-]{1,64}}`. Vanity syntax must stay inside this pattern. Do not change resolve
- `shorty-url/src/main/java/com/shortyurl/web/ApiExceptionHandler.java` -- map the taken-vanity signal to ProblemDetail 409. Leave 400, 404, 410, and 502 as they are
- `shorty-url/src/main/resources/application.properties` -- `shortyurl.public-base-url` is how assign recognizes a tiny URL. Do not hard-code the host in the service
- Tests live next to `CreateUrlIT` / `ResolveUrlIT`. Cover the matrix, including “old code is 404” and “vanity on an existing long URL is not applied”
- Do not change `_bmad/` or `.agents/`

## Tasks & Acceptance

**Execution:**
- [x] `CreateUrlRequest.java` / `CreateUrlController.java` -- optional `vanity` on create; new assign route under `/api/v1/urls/vanity` -- CAP-8 HTTP surface
- [x] `UrlCreateService.java` (or a sibling assign service) -- syntax and availability before liveness; persist a free vanity; refuse a taken one without regenerating; assign replaces `shortCode`, and an expired binding’s `lastAccessedAt` becomes now -- AD-8
- [x] `ApiExceptionHandler.java` -- invalid vanity is 400; missing assign target is 404; taken vanity is 409 -- merchant-facing refusal
- [x] `CreateUrlIT.java` / `ResolveUrlIT.java` -- matrix rows, including default create still opaque, 409, old code 404, expired assign then 302, and create-does-not-upgrade -- testable

**Acceptance Criteria:**
- Given a new long URL and no vanity, when it is created with valid Basic Auth, then the response is 201 and the short code is an opaque generated code
- Given a new long URL and a free vanity, when it is created with valid Basic Auth, then the tiny URL uses that vanity and resolve returns 302 to the pasted URL
- Given a vanity already stored for another destination, when create or assign requests it, then the response is 409 and the store is unchanged
- Given an expired binding and a free vanity, when assign is called, then the new vanity resolves with 302 and `lastAccessedAt` is now
- Given a long URL that already has a binding, when create is called with a different vanity, then the response is the existing tiny URL
- Given an existing binding and a free vanity, when assign is called with valid Basic Auth, then the tiny URL uses the vanity, the original URL is unchanged, and the previous short code is 404
- Given no Basic Auth, when create or assign is called, then the response is 401 and nothing is stored

## Implementation Notes

- Create accepts optional `vanity`. `Vanity.optional` treats null or blank as “generate”. Any other value must match the resolve pattern, including the `api` / `error` exclusion. If the sanitized URL already has a binding, the vanity is ignored and create still runs liveness and `reuseExisting`. A taken vanity is 409 before liveness only when no binding exists for that URL. A free vanity is inserted as `shortCode`. A short-code unique-constraint loss on that insert is 409, not a regenerated code.
- Assign is `POST /api/v1/urls/vanity` in `UrlAssignService`. A URL under the configured public base whose remainder is a short code is looked up by short code; otherwise the long URL is sanitized. The same short code returns the row unchanged. A taken vanity is 409. An expired binding’s `lastAccessedAt` is set to the clock; a live binding keeps it. `originalUrl`, `sanitizedUrl`, and `createdAt` stay.
- `gradlew.bat test --rerun-tasks` BUILD SUCCESSFUL.

## Spec Change Log

## Review Triage Log

- **medium, patch** — `UrlCreateService.create` throws `VanityTakenException` before lookup. A create for a long URL that already has a binding then returns 409 and skips reactivation, even though the frozen rule says that create returns the current tiny URL and does not apply the vanity. `existingLongUrl_withTakenVanity_returns409AndDoesNotProbeAgain` locks the 409. Look up the sanitized URL first; 409 before liveness only when no binding exists.
- **medium, patch** — No test creates the same long URL again with the vanity already stored on that binding. Dropping the same-destination filter in `isVanityTakenByOther` would still leave the suite green, and that repeat create would 409 instead of returning the current tiny URL. Add the repeat create.
- **medium, patch** — Successful vanity create never asserts a probe, and a 404 destination is only checked on the no-vanity path. Skipping `urlLivenessChecker.check` when a vanity is present would still pass `vanityCreate_storesVanityAndResolves`. Add the 404/502 vanity case and a probe count on success.
- **medium, patch** — No test assigns a stored long URL that starts with the public base but is not itself a short code. Removing the short-code match in `UrlAssignService.locate` would still pass, and `http://localhost:8080/docs/guide` would 404. Add that assign.
- **defer** — `persistVanity` and `UrlAssignService.assign` map a lost `uk_bindings_short_code` race to 409, and no test raises `DataIntegrityViolationException` after the pre-check. Restoring a generated code, or dropping the assign catch, would still pass. The non-racing 409 is already asserted.
- **false** — A long URL that starts with the public base and whose remainder is a short code is looked up as that short code. The design notes require that classification.
- **low, rejected** — A tiny URL with a trailing slash, query, or different host case is not recognized as the code the API returned. Merchants use the tiny URL from the response. Extra URL normalization is more than a direct correction.
- **low, rejected** — Two concurrent assigns of one binding to two free vanities can both return 200, and the earlier short code is lost. The skew needs overlapping writes. A row version or lock is extra complexity.
- **low, rejected** — A sanitized URL longer than the column, on a vanity insert, is reported as 409. The request URL is already capped at 2048 characters. Parsing constraint names to split that from a taken code is extra.
- **low, rejected** — The resolve route and `Vanity.SHORT_CODE` are separate copies of the same pattern, and public-base slash trimming is duplicated. A shared helper is new structure. The copies match today.
- **low, rejected** — A blank assign vanity and an illegal vanity produce different 400 ProblemDetail bodies. Both are 400, which is what the spec requires.
- **low, rejected** — Assign’s 400 test only sends `api`. Create’s parameterized test already rejects `error`, a slash, and 65 characters through the same `Vanity.require`.
- **false** — The review diff shows mojibake in the story text. That is the temp diff’s encoding. The spec file on disk does not contain those sequences.
- **low, rejected** — Unknown long URLs and unknown tiny URLs on assign share the detail `No binding found for url`. Both are 404, which is what the spec requires.

## Design Notes

Assign looks up `url` as a tiny URL when it starts with the configured public base and the remainder matches the short-code pattern; otherwise it sanitizes `url` and looks up by `sanitizedUrl`. A supplied vanity that hits `uk_bindings_short_code` is a refusal, not another attempt with a generated code.

## Verification

**Commands:**
- `shorty-url/gradlew.bat test --rerun-tasks` -- expected: BUILD SUCCESSFUL

**Manual checks (if no CLI):**
- Create with a vanity, open that path, then assign a second free vanity and confirm the first path no longer redirects.
