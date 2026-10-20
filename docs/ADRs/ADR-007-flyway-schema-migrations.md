# ADR-007: Manage the schema with Flyway migrations; Hibernate only validates

- **Status:** Accepted
- **Date:** 2026-10-20
- **Issue:** #31 (versioned schema migrations; `ddl-auto: validate` on Postgres)

## Context

Until now Hibernate built the schema from the `@Entity` mappings. Runs with no profile, and the
`supabase` profile, used `ddl-auto: update`. Dev and the test suite used `create-drop`. That has
four problems:

- **Nobody reviews the production schema.** It is whatever Hibernate derives at boot.
- **`update` only adds.** It never drops, renames or retypes a column, never tightens a nullable
  column to NOT NULL, and never changes an enum's check constraint. ADR-001 had to leave
  `receipt_lines.refund_state` nullable for exactly that reason.
- **It cannot move data.** #73 (money as minor units) and #74 (zoned event times) both change
  column types and need their rows converted.
- **Downstream work assumes migrations.** RDS (#87) and the delivery pipeline (#93, "migrate before
  serving traffic") both expect them.

The upstream project used Flyway briefly. It dropped Flyway for `ddl-auto` early in the course
(eb3390d7, upstream #7), before any database held data worth keeping. Today nothing deployed holds
data either. On 2026-10-12 the owner decided that the Supabase data need not be kept (#97), and
production will start empty on RDS (#87).

Two databases run the schema:

- **PostgreSQL** in production and in the `*IT`s (ADR-006).
- **H2** in dev, in the `test` profile and in the `@DataJpaTest` contract suites.

`@DataJpaTest` imports Flyway's auto-configuration. So whatever owns the schema on PostgreSQL also
runs on H2, unless it is switched off there.

## Decision

**Flyway owns the schema, and Hibernate only validates it, in every profile.**

- **Plain SQL, versioned.** Migrations live in `src/main/resources/db/migration` as
  `V<n>__<description>.sql`.
  - `V1__baseline_schema.sql` was generated once by Hibernate 6.6.15's PostgreSQL schema export, then
    reviewed. Its tables are grouped by bounded context, and every unique, check and foreign-key
    constraint has an explicit `uk_`/`ck_`/`fk_` name.
  - Applied to a clean PostgreSQL, it gave the same 160 columns and 54 constraint definitions as
    Hibernate's own export. Only the constraint names differed.
- **One migration set for both databases.** The same files run on PostgreSQL and on H2. That works
  only if the SQL stays in the subset both accept. H2 2.3 accepts Hibernate's PostgreSQL DDL as
  generated, and stores `text` as `CHARACTER VARYING`.
- **`ddl-auto: validate` everywhere.** It is set once in `application.yml`, and the dev and test
  profiles no longer override it. Hibernate checks that every mapped table and column exists with a
  compatible type, and fails the boot otherwise. On H2 it maps an enum to a native `ENUM`, and its
  `Dialect.equivalentTypes` accepts a `varchar` column for it.
- **Migrate at startup.** Spring Boot's auto-configuration runs Flyway before the
  `EntityManagerFactory`. This suits the single instance assumed today (#35). On PostgreSQL, Flyway
  holds an advisory lock while it migrates (`PostgreSQLAdvisoryLockTemplate`), so two instances that
  start together do not both apply the same migration.
- **Flyway's safe defaults are kept, plus one setting:**
  - `baseline-on-migrate` stays off, so a schema Flyway did not create stops the boot rather than
    being adopted silently.
  - `clean-disabled` stays on.
  - `validate-on-migrate` stays on, so an edited, already-applied migration fails the boot.
  - `validate-migration-naming: true` is added, so a misnamed file fails the boot instead of being
    skipped.
- **Test databases are per context.** Every `test`-profile context gets its own H2 database
  (`jdbc:h2:mem:testdb-${random.uuid}`). Without `create-drop` nothing wipes a shared one, and a new
  context would otherwise inherit an older one's rows.
- **`SchemaMigrationIT` enforces this** on a clean PostgreSQL:
  - the baseline applies, and the mapping validates against it;
  - a restart applies nothing;
  - a schema created outside Flyway is refused.

  It runs under failsafe in `verify`, so CI checks every migration against PostgreSQL.

**Dependencies:** `org.flywaydb:flyway-core` and `org.flywaydb:flyway-database-postgresql`, at
runtime scope, with versions managed by the Spring Boot 3.5.0 BOM (11.7.2).

- *What they replace:* Hibernate's `hbm2ddl` `update`/`create-drop` as the source of the schema.
  Neither the JDK nor an existing dependency applies versioned migrations.
- *Why two artifacts:* `flyway-core` includes H2 support. PostgreSQL support has been a separate
  module since Flyway 10.
- *Licence:* Apache-2.0, from the artifacts' POMs (`flyway-parent`). Neither artifact ships native
  code.
- *Maintenance:* Redgate develops Flyway actively, and its 12.x line is current. Spring Boot's
  dependency management decides the version until the Boot upgrade (#40).

## Consequences

**What this makes easy:**

- **The schema is reviewed and versioned,** and a deployment knows exactly which version it runs.
- **Type changes and data moves are possible.** #73 and #74 can ship as migrations, and so can a
  NOT NULL backfill for `receipt_lines.refund_state`.
- **Migrations are exercised every day.** They run on every dev boot and in every JPA test context
  on H2, and in CI on PostgreSQL. A new or retyped table or column without its migration fails
  the next test run; the changes `validate` cannot see are listed below.
- **The pipeline (#93) has a migration step to call,** whether it keeps startup migration or moves
  to a one-off task.

**What this makes hard, and what is accepted:**

- **Every entity change needs a migration.** Never edit an applied migration; add `V<n+1>`.
- **`validate` has blind spots.** It checks that every mapped table and column exists with a
  compatible type. It does not check column length, nullability, unique and foreign-key
  constraints, or the values in an enum's `ck_` check, so a new `length`, `nullable = false`,
  `unique` or enum constant boots without its migration. Those are the reviewer's job. Adding an enum constant needs a migration that drops and re-adds its `ck_`
  constraint, or inserts of the new value fail.
- **The SQL must stay portable between H2 and PostgreSQL.** A PostgreSQL-only feature (`jsonb`,
  partial indexes, `create index concurrently`) needs a decision first; see Revisit when.
- **Migrations must stay expand-only** while the running version still reads the schema: add
  first, remove in a later release. Startup migration makes the new schema visible before the old
  instance stops.
- **A database built by `update` refuses to boot.** That covers the shared Supabase database and
  any local `ticketing` database. There are two ways forward, once each:
  - *Preferred:* drop and recreate the `public` schema. The next boot builds it from V1.
  - *If the data matters:* boot once with `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` and
    `SPRING_FLYWAY_BASELINE_VERSION=1`. This records V1 as applied without running it, and accepts
    Hibernate's auto-generated constraint names and any drift. A later migration that names a
    V1 constraint will then fail on that database.

**Revisit when:**

- more than one instance runs, or deploys must be zero-downtime: move migration out of startup into
  a pipeline step (#93);
- a migration needs SQL that H2 cannot run: give the JPA tests a PostgreSQL container instead, or
  split the migration into per-vendor locations;
- the project needs rollback scripts, or a database other than PostgreSQL in production.

## Alternatives considered

- **Liquibase.** Rejected:
  - Its database-agnostic changelog (XML/YAML/JSON) earns its keep across many vendors, not two
    that already share SQL.
  - Its Community edition moved from Apache-2.0 to the Functional Source License from 5.0 (Liquibase,
    "Liquibase Community for the future", 2025). The Boot 3.5 BOM still manages 4.31.1, but every
    upgrade past it would carry that licence.
- **Keep `ddl-auto: update`, or set `validate` with no migration tool.** Rejected. `update` is the
  problem this ADR solves. `validate` alone would leave nothing to create the schema.
- **Run Flyway on PostgreSQL only, and keep `create-drop` on H2.** Rejected:
  - `@DataJpaTest` runs Flyway anyway, so the H2 suites would have to switch it off one by one.
  - Dev and the tests would run on a schema Flyway never built, and a missing migration would show
    only in the `*IT`s.
- **Per-vendor migration folders (`db/migration/{vendor}`).** Rejected for now: every change would
  be written twice, and the two copies can drift. It stays the fallback named in Revisit when.
- **Hibernate's auto-generated constraint names in V1.** Rejected: they are hashes, and H2 and
  PostgreSQL name unnamed constraints differently. A later migration could not refer to a
  constraint by one name on both.
