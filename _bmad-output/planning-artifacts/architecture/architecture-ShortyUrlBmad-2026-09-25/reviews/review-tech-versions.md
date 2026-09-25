---
review: tech-versions
lens: Confirm committed stack decisions were web-researched or reality-checked (current versions, technology still exists/fits, greenfield starter defaults)
artifact: ARCHITECTURE-SPINE.md
evidence: .memlog.md
reviewed: '2026-09-25'
verdict: PASS WITH GAPS
---

# Tech / Versions Review — Architecture Spine

## Verdict

**PASS WITH GAPS** — Core version pins (Spring Boot **4.1.1**, Java **21**, Gradle **8.14+ / 9.x**, `spring-boot-starter-webmvc`, BOM-managed H2, embedded Tomcat) are coherent and reconfirmed against live sources on **2026-09-25**. One committed runtime choice (service-layer **RestClient** for CAP-4 liveness) was only assumed and is **incomplete against Boot 4.1 modularization**; greenfield Initializr live defaults were not recorded as an explicit delta from the spine’s pins.

## Scope & method

| Source | Role |
| --- | --- |
| `ARCHITECTURE-SPINE.md` Stack + AD-3/AD-4/AD-6 | Claims under review |
| `.memlog.md` `(version)` / `(assumption)` lines | Claimed evidence trail |
| Live re-check 2026-09-25 | spring.io, Boot system requirements, Boot 4 migration guide, GitHub issues, `start.spring.io/metadata/client` |

This review does **not** re-judge paradigm or capability mapping except where they name a technology or starter.

## Reconfirmation matrix (2026-09-25)

| Claim | Spine / memlog | Live check | Status |
| --- | --- | --- | --- |
| Spring Boot **4.1.1** | Memlog: verified spring.io **2026-08-20**; spine pins 4.1.1 | [spring.io/projects/spring-boot](https://spring.io/projects/spring-boot) shows **Spring Boot 4.1.1**; release blog 2026-08-20; Initializr default `bootVersion` = `4.1.1.RELEASE` | **Confirmed** (still current stable; 4.1.2 / 4.2 are SNAPSHOT/milestone only) |
| Java **21** | Pinned; memlog notes Boot 4.1 supports Java 17–26 | [System Requirements](https://docs.spring.io/spring-boot/system-requirements.html): Java **17+** up to **26** | **Fits**; intentional pin above Initializr default (see Finding 2) |
| Gradle **8.14+ or 9.x** | Spine + memlog “per Boot 4.1” | Same docs + Gradle plugin docs: Gradle **8.x (8.14+)** and **9.x** | **Confirmed** |
| `spring-boot-starter-webmvc` | Stack table | Boot 4 rename: `spring-boot-starter-web` → **`spring-boot-starter-webmvc`** ([Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)) | **Confirmed** (not stale Boot 3 naming) |
| `spring-boot-starter-security`, `spring-boot-starter-data-jpa` | BOM-managed | Still valid Boot 4 starter coordinates | **Confirmed** |
| H2 BOM-managed | AD-3 + stack | Boot 4.1 coordinates manage `com.h2database:h2` (e.g. **2.4.240** in 4.1 docs); embedded H2 + Data JPA still documented | **Confirmed** (version via BOM is correct stance) |
| Embedded Tomcat default | AD-6 / stack | `webmvc` starter still pulls Tomcat; Boot docs list Tomcat 11 as supported embedded container | **Confirmed** |
| RestClient for liveness | AD-4 + memlog **(assumption)** | Boot 4.x expects **`spring-boot-starter-restclient`** for `RestClient.Builder` auto-config; **not** provided by `webmvc` alone ([#50768](https://github.com/spring-projects/spring-boot/issues/50768), [#48253](https://github.com/spring-projects/spring-boot/issues/48253)) | **Gap** — see Finding 1 |
| Initializr / greenfield defaults | Memlog mentions Initializr; spine does not snapshot defaults | Live Initializr defaults: Boot **4.1.1.RELEASE**, Java **17**, type **`gradle-project`**, language **java**, packaging **jar** | **Partially checked** — see Finding 2 |

## Findings

### 1. HIGH — RestClient committed without Boot 4.1 starter reality-check

- **Where:** AD-4 (“RestClient”); Capability map CAP-4; Consistency (implied outbound HTTP); Stack table omits any client starter.
- **Memlog:** Explicitly `(assumption) Outbound liveness uses Spring RestClient…` — not a `(version)` / web verification line.
- **Reality (2026-09-25):** In Spring Boot **4.1**, `RestClient.Builder` auto-configuration comes from **`spring-boot-starter-restclient`** / `spring-boot-restclient`. Maintainers state that **`spring-boot-starter-web` / `webmvc` do not auto-configure `RestClient.Builder`**. Implementers who only add the spine’s listed starters and inject `RestClient.Builder` will fail at runtime (tests can falsely pass if test starters pull restclient transitively).
- **Fit:** RestClient itself is the right imperative client for a layered MVC app (Boot docs still recommend it over reactive `WebClient` here; Framework 7 deprecates `RestTemplate` in favor of RestClient). The miss is **dependency/surface**, not technology choice.
- **Required fix before treat-as-final:** Add `spring-boot-starter-restclient` (BOM-managed) to the Stack table and Structural Seed notes; convert the memlog assumption into a verified stack line citing Boot 4.1 HTTP client modularization.

### 2. MEDIUM — Greenfield Initializr defaults not recorded; Java pin diverges silently

- **Where:** Stack (Java 21, Gradle); Structural Seed (`build.gradle.kts / build.gradle`); memlog “via Initializr / Boot 4.1.1”.
- **Live Initializr (`https://start.spring.io/metadata/client`, 2026-09-25):**
  - `bootVersion.default` = **`4.1.1.RELEASE`** → matches spine
  - `javaVersion.default` = **`17`** → spine pins **21** (valid LTS, but **not** the live default)
  - `type.default` = **`gradle-project`** → aligns with Gradle decision (wrapper/Kotlin vs Groovy DSL still unspecified)
  - `language.default` = `java`, `packaging.default` = `jar` → fine
- **Gap:** For a greenfield build-substrate, the lens expects either “use Initializr defaults” or an explicit “override defaults: Java 21 …” with a date-stamped check. Neither appears in spine or memlog. Story implementers generating from Initializr without reading the Stack table may ship Java 17 while the spine says 21.
- **Required fix:** Add a one-line “Initializr delta” note (Boot 4.1.1 default OK; override Java → 21; Gradle project type matches default; choose Groovy or Kotlin DSL).

### 3. MEDIUM — Boot 4 matching `*-test` starters omitted from stack guidance

- **Where:** Stack lists only runtime starters.
- **Reality:** Boot 4 modularization pairs each starter with a test starter (`spring-boot-starter-webmvc-test`, `security-test`, `data-jpa-test`, and — if Finding 1 is fixed — `restclient-test`). Documented as expected practice for Boot 4 apps.
- **Impact:** Not a wrong production version, but an unresearched starter default that causes incomplete greenfield scaffolds and flaky/missing test auto-config.
- **Suggested fix:** Extend Stack (or Structural Seed) with test-scoped companions for the chosen starters.

### 4. LOW — Boot 4.1.1 evidence is ~5 weeks old but still accurate; no same-day re-stamp in artifact

- **Where:** Memlog `(version) Verified current stable Spring Boot 4.1.1 (spring.io 2026-08-20)`.
- **Re-check:** Still current on spring.io and Initializr as of **2026-09-25**. No newer stable supersedes it (4.2.0-M2 / 4.1.2-SNAPSHOT only).
- **Gap:** Process only — spine “updated: 2026-09-25” does not record a fresh version check. Coherence is fine; freshness documentation is thin.
- **Suggested fix:** Append memlog `(version) Reconfirmed spring.io + Initializr 2026-09-25 → still 4.1.1`.

### 5. LOW — H2 concrete BOM version never looked up (acceptable if intentional)

- **Where:** AD-3 / Stack “H2 | BOM-managed”.
- **Assessment:** Correct decision for Boot greenfield; live Boot 4.1 coordinates still manage H2. Optional hardening: cite the BOM-managed coordinate (e.g. `com.h2database:h2` @ managed **2.4.240** per 4.1 appendix) so implementers can spot drift. Not required for PASS if BOM policy stays explicit.

## Coherence check (stack as a whole)

| Question | Answer |
| --- | --- |
| Do Java 21 + Boot 4.1.1 + Gradle 8.14+/9.x form a supported triangle? | **Yes** |
| Are named starters current Boot 4 names? | **Yes** for webmvc / security / data-jpa; **incomplete** without restclient |
| Does embedded H2 + JPA + single-process Tomcat fit AD-3/AD-6? | **Yes** |
| Does RestClient fit CAP-4 sync liveness in a non-reactive app? | **Yes**, once starter is declared |
| Were greenfield starter defaults reality-checked? | **Partially** — Boot/Gradle align; Java default override undocumented; RestClient modular default missed |

## What was adequately evidenced

- Spring Boot **4.1.1** existence and currency (memlog dated check + live reconfirm).
- Java version band and Gradle band from Boot system requirements (memlog + docs).
- `spring-boot-starter-webmvc` naming (Boot 4 rename — not a training-data Boot 3 hangover).
- H2 via Spring Data JPA + Boot BOM (technology still exists and fits embedded local store).
- Embedded Tomcat as Boot default for the webmvc path.

## Recommended spine edits (minimal)

1. Stack table: add `spring-boot-starter-restclient | BOM-managed`.
2. Stack or short note: Initializr defaults as of 2026-09-25; override Java to 21.
3. Optional: list matching `*-test` starters for Boot 4 scaffolds.
4. Memlog: promote RestClient from `(assumption)` to verified stack decision; reconfirm Boot 4.1.1 same-day.

## Residual risk if shipped as-is

Implementers following only the Stack table can create a Boot 4.1.1 + webmvc + security + data-jpa + H2 app that **does not** auto-wire `RestClient.Builder`, breaking AD-4 / CAP-4 unless they independently discover `spring-boot-starter-restclient`. Core version numbers themselves are not stale.
