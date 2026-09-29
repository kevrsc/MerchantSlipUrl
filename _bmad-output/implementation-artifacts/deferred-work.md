# Deferred work

- source_spec: `_bmad-output/specs/spec-tiny-url-service/stories/1-authenticated-default-create.md`
  summary: Add a unit test that forces short-code collision and asserts regenerate-or-fail behavior
  evidence: allocateUniqueShortCode / DataIntegrityViolationException shortCode retry path is untested; rare under 7-byte entropy but regenerate could silently regress

- source_spec: `_bmad-output/specs/spec-tiny-url-service/stories/1-authenticated-default-create.md`
  summary: Decide whether trailing-slash (and similar) URL equivalents should collapse in UrlSanitizer
  evidence: Architecture deferred exact sanitize rules; current sanitizer does not normalize path trailing slash, so two bindings can exist for near-equivalent destinations

- source_spec: `_bmad-output/specs/spec-tiny-url-service/stories/2-resolve-tiny-url-to-original-destination.md`
  summary: Harden resolve against illegal originalUrl when building Location (URI.create) without updating lastAccessedAt on failure
  evidence: Unverified edge case; create path only stores validated http(s) URLs, so would require corrupted/manual DB rows to trigger

- source_spec: `_bmad-output/specs/spec-tiny-url-service/stories/3-liveness-gate-before-mint.md`
  summary: Confirm whether a 2xx or 3xx with a null response body is refused as a transient liveness failure
  evidence: `UrlLivenessChecker.openBody` throws `TransientLivenessException` when `getBody()` returns null for a non-4xx status. Maybe-false, would be medium if true. Settled by checking whether `JdkClientHttpRequest` returns null or an empty stream for 204 and 302.

- source_spec: `_bmad-output/specs/spec-tiny-url-service/stories/3-liveness-gate-before-mint.md`
  summary: A 2xx or 3xx with a null response body is liveness success
  evidence: Human resolved the earlier maybe-false. `UrlLivenessChecker.openBody` now treats a null body on 2xx or 3xx as an empty successful body.

- source_spec: `_bmad-output/specs/spec-tiny-url-service/stories/3-liveness-gate-before-mint.md`
  summary: Prior story-3 review triage (redirect-following and uncapped-body pass) stays out of the implementation spec
  evidence: The spec was ~3500 tokens because that triage log sat in the story file. User chose Split. Human already settled the two open items — do not follow redirects (3xx on the pasted URL is success); read at most 64 KiB and do not fail a 2xx or 3xx for size. Rejected or false on that pass — leftover 3xx status policy, cookie jar, browser user-agent, stacked timeouts, HttpClient close, maxAttempts below 1 or Integer.MAX_VALUE, missing 502 server log, extra create-IT rows already covered by the checker tests, resolve-time probe (resolve does not call RestClient), and ProblemDetail title on the timeout IT. Still required on the next derivation — a test that the probe uses the pasted URL rather than the sanitized key, and a default-profile check that attempts=3, timeout=30s, and max-body-bytes=65536.

- source_spec: `_bmad-output/specs/spec-tiny-url-service/stories/4-configurable-inactivity-expiry.md`
  summary: Cover reactivation on the sanitized-URL unique-constraint catch, not only the find-then-reuse path
  evidence: `UrlCreateService.persistNewBinding` now calls `reuseExisting` after `DataIntegrityViolationException` when the sanitized URL already exists. No test raises that exception, so restoring “return the row unchanged” would still pass. The branch runs only after a lost insert race; find-then-reuse reactivation is already asserted.

- source_spec: `_bmad-output/specs/spec-tiny-url-service/stories/5-vanity-create-and-assign.md`
  summary: Cover a lost short-code unique-constraint race on vanity create and assign
  evidence: `persistVanity` and `UrlAssignService.assign` map `DataIntegrityViolationException` to 409 after the pre-check misses. No test raises that exception, so regenerating a supplied vanity or dropping the assign catch would still pass. The non-racing 409 is already asserted in `CreateUrlIT` and `ResolveUrlIT`.

## Deferred from: code review of 5-vanity-create-and-assign.md (2026-09-29)

- Lost unique-constraint races are not raised in tests. `persistVanity` and `UrlAssignService.assign` map a lost `uk_bindings_short_code` race to 409, and `persistNewBinding` retries a generated code. No test raises `DataIntegrityViolationException` after the pre-check. The non-racing 409 is already asserted. Same gap as the story 5 deferral above.
