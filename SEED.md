# Demo data seeding

Under the `dev` profile the platform boots into a populated, ready-to-explore state. Seeding is not a
hardcoded data dump — it **replays an editable `.scenario` file through the real application services**,
so the seed doubles as an end-to-end smoke test. Two pieces cooperate at startup:

| Component | Profile / order | Role |
|---|---|---|
| `ui/dev/DevUserSeeder` | `@Profile("dev")`, `@Order(1)` | Registers the single `dev.member` DevPanel persona via the real auth path. Idempotent. |
| `bootstrap/dev/seed/scenario/ScenarioRunner` | `@Profile("!test")`, `@Order(2)` | Replays a `.scenario` file line-by-line through the application services, classifying every step in a `SeedReport`. Present on every non-`test` profile but **inert unless `seed.mode` is set to something other than `off`** (`off` is the default) — it never touches prod/cloud unless explicitly asked. |

Run the seeded dev app:

```bash
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev"
```

## How dev seeding works

The `dev` profile runs the JPA repositories on in-memory **H2** (empty every boot; Flyway builds the schema from the migrations)
and sets `seed.mode=reseed` (see `application-dev.yml`). The market opening is not dev-specific:
`bootstrap/scheduling/MarketOpener` opens it on every boot outside the `test` profile, and
`MarketSelfHealScheduler` keeps retrying if the external services are not reachable yet. On startup:

1. **`DevUserSeeder`** registers `dev.member`.
2. **`ScenarioRunner`** opens the market (via the real admin path, so purchases can transact) and replays
   the scenario in `seed.scenario` (default `classpath:scenarios/demo.scenario`) through the real
   `AuthenticationService` / `CompanyManagementService` / `EventManagementService` / `ReservationService`
   / `CheckoutService` / `MessagingService`.

Because every line is a real service call, each is classified in the end-of-run `SeedReport`:

| Status | Meaning |
|---|---|
| **PASS** | The service call succeeded. |
| **SKIPPED** | The call threw `UnsupportedOperationException` — a known, not-yet-built feature stub (e.g. `add-coupon`). |
| **FAIL** | Any other exception — a real bug worth surfacing — or an `expect-error` step whose operation threw nothing or another exception type. |
| **BLOCKED** | Reserved for a step skipped because a prerequisite failed. The scenario engine records none today (#50). |

With `seed.fail-fast=true` the run aborts at the first unexpected failure (a partial report is still
logged) and startup fails. That is the default outside `dev`; `dev` sets it to `false` so a broken demo
line doesn't block local work. A missing or unreadable scenario file stops startup in every profile
([ADR-003](docs/ADRs/ADR-003-fatal-and-self-healing-init-failures.md)). The same mechanism seeds a real Postgres/Supabase database too — it runs against whatever database
the run is configured with, not just H2 — but stays gated behind `seed.mode` so it is opt-in there.

> **Warning:** the committed scenarios (`classpath:scenarios/…`) register accounts whose passwords are
> published in this repository (see [Credentials](#credentials)). Seeding one into a database others can
> reach creates logins anyone can use. Outside `dev` the runner logs a WARN before the first step; it does
> not refuse (#78). On a shared or reachable database, use a scenario file of your own with passwords
> nobody else knows.

## What gets seeded (`demo.scenario`)

The default dataset boots a small but complete marketplace:

| Aggregate | Count | Notes |
|---|---|---|
| Members | 5 | `alice`, `bob`, `carol`, `dave`, `erin` (all password `password123`) |
| Guest | 1 | `g1` — an anonymous guest session that completes a purchase |
| Companies | 2 | **Live Nation Israel** (owner `alice`) · **Habima Theatre** (owner `carol`) |
| Appointments | 1 | `bob` accepted as a **manager** at Live Nation (`CONFIGURE_VENUE`, `VIEW_SALES`) |
| Events | 6 | 5 published (varied states — on sale, one SOLD_OUT, one CANCELED) + 1 left unpublished (`SCHEDULED`); see below |
| Completed orders + tickets | 5 | Real reserve → checkout flow through the stub payment gateway |
| Active reservation (pending cart) | 1 | `erin` holds 2 standing tickets for Coldplay on first load |
| Conversations | 2 | 1 buyer→company inquiry + 1 open complaint |
| Announcements | 1 | Admin → all members |

### Events

| Event | Company | Category | Zones | State |
|---|---|---|---|---|
| Coldplay | Live Nation Israel | CONCERT | 2 seated + 1 standing | On sale |
| Beyonce | Live Nation Israel | CONCERT | 2 standing | On sale |
| Othello | Habima Theatre | THEATER | 2 seated | On sale |
| Indie Night | Live Nation Israel | CONCERT | 1 standing (cap 6) | **SOLD_OUT** (bought out) |
| Postponed Gala | Habima Theatre | OTHER | 1 standing (cap 50) | **CANCELED** (+ refunded; a negative `expect-error` check then reserves against it, and the reservation guard refuses it as not on sale) |
| Not On Sale Yet | Live Nation Israel | CONCERT (the `add-event` default when `category` is omitted) | 1 standing | **SCHEDULED** (unpublished, so buyers do not see it) |

Event dates are relative to boot (`days=` offsets), so they are always in the future.

## Credentials

Every seeded member shares the password **`password123`**.

| Login | Role | Used for |
|---|---|---|
| `dev.member` | Regular member | The DevPanel **"Member"** persona toggle |
| `alice` | Producer (owns Live Nation Israel) | Founder / event creation |
| `bob` | Manager at Live Nation | Delegated `CONFIGURE_VENUE` / `VIEW_SALES` |
| `carol` | Producer (owns Habima Theatre) + buyer | Founder, purchases, messaging |
| `dave` | Buyer | Past orders, a complaint |
| `erin` | Buyer | A pending cart on first load |
| `admin` / `admin` | Platform admin | `/admin/sign-in` and the DevPanel **"Admin"** toggle — **override in production** |

> There is no `dev.admin` persona. Admin access uses the **default platform admin** created at
> initialization from `platform.admin.*` (`application.yml`), reached via `/admin/sign-in`.

## Reset behaviour — `seed.mode`

Set via the `seed.mode` Spring property (env: `SEED_MODE`). `dev` defaults to `reseed`. Any other value
stops the boot with a bind error naming `seed.mode` (ADR-003).

| Mode | What it does |
|---|---|
| `off` (default outside dev) | Do nothing. |
| `reseed` | Open the market and run the scenario. If a user the scenario registers already exists (by username, honouring a line's `username=`), the scenario has run against this database before: no step runs, a WARN points at `seed.mode=reset`, and the boot goes on. Users the scenario does not register, such as `dev.member`, do not count. **dev's default.** |
| `wipe` | Delete **all business data** (keeping the platform admin), then stop. *Destructive.* |
| `reset` | Read the scenario, `wipe`, then reseed (`DevUserSeeder` + scenario). A scenario that is missing, unreadable or refused by the parser stops the boot before anything is deleted. The scenario runs even if its users existed, since the wipe removed them. *Destructive.* |
| `ask` | Interactive startup menu — choose the mode *and* scenario on the console at boot. If stdin yields EOF/empty input (or an `IOException`), each prompt falls back to its default (`off` for the mode, `demo` for the scenario). An unrecognised mode is skipped with a warning. An unrecognised scenario is refused, the valid names are shown, and the question is asked again; if the console ends after one, the boot stops with a message naming the valid choices. |

**Destructive modes are gated by an explicit opt-in**, not a console prompt: `wipe`/`reset` only proceed
when `seed.assume-yes=true` (env: `SEED_ASSUME_YES=true`). Without it they log exactly what *would* be
deleted and refuse — so a stray `SEED_MODE=reset` can never wipe a database on its own.

```bash
# Force a full wipe + reseed (needs the explicit opt-in). Mainly for a persistent DB —
# dev's H2 is in memory, so a normal `dev` boot already starts empty and reseeds.
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--seed.mode=reset --seed.assume-yes=true"
```

The wipe is `JpaRepoCleaner`, which deletes through the Spring Data repositories — H2 and Postgres
alike. It deliberately keeps the `admins` row so a reset still has an admin to re-open the market for
the reseed's checkouts (`PlatformInitializationRunner` re-creates it regardless).

## Choosing / replaying a different scenario

Point `seed.scenario` at any file. Two ship under `src/main/resources/scenarios/`: `demo.scenario` (this
rich dataset) and `review.scenario` (a minimal baseline). The format is in
[Scenario file format](#scenario-file-format) below.

```bash
# Replay a specific file instead of the default demo scenario
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--seed.mode=reseed --seed.scenario=file:/abs/path/to/your.scenario"
```

## Scenario file format

A `.scenario` file is the platform's initial state, written as **use-case operations replayed
through the real application services**, one per line. The engine is
`bootstrap/dev/seed/scenario/` (`ScenarioRunner` runs it, `ScenarioOps` maps each operation to a
service call).

- One operation per line; blank lines and lines starting with `#` are ignored.
- Tokens are whitespace-separated; wrap values containing spaces in `"double quotes"`.
- The first token is the operation; remaining bare tokens are **positional** args; `key=value` tokens
  are **named** args.
- Refer to users, companies and events by short **aliases** (`u1`, `p1`, `e1`); the engine maps each
  alias to the real id the service mints.

| Operation | Application-service call |
|---|---|
| `register <alias> <password> <email> <age>` | `AuthenticationService.register` |
| `login <alias>` | `AuthenticationService.login` |
| `login-admin <alias>` | `AuthenticationService.signInAsAdmin` with the configured default admin's `platform.admin.username` and `platform.admin.password`, so the file holds no password |
| `login-admin <alias> <username> <password>` | `AuthenticationService.signInAsAdmin` with the credentials the file names |
| `logout <alias>` / `logout-all` | `AuthenticationService.logout` |
| `guest <alias>` | `AuthenticationService.startGuestSession` |
| `open-company <owner> <companyAlias> "<name>" ["<desc>"]` | `CompanyManagementService.registerCompany` |
| `appoint-owner <by> <company> <target>` | `CompanyManagementService.appointOwner` |
| `appoint-manager <by> <company> <target> perms=A,B,C` | `CompanyManagementService.appointManager` |
| `confirm <alias> <company>` | `CompanyManagementService.respondToAppointment` |
| `add-event <by> <company> <eventAlias> <zone>… [name= category= city= days= rating= publish=]` | `EventManagementService.addEvent` + `configureVenueMap` (+ `publishEvent`) |
| `publish <by> <company> <event>` | `EventManagementService.publishEvent` |
| `reserve <buyer> <event> <zoneRef> qty=<n> \| seats=A1,A2` | `ReservationService.reserveForMember` / `reserveForGuest` |
| `checkout <buyer> [email= age=]` | `CheckoutService.checkoutMember` / `checkoutGuest` |
| `cancel-event <by> <event>` | `EventManagementService.cancelEventAndRefund` |
| `contact-company <from> <company> subject= body=` | `MessagingService.startConversation` |
| `submit-complaint <from> subject= body=` | `MessagingService.submitComplaint` |
| `announce <admin> title= body= [audience=BROADCAST_MEMBERS\|BROADCAST_PRODUCERS]` | `MessagingService.sendOutreach` |
| `assert-status <event> <STATUS> by=<owner>` | Read-only assertion via `EventManagementService.getEventDetail` |
| `expect-error <op> <args…> error=<ExceptionSimpleName>` | Negative test: passes only if the wrapped operation throws an exception whose simple class name is exactly `<ExceptionSimpleName>` (a subclass does not count); another exception fails the step, naming both types. A line without `error=` is refused when the file is parsed |
| `add-coupon …` | Not built: always throws `UnsupportedOperationException`, so the step is SKIPPED |

Manager permissions (`perms=`) are any of `CONFIGURE_VENUE`, `MANAGE_INVENTORY`, `EDIT_POLICIES`,
`VIEW_SALES`, `RESPOND_TO_INQUIRIES`. Zone specs for `add-event` are `standing:<capacity>@<price>` or
`seated:<rows>x<cols>@<price>` (e.g. `standing:30@50`, `seated:10x10@100`).

The operations in `review.scenario`, without its comments:

```text
register u1 password123 u1@demo.test 30
register u2 password123 u2@demo.test 35
register u3 password123 u3@demo.test 28
register u4 password123 u4@demo.test 41
login u1
open-company u1 p1 "Production Company p1"
appoint-owner u1 p1 u2
login u2
confirm u2 p1
appoint-manager u2 p1 u3 perms=CONFIGURE_VENUE
login u3
confirm u3 p1
add-event u2 p1 e1 standing:30@50 seated:10x10@100 name="e1" rating=4.5
add-coupon u2 p1 code=sale123 percent=20
logout-all
```

Each line ends up PASS, SKIPPED or FAIL in the end-of-run `SeedReport` (see
[How dev seeding works](#how-dev-seeding-works)); the scenario engine records no BLOCKED step. With
`seed.fail-fast=true` (the default outside `dev`) the run stops at the first unexpected failure, and
startup fails with it.

## Date relativity

`DemoClock` captures `clock.instant()` once at run start; every event/reservation/notification timestamp
is computed as an offset from that anchor (`anchor.plusDays(N)`, `anchor.minusHours(N)`, …). Boot on a
different day and all dates shift accordingly — there are no hard-coded calendar dates.

A seeded event's time zone is `platform.time-zone` (`Asia/Jerusalem` by default), and its show starts
at the anchor plus `days=` read as wall-clock time in that zone (ADR-008).
