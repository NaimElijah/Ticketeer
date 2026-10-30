# ADR-009: Direct dependencies — what each is for, the versions pinned, and the risks accepted

- **Status:** Accepted; amended by [ADR-013](ADR-013-hilla-excluded-from-the-vaadin-starter.md), which excludes Hilla, so decision 3's Spring MVC users are the actuator and Boot's `DispatcherServlet` only
- **Date:** 2026-10-29
- **Issue:** #183 (scan 2/10 · dependency-vetter), with #40 and #79 pulled in; the follow-up is #199

## Context

The global rules ask every third-party dependency for a recorded justification:
- what it replaces;
- its maintenance status;
- its licence;
- why the standard library or an existing dependency is not enough.

None of this project's dependencies had one. Scan #183 vetted every direct dependency at its exact
resolved version, in six partitions:
- Spring Boot and the libraries its BOM manages;
- Vaadin;
- Spring Modulith;
- jjwt;
- the test-only libraries;
- the CI tooling.

Advisory data comes from OSV.dev and the GitHub Advisory Database, versions from Maven Central, and
support dates from the vendors. All were read on 2026-10-29.

What the scan found at the starting point (`789b20dc`):
- **Spring Boot 3.5.0**, sixteen patches behind 3.5.16. Its managed versions carried known critical
  and high advisories: Tomcat 10.1.41 (8 critical, 15 high), Jackson 2.19.0 (5 high), pgjdbc
  42.7.5 (3 high), Spring Framework 6.2.7, Spring Security 6.5.0 and Spring Boot itself
  (CVE-2026-40973).
- **The 3.5 line is out of OSS support since 2026-06-30.** 3.5.16 (2026-06-25) is its last free
  release. Even 3.5.16's managed versions still carry four critical Tomcat advisories, three high
  Jackson advisories and one high pgjdbc advisory.
- **Vaadin 24.7.0** carried three advisories: CVE-2026-2742, CVE-2026-7860 and
  GHSA-94g8-xv23-7656.
  - Vaadin 24's free maintenance ended on 2026-06-16, and vaadin.com/roadmap names **24.10.7 the
    last free release**. 24.10.8 and later need a Vaadin subscription, although Maven Central
    serves them.
  - Vaadin 25 needs Spring Boot 4.
- **Vaadin 24.10.7's own transitives carry high and critical advisories:**
  - jsoup 1.20.1 (`flow-server`);
  - Guava 33.4.0 (Hilla's javaparser) and 33.6.0 (Copilot);
  - Netty 4.1.135, Boot's managed version, through Copilot.

  An OSV audit of the whole resolved tree found these after the bumps.
- **Test-only libraries leaked into the application jar.** Testcontainers' `commons-compress`
  1.24.0 won Maven's nearest-wins mediation over Vaadin's 1.27.1, and ArchUnit arrived at runtime
  through `spring-modulith-starter-core`.
- **The `production` profile shipped the dev-only libraries:** Vaadin's dev server, dev bundle and
  Copilot, and the H2 engine (#79).

## Decision

**1. Stay on the Spring Boot 3.5, Vaadin 24 and Spring Modulith 1.4 lines, at their last free
releases, until #199 moves the project to Boot 4, Vaadin 25 and Modulith 2.** That move is a
migration across Spring Framework 7, Hibernate 7 and Jackson 3, at least a week of work, with a
design of its own. It does not belong in a scan's fix-up.

**2. Close the advisories inside those lines with version overrides** in `pom.xml`:
- Tomcat 10.1.61, a same-line patch (Boot property);
- Jackson 2.21.8, a same-line patch (Boot property);
- pgjdbc 42.7.14, a same-line patch (Boot property);
- Netty 4.1.139.Final, a same-line patch (Boot property);
- `commons-compress` 1.27.1, the version Vaadin's `flow-server` is built against
  (`dependencyManagement`);
- jsoup 1.23.2, the version Vaadin's own later 24.10 releases moved `flow-server` to
  (`dependencyManagement`);
- Guava 33.7.2-jre, the first fixed release (`dependencyManagement`).

Each override is dropped once the Boot or Vaadin version in use manages that version or later.

**3. Accept two critical `spring-webmvc` advisories that have no 6.2 fix.** Spring Framework 6.2.19
is the last 6.2 release, and both advisories are fixed only in 7.0.9:
- GHSA-pc63-qcmh-9cmg (CVE-2026-47884): improper path limitation in `XsltView`;
- GHSA-j9f9-w8pj-32f8 (CVE-2026-47890): SSE stream corruption when rendering view fragments.

They are accepted because nothing on the classpath uses either feature:
- the first needs an `XsltView` whose view name isn't set explicitly;
- the second needs SSE with view fragments.

`src/main` has no `XsltView`, `SseEmitter`, fragment rendering, `ModelAndView` or `ViewResolver`.
Spring MVC still serves requests: Hilla's `EndpointController` (on the classpath through the Vaadin
starter) and the actuator endpoints. But neither those, nor Boot's, Vaadin's or the actuator's own
configuration, sets up an XSLT view or SSE fragment rendering. Vaadin push runs over Atmosphere,
not Spring SSE.

`.github/workflows/dependency-audit.yml` allow-lists exactly these two (ADR-010). The allow-list
matches by advisory id, so it would also hide GHSA-j9f9-w8pj-32f8 in `spring-webflux`, which is not
in the tree today.

Two moderate advisories stay too, below the gate's threshold, in libraries the app reaches only
through its framework. `src/main` imports neither.
- GHSA-qv9r-c865-cp47 in `log4j-api` 2.24.3: `MapMessage` JSON encoding. Logging runs on Logback,
  and `log4j-api` is only the bridge `log4j-to-slf4j` needs.
- GHSA-j288-q9x7-2f5v in `commons-lang3` 3.17.0: recursion in `ClassUtils.getClass` on long
  input.

Their fixes (log4j 2.25.5, commons-lang3 3.18.0) are minor-line jumps over what Boot 3.5 manages,
so they wait for #199.

**4. Keep the dev-only libraries out of the production jar** (#79):
- The `production` profile excludes `vaadin-dev` where `vaadin-core` brings it in, as Vaadin's
  production-build documentation does.
- It also leaves H2 out of the repackaged jar. A datasource URL pointing at H2 then fails the boot
  for want of a driver, rather than starting on an in-memory database.
- The CI job `Production artifact` fails if either library comes back. It also boots the jar
  against PostgreSQL, so an override that breaks Boot or Vaadin at runtime fails CI too.
- H2 keeps `runtime` scope for the dev profile and the tests.

**5. The inventory.** These are the versions after this decision, with a justification for each:

| Dependency (scope) | Version | Replaces / why not the JDK or an existing dependency | Maintenance (2026-10-29) | Licence |
|---|---|---|---|---|
| `spring-boot-starter-parent` | 3.5.16 | Dependency management and fat-jar packaging. Nothing in the JDK manages a dependency set | 3.5 OSS ended 2026-06-30; 3.5.16 is final (decision 1) | Apache-2.0 |
| `spring-boot-starter-data-jpa` | Boot BOM (Hibernate 6.6.53) | Hand-written JDBC mapping for 11 aggregates | Hibernate 6.6 active | Apache-2.0; Hibernate LGPL-2.1 (see Consequences) |
| `spring-boot-starter-web` | Boot BOM (Tomcat 10.1.61 override) | The servlet container and wiring the Vaadin servlet runs in | Tomcat 10.1 active | Apache-2.0 |
| `spring-boot-starter-validation` | Boot BOM | Hand-written validation of the `@ConfigurationProperties` records (ADR-003) | with Boot | Apache-2.0 |
| `spring-boot-starter-actuator` | Boot BOM | Health and info endpoints (SLR.8) | with Boot | Apache-2.0 |
| `spring-boot-starter-aop` | Boot BOM | Proxy advice for `LoggingAspect` and `OptimisticLockTranslationAspect` | with Boot | Apache-2.0 |
| `spring-security-crypto` | Boot BOM (6.5.11) | A hand-rolled BCrypt (`BcryptPasswordHasher`), without the Spring Security web stack | with Boot | Apache-2.0 |
| `org.postgresql:postgresql` (runtime) | 42.7.14 (override) | The production JDBC driver; the JDK has none | active | BSD-2-Clause |
| `flyway-core`, `flyway-database-postgresql` (runtime) | Boot BOM (11.7.2) | `ddl-auto` and ad-hoc scripts (ADR-007) | active | Apache-2.0 |
| `com.h2database:h2` (runtime; out of the production jar) | Boot BOM (2.3.232) | An in-memory database for dev and the tests | active | MPL-2.0 or EPL-1.0 |
| `org.projectlombok:lombok` (optional; build time only) | Boot BOM (1.18.46) | Boilerplate accessors and constructors; excluded from the jar | active | MIT |
| `com.vaadin:vaadin-bom`, `vaadin-spring-boot-starter`, `vaadin-maven-plugin` | 24.10.7 | A server-side Java UI for about 40 views. Spring has no UI component model, and a JS SPA plus REST would be a second stack | Vaadin 24 free maintenance ended 2026-06-16; 24.10.7 is the last free release (decision 1) | Apache-2.0: only `vaadin-core` resolves, and no commercial component is used |
| `spring-modulith-bom`, `spring-modulith-api` | 1.4.14 | The `@ApplicationModule` model on each module's package-info. The JDK has no application-module model | 1.4 is the Boot 3.5 line; patched 2026-10-23 | Apache-2.0 |
| `jjwt-api`, `jjwt-impl`, `jjwt-jackson` (impl and jackson at runtime) | 0.13.0 | A hand-written JWS signer and parser (`JwtSessionManager`). The JDK has HMAC but no JWT, and Spring Security's JOSE support is not on the classpath | latest release 2025-08-20; repository active; OpenSSF Scorecard 4.3 | Apache-2.0 |
| `spring-boot-starter-test` (test) | Boot BOM | JUnit 5, Mockito and AssertJ | with Boot | Apache-2.0 / EPL-2.0 / MIT |
| `spring-modulith-starter-test` (test) | 1.4.14 | `ApplicationModules.verify()` and the Documenter (`ModularityTests`) | as above | Apache-2.0 |
| `com.tngtech.archunit:archunit` (test) | 1.4.2 | Hand-written dependency checks for `HexagonalRulesTest`. Kept at Modulith 1.4.14's own ArchUnit version | active; 1.5.1 latest | Apache-2.0 |
| `spring-boot-testcontainers` (test) | Boot BOM | Manual datasource wiring per container (`@ServiceConnection`) | with Boot | Apache-2.0 |
| `org.testcontainers:postgresql`, `junit-jupiter` (test) | Boot BOM (1.21.4; this retires ADR-006's version override) | Starting, pausing and wiring a real PostgreSQL (ADR-006). Docker Engine 29 needs 1.21.4 or later | 1.x frozen at 1.21.4; 2.x goes with Boot 4 | MIT |

An OSV audit of all 285 resolved artifacts, test scope included, reports only the four advisories
accepted in decision 3. The CI tooling is in ADR-010.

## Consequences

**What this makes easy:**
- No dependency carries a known high or critical advisory, apart from the two that decision 3
  accepts.
- The application jar no longer ships ArchUnit, `spring-modulith-core`, or a vulnerable
  `commons-compress`, jsoup or Guava. The production jar no longer ships Vaadin's dev tooling or
  H2.
- Every dependency has a recorded reason, so a later change can be judged against it.

**What this makes hard, and what is accepted:**
- **The platform runs on lines with no free security fixes.** Each new Spring Framework 6.2 or
  Boot 3.5 advisory either has a same-line patch for an override, or is accepted here, or forces
  #199 early. Tomcat, Jackson and pgjdbc still ship patches in their lines, but that is not
  guaranteed.
- **The overrides run versions Boot 3.5.16 and Vaadin 24.10.7 were not tested with.** Most are
  patch releases in the same lines. jsoup and Guava are newer minors, and Vaadin's own later 24.10
  releases took the same jsoup.
- **Vaadin cannot move inside 24.x at all.** Taking 24.10.8 or later would breach Vaadin's
  subscription terms, so `.github/dependabot.yml` ignores those versions.
- **Copyleft libraries ship in the jar:** Hibernate (LGPL-2.1) and Logback (EPL-1.0 or LGPL-2.1),
  unmodified, as separate jars. The application is run as a service, not distributed, so their
  notice obligations are not triggered today. If the jar or an image of it is ever distributed to
  a third party, it needs a third-party notices file first.
- **The `-Pno-vaadin` profile stays.** The Windows `prepare-frontend` NPE it was written for no
  longer reproduces (24.7.0 and 24.10.7). Tests still don't need the frontend step.

**Revisit when:**
- #199 starts. It supersedes decisions 1 to 3, and the overrides and allow-listed advisories go
  with it;
- an advisory affects a feature the app uses and no same-line patch exists;
- an XSLT view resolver, or SSE with view-fragment rendering, appears in `src/main` or on the
  classpath, or `spring-webflux` enters the tree (decision 3 no longer holds);
- a Boot or Vaadin release the project may take manages any overridden library at or above its
  override (drop that override);
- Vaadin changes the terms of 24.x, or a commercial Vaadin component is needed;
- the jar is distributed to anyone outside the project (third-party notices);
- jjwt goes 24 months without a release (since 2025-08-20), or the project adopts Spring Security's
  JOSE support for another reason (drop jjwt).

## Alternatives considered

- **Do the Boot 4 / Vaadin 25 migration now.** This is the only way back onto lines with free
  support, and #199 tracks it. Rejected for this change: it is a migration with its own design and
  ADR, larger than every fix here combined, and the overrides close every advisory that affects
  the app today.
- **Take Vaadin's newest 24.10.x (24.10.13) from Maven Central.** Rejected: 24.10.8 and later need
  a subscription.
- **Bump to Boot 3.5.16 alone and accept its managed versions' advisories.** Rejected: it would
  leave four critical Tomcat advisories on the public HTTP port, while fixed same-line patches
  exist.
- **Move H2 to `test` scope, and give dev runs a property-activated Maven profile.** Rejected: a
  run from the IDE, or with a second Spring profile in the list, would lose the driver. Excluding
  H2 from the production jar alone meets #79's acceptance criteria.
- **A startup guard that refuses an H2 URL outside `dev` and `test`** (in #79's scope). Not built:
  with H2 out of the production jar, such a URL already fails the boot. A guard would matter only
  for a non-production jar, and nothing deploys one.
- **Keep `spring-modulith-starter-core` and `archunit-junit5`.** Rejected: main code uses only the
  `@ApplicationModule` annotation, and `HexagonalRulesTest` never uses ArchUnit's JUnit engine.
