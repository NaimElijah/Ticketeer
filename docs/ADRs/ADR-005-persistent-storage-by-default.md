# ADR-005: Persistent storage is the default; the in-memory adapters exist only for tests

- **Status:** Accepted
- **Date:** 2026-10-19
- **Issue:** #28 (make persistent storage the default; in-memory adapters only for tests)

## Context

V3 requires that state survives a planned or unplanned shutdown, on a remote database that a
configuration change can swap (SLR.7). Each of the 11 repository ports has two adapters: a
`Memory…Repository` on a `ConcurrentHashMap` and a `Jpa…Repository` on Spring Data JPA.

Before this decision, the profile selected them the wrong way round:

- **Memory was the default.** Every Memory adapter was `@Profile("!jpa")`. A run that set no profile
  (a deploy that forgot `SPRING_PROFILES_ACTIVE`, or a bare `spring-boot:run`) booted and served
  traffic, then lost every order, ticket and account on restart.
- **That run still built the whole JPA stack.** Nothing excluded the DataSource or Hibernate
  auto-configuration. It needed a reachable PostgreSQL and ran `ddl-auto: update` against it, but
  wrote nothing there. The README and CLAUDE.md described the datasource as "configured but unused".
- **Persistence came only through profile groups.** `dev` and `supabase` each activated `jpa`
  through `spring.profiles.group`; any new runtime profile had to remember to do the same.

The in-memory adapters are still worth keeping. The 14 Spring acceptance classes share one cached
context on them, whose state needs no flushing, transactions or row locks to set up, and plain
unit tests construct them with `new`. The contract tests keep the two adapters of each port in
step.

## Decision

**The Jpa adapters are active unless both `test` and `memory` are active; the Memory adapters are
active only then.** The expressions live in `shared/persistence/PersistenceProfiles`:

| Constant | Value | On |
|---|---|---|
| `IN_MEMORY_ADAPTERS` | `test & memory` | the 11 `Memory…Repository` adapters |
| `JPA_ADAPTERS` | `!(test & memory)` | the 11 `Jpa…Repository` adapters and `JpaRepoCleaner` |
| `MEMORY` | `memory` | the profile a test adds to opt in |

- **The `jpa` profile is gone.** `dev` and `supabase` no longer group it in, because persistence
  needs no profile. An environment that still sets `jpa` activates nothing and does no harm.
- **The in-memory Spring tests opt in** with `@ActiveProfiles({"test", PersistenceProfiles.MEMORY})`,
  always in that order: Spring keys its context cache on the ordered profile list, so a second order
  would start a second context.
- **The JPA Spring tests use `test` alone,** which gives them the stub WSEP gateways on the JPA
  adapters over H2 (`application-test.yml`).
- **`memory` without `test` does nothing.** A stray `memory` in a deployment cannot bring back the
  in-memory default: the Memory adapters also require the suite profile, so the run stays on JPA.
- **A run with no reachable database fails at startup** with a message that names the datasource
  URL (`DatabaseUnavailableFailureAnalyzer`). It never falls back to memory.

`RepositoryAdapterProfileTest` evaluates the adapters' real profile conditions for the profile sets
that matter, so a new adapter with the wrong `@Profile` fails the build.

## Consequences

**What this makes easy:**

- **A forgotten profile is safe.** It runs on PostgreSQL through the `DB_*` variables, or fails at
  startup if the database is unreachable.
- **A new runtime profile needs nothing for persistence.** It no longer has to remember a group.
- **The test suite keeps its speed.** The acceptance context still runs on the in-memory adapters.

**What this makes hard, and what is accepted:**

- **A bare `spring-boot:run` now needs PostgreSQL.** The one-command local run is the `dev` profile,
  which uses in-memory H2 through JPA, as before.
- **The Memory adapters still ship in the jar.** They are inert outside the suite. Moving them out of
  `src/main` is a separate decision (see below).
- **Opting in is per test class.** A new in-memory Spring test that forgets `memory` runs on JPA over
  H2 instead, and may join the cached `test` context of the integration suites. There, rows that
  other classes committed are still visible, and the two backends differ in locking, exception
  types and flushing, so the test can fail, or pass only in some run orders. The fix is the
  missing `memory`.

**Revisit when** the acceptance suite runs on JPA in about the same time as on the Memory adapters,
or when keeping the two adapters of a port in step costs a bug that reaches `dev`. Either makes
deleting the Memory adapters cheaper than keeping them.

## Alternatives considered

- **Let `test` activate `memory` through `spring.profiles.group`,** as #28 suggested. Rejected: Spring
  cannot remove a grouped profile per test, so the integration and concurrency suites, which need
  `test` on the JPA adapters, could no longer opt out.
- **A plain `memory` / `!memory` pair.** Rejected: one stray `memory` in a deployment would bring the
  original bug back. Requiring `test` as well costs nothing.
- **Move the Memory adapters into `src/test`.** Rejected for now: they would leave the production
  classes that `HexagonalRulesTest` and `ModularityTests` check, and the move touches every context.
  The revisit trigger above covers deleting them outright.
- **Run the acceptance tests on JPA and delete the Memory adapters.** Deferred: it removes a second
  implementation of every port, but slows the largest Spring suite and is a larger change than #28.
- **Keep the in-memory default and warn at startup.** Rejected: a warning in a log nobody reads
  still loses the data.
