# Test Automation Summary

## Generated Tests

### API Tests
- [x] `shorty-url/src/test/java/com/shortyurl/web/ShortyApiGapsIT.java` — contracts the existing create and resolve suites did not assert

### E2E Tests
- [x] `shorty-url/src/test/java/com/shortyurl/web/ShortyPageE2E.java` — merchant page workflows in Chromium

## Coverage

- API endpoints: 3/3 covered
  - `POST /api/v1/urls` — create (existing `CreateUrlIT`, plus new 401 problem JSON without `WWW-Authenticate`, and malformed JSON)
  - `POST /api/v1/urls/vanity` — assign (existing `ResolveUrlIT`, plus new blank `url` and blank `vanity` 400s)
  - `GET /{shortCode}` — resolve (existing `ResolveUrlIT`, plus new checks that `/api` and `/error` are not short-code redirects)
- UI features: 6/7 covered
  - Sign-in form, create, assign vanity, rejected credentials, missing vanity, and opening a short code
  - Copy is not covered

## Verification

`./gradlew test` in `shorty-url` — 100 tests, 0 failures.

## Next Steps

- Run these tests in CI (Playwright downloads Chromium on first run)
- Add a test for the Copy button if clipboard behavior becomes a requirement
