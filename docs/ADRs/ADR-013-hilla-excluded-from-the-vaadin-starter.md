# ADR-013: Hilla is excluded from the Vaadin starter

- **Status:** Accepted
- **Date:** 2026-10-30
- **Issue:** #185 (security scan); amends [ADR-009](ADR-009-direct-dependency-inventory.md); follow-up #199

## Context

`com.vaadin:vaadin-spring-boot-starter` 24.10.7 depends on `com.vaadin:hilla` 24.10.6. The app
uses Vaadin Flow only:
- `src/main` imports no Hilla type;
- it has no `@BrowserCallable` or `@Endpoint` bean;
- it has no `frontend/views`.

Hilla still shipped in the production jar. Scan #185 found that it registers two things on the
public port, and brings about 30 jars (14 MB uncompressed) the app never calls:
- **`EndpointController`**, a Spring MVC controller under `/connect` that parses JSON and
  multipart bodies;
- **an Atmosphere servlet** at `/HILLA/push`.

Their registry is empty today, so nothing is callable. But any bean later annotated
`@BrowserCallable` would become a public HTTP endpoint that `AuthBootstrap`, which guards only
Vaadin navigation, never sees.

Forces:
- **Flow tolerates Hilla's absence by design.** It detects Hilla by class presence
  (`EndpointRequestUtil.isHillaAvailable`), and it adds Hilla's npm packages only when Hilla views
  exist.
- **The build is unaffected.** `vaadin-maven-plugin` carries its own Hilla on the plugin classpath,
  so `prepare-frontend` and `build-frontend` do not need it on the project's.
- **Vaadin 25 drops Hilla from the starter by default** (#199).

## Decision

**Exclude `com.vaadin:hilla` from `vaadin-spring-boot-starter`.**
- `hilla-dev` comes only through `vaadin-dev`, which the `production` profile already excludes, so
  it needs no exclusion of its own.
- CI's production-artifact check now refuses any `hilla` jar in `BOOT-INF/lib`, so Hilla cannot
  return unnoticed.

The production runtime goes from 190 to 159 artifacts, as listed by
`./mvnw -Pproduction dependency:list -DincludeScope=runtime`; `org.jetbrains:annotations` also
drops to test scope. The jars that leave include:
- the Hilla jars;
- javaparser and Guava, and swagger;
- Kotlin, classgraph, reactor-core and spring-security-core.

Flow push (`flow-push`, Atmosphere), `flow-react`, routing and jsoup stay.

## Consequences

- **Easier:**
  - The production jar ships no unused HTTP endpoint and about 30 fewer jars to keep patched. One
    of them is LGPL javaparser.
  - A future `@BrowserCallable` cannot become public by accident.
- **Harder:**
  - Adding a Hilla or React view later means adding Hilla back, deliberately.
  - Copilot, in dev only, loses its Hilla-specific features, such as the component picker.
- **ADR-009's decision 3:** Spring MVC's remaining users are the actuator endpoints and Boot's
  `DispatcherServlet`. The two accepted `spring-webmvc` advisories still apply, and their analysis
  is unchanged, since neither user sets up an XSLT view or SSE fragments. Its sentence naming
  Hilla's `EndpointController` no longer holds.
- **The Guava pin now serves Copilot's dev tree only.** It stays: the dependency audit submits the
  default-profile tree, which still has Guava through Copilot. It goes with #199.

## Alternatives considered

- **Keep Hilla and accept the surface.** Rejected: nothing uses it.
- **Depend on `vaadin-core` and `vaadin-spring` instead of the starter.** Rejected: the starter's
  only other contributions (`spring-boot-starter-web`, `vaadin-spring`, `log4j-to-slf4j`) are still
  needed, so it would rebuild the starter by hand for the same result.
- **Disable Hilla by a property.** Rejected: no Maven property removes the jars, and leaving them on
  the classpath leaves the controller registered.

## Revisit when

- A view needs a Hilla endpoint, `@BrowserCallable`, React or file-based routing, or Hilla signals.
- The move to Vaadin 25 (#199), where Hilla is out of the starter by default. This ADR, the
  exclusion and the Guava pin then become moot.
- A Vaadin 24.10.x or Copilot update starts requiring Hilla on the classpath.
