# ADR-026: Integration tests prove they run on PostgreSQL

- **Status:** Accepted
- **Date:** 2026-11-07
- **Issue:** #189 (test-suite scan, finding F10)

## Context

Every `*IT` reruns a suite on a PostgreSQL container, the production database
([ADR-006](ADR-006-postgres-integration-tests-with-testcontainers.md)). Each one declares its own container from
`support/PostgresContainers`, with `@Container @ServiceConnection`.

Nothing checks that the connection took. `application-test.yml` points every `test` context at an in-memory H2
database and names no dialect, so a context that never reaches the container still boots, on H2. An IT that lost
its `@ServiceConnection`, or a Spring Boot upgrade that changed how `@AutoConfigureTestDatabase` replaces a data
source, would then pass under a PostgreSQL name while testing H2. Most ITs use nothing PostgreSQL alone has, so
nothing in them would fail. The difference matters because H2 and PostgreSQL differ where these tests look: H2
has no shared row lock, gives up on an insert that collides with an uncommitted key after its 2 s lock timeout
where PostgreSQL waits until the other transaction ends, and serialises what PostgreSQL runs together.

## Decision

- `support/RunsOnPostgres` is a test annotation that adds `PostgresDatabaseCheck`. Before each test, the check
  asks the Spring context's `DataSource` for `DatabaseMetaData.getDatabaseProductName()`, and fails the test,
  naming the database it found, unless that is `PostgreSQL`.
- Every `*IT` carries `@RunsOnPostgres`.
- `architecture/TestConventionsTest` fails the build on a concrete class that failsafe runs without it: one whose
  simple name starts with `IT` or ends with `IT` or `ITCase`, failsafe's default includes. Self-tests prove the
  rule fails on such a class without the annotation and passes on one with it.
- `PostgresDatabaseCheckTest` proves the check fails on H2.

ADR-006 stays as written; this adds a check to the ITs it describes.

## Consequences

**What this makes easy:**

- An IT that lost its container fails at its first test, with a message naming the database it got.
- A new IT can't leave the check out, because the build refuses it.

**What this makes hard:**

- Each IT test borrows one pooled connection before it runs. A test that counts or controls the pool's
  connections has to allow for that.
- The check reads the database through the test's Spring context, so an IT without one can't use the
  annotation, and would need a check of its own.
- That the check passes on PostgreSQL is proven only where the ITs run, in CI, since they need Docker.

## Alternatives considered

- **Name the PostgreSQL dialect in each IT's properties.** Rejected: Hibernate would still talk to an H2
  database through it, and fail late and confusingly, or not at all.
- **A base class that every IT extends.** Rejected: most ITs already extend the H2 suite they rerun
  (`PostgresConcurrentFirstReservationIT extends ConcurrentFirstReservationJpaTest`), and Java allows one
  superclass.
- **Drop the H2 default from `application-test.yml`,** so that a context that misses the container fails to
  boot. Rejected: most of the suite's `test` contexts run on that H2 default.
- **No rule, only the annotation.** Rejected: an IT that forgets its `@ServiceConnection` can forget the
  annotation as easily, which is the failure this exists for.

## Revisit when

The ITs stop running on Spring test contexts, or Testcontainers' service connections are replaced by something
that refuses to start without its database.
