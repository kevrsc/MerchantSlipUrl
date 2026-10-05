# Agent notes

Shorty is a Java 21 Spring Boot URL shortener. Application code lives in `shorty-url/`. The product contract is `_bmad-output/specs/spec-tiny-url-service/SPEC.md`, with the ordered create steps in `create-pipeline.md` beside it.

## Commands

Run these from `shorty-url`. Use the wrapper, not a globally installed Gradle.

- `gradlew.bat bootRun` starts the service on port 8080.
- `gradlew.bat test` runs the suite. On macOS or Linux the command is `./gradlew`.

The test profile sets inactivity to 5 seconds and the liveness timeout to 500 milliseconds. Production defaults are 5 minutes and 30 seconds. Do not "fix" a test by sleeping for the production period.

## Where to change behavior

- HTTP and problem details: `shorty-url/src/main/java/com/shortyurl/web/`
- Create, assign, resolve, sanitize, and liveness: `.../service/`
- The only persisted type is `persistence/Binding.java`. `sanitizedUrl` and `shortCode` are each unique. `shortCode` may change on vanity assign. `originalUrl` stays the merchant's paste.
- Time comes from the injected `Clock`. Tests move `MutableClock`. Do not call `Instant.now()` for expiry decisions.
- Merchant page: `shorty-url/src/main/resources/static/index.html`. It calls the same API as `curl`.

Create and assign require HTTP Basic Auth. `GET /{shortCode}` does not. Do not put create credentials on resolve.

## Behavior that is easy to get wrong

- Liveness probes the trimmed pasted URL and does not follow redirects. A 3xx on that URL is success. Store the paste, not the redirect target.
- A 4xx fails once. Timeout and 5xx retry up to `shortyurl.liveness.max-attempts`. Hitting the body cap does not fail a 2xx or 3xx.
- If a sanitized URL already has a binding, create returns that row's current short code and does not apply a requested vanity. A taken vanity is 409 only when the destination is new.
- After the probe, reload the binding before returning it. The short code may have changed during the check.
- A failed probe does not refresh `lastAccessedAt` on an expired row.
- Expiry uses `lastAccessedAt` when it is set, otherwise `createdAt`. Resolve writes `lastAccessedAt` only after the code is still active.
- Reserved vanity names are the exact strings `api` and `error`.

## Leave alone unless asked

- `_bmad/` and `.agents/` are workflow tooling, not the service.
- Story files under `_bmad-output/specs/spec-tiny-url-service/stories/` contain a frozen intent section. Do not rewrite that section to match the code. If the code and the spec disagree, stop and say so.
- H2 is in-memory. Bindings do not survive a restart. Do not add a file database or a migration tool for a behavior the spec does not ask for.
- Default credentials in `application.properties` are `merchant` / `merchant`. Do not invent a second account.

## Tests

Add the check next to the behavior it locks: `UrlLivenessCheckerTest` for the probe, `CreateUrlIT` for create and vanity create, `ResolveUrlIT` for resolve, expiry, and assign. Assert status, the stored row, and probe count when the change is about liveness. Prefer the mutable clock over wall-clock timeouts.
