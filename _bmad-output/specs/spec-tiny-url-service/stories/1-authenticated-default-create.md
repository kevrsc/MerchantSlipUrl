---
title: 'Authenticated default create'
type: 'feature'
created: '2026-09-25'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 'NO_VCS'
context:
  - '{project-root}/_bmad-output/specs/spec-tiny-url-service/SPEC.md'
  - '{project-root}/_bmad-output/specs/spec-tiny-url-service/create-pipeline.md'
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-ShortyUrlBmad-2026-09-25/ARCHITECTURE-SPINE.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Merchants need an authenticated way to mint a persistent, idempotent default tiny URL for a long order-site URL, and see both the tiny URL and the original long URL in the response — without building resolve, liveness, expiry, or vanity yet.

**Approach:** Greenfield a layered Spring Boot 4.1.1 / Java 21 / Gradle service with H2 + JPA + Security; expose Basic Auth create that sanitizes, atomically binds (or returns existing), and returns JSON with tiny URL + original long URL. Liveness, public resolve, inactivity expiry, and vanity are out of this story.

## Boundaries & Constraints

**Always:**
- Layered packages: `web` → `service` → `persistence` (AD-1); service is sole mutator under transactions (AD-2)
- Binding identity: stable surrogate PK; unique `sanitizedUrl` for idempotency; unique opaque mutable `shortCode` that does not encode destination (AD-8)
- Store the original merchant-pasted long URL string
- Create requires HTTP Basic Auth via one Security filter chain path rule (AD-5)
- Stack: Spring Boot 4.1.1, Java 21, Gradle, webmvc, security, data-jpa, H2, embedded Tomcat (restclient may be added but unused until story 3)
- CAP-1, CAP-3, CAP-5, CAP-6 for this story only

**Never:**
- Implement CAP-2 resolve, CAP-4 liveness, CAP-7 expiry, or CAP-8 vanity in this story
- Controllers writing repositories directly
- Encoding order/destination data into `shortCode`
- Multi-instance or external DB assumptions

**Decisions:**
- Local Basic Auth credentials: username `merchant`, password `merchant` in `application.properties`
- Returned `tinyUrl` is composed with public base `http://localhost:8080/`

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Happy create | Valid Basic Auth + JSON `{ "url": "<long>" }` | 201/200 with `{ "tinyUrl", "originalUrl" }`; binding persisted | N/A |
| Idempotent create | Same sanitized URL twice | Same `tinyUrl` both times; no second binding | N/A |
| Different URLs | Two distinct sanitized URLs | Distinct `tinyUrl` values | N/A |
| Missing auth | No/invalid Basic Auth | 401; nothing stored | Reject before service write |
| Invalid body | Missing/blank `url` | 400; nothing stored | Validation error body |

</frozen-after-approval>

## Code Map

- _(greenfield — no application source yet)_
- `_bmad-output/specs/spec-tiny-url-service/SPEC.md` -- CAP-1/3/5/6 contract
- `_bmad-output/specs/spec-tiny-url-service/create-pipeline.md` -- create steps; skip liveness/vanity this story
- `_bmad-output/planning-artifacts/architecture/architecture-ShortyUrlBmad-2026-09-25/ARCHITECTURE-SPINE.md` -- AD-1..8, stack, package seed
- `shorty-url/` _(to create)_ -- Spring Boot root (Gradle, Java 21)
- `shorty-url/src/main/java/com/shortyurl/.../web/` -- create controller + Security filter chain
- `shorty-url/src/main/java/com/shortyurl/.../service/` -- create service, sanitize, shortCode generator
- `shorty-url/src/main/java/com/shortyurl/.../persistence/` -- Binding entity + repository
- `shorty-url/src/main/resources/application.properties` -- H2, Basic Auth user, server port
- Do not change BMAD tooling under `_bmad/`, `.agents/`, or planning memlogs during implementation

## Tasks & Acceptance

**Execution:**
- [x] `shorty-url/settings.gradle(.kts)` + `build.gradle(.kts)` -- Scaffold Spring Boot 4.1.1 Gradle project with Java 21 and starters webmvc, security, data-jpa, H2 (+ test starters) -- cold-start stack pin
- [x] `shorty-url/src/main/java/com/shortyurl/ShortyUrlApplication.java` -- Boot entrypoint -- runnable service
- [x] `shorty-url/.../persistence/Binding.java` + `BindingRepository.java` -- Entity with id, shortCode, originalUrl, sanitizedUrl, timestamps; unique constraints; repository finders -- AD-3/AD-8 store
- [x] `shorty-url/.../service/UrlSanitizer.java` + `ShortCodeGenerator.java` -- Stable sanitize key; opaque unique short codes -- idempotency + mute short form
- [x] `shorty-url/.../service/UrlCreateService.java` -- Transactional create: sanitize → lookup → bind or return existing -- CAP-1/3, AD-2
- [x] `shorty-url/.../web/CreateUrlController.java` + DTOs -- POST create endpoint; JSON response tinyUrl + originalUrl -- CAP-5
- [x] `shorty-url/.../web/SecurityConfig.java` -- Basic Auth required on create path; leave room for later resolve permitAll -- CAP-6, AD-5
- [x] `shorty-url/src/main/resources/application.properties` -- H2, auth user, port, tiny-url base -- local runtime
- [x] `shorty-url/src/test/java/...` -- Tests for I/O matrix (auth, happy, idempotent, validation) -- Ready-for-dev testable

**Acceptance Criteria:**
- Given valid Basic Auth and a long URL, when create is called, then the response includes a tiny URL and the same original URL, and a binding exists in H2
- Given the same long URL (after sanitize) is created twice, when both succeed, then both responses share the same tiny URL
- Given no or invalid Basic Auth, when create is called, then the response is 401 and no binding is stored
- Given a blank or missing URL, when create is called with auth, then the response is 400 and no binding is stored
- Given two different long URLs, when both are created, then they receive different tiny URLs whose short codes do not embed destination content

## Implementation Notes

- Greenfield `shorty-url/` Spring Boot 4.1.1 / Java 21 / Gradle; Foojay toolchain for JDK 21 on host with JDK 25.
- `POST /api/v1/urls` with Basic Auth `merchant`/`merchant`; tiny URL base `http://localhost:8080/`.
- Matrix covered by `CreateUrlIT` + `UrlSanitizerTest`; `gradlew.bat test --rerun-tasks` BUILD SUCCESSFUL.
- Review patches: DataIntegrityViolationException recovery on create; `@Size(max=2048)`; invalid-URL IT + sanitizer hostless/malformed tests; gradle-wrapper.jar regenerated; h2console removed.

## Spec Change Log

## Review Triage Log

| Finding | Verdict | Evidence / route |
| --- | --- | --- |
| Concurrent same-sanitizedUrl create can hit unique constraint (UrlCreateService check-then-act) | medium | Confirmed at UrlCreateService.create:31-33; no DataIntegrityViolationException recovery. **patch** |
| Short-code TOCTOU between existsByShortCode and save | medium | Confirmed allocateUniqueShortCode:44-51; collision on insert unhandled. **patch** |
| Intent claim “atomically binds or returns existing” fails under concurrency | medium | Same root as concurrent sanitizedUrl race. **patch** (grouped) |
| CreateUrlRequest missing @Size(max=2048) vs column length | medium | Confirmed CreateUrlRequest only @NotBlank; Binding columns 2048. **patch** |
| InvalidUrlException→HTTP 400 never exercised in IT | medium | verification-gap pre-verified; CreateUrlIT only covers @NotBlank path. **patch** |
| Hostless/malformed URI sanitizer branches untested | medium | verification-gap pre-verified; UrlSanitizerTest lacks hostless/malformed. **patch** |
| Short-code collision regenerate path untested | medium (unverified defer) | Rare under 7-byte entropy; story notes race hardening later. **defer** |
| gradle-wrapper.jar missing from tree | high | Glob found 0 jar; wrapper scripts alone cannot bootstrap. **patch** |
| UrlSanitizer does not collapse trailing-slash equivalents | low | Architecture deferred exact sanitize rules; uniqueness of sanitizedUrl still holds. **defer** |
| ApiExceptionHandler incomplete for IllegalStateException / integrity | low | Everyday create path uses validation + InvalidUrlException; rare exhaustion. Reject (unlikely everyday + non-trivial surface) |
| HELP.md listed in .gitignore | low | Cosmetic; unlikely everyday harm. Reject |
| Security anyRequest.permitAll + h2console dependency | medium | Confirmed SecurityConfig + build.gradle h2console; console could be anonymous. **patch** (remove unused h2console) |
| CreateUrlIT.readTinyUrl fragile string parse | low | Works for current JSON; fix adds complexity. Reject |
| No dedicated short-code format/length assertion | low | differentUrls IT already asserts mute short form. Reject |
| Idempotent create returns first-stored originalUrl | false | Spec requires store original merchant string; returning stored original is correct |

## Design Notes

Story 1 intentionally omits create-pipeline step 4 (liveness). Story 3 owns CAP-4. Create path: auth → accept → sanitize → lookup → atomic bind.

Default shortCode: cryptographically random URL-safe opaque token (length ~8–10), uniqueness enforced by DB unique constraint with regenerate-on-collision. Sanitize (agent-owned): trim; reject non-http(s); normalize with `URI` (lowercase host); drop fragment; stable string for `sanitizedUrl`.

Suggested create route: `POST /api/v1/urls` with body `{ "url": "..." }` and response `{ "tinyUrl": "http://localhost:8080/{shortCode}", "originalUrl": "..." }`.

## Verification

**Commands:**
- `cd shorty-url && ./gradlew test` (or `gradlew.bat test` on Windows) -- expected: all tests green
- `cd shorty-url && ./gradlew bootRun` -- expected: app starts on configured port

**Manual checks (if no CLI):**
- curl create with Basic Auth returns tinyUrl + originalUrl; repeat yields same tinyUrl; without auth → 401
