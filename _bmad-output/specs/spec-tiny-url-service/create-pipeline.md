# Create pipeline

Ordered steps for minting a tiny URL. Each step must complete before the next. Nothing is stored until the final bind succeeds.

1. **Authenticate** with HTTP Basic Auth. Reject unauthenticated or invalid credentials; store nothing.
2. **Accept** the merchant's long URL string, and optionally a custom/vanity short form.
3. **Sanitize** the long URL into the form used for lookup and storage keys. Validate vanity form if provided (syntax and uniqueness rules).
4. **Liveness check** the original merchant-pasted URL. Do not follow redirects. Each probe waits up to 30 seconds and reads at most 64 KiB of the body.
   - Client errors such as 404: fail immediately; refuse and store nothing.
   - Timeouts and 5xx: retry the **same** long-URL probe up to **3** attempts total; if still failing after those attempts, refuse and store nothing.
   - HTTP 2xx or 3xx on that URL: proceed. Reaching the body limit does not fail a 2xx or 3xx. Do not request the redirect target.
5. **Lookup** by sanitized long URL. If a binding exists and it is still inside the inactivity window, return that tiny URL and stop; write nothing. If that binding is expired, keep the row, return the same tiny URL, and set last access to now so the window starts again. (Vanity upgrade of an existing binding is a separate path below.)
6. **Atomic bind** a new unique tiny URL (system default, or the requested available vanity) to the **original** merchant-pasted long URL string (not the post-redirect location). Write both together or neither. Concurrent creates for different destinations must not collide onto one tiny URL; concurrent creates for the same sanitized long URL must converge on one binding. A vanity already bound to a different destination is refused.

## Vanity assign (existing binding)

Authenticated merchant identifies an existing binding (by tiny URL or by long URL) and requests an available vanity. On success, the short form becomes that vanity while the stored original long URL stays the same. A vanity taken by another destination is refused.

## Resolve path

Given a tiny URL: if missing or expired by inactivity, do not return the destination. If active, return or navigate to the stored original long URL and record access so the inactivity window resets. Inactivity period is configurable (5 minutes for initial testing).
