> This project is an extended and upgraded version of [event-ticket-system](https://github.com/AdamSimkinbgu/event-ticket-system), 
> originally built by me and collaborators as a university project at Ben-Gurion University.
> Being extended and enhanced by me in this repository.

# Events Ticketing System

An event management and ticketing platform that provides an infrastructure for trading event
tickets between producers and buyers. Users can visit the platform to purchase tickets, as well as
to create and manage events.

## Architecture

The codebase follows a clean, layered architecture:

| Layer | Package | Responsibility |
|---|---|---|
| Domain | `Core/Domain` | Aggregates, entities, value objects, domain rules and the `IXxxRepository` ports |
| Application | `Core/Application` | Use-case services, DTOs, application-level interfaces (ports) |
| Infrastructure | `Infrastructure` | JPA/in-memory repository adapters, security (JWT), scheduling, external WSEP clients, dev seeding |
| Presentation | `Presentation` | Vaadin views, presenters (MVP), UI components |

**V3 — Persistence & Robustness.** The in-memory repositories are being replaced by JPA-backed
adapters behind the unchanged `IXxxRepository` ports, every use-case runs inside a database
transaction (`@Transactional` at the application layer, not the domain), external WSEP calls are
kept outside the DB transaction, and the platform is deployable on PostgreSQL (Supabase). The same
`jpa` profile runs against H2 locally and PostgreSQL on the deploy target — a config-only switch.

## Build & run

Prerequisites: JDK 21 and the bundled Maven wrapper (`./mvnw`).

```bash
# Production / staging path: jpa profile against PostgreSQL (see DB_* env vars below)
./mvnw spring-boot:run

# Local development: H2 in-memory DB, auto-opened market, and the demo scenario seed
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The app serves the Vaadin UI on `http://localhost:8080`. The Maven `production` profile
(`./mvnw -Pproduction ...`) bundles the optimized Vaadin frontend for deployment.

## How initialization works (UC-1 / UC-32)

The platform comes up through a deterministic boot sequence, then waits for an admin to open the
market before any money can move.

1. **`PlatformInitializationRunner`** (`@Order(0)`, skipped under the `test` profile) calls
   `SystemAdminService.initializePlatform()`.
2. **`initializePlatform()`** (UC-1) runs the I.1 post-conditions in order:
   - **I.1.2 / I.1.3** — external-service quorum: at least one payment gateway *and* one ticket
     issuer must answer a WSEP `handshake` (`requireExternalServicesReachable()`), else
     `ExternalServiceUnavailableException`.
   - **I.1.4** — guarantee a System Admin: `createDefaultAdminIfMissing()` auto-creates the default
     admin from `platform.admin.*` if none exists, else `MissingDefaultAdminException`.
   - **I.1.1 gate** — re-assert the post-conditions (`verifyInitializationInvariants()`) and the
     structural correctness scan (`SystemIntegrityVerifier.verify()`), else
     `InitializationIntegrityException`. The platform transitions to **READY**.
3. The market stays **CLOSED** until an admin calls **`openMarket()`** (UC-32), which re-verifies the
   external services (I.2.2) and structural invariants (I.2.1) before flipping to **OPEN**. Sales
   (`ReservationService` / `CheckoutService`) are gated on `isMarketOpen()`.
4. Under the `dev` profile, **`DevMarketOpener`** (`@Order(1)`) opens the market automatically so the
   seeded scenario can transact.

The lifecycle is a small state machine: `UNINITIALIZED → READY → OPEN ↔ CLOSED`.

> Initialization failures are logged but currently do not abort the JVM (the process boots, the
> market just can't open). A hard boot-validity check that fails startup on invalid init is tracked
> in **#367 (V3-INIT-02)** and not yet wired.

## Config-file format

Runtime configuration lives in `src/main/resources/application.yml` (all values are env-var
overridable, shown as `${ENV:default}`). The `dev` profile overlay is
`src/main/resources/application-dev.yml`.

### `application.yml`

| Key | Default | Purpose |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:ticketing}` | DB connection (env: `DB_HOST`/`DB_PORT`/`DB_NAME`) |
| `spring.datasource.username` / `password` | `${DB_USER:postgres}` / `${DB_PASSWORD:postgres}` | DB credentials |
| `spring.jpa.hibernate.ddl-auto` | `update` | Schema strategy (prod). Hibernate auto-detects the dialect per connection |
| `spring.profiles.group.dev` | `jpa` | Activating `dev` also activates `jpa` |
| `jwt.secret` | `${JWT_SECRET:…}` | JWT signing secret — **must** be supplied via env in production |
| `session.member-ttl-minutes` | `1440` | Absolute member-session lifetime |
| `session.guest-idle-timeout-minutes` | `30` | Idle timeout for guest sessions |
| `auth.lockout.max-attempts` | `5` | Brute-force lockout threshold (`0` disables) |
| `auth.lockout.lock-minutes` | `15` | Lockout duration |
| `constants.ticket-reservation-duration` | `10` | Reservation hold window (minutes) |
| `constants.order-expiration-duration` | `60` | Active-order expiration (minutes) |
| `analytics.rate-window-minutes` | `5` | Trailing window for analytics rates |
| `analytics.refresh-interval-ms` | `5000` | Dashboard auto-refresh (`0` disables) |
| `management.endpoints.web.exposure.include` | `health,info` | Exposed Actuator endpoints |
| `vaadin.launch-browser` | `false` | Don't auto-open a browser on run |
| `vaadin.allowed-packages` | `com.ticketing.system.Presentation` | Restrict Vaadin component scan |
| `platform.admin.username` | `${PLATFORM_ADMIN_USERNAME:admin}` | **UC-1 / I.1.4** default admin username |
| `platform.admin.password` | `${PLATFORM_ADMIN_PASSWORD:admin}` | Default admin password — **override in production** |
| `wsep.base-url` | `${WSEP_BASE_URL:https://…koyeb.app/}` | WSEP payment/issuance endpoint |

### `application-dev.yml`

| Key | Default | Purpose |
|---|---|---|
| `spring.datasource.url` | `jdbc:h2:mem:devdb;…` | In-memory H2 (no Postgres/Docker needed locally) |
| `spring.jpa.hibernate.ddl-auto` | `create-drop` | Drop + recreate schema each boot |
| `seed.scenario` | `classpath:scenarios/demo.scenario` | Initial-state file to replay (see below) |
| `seed.mode` | `idempotent` | `off` · `wipe` · anything else runs it |
| `seed.fail-fast` | `false` | `true` stops at the first unexpected failure |

> Per-environment `ddl-auto` (`validate`/migrations for the Supabase deploy, `create-drop` for
> tests) and seeding/reset safety against a persisted DB are tracked in **#366 (V3-INIT-01)**.

## Initial-state-file format

The platform can be booted into a known state from an editable text file that **replays a sequence
of use-case operations through the real application services** (one operation per line). This is the
in-repo realization of the "initialize from a file" deliverable (#368). Two sample files ship under
`src/main/resources/scenarios/`: `demo.scenario` (rich dataset) and `review.scenario` (minimal TA
baseline). The runner (`Infrastructure/dev/seed/scenario/ScenarioRunner`) is `@Profile("dev")`, so
it never runs in production.

### Syntax

- One operation per line; blank lines and lines starting with `#` are ignored.
- Tokens are whitespace-separated; wrap values containing spaces in `"double quotes"`.
- The first token is the operation; remaining bare tokens are **positional** args; tokens of the
  form `key=value` are **named** args.
- Refer to users/companies/events by short **aliases** (`u1`, `p1`, `e1`); the engine maps each
  alias to the real id the service mints.

### Operations

Each operation dispatches to a real application-service method (`Infrastructure/dev/seed/scenario/ScenarioOps`):

| Operation | Application service call |
|---|---|
| `register <alias> <password> <email> <age>` | `AuthenticationService.register` |
| `login <alias>` | `AuthenticationService.login` |
| `login-admin <alias> <username> <password>` | `AuthenticationService.signInAsAdmin` |
| `logout <alias>` / `logout-all` | `AuthenticationService.logout` |
| `guest <alias>` | `AuthenticationService.startGuestSession` |
| `open-company <owner> <companyAlias> "<name>" ["<desc>"]` | `CompanyManagementService.registerCompany` |
| `appoint-owner <by> <company> <target>` | `CompanyManagementService.appointOwner` |
| `appoint-manager <by> <company> <target> perms=A,B,C` | `CompanyManagementService.appointManager` |
| `confirm <alias> <company>` | `CompanyManagementService.respondToAppointment` |
| `add-event <by> <company> <eventAlias> <zone>… [name= category= city= days= publish=]` | `EventManagementService.addEvent` + `configureVenueMap` (+ `publishEvent` if `publish=true`) |
| `publish <by> <company> <event>` | `EventManagementService.publishEvent` |
| `reserve <buyer> <event> <zoneRef> qty=<n> \| seats=A1,A2` | `ReservationService.reserveForMember` / `reserveForGuest` |
| `checkout <buyer> [email= age=]` | `CheckoutService.checkoutMember` / `checkoutGuest` |
| `cancel-event <by> <event>` | `EventManagementService.cancelEventAndRefund` |
| `contact-company <from> <company> subject= body=` | `MessagingService.startConversation` |
| `submit-complaint <from> subject= body=` | `MessagingService.submitComplaint` |
| `announce <admin> title= body= [audience=BROADCAST_MEMBERS\|BROADCAST_PRODUCERS]` | `MessagingService.sendOutreach` |
| `assert-status <event> <STATUS> by=<owner>` | read-only assertion via `EventManagementService.getEventDetail` |
| `expect-error <op> <args…>` | negative test — passes only if the wrapped op throws |
| `add-coupon …` | recognized but `SKIPPED` (no coupon feature) |

Permissions for `appoint-manager perms=` are any of: `CONFIGURE_VENUE`, `MANAGE_INVENTORY`,
`EDIT_POLICIES`, `VIEW_SALES`, `RESPOND_TO_INQUIRIES`. Zone specs for `add-event` are
`standing:<capacity>@<price>` or `seated:<rows>x<cols>@<price>` (e.g. `standing:30@50`,
`seated:10x10@100`).

### Example (`review.scenario`)

```text
register u1 password123 u1@demo.test 30
login u1
open-company u1 p1 "Production Company p1"
appoint-owner u1 p1 u2
login u2
confirm u2 p1
appoint-manager u2 p1 u3 perms=CONFIGURE_VENUE
login u3
confirm u3 p1
add-event u2 p1 e1 standing:30@50 seated:10x10@100 name="e1"
logout-all
```

### Running a state file

```bash
# Use a specific file, wiping the in-memory repos first
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev \
    -Dspring-boot.run.arguments="--seed.scenario=file:/abs/path/to/your.scenario --seed.mode=wipe"
```

Each line is classified `PASS` / `SKIPPED` / `FAIL` / `BLOCKED` and a summary report is logged at the
end. With `seed.fail-fast=true` the run aborts (and reports) on the first unexpected failure, which
satisfies the "init fails if any step fails" requirement.

## Persistent cart (UC-13)

A member's Active Order (cart) persists by `userId` across logout and is restored on re-authentication
within the reservation window: `AuthenticationService.handleCartOnPromotion` re-attaches the persisted
cart (or promotes a guest cart) on login, and `ReservationService.restoreActiveOrder` rehydrates it
with the remaining timer (II.3.0.2 / UC-13). Expired carts are released back to inventory by the
scheduled `SessionAndOrderSweeper` (II.3.0.3) and are not restored.

## Further docs

- `docs/use-cases-v3.md` — V3 use-case notes (UC-1, UC-32, UC-13).
- `docs/version-3-requirements.html` — V3 requirements page (SLR.5/6/7, I.2.x, II.3.0.x).
- `SEED.md` — the `dev` demo dataset (users, companies, events) and `seed.mode` behavior.
