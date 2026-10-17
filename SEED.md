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

The `dev` profile activates `jpa` on in-memory **H2** (`create-drop`, so the schema is empty every boot)
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
| **FAIL** | Any other exception — a real bug worth surfacing. |
| **BLOCKED** | A dependent step skipped because a prerequisite failed (recorded to avoid cascade noise). |

With `seed.fail-fast=true` the run aborts at the first unexpected failure (a partial report is still
logged). The same mechanism seeds a real Postgres/Supabase database too — it runs against the live `jpa`
backend, not just H2 — but stays gated behind `seed.mode` so it is opt-in there.

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
| Postponed Gala | Habima Theatre | OTHER | 1 standing (cap 50) | **CANCELED** (+ refunded) |
| Not On Sale Yet | Live Nation Israel | CONCERT (the `add-event` default when `category` is omitted) | 1 standing | **SCHEDULED** (unpublished; a negative `expect-error` check reserves against it and must fail) |

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

Set via the `seed.mode` Spring property (env: `SEED_MODE`). `dev` defaults to `reseed`.

| Mode | What it does |
|---|---|
| `off` (default outside dev) | Do nothing. |
| `reseed` | Open the market and run the scenario (creates what's missing). **dev's default.** |
| `wipe` | Delete **all business data** (keeping the platform admin), then stop. *Destructive.* |
| `reset` | `wipe`, then reseed (`DevUserSeeder` + scenario). *Destructive.* |
| `ask` | Interactive startup menu — choose the mode *and* scenario on the console at boot. If stdin yields EOF/empty input (or an `IOException`), each prompt falls back to its default (`off` for the mode). |

**Destructive modes are gated by an explicit opt-in**, not a console prompt: `wipe`/`reset` only proceed
when `seed.assume-yes=true` (env: `SEED_ASSUME_YES=true`). Without it they log exactly what *would* be
deleted and refuse — so a stray `SEED_MODE=reset` can never wipe a database on its own.

```bash
# Force a full wipe + reseed (needs the explicit opt-in). Mainly for a persistent DB —
# dev's H2 is create-drop, so a normal `dev` boot already starts empty and reseeds.
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--seed.mode=reset --seed.assume-yes=true"
```

The wipe is implemented per persistence backend: `JpaRepoCleaner` (`@Profile("jpa")`, deletes through the
Spring Data repositories — H2 and Postgres alike) and `MemoryRepoCleaner` (`@Profile("!jpa")`, clears the
in-memory maps via reflection). `JpaRepoCleaner` deliberately keeps the `admins` row so a reset still has
an admin to re-open the market for the reseed's checkouts (`PlatformInitializationRunner` re-creates it
regardless). `MemoryRepoCleaner` clears every in-memory repo including `MemoryAdminRepository`, so on the
in-memory backend the admin is re-seeded by init on the next cycle rather than preserved.

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
| `login-admin <alias> <username> <password>` | `AuthenticationService.signInAsAdmin` |
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
| `expect-error <op> <args…>` | Negative test: passes only if the wrapped operation throws |
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

Each line ends up PASS, SKIPPED, FAIL or BLOCKED in the end-of-run `SeedReport` (see
[How dev seeding works](#how-dev-seeding-works)). With `seed.fail-fast=true` the run stops at the
first unexpected failure, though startup itself still continues (#27).

## Date relativity

`DemoClock` captures `clock.instant()` once at run start; every event/reservation/notification timestamp
is computed as an offset from that anchor (`anchor.plusDays(N)`, `anchor.minusHours(N)`, …). Boot on a
different day and all dates shift accordingly — there are no hard-coded calendar dates.
