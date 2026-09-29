---
id: SPEC-tiny-url-service
companions:
  - create-pipeline.md
  - ../../planning-artifacts/architecture/architecture-ShortyUrlBmad-2026-09-25/ARCHITECTURE-SPINE.md
sources: []
---

> **Canonical contract.** This SPEC and the files in `companions:` are the complete, preservation-validated contract for what to build, test, and validate. Source documents listed in frontmatter are for traceability — consult them only if you need narrative rationale or prose color this contract intentionally omits.

# Tiny URL Service

## Why

**Pain + opportunity.** Merchants need customers to open a specific order page whose address is long and awkward to share. This service exists so a merchant can mint a mute digital address that, on a web-connected device, takes the customer to that exact destination — without the customer handling the long URL, and without the service needing to understand orders.

## Capabilities

- **CAP-1**
  - **intent:** Merchant can submit a long destination URL from their order site and receive a tiny URL bound to it.
  - **success:** Given a valid long URL, the service returns a tiny URL; resolving that tiny URL yields the same original long URL string that was submitted.

- **CAP-2**
  - **intent:** A customer can use a tiny URL on a web-connected device and land on the exact original long URL the merchant submitted.
  - **success:** Opening an active tiny URL navigates to that original long URL; landing on a different destination (wrong order page) is a failure.

- **CAP-3**
  - **intent:** Creating a tiny URL for a long URL that is already known returns the existing tiny URL without creating a new binding.
  - **success:** Two create requests with the same sanitized long URL return the same tiny URL; a different long URL never receives that tiny URL.

- **CAP-4**
  - **intent:** The service refuses to store a long URL that fails an HTTP liveness check after applying the retry policy for transient failures.
  - **success:** Client errors such as 404 fail immediately with no store; on timeout or 5xx the same long-URL probe is attempted up to 3 times with a 30-second wait per attempt; only after all 3 attempts fail do timeout/5xx refuse with no store; HTTP 2xx or a redirect (3xx) on the probed URL allows minting. The probe does not follow redirects. It reads at most 64 KiB of the body; reaching that limit does not fail a 2xx or 3xx.

- **CAP-5**
  - **intent:** On successful create, the merchant can see the tiny URL and the original long URL it is bound to; the tiny URL itself does not encode destination or order content.
  - **success:** Create response exposes both the tiny URL and the bound original long URL; inspection of the tiny URL alone reveals neither the long URL nor order details.

- **CAP-6**
  - **intent:** Merchant can create a tiny URL only when authenticated with basic authentication.
  - **success:** Create without valid basic auth is rejected and stores nothing; create with valid basic auth proceeds through the create pipeline.

- **CAP-7**
  - **intent:** A tiny URL expires only after a configurable inactivity period with no access; each successful access resets that window.
  - **success:** With the inactivity period set to 5 minutes for initial testing, a tiny URL accessed within that window still resolves; after the full inactivity period with no access, resolve fails and the destination is not returned. Changing the configured period changes the window used.

- **CAP-8**
  - **intent:** Merchant can mint a system-default tiny URL or a custom/vanity short form, and can later assign an available vanity to an existing binding for that long URL.
  - **success:** Omitting a custom form yields a default tiny URL; supplying an available vanity at create yields that short form; assigning an available vanity to an existing binding replaces the short form while keeping the same original long URL; a vanity already bound to a different destination is refused.

## Constraints

- Sanitize the long URL before the existence check so equivalent messy copies collapse to one binding.
- Create is atomic: either return an existing binding or write tiny URL and original long URL together; concurrent creates must not bind the wrong destination.
- Persist bindings until inactivity expiry; tiny URLs remain unique across different long URLs.
- Store and resolve the original merchant-pasted long URL, not a post-redirect final URL.
- Liveness does not follow redirects. A 3xx on the pasted URL is success. Storage still keeps the original string. The probe reads at most 64 KiB of the body; hitting that limit does not fail a 2xx or 3xx.
- Client errors such as 404 fail liveness immediately; timeouts and 5xx retry the same long-URL probe up to 3 attempts with a 30-second per-attempt wait, then refuse if still failing.
- Inactivity period is configurable; initial testing value is 5 minutes.
- Create requires HTTP Basic Auth; resolve does not require create credentials.
- Vanity short forms must be unique; a taken vanity for a different destination is refused.
- Expired tiny URLs must not resolve to the destination.
- Ordered create (and vanity-assign) steps are defined in `create-pipeline.md`.

## Non-goals

- Interpreting or modeling "orders" — destinations are opaque URLs.
- Building or hosting the merchant's customer order site.
- Encoding destination or order data into the tiny URL form.
- Analytics / click reporting beyond what inactivity expiry needs for last-access.

## Success signal

An authenticated merchant pastes a long order-page URL (optionally with a vanity), receives a tiny URL, and a customer opening it lands on that specific order. Creating again with the same long URL returns the same tiny URL. The merchant can later turn that tiny URL into an available vanity. A mistaken long URL that 404s is refused immediately. A 3xx on the pasted URL succeeds without following it. The per-attempt 30s budget includes the body read. Hitting the 64 KiB cap does not fail a 2xx or 3xx. Timeouts/5xx retry the probe up to 3 times (30s each) then refuse if still failing. After the configured inactivity period (5 minutes in initial testing) with no access, the tiny URL no longer resolves.

## Assumptions

- HTTP 2xx or 3xx on the probed URL counts as liveness success. Redirects are not followed.
- The service is destination-agnostic: it stores and resolves URLs, not order objects.
- Primary actors are merchant (authenticated create / vanity assign) and customer (unauthenticated resolve via the tiny URL).
- Basic authentication means HTTP Basic Auth on the create and vanity-assign APIs.
- 30 seconds is the standard per-attempt HTTP request timeout for the liveness probe.
