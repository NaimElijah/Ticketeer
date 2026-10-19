# ADR-006: Run the persistence and database-outage tests on PostgreSQL in Testcontainers

- **Status:** Accepted
- **Date:** 2026-10-19
- **Issue:** #32 (run persistence and DB-outage tests against real Postgres in CI)

## Context

V3 requires resilience tests for the database as well as the external systems (V3 4.a). It also
requires automatic recovery once the database returns (SLR.6), and tests that never touch a real
system (V3 4.c). The production database is PostgreSQL, and since ADR-005 every run uses the JPA
adapters.

Before this decision, the production dialect was never tested:

- **All 11 JPA repository contract suites ran on H2.** `@DataJpaTest` swaps in an embedded
  database. H2 differs from PostgreSQL in DDL, types, row locking and in how a failed statement
  affects its transaction.
- **The one database-outage test needed a Mac.** `DbOutageRecoveryLocalPgIT` drove `pg_ctl`
  through Homebrew paths, ran only with `LOCAL_PG_IT=true`, and retried with `Thread.sleep`.
- **No `*IT` class ever ran.** Surefire skips `*IT` and failsafe was not bound, so even a portable
  IT would not have run in CI.

The CI runner (`ubuntu-latest`) has Docker, and so does the maintainer's machine.

## Decision

**Database tests run against a throwaway PostgreSQL container started by Testcontainers, as `*IT`
classes that failsafe runs in `verify`.**

- **Dependencies (test scope only):** `org.testcontainers:postgresql` and
  `org.testcontainers:junit-jupiter`, plus Spring Boot's `spring-boot-testcontainers` for
  `@ServiceConnection`.
- **The version is pinned to 1.21.4.** Spring Boot 3.5.0 manages Testcontainers 1.21.0, and Docker
  Engine 29 answers its client with HTTP 400 ("Could not find a valid Docker environment").
  1.21.4, the latest 1.21.x, works; both were checked against Engine 29.8.
- **One image, one place.** `support/PostgresContainers` pins `postgres:17.11-alpine`, the current
  major at a fixed minor. #87 will choose the RDS engine version, and the image follows it then.
- **One container per test class.** Each `*IT` declares its own static container, so committed rows
  and dead pools cannot leak from one class into another. `@DirtiesContext` closes each class's
  Spring context with its container.
- **The contract suites run twice.** `Postgres<X>RepositoryContractIT` extends the existing H2
  subclass `Jpa<X>RepositoryContractTest`, so the same tests run on H2 in `test` and on PostgreSQL
  in `verify`. `@AutoConfigureTestDatabase`'s default in Boot 3.5 (`Replace.NON_TEST`) keeps the
  container's datasource.
- **An outage is a paused container.** `DbOutageRecoveryIT` pauses the container through the Docker
  API, asserts the application's read fails as a database outage, unpauses it, and waits with
  Awaitility until the same read succeeds without a restart. Pausing keeps the mapped port; a
  stopped and restarted container gets a new host port, so the datasource URL would go stale.
- **`verify` needs Docker.** Testcontainers' default (`disabledWithoutDocker = false`) fails an IT
  rather than skipping it, so a CI runner without Docker turns the build red instead of silently
  testing less. Locally, `-DskipITs` skips them.

## Consequences

**What this makes easy:**

- **The production dialect is tested on every push,** with the same contract tests that keep the
  Memory and JPA adapters in step.
- **Recovery from a database outage is proven in CI,** on any machine with Docker, with no sleeps.
- **#31 has a home.** The migrations it adds can be checked against a clean PostgreSQL in the same
  ITs.

**What this makes hard, and what is accepted:**

- **CI time grows** by roughly one container start per IT class, a few seconds each.
- **`./mvnw verify` fails without Docker.** Docker is now a development prerequisite for the full
  build, but not for `test`.
- **The version override must be removed by hand** once the Boot upgrade (#40) manages a working
  Testcontainers.
- **A paused database hangs connections rather than refusing them.** That is the harder case, a
  network partition. A crashed server that refuses connections at once is not covered separately.

**Revisit when** the ITs make CI noticeably slower than the unit suite (then share one container
per JVM and isolate by schema), or a CI runner without Docker becomes necessary.

## Alternatives considered

- **A GitHub Actions service container.** Rejected: it exists only in CI, so the ITs would not run
  locally, and a test cannot pause or resume it.
- **An embedded PostgreSQL (zonky, `embedded-postgres`).** Rejected: it ships native PostgreSQL
  binaries per platform and adds a second way to run the database. The global rule is real
  dependencies in containers.
- **H2 in PostgreSQL compatibility mode.** Rejected: it emulates some syntax but not PostgreSQL's
  locking, types or transaction semantics, which are what the contract tests must prove.
- **Keep `pg_ctl`, with paths taken from the environment.** Rejected: it still needs a local
  PostgreSQL installation and never runs in CI.
