---
title: 'Liveness gate before mint'
type: 'feature'
created: '2026-09-25'
status: 'done'
route: 'dispatch'
review_loop_iteration: 2
baseline_commit: '5057d505d6608ce5108709914034081480292e07'
context:
  - '{project-root}/_bmad-output/specs/spec-tiny-url-service/SPEC.md'
  - '{project-root}/_bmad-output/specs/spec-tiny-url-service/create-pipeline.md'
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-ShortyUrlBmad-2026-09-25/ARCHITECTURE-SPINE.md'
  - '{project-root}/_bmad-output/specs/spec-tiny-url-service/stories/1-authenticated-default-create.md'
  - '{project-root}/_bmad-output/specs/spec-tiny-url-service/stories/2-resolve-tiny-url-to-original-destination.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Create currently mints bindings without verifying the merchant’s long URL is reachable, so a 404 or dead destination can still get a tiny URL (CAP-4 gap; create-pipeline step 4 skipped in story 1).

**Approach:** Add a service-layer RestClient liveness probe of the original pasted URL after sanitize and before lookup/bind. Do not follow redirects: 2xx or 3xx succeeds on that response; 404-class fails once with no store; timeout/5xx retry the same probe up to 3 attempts × 30s request timeout each, then refuse with no store. Read at most a fixed body limit; reaching that limit does not fail a 2xx or 3xx.

## Boundaries & Constraints

**Always:**
- Layered web → service → persistence; web never probes (AD-1, AD-4)
- Probe the **original** merchant-pasted URL (trimmed), not the sanitized key; still store the original string on success (AD-4)
- Liveness runs **outside** the binding write transaction; TX remains lookup + insert only (AD-2/AD-4)
- Pipeline order: sanitize → **liveness** → lookup → atomic bind (create-pipeline step 4–6)
- Do **not** follow redirects. A 3xx response is liveness success. Do not request the `Location` target
- Success is HTTP 2xx or 3xx on the probed URL itself
- Read at most **64 KiB** of the response body. Reaching that limit is not a failure: 2xx and 3xx still succeed; 4xx still fails immediately; 5xx and timeouts still retry. Truncation never flips a 4xx or 5xx into success
- Client errors (4xx, e.g. 404): fail immediately, no retry, no store
- Timeout / IO failure / 5xx: up to **3** attempts of the same probe, **30s** per-attempt request timeout, then refuse with no store
- Existing create auth, sanitize, idempotent bind, resolve behavior stay intact once a destination passes the gate
- CAP-4 only for this story

**Never:**
- Controllers or web layer owning liveness / retry policy
- HTTP I/O inside the binding `TransactionTemplate`
- Storing a binding when liveness fails
- Changing resolve, inactivity expiry (story 4), or vanity (story 5)
- Replacing RestClient with WebClient/RestTemplate for this gate

**Decisions:**
- Add `spring-boot-starter-restclient` and inject `RestClient.Builder` into a dedicated service-layer checker used by `UrlCreateService`
- Transport/connect failures and request timeouts count as the timeout/retry class (same as 5xx), not as 404-class
- Default production settings: 3 attempts, 30s timeout; expose via config so tests can shorten timeouts without changing retry semantics
- Update existing create ITs (and create-then-resolve IT) to stub a live destination; add matrix coverage for 404 immediate and exhausted timeout/5xx refuse with `count()==0`
- Liveness refuse returns **502 Bad Gateway** to the merchant (ProblemDetail), framing upstream destination failure
- Redirect policy: stop at the first response. 3xx means the pasted URL is live
- Body cap: `shortyurl.liveness.max-body-bytes` defaults to **65536**. Tests may lower it. Hitting the cap while reading a 2xx or 3xx still counts as success
- A 2xx or 3xx with a null response body is liveness success

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Happy 2xx | Auth + URL whose probe returns 2xx | Create succeeds; binding stored; same response shape as today | N/A |
| Redirect | Probe returns 3xx | Create succeeds; **one** probe; `Location` is not requested; stored `originalUrl` is the merchant paste | N/A |
| Large body | 2xx or 3xx whose body exceeds 64 KiB | Create succeeds; read stops at the cap | N/A |
| Destination 404 | Probe returns 404 | **502**; **one** probe; store unchanged (`count` unchanged / zero) | ProblemDetail 502 |
| Other 4xx | Probe returns e.g. 401/403 | Same as 404: immediate **502**, no retry, no store | ProblemDetail 502 |
| Transient then OK | First probe 5xx/timeout, later 2xx within 3 attempts | Create succeeds after retry | N/A |
| Exhausted 5xx/timeout | All 3 probes fail with 5xx or timeout | **502**; no store | ProblemDetail 502 |
| Idempotent after live | Same sanitized URL twice, both probes 2xx | Second returns existing tiny URL; still one binding | N/A |
| Auth still first | Missing/invalid Basic Auth | 401; no probe needed; no store | Unchanged |

</frozen-after-approval>

## Code Map

- `shorty-url/build.gradle.kts` -- add `spring-boot-starter-restclient`; test stub via MockWebServer
- `shorty-url/src/main/resources/application.properties` -- `shortyurl.liveness.max-attempts=3`, `timeout=30s`, `max-body-bytes=65536`; test profile shortens timeout and may lower the cap
- `shorty-url/src/main/java/com/shortyurl/service/UrlCreateService.java` -- after sanitize, `check(originalUrl)` before lookup/bind; transaction wraps only save
- `shorty-url/src/main/java/com/shortyurl/service/UrlLivenessChecker.java` _(add)_ -- RestClient GET, redirects off; 2xx/3xx success; 4xx fail once; timeout/5xx retry; stop body read at the cap. The per-attempt timeout bounds the whole attempt, including bytes read after response headers. A stall mid-body fails that attempt within the timeout and uses the timeout retry path
- `shorty-url/src/main/java/com/shortyurl/service/LivenessFailedException.java` _(add)_ -- refuse signal
- `shorty-url/src/main/java/com/shortyurl/web/ApiExceptionHandler.java` -- map that exception to ProblemDetail 502
- `shorty-url/src/main/java/com/shortyurl/web/CreateUrlController.java` -- leave thin
- `shorty-url/src/test/java/com/shortyurl/web/CreateUrlIT.java` -- stub destinations; cover the matrix, including pasted URL vs sanitized key
- `shorty-url/src/test/java/com/shortyurl/web/ResolveUrlIT.java` -- create-then-resolve must stub a live destination
- `shorty-url/src/test/java/com/shortyurl/service/UrlLivenessCheckerTest.java` _(add)_ -- retry vs immediate 4xx, 3xx not followed, body cap
- Do not change `_bmad/` or `.agents/`

## Tasks & Acceptance

**Execution:**
- [x] `shorty-url/build.gradle.kts` -- restclient starter and MockWebServer -- AD-4 stack
- [x] `application.properties` (+ test props) -- attempts, timeout, max-body-bytes -- CAP-4 timings
- [x] `UrlLivenessChecker` + `LivenessFailedException` -- pasted URL; no redirect follow; 2xx/3xx success; 4xx once; timeout/5xx ≤3; body stops at the cap; the attempt timeout also bounds the body read after headers -- CAP-4
- [x] `UrlCreateService.java` -- liveness after sanitize, before lookup/bind, outside the bind transaction -- AD-4
- [x] `ApiExceptionHandler.java` -- liveness refuse is **502** -- merchant informed
- [x] `CreateUrlIT` (+ `ResolveUrlIT` create path) -- matrix rows, including 3xx-without-follow, body cap, and pasted URL vs sanitized key -- testable
- [x] Default-profile property check -- attempts=3, timeout=30s, max-body-bytes=65536 without the test profile -- config cannot drift

**Acceptance Criteria:**
- Given a probe of 2xx or 3xx, when create runs, then a binding is stored, a 3xx `Location` is not requested, and the response matches today’s success shape
- Given a 2xx or 3xx body over the cap, when create runs, then reading stops at the cap and create still succeeds
- Given 404 or another 4xx, when create runs, then the response is **502**, one probe runs, and nothing is stored
- Given timeout or 5xx on every attempt, when create runs, then after at most 3 probes the response is **502** and nothing is stored
- Given a timeout or 5xx followed by 2xx within 3 attempts, when create runs, then a binding is stored
- Given liveness failure, when create is attempted, then no new binding is stored

## Implementation Notes

- First implementation was reverted. Human decision: do not follow redirects (3xx on the pasted URL is success); read at most 64 KiB and do not fail a 2xx or 3xx for size. Prior review triage was split to `deferred-work.md`.
- `UrlLivenessChecker` GETs the trimmed paste with redirects disabled. 2xx and 3xx succeed. 4xx throws immediately. Timeout, I/O, and 5xx retry up to `max-attempts`. The body read stops at `max-body-bytes` and closes the stream so the remainder is not drained.
- `UrlCreateService` calls the checker after sanitize and before lookup. The bind transaction still wraps only the insert.
- `ApiExceptionHandler` maps `LivenessFailedException` to ProblemDetail 502. Production defaults are 3 attempts, 30s, and 65536 bytes. Tests use a 500ms timeout and a 128-byte cap. `LivenessDefaultsTest` loads the default profile.
- `gradlew.bat test --rerun-tasks` BUILD SUCCESSFUL.
- `UrlLivenessChecker` probes the trimmed pasted URL with RestClient GET, redirects disabled, before lookup. 4xx fails once; timeout/5xx retry up to 3. The response stream is closed at the body cap so RestClient does not drain the rest. `LivenessFailedException` maps to ProblemDetail 502. Test profile uses a 500ms timeout and a 128-byte cap. `gradlew.bat test --rerun-tasks` BUILD SUCCESSFUL.
- Reverted again. The header timeout did not cover a stalled body. Re-derive with the attempt timeout bounding the body read.
- Re-derived. `setReadTimeout` closes a body that stalls after headers. `stalledBody_failsWithinAttemptTimeoutAndRetries` takes three attempts inside the timeout budget. A capped 2xx or 3xx still returns without waiting out the timeout. `gradlew.bat test --tests` for the liveness, create, resolve, and defaults classes: BUILD SUCCESSFUL.
- Human decision: a 2xx or 3xx whose response body is null is liveness success. `openBody` treats that as an empty body.
- `UrlLivenessChecker` GETs the trimmed paste with RestClient, redirects disabled, and `JdkClientHttpRequestFactory.setReadTimeout` so the attempt deadline closes the body stream after headers too. The checker stops at `max-body-bytes` and closes that stream, so a 2xx or 3xx cap does not wait out the timeout or drain the rest. 4xx fails once even if the body then stalls. Timeout, I/O, and 5xx retry up to `max-attempts`. `LivenessFailedException` is ProblemDetail 502. Test profile: 500ms and 128 bytes. `LivenessDefaultsTest` checks 3, 30s, and 65536 on the default profile. `gradlew.bat test --rerun-tasks` BUILD SUCCESSFUL (44 tests, 0 skipped, 0 failed).

## Spec Change Log

- Trigger: token split after the redirect and body-cap decision. Spec regenerated to the liveness gate only. Prior triage lives in deferred-work. KEEP: 502 on refuse; probe outside the bind transaction; probe the pasted URL; 4xx once; timeout/5xx up to 3×30s; do not follow redirects; 64 KiB cap does not fail 2xx/3xx; store the pasted URL.
- Trigger: review found the per-attempt timeout ended when headers arrived, so a stalled body could block create past 30s. Amended the code map, the checker task, and design notes. Known-bad state avoided: a body trickle after headers holding the attempt open with no retry. KEEP: 502 on refuse; probe outside the bind transaction; probe the pasted URL with GET; do not follow redirects; 2xx/3xx success; 4xx once even if the body then fails; timeout/5xx up to 3 attempts; the attempt timeout bounds connect, headers, and body read; 64 KiB cap does not fail a 2xx or 3xx and must not wait out the timeout; store the pasted URL.

## Review Triage Log

| Finding | Verdict | Evidence |
|---|---|---|
| Per-attempt timeout ends at response headers, so a stalled body blocks past 30s | medium | JDK request timeout completes when headers arrive. `readAtMost` then blocks on `InputStream.read` with no deadline. Create can hang past the configured attempt timeout. |
| Body-cap tests write the capped prefix before sleeping, so they never block in `read` | low | The tests show a fast success after a full prefix, not a mid-body stall. Rejected as its own defect; the stall is the timeout finding above. |
| Locally rejected URLs (port 99999) are retried and returned as 502 | false | Frozen rule puts transport and connect failures in the timeout/5xx retry class. No request on the wire is still a transport failure. |
| Probe can target loopback, private, or link-local hosts | false | Frozen rule is to probe the pasted URL. Refusing those hosts would reject the URL the merchant submitted. Redirect following, which could leave that host, is already disabled. |
| No test for a 5xx body over the cap | low | Status is applied after a capped read, so a 5xx stays a 5xx. Rejected: everyday order bodies are small, and a new 5xx-cap test is extra coverage rather than a direct correction. |
| No connection-refused test | low | `RuntimeException` already retries, and the timeout tests cover that path. Rejected. |
| Elapsed bounds under 250ms can flake | low | The suite passed. Rejected: loosening timers is extra test tuning. |
| `deferred-work.md` still lists tests this change added | low | Those tests exist. Existing deferred entries are left unchanged. Rejected. |
| Architecture memlog still says follow redirects | false | That line is the earlier decision record. The spine rule and spec memlog now say 3xx is success and redirects are not followed. |
| SPEC success signal omits 3xx and the body cap | low | CAP-4, constraints, and assumptions already state both. The success-signal sentence is a short summary. Rejected. |
| Walkthrough omits the 64 KiB cap | low | The spine and create pipeline state the cap. Rejected. |
| Implementation notes repeat the same summary | low | Append-only notes. Rejected. |
| `maxAttempts == Integer.MAX_VALUE` wraps the retry loop | low | Only that config value. Production default is 3. Rejected. |
| No test times the configured timeout | medium | `LivenessDefaultsTest` checks the property value. Timeout tests assert failure and request count, not elapsed time. A 1ms read timeout would still pass. Filed as patch; moot while the timeout bug is re-derived. |
| No test asserts the probe method is GET | medium | Recorded requests check path and count only. `restClient.head()` would still pass. Filed as patch; moot while the timeout bug is re-derived. |
| Body `IOException` after a status code is untested | medium | 4xx tests use a finished body. Deleting the 4xx `IOException` branch could retry a 404 into a later 200 with the suite still green. Filed as patch; moot while the timeout bug is re-derived. |
| `maxAttempts == Integer.MAX_VALUE` wraps the retry loop | low | carried. Only that config value. Production default is 3. Rejected. |
| Probe of a pasted loopback, private, or link-local host | false | carried. Frozen rule is to probe the pasted URL. Redirect following is disabled. |
| Elapsed bounds on body-cap tests can flake | low | carried. The suite passed. Rejected. |
| `deferred-work.md` still lists tests this change added | low | carried. Those tests exist. Existing deferred entries stay unchanged. Rejected. |
| Walkthrough omits the 64 KiB cap | low | carried. The spine and create pipeline state the cap. Rejected. |
| Embedded `user:pass@` is not sent as Authorization | low | JDK client does not turn userinfo into a header. Order URLs in this product use query tokens. A credential header would be new behavior. Rejected. |
| Create can hold a server thread for 3× the attempt timeout | low | Sync RestClient on the create thread is the approved client. Rejected. |
| `missingUrl` does not assert that no probe ran | low | Missing URL fails bean validation before the service probe. The test still expects 400 and an empty store. Rejected. |
| Tiny-URL test still forbids `orders.example.com` | medium | The fixture host is the mock server. A short code that embeds `127.0.0.1` would still pass. |
| Probe query string is not asserted | low | The checker sends `URI.create` of the full paste. The path test already records the request target. Rejected. |
| Second equivalent paste is not checked on the wire | low | The first probe records the un-normalized path. Rejected. |
| Pipeline and spine omit the mid-body stall sentence | low | The story design notes and SPEC success signal already require the 30s budget to include the body read. Rejected. |
| `max-body-bytes` of 0 still treats 2xx as success | false | A cap of 0 means read nothing. Reaching that limit is defined as success for 2xx and 3xx. |
| 4xx `getBody()` `IOException` can still be retried | medium | The 4xx stall test fails during `read`, after `getBody()` returns. Dropping the `getBody()` 4xx branch would retry into a later 200 with current tests still green. |
| Null body on 2xx or 3xx is classified as a retryable failure | maybe-false | `openBody` throws `TransientLivenessException` when `getBody()` returns null and the status is not 4xx. Unconfirmed whether the JDK client returns null or an empty stream for 204/302. |

## Design Notes

Order: trim → sanitize → liveness(originalUrl) → lookup by sanitized URL → transaction bind. A later create of a now-dead destination does not return the existing tiny URL.

Disable redirect following. Tests use a short timeout. A 3xx test shows one request and stores the paste. A body-cap test uses a limit smaller than the stubbed body.

The configured per-attempt timeout covers connect, response headers, and the body read. Header-only timeouts are not enough: if headers arrive and the body then stalls, that attempt must still end within the timeout and count as a timeout failure (retry up to 3, then 502, no store). Reaching the body cap on a 2xx or 3xx still succeeds and must not wait out the timeout.

## Verification

**Commands:**
- `shorty-url/gradlew.bat test --rerun-tasks` -- expected: BUILD SUCCESSFUL

**Manual checks (if no CLI):**
- Stub 200: authenticated `POST /api/v1/urls` succeeds. Stub 404: 502 and no binding. Stub 302: succeeds with one probe.
