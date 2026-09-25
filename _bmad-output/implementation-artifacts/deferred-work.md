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
