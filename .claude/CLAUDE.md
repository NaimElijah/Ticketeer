# CLAUDE.md

Guidance for Claude Code in this repo. `.claude/` is tracked, so a change here ships like any other
doc change: on a branch, through a PR into `dev`. Facts a human reader needs belong in README/docs;
this file holds what a coding session needs on top of them.

## What this is

Event management and ticketing platform: producers run companies and events, buyers browse and buy
tickets. Spring Boot 3.5.16 + Vaadin Flow 24.10.7 + Spring Modulith 1.4.14 monolith, Java 21, Maven
wrapper (Maven 3.9.16). It extends an upstream BGU course project (`AdamSimkinbgu/event-ticket-system`);
history before July 2026 is imported from there. Roadmap: GitHub milestones on `NaimElijah/Ticketeer`.

## Commands

**Run Maven from the Bash tool** (`./mvnw`, the POSIX wrapper). In Claude Code's PowerShell tool,
`./mvnw` resolves to `mvnw.cmd`, which shells out to `powershell` and fails ("'powershell' is not
recognized … Cannot start maven from wrapper") because `C:\Windows\System32` isn't on that PATH. If you
must use PowerShell, prepend `$env:SystemRoot\System32;$env:SystemRoot\System32\WindowsPowerShell\v1.0`
to `$env:Path`, and quote every dotted `-D…` argument: PowerShell 5.1 splits it at the first dot
(`-Dspring-boot.run.profiles=dev` arrives as `-Dspring-boot .run.profiles=dev`). The commands below are
quoted and on one line, so they work in both shells.

```bash
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev"   # local default: JPA on in-memory H2, demo seeded
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--seed.scenario=file:<abs-path>.scenario"
./mvnw spring-boot:run                                    # NO profile: JPA on PostgreSQL via DB_*, plus JWT_SECRET and PLATFORM_ADMIN_PASSWORD; fails at boot without them
./mvnw -Pproduction package                               # minified Vaadin bundle, for deployment
```

UI at `http://localhost:8080`. Dev logins: `SEED.md`, section Credentials.

`seed.mode` (env `SEED_MODE`): `off` (default) · `reseed` (dev default) · `wipe` (delete, then stop) ·
`reset` (wipe + reseed) · `ask`; any other value stops the boot. `wipe`/`reset` refuse unless `--seed.assume-yes=true`, and only matter
on a persistent DB; dev's H2 is in memory, already empty on every boot. A missing or unreadable
scenario file stops the boot in every profile. A failing scenario step stops it only while
`seed.fail-fast` (env `SEED_FAIL_FAST`) is `true`, the default outside dev (ADR-003). Full reference: `SEED.md`.

## Tests and CI

```bash
./mvnw -Pno-vaadin test "-Dtest=ReservationServiceTest"
./mvnw -Pno-vaadin test "-Dtest=ReservationServiceTest#GivenMarketClosed_WhenReserveForMember_ThenThrows"
./mvnw -Pno-vaadin test "-Dtest=*UserRepositoryContractTest"     # Memory + Jpa adapter of one port
```

- `-Pno-vaadin` skips Vaadin's `prepare-frontend`, which tests don't need. Tests only; the app then serves no views. The Windows NPE it once worked around no longer reproduces (24.7.0 and 24.10.7, #40).
- **Don't run the full suite locally.** About 2,100 tests boot many Spring contexts, and a full run has been killed for low memory on the maintainer's Windows machine. Run the targeted classes, treat CI as the full-suite gate (say so in the PR), and ask before a full local run.
- CI (`.github/workflows/ci.yml`): `mvn -B verify` on ubuntu with Temurin 21, for pushes and PRs to `main`/`dev`, with the JVM in UTC ("Build, test and package", the required check), and again with `-Pno-vaadin` in Asia/Jerusalem ("Test in Asia/Jerusalem", not required yet). A third job, "Production artifact", packages with `-Pproduction`, fails if the jar holds H2, Vaadin's dev tooling or Hilla (ADR-013), and boots it against a PostgreSQL service until `/actuator/health` is UP (not required yet). `.github/workflows/dependency-audit.yml` submits the resolved Maven tree to the dependency graph, and on PRs its "Dependency review" fails a PR that adds or upgrades to a dependency with a high or critical advisory (not required yet, #200; accepted ones go in `allow-ghsas` with their ADR, ADR-010). Nothing watches the merged tree until Dependabot alerts are on (#202). Dependabot (`.github/dependabot.yml`) opens weekly update PRs into `dev`, ignoring majors, Jackson and Netty minors, Vaadin past 24.10.7 and ArchUnit (rules in ADR-010, reasons in ADR-009); it reads the file from `main`, so it starts once that file is promoted. There is no format or lint step.
- `.mvn/wrapper/maven-wrapper.properties` pins `distributionSha256Sum`: bump it with `distributionUrl`, or `./mvnw` refuses the download. Apache publishes only SHA-512, so check the zip against that and compute the SHA-256 from the checked zip.
- To run tests in another JVM zone locally, add `"-DargLine=-Duser.timezone=Asia/Jerusalem"` (or `UTC`). It reaches the surefire and failsafe forks, and overrides any `argLine` the pom gains later (e.g. a JaCoCo agent), so CI's command must then append to it.
- `*IT` classes run under failsafe in `verify`, never under `test`, and need Docker (Testcontainers, ADR-006); `-DskipITs` skips them. One IT, without surefire or packaging: `./mvnw -Pno-vaadin test-compile failsafe:integration-test failsafe:verify "-Dit.test=DbOutageRecoveryIT"`. Each IT declares its own container from `support/PostgresContainers` (`@Container @ServiceConnection`). Under `test`, Hibernate detects the dialect, so an IT can point a `test` context at PostgreSQL.
- Acceptance classes share one cached Spring context with in-memory state. A class that mutates shared pools (admins, users) needs `@DirtiesContext(classMode = AFTER_CLASS)`, as `MessagingAcceptanceTest` has; otherwise it passes locally and breaks under CI's ordering (87e6952e).

| Folder (`src/test/java/com/ticketing/system/`) | Boots |
|---|---|
| `unit/**` (except persistence), `ui/` | Nothing: plain JUnit + Mockito |
| `unit/infrastructure/persistence/<Agg>Persistence/` | Abstract `IXxxRepositoryContractTest` + `Memory…`/`Jpa…` subclasses; Jpa = `@DataJpaTest @Import(JpaXxxRepository.class)`, no profile, on H2. `Postgres…IT` extends each Jpa subclass to run the same suite on a PostgreSQL container (failsafe). Admin and MemoryEvent subclasses are named `…RepositoryTest`; Admin's IT is `PostgresAdminRepositoryIT` |
| `acceptance/` | `@SpringBootTest @ActiveProfiles({"test", PersistenceProfiles.MEMORY})`: Memory repos, `Stub*` WSEP |
| `integration/`, `concurrency/` | `@SpringBootTest @ActiveProfiles("test")`: Jpa repos on H2. Each `concurrency/` suite has a `Postgres…IT` subclass that runs it on a PostgreSQL container (#171); so do the cancel-during-checkout race and, PostgreSQL only, the concurrent-checkout race (`PostgresConcurrentCheckoutsOneZoneIT`), since H2 serialises what PostgreSQL runs together |
| `architecture/` | `HexagonalRulesTest` (ArchUnit), `ModularityTests` (Modulith), `RepositoryAdapterProfileTest` (adapter per profile set) |

## Architecture

DDD + hexagonal modular monolith. Each top-level package under `com.ticketing.system` is a Spring
Modulith `@ApplicationModule` (all `Type.OPEN`; Modulith validates the model, ArchUnit is the gate).
Cross-context calls should go through ports and events, and new code must. Existing code still
reaches into other contexts, mainly (#184's triage has the full table):
- identity has no inbound port, so its `SessionManager`, `UserRepository` and `User` are used
  everywhere;
- catalog, messaging and reporting call organization's `CompanyMembershipService`;
- catalog, messaging, reporting, sales' `OrderReceiptMapper` and governance read `ProductionCompany`,
  through organization's repository port or as the entity;
- governance and reporting read sales', catalog's and messaging's repository ports and entities
  directly; governance also calls sales' `PaymentGateway`/`TicketIssuer` and writes identity's
  `Admin` (`AdminRepository`, `PasswordHasher`); reporting uses catalog's `CompanyRatings`;
- messaging's admin gate reads identity's `AdminRepository`;
- sales takes catalog's `EventRepository` for the buyer lock.

#54 replaces these, #53 the notifications call, and #55 closes the modules.

Dependencies (acyclic, and each edge allowed by `HexagonalRulesTest`'s module map):
`shared ← identity ← organization ← catalog ← sales`, and `organization ← messaging`. `notifications`
depends only on `shared`. `governance` and `reporting` depend on the backend contexts except
`notifications`, and not on each other. `ui` uses every backend module; `bootstrap` may use everything
including `ui`. No backend module depends on either, and nothing depends on `bootstrap`. A new module fails the build until it is added to that map;
add it to this paragraph in the same change, since nothing checks the two agree ([ADR-011](../docs/ADRs/ADR-011-archunit-module-map-as-the-boundary-gate.md)). Known
exception, due to move behind an inbound port (#53): `identity`'s `AuthenticationService` calls
`notifications`' `NotificationDispatchService` directly. The map allows that one class pair, nothing
wider. `bootstrap`'s `NotificationPollingScheduler` calls it too, which bootstrap's edges allow.

| Module | Holds |
|---|---|
| `shared` | Kernel: `DomainException` hierarchy, `IRepository`, `InvariantChecked`, AOP aspects, `RepositoryLocks`, `WsepHttpClient`, metrics, `Clock`/BCrypt beans, published-language DTOs (`shared/dto`), purchase-policy domain (`shared/domain/policy`), the user-text length rule (`shared/domain/TextLimits`), integration notices (`shared/event`), PostgreSQL driver timeouts (`shared/persistence/PostgresDriverSettings`) |
| `identity` | User, Session (typed by `SessionKind`), Admin, custom JWT auth (`JwtSessionManager`) |
| `organization` | ProductionCompany + the `CompanyAppointment` aggregate (membership, roles, permissions) |
| `catalog` | Event, venue maps, zones, seats, **inventory** (reserve/release/confirm via inbound `InventoryCommandPort`) |
| `sales` | ActiveOrder, OrderReceipt, Ticket, checkout, reservations, refunds, WSEP adapters |
| `messaging` | Conversations, support inquiries |
| `notifications` | Consumes `shared/event` notices after commit on the `notify-` thread (`adapter/in/event`, ADR-004) |
| `governance` | Platform lifecycle, market state, system analytics/integrity; implements sales' `MarketGate`; creates the default admin at init (writes identity's `Admin` directly, #54) |
| `reporting` | CQRS read side (purchase history, company dashboards), read by `ui` |
| `ui` | The single Vaadin driving adapter |
| `bootstrap` | Composition root: lifecycle runners, `@Scheduled` sweepers, dev seed/`.scenario` engine |

Session tokens ([ADR-012](../docs/ADRs/ADR-012-session-tokens-bound-to-typed-session-rows.md)): each `sessions` row has a `SessionKind` (`GUEST`, `MEMBER`, `ADMIN`; a guest row
alone has no user id), and a JWT counts only while its `sub` and `role` match the live row it names.
`SessionManager`'s member readers (`validateToken`, `extractUserId`, `tryExtractUserId`, `extractUsername`,
`validateCredential`) reject an admin token as they reject a revoked one; its admin readers (`validateAdminToken`,
`extractAdminId`, `isAdminToken`) reject a member token. Member paths call the member readers; an admin gate calls the
admin readers, and only `SystemAdminService`'s and `MessagingService`'s gates also check `AdminRepository`. A raw
session id is a credential only for a live guest row: every guest path of sales checks it with
`validateGuestSession`, which accepts no token; `validateCredential` (member token or guest id) is for catalog browsing.

Context layout: `domain/` · `application/{port/in, port/out, service, dto, dtoMappers, event}` · `adapter/in/event` ·
`adapter/out/<persistence|wsep|security|…>`. Repository ports sit in `application/port/out`, not
`domain`. An `adapter/out/<context>` package implements a port owned by that other context (e.g.
`sales/adapter/out/catalog`). `governance` and `reporting` have no domain. Domain classes double as
the JPA entities, so `HexagonalRulesTest` allows `jakarta.persistence` and `org.hibernate.annotations`
in `domain`, and Jackson only inside JPA `AttributeConverter` classes (the JSON column converters). A
type stored through a converter needs value equality: Hibernate compares a converted copy with
`equals`, so without it the owning row is rewritten, and its `@Version` bumped, on every flush
(`PolicyValueEqualityTest`). A context's `domain` depends only on its own context and `shared`.

Seams: `MarketGate`, `CompanyEventStatsPort`, `CartRestorationPort`, `EventSalesHistoryPort`,
`InventoryCommandPort`, `CatalogEventDisplayPort`, `EventCancelledEvent`, the `shared/event` notices.
Events are in-process, with no outbox and no Modulith event registry. Catalog → sales (`EventCancelledEvent`) is a synchronous `@EventListener`, because the cancel call must see refund failures. Every `shared/event` notice is consumed by `NotificationEventListener` with `@Async("notificationExecutor")` + `@TransactionalEventListener(fallbackExecution = true)`: after commit, on one `notify-` thread, never delivered for a rolled-back transaction (ADR-004). Publishers don't catch notification failures, so a new notice consumer must follow the same pattern; `HexagonalRulesTest` fails the build otherwise. A Spring test that reads notifications must wait for them (Awaitility).

**Enforcement** (`src/test/.../architecture/`): `HexagonalRulesTest` (domain purity and its
allowances, each domain inside its context, application-not-adapter, no-backend→ui, ui never depends on
another module's `application.port.out` or `adapter` (a deny-list: `config` records and `domain` types
stay open, #208), the module map,
`bounded_contexts_are_acyclic`, notice listeners are `@Async` after-commit, the ADR-008 clock rules;
the clock, module-map, domain-context, domain-allowance and ui rules each have self-tests that prove
they fail on a fixture) and `ModularityTests`
(`verify()` + Documenter output to `target/spring-modulith-docs/`). The committed
`docs/architecture/*.puml|*.adoc` is a hand-copied snapshot, stale since 2026-07-09. Refresh it by
copying from `target/`; git has CRLF and the generator writes LF, so a naive diff shows every line.

### Repository ports with dual adapters (the central pattern)

Each of the 11 aggregates has a port `XxxRepository extends IRepository<T, ID>` and two adapters,
selected by profile (ADR-005). **Depend only on the port; never wire a concrete adapter.**

- `JpaXxxRepository`: `@Profile(PersistenceProfiles.JPA_ADAPTERS)`, i.e. every run except `test` + `memory`. Adapts the port onto a Spring Data `SpringDataXxxRepository`. The application layer never sees Spring Data types.
- `MemoryXxxRepository`: `@Profile(PersistenceProfiles.IN_MEMORY_ADAPTERS)` (`test & memory`), `ConcurrentHashMap`-backed. A Spring test opts in with `@ActiveProfiles({"test", PersistenceProfiles.MEMORY})`, always in that order: the context cache keys on the ordered list. `RepositoryAdapterProfileTest` fails a new adapter with the wrong profile.

Changing a port means implementing it in **both** adapters and adding the behaviour to the port's
`IXxxRepositoryContractTest`. The two backends drift silently (lock semantics, exception types, key
types, unflushed writes), and a merge can compile while one adapter lacks a method, so run both
subclasses (third test command above).

### Transactions and concurrency

- `@Transactional` sits on the application services (~15) and on Jpa adapter methods, never in `domain` or `ui`.
- Keep WSEP calls outside the DB transaction (V3-TX-02). `CheckoutService` and `EventCancellationRefundService` wrap only the DB steps in a programmatic `TransactionTemplate`; follow that pattern for any new external call. `RefundService` is the exception: it holds the receipt's row lock across the gateway refund by design (`AdamSimkinbgu/event-ticket-system#410`; `requirements.md` V3 appendix 4). It needs an ADR (#44), and #130 redesigns it. Don't copy it.
- `@Transactional(readOnly = true)` on a path that writes drops the write under JPA without an error (b356bf57: the admin session vanished on the next request).
- `spring.jpa.open-in-view` is `false` (`OpenInViewJpaTest`), so an entity is detached once its service returns. Every association loads eagerly; a new `LAZY` one must be read inside the service's transaction, or the view's access throws.
- On the Jpa adapters, concurrency is optimistic (`@Version`). `OptimisticLockTranslationAspect` (`HIGHEST_PRECEDENCE`, so it wraps the tx advice) re-types the commit-time `OptimisticLockingFailureException` into `ConcurrentReservationException`, for methods in `..application.service..` only (a bootstrap sweeper's own `@Transactional` sees the raw exception). Jpa `lockForUpdate`/`unlock` are no-ops **except** in `JpaOrderReceiptRepository`, which takes a real `PESSIMISTIC_WRITE` lock so concurrent refunds can't both reach the gateway.
- A sale holds a shared row lock on its events: checkout Phase 3 loads them through `EventRepository.findForSale` (`PESSIMISTIC_READ`, `MANDATORY` transaction), so a cancel, a lifecycle write or a sell-out flip (`Event.markSoldOut`/`revertToOnSale`, from a reservation or release) waits for the sale, and the cancel's refund sees the receipt ([ADR-015](../docs/ADRs/ADR-015-sales-hold-a-shared-lock-on-their-events.md)). H2 has no shared row lock (Hibernate maps it to `FOR UPDATE`), so on H2 the Phase 3s of one event run one after another; test races that need them to overlap on PostgreSQL. A sale must never write the event row, or concurrent sales deadlock upgrading the lock. `CheckoutService.runPhase3` runs a Phase 3 that lost a `ConcurrencyFailureException` again, up to three attempts in all; keep Phase 3 free of external calls and side effects, or that retry repeats them.
- Never mint IDs from an in-memory counter: it restarts at 1 on every boot and collides with persisted rows. Ticket IDs come from `TicketRepository.nextId` (seeded from the max existing id) inside the checkout transaction (6ce60ea1).
- Flyway owns the schema (ADR-007): `src/main/resources/db/migration/V<n>__*.sql`, applied at startup on PostgreSQL **and** on H2 (dev, every Jpa/`test` context), with `ddl-auto: validate` everywhere. An `@Entity` change ships the next `V<n>` in the same commit; never edit one that has run (Flyway's checksum fails the boot). Write SQL both databases accept, keep migrations expand-only (add now, remove a release later), and name every constraint `uk_`/`ck_`/`fk_<table>_<column>` and every index `ix_<table>_<column>`. PostgreSQL does not index a foreign key's column (H2 does), so a new foreign key, or a column a repository starts filtering on, ships its index in the same migration. `SchemaMigrationIT` fails on PostgreSQL for a foreign key without one; a newly filtered column goes in its list of queried columns. Every statement, migrations included, must finish within `database.socket-timeout` (30 s): raise it for a deploy whose migration takes longer. `validate` checks only tables, columns and types, not length, nullability, uniques, FKs or enum `ck_` values: adding an enum constant needs a migration that drops and re-adds its `ck_` constraint, or inserts of it fail.
- Each `test`-profile context gets its own H2 database (`testdb-${random.uuid}`), so nothing leaks between cached contexts; a test that needs a fixed name sets its own URL (`TicketIdRestartJpaTest`).
- `LoggingAspect` traces every public method in `..application.service..` with argument *counts* only, but it logs exception messages at WARN, so keep PII out of exception messages.

### Platform lifecycle (UC-1 / UC-32)

An in-memory state machine in `SystemAdminService` (governance, not persisted):
`UNINITIALIZED → READY → OPEN ↔ CLOSED`.
- `PlatformInitializationRunner` (`@Order(0)`) calls `initializePlatform()`. It needs a reachable WSEP `PaymentGateway` *and* `TicketIssuer` plus a System Admin, then moves to READY. Only a WSEP outage (`ExternalServiceUnavailableException`) is logged and left to self-heal; every other init failure propagates and stops the boot (ADR-003).
- `bootstrap/scheduling/MarketOpener` opens the market on **every** non-test boot, and `MarketSelfHealScheduler` keeps retrying (it never reopens a market an admin closed). While CLOSED, `ReservationService`/`CheckoutService` refuse via `MarketGate`.
- Under `test`, none of these runners, schedulers or real WSEP clients exist (`@Profile("!test")`); tests drive init themselves against `Stub*` gateways. The `bootstrap/scheduling` sweepers and the notification poller are still beans under `test`, but nothing schedules them, because `SchedulingConfig`'s `@EnableScheduling` is `!test`; a test calls them directly.

### External services (WSEP)

`shared/external/WsepHttpClient` is the HTTP client. `sales/adapter/out/wsep/` holds
`WsepPaymentGateway`/`WsepTicketIssuer` (`!test`) and `StubPaymentGateway`/`StubTicketIssuer`
(`test`), implementing the sales ports `PaymentGateway`/`TicketIssuer`. `WsepHttpClient.postWithRetry`
is only for the idempotent handshake; `pay`/`issue_ticket`/`refund` must use plain `post`, because a
blind retry could double-charge or double-issue. `post` throws `WsepCommunicationException` on a
non-2xx status or a body over `MAX_RESPONSE_BODY_BYTES`, and
`wsep.base-url` must be https at binding; tests against `testutil/LoopbackHttpServer` build
`WsepProperties` directly with its `http://` URL. Checkout idempotency is a bounded in-memory cache
(`CheckoutIdempotencyCache`, oldest evicted first), lost on restart.

### UI (Vaadin MVP)

- ~40 thin `@Route` views in `ui/views`; logic lives in matching `ui/presenters`; shared widgets in `ui/components`. Calculations don't belong in views (revenue moved to reporting's `SalesSummaryCalculator`).
- Vaadin scans only `com.ticketing.system.ui` (`vaadin.allowed-packages`); a view outside it is silently never routed.
- `ui/security/AuthBootstrap` (a `VaadinServiceInitListener`) is the global guard. It first checks that the stored token is live (a member's through `SessionIdentity.isMember()`, an admin's through `AuthenticationService.liveAdminId`) and signs out a visitor whose token is dead (`FreshGuestSession.restore()`). Then it runs an auth gate (routes without `@AnonymousAllowed` need a session) and a capability gate (`@RequireCapability` + `Capability`). The gates only steer navigation; the application services still validate the token on every call.
- Session state lives in `ui/session` (`AuthSession`, `GuestSession`, `OrderSession`, …). Use `SessionIdentity` for "member or guest, and which credential": it also replaces a dead guest session id (`FreshGuestSession.renewGuest()`). Dev-only seeding UI lives in `ui/dev` (`DevUserSeeder`, `DevPanel`).
- A presenter that shows a caught `RuntimeException` passes it through `ServiceErrors.userMessage`, never `e.getMessage()`: only a `DomainException`, `IllegalArgumentException` or `IllegalStateException` message reaches the user; anything else shows generic text and is logged at ERROR. So a business rule that a user should read must throw one of those, not a plain `RuntimeException`.

## Profiles

| Profile | Effect |
|---|---|
| *(none)* | Jpa repos on Postgres via `DB_*`; boot fails if it is unreachable, never falls back to memory (ADR-005). Real WSEP; market auto-opens |
| `dev` | Jpa repos on in-memory H2, built by Flyway each boot; `seed.mode=reseed` with `demo.scenario`; `ui/dev` seeders; 8 s WSEP timeout |
| `supabase` | Jpa repos on remote Postgres over SSL; `DB_HOST`/`DB_USER`/`DB_PASSWORD` required (`application-supabase.yml`); one app instance at a time, since ids come from per-instance counters (#108) |
| `test` | Suite profile (`src/test/resources/application-test.yml`): Jpa repos on H2, no runners/schedulers, `Stub*` WSEP |
| `memory` | Only with `test`: swaps in the Memory repos. Inert on its own. `jpa` no longer exists; setting it does nothing |
| `production`, `no-vaadin` | Maven build profiles: bundle the frontend and keep `vaadin-dev` and H2 out of the jar (#79) / skip `prepare-frontend` |

- Every value in `application.yml` is `${ENV:default}`-overridable. The committed defaults are dev-only; production secrets (`JWT_SECRET`, `DB_PASSWORD`, `PLATFORM_ADMIN_PASSWORD`) must be set from env. Outside `dev` and `test`, `bootstrap/CommittedDefaultSecretsGuard` stops the boot on the committed `JWT_SECRET` or `PLATFORM_ADMIN_PASSWORD`, so a run without a profile needs both (CI's "Production artifact" sets throwaway ones); its test pins its constants to `application.yml`. Keys: `.env.example`.
- Init parameters bind to `@Validated` `@ConfigurationProperties` records in each module's `config` package (picked up by `@ConfigurationPropertiesScan`); inject the record, not `@Value`. The exception: where a `@Scheduled` placeholder needs the value, the record only validates and nothing injects it (`MarketProperties`, `SweeperProperties`, `EventCompletionProperties`, `NotificationPollingProperties`). A new bound key needs a default in `application.yml`, or every Spring test fails to start (ADR-003).
- `logback-spring.xml` has a catch-all `!dev & !test` block, so a new runtime profile gets appenders without a block of its own. A new profile that needs different logging gets its own block and an exclusion in the catch-all: blocks that both match merge (root gets both blocks' appenders, logback ignores a duplicate, and the later block's levels win), and a run that matches none is silent (36ac7924).

## Conventions

- **Traceability IDs.** Preserve them when editing, and cite one when adding behaviour tied to a requirement.
  - Requirement and use-case IDs (`UC-1`, `I.1.4`, `II.3.0.x`, `SLR.6`) are defined in `docs/requirements/requirements.md`.
  - There are **two `#n` namespaces**. In code and Javadoc, a bare `#n` (e.g. `#367`, `#410`) and codes like `V3-INIT-02` usually mean the upstream course repo. In commits, PRs, `requirements.md` and ADRs, `#n` is an issue in `NaimElijah/Ticketeer`. The numbers collide (#57), so don't look up a code-comment `#n` here and assume it's ours.
- **Time** ([ADR-008](../docs/ADRs/ADR-008-event-time-zones-and-one-clock.md)). Every "now" comes from the `Clock` bean (`shared/config/SecurityConfig.systemClock`, UTC): services and adapters inject it; domain methods and repository ports take `now`. `HexagonalRulesTest` fails the build on `now()`/`now(ZoneId)` of a `java.time` type, `ZoneId.systemDefault()` or a second system clock in production code.
  - Recorded timestamps are `LocalDateTime` holding UTC wall time (`LocalDateTime.now(clock)`). Show dates are wall time at the venue plus `Event.timeZone`; compare them as instants (`ShowDate.startsAt/endsAt(zone)`), never as wall times.
  - The UI shows recorded times and "today" through `ui/support/DisplayTime`, in `platform.time-zone`; show times with the event's own zone.
  - Tests pass `Clock.fixed(...)` and stamp their data from the same instant: mixing `LocalDateTime.now()` with a fixed clock passes in one JVM zone and fails in another. Seeded dates are relative to boot via `DemoClock`.
- **Scenario files** (`src/main/resources/scenarios/{demo,review}.scenario`) replay real use-case calls line by line through the application services. Syntax: `SEED.md`, "Scenario file format". Engine: `bootstrap/dev/seed/scenario/`, inert while `seed.mode=off`.
- **Licence.** All Rights Reserved for work since 2026-07-08; the code inherited from event-ticket-system stays MIT ([ADR-002](../docs/ADRs/ADR-002-all-rights-reserved-licence.md)). Never remove the MIT notice from `LICENSE`: MIT requires it, and deleting it once (20c0f520) put the repo out of compliance.

## Git and PRs

- Branch off `dev` as `fix/…`, `feat/…`, `docs/…`, `chore/…` or `refactor/…`, and open the PR into `dev`. Releasing is a separate promote PR from `dev` to `main`; after one, `dev` may need syncing from `main`.
- PRs merge as merge commits, not squash: the squash-only ruleset is bypassed with the owner's admin role (`gh pr merge --merge`).
- Commit bodies end with a `Refs #N` line.
- After resolving merge conflicts, grep for leftover `<<<<<<<` / `>>>>>>>` markers before committing; they have been committed twice (98f8bda3, f66dbc59).

## Where things are documented

| Need | Read |
|---|---|
| What the system must do; requirement status and enforcing class/test | `docs/requirements/requirements.md` |
| Why a decision was made | `docs/ADRs/` |
| Module docs, generated C4/PlantUML snapshot | `docs/architecture/README.md` |
| Overview, checkout request path, config variables and profiles, test layers, known limitations | `README.md` |
| Dev dataset, logins, full `seed.mode` reference, `.scenario` syntax | `SEED.md` |
