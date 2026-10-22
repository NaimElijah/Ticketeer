# ADR-008: Event times carry their venue's time zone; every "now" comes from one UTC clock

- **Status:** Accepted
- **Date:** 2026-10-22
- **Issue:** #74 (explicit time zone for event times; inject the Clock everywhere)

## Context

Show times are zone-less. `ShowDate` stores `LocalDateTime` start and end times in
`event_show_dates.start_time`/`end_time` (`timestamp(6)`), so what "20:00" means depends on the
server's zone.

"Now" was read three different ways:

- 49 no-argument `now()` calls, which use the JVM's default zone;
- the injected `Clock` bean, which is UTC, in a few services and sweepers;
- `ZoneId.systemDefault()` when seeding.

The readings disagreed, with visible effects:

- **Events completed late.** `EventCompletionSweeper` compared UTC wall time with show times
  entered as Israeli wall time, so an event completed 2–3 hours after its last show ended.
- **"Must be in the future" used the JVM zone.** On a UTC server (the containers planned for M5),
  a producer in Israel could schedule a show that had started up to 3 hours earlier.
- **Recorded timestamps followed the machine.** Creation, sent, purchase and hold times were
  stamped in whatever zone the JVM ran in. Behaviour differed between a laptop and a server, and
  tests could not pin the time.

Other forces:

- **There is no Venue aggregate.** `VenueMap` holds a `Location(country, city)` and may be null
  while an event is a DRAFT, but show dates are mandatory in every state.
- **The seat hold ignored its setting.** `SeatedZone.reserve` hard-coded a 10-minute hold instead
  of `constants.ticket-reservation-duration`.
- **ADR-007 expected #74 to retype columns.** Nothing is deployed, and the owner decided on
  2026-10-12 that the Supabase data need not be kept (#97).

## Decision

**1. Show times are wall-clock times at the venue, read in the event's time zone.**

- `ShowDate` keeps its `LocalDateTime` start and end. `Event` gains `timeZone`, an IANA `ZoneId`
  stored in `events.time_zone varchar(64) not null` (`V2__event_time_zone.sql`). Hibernate 6.6
  maps `ZoneId` to its id string.
- **Every comparison with "now" converts to an `Instant`** through the event's zone
  (`ShowDate.startsAt`/`endsAt`). `java.time` resolves daylight-saving edges: a wall time in the
  spring gap moves forward by the gap, and one in the autumn overlap takes the earlier offset.
- **Date filters compare the date at the venue.** A show on 15 July at the venue is "on 15 July"
  whatever the viewer's zone.
- **"Must start in the future" belongs to creating and editing a schedule.** The rule is checked
  against the injected clock in the event's zone. A `ShowDate` itself only requires that it ends
  after it starts, so a past show stays valid and loadable.
- **The zone lives on the `Event`.** Existing rows are backfilled with `Asia/Jerusalem`, the zone
  every producer so far has worked in.

**2. Recorded timestamps stay `LocalDateTime` and hold UTC wall time.**

- Creation, sent, purchase, charge and hold times are read with `LocalDateTime.now(clock)` from the
  UTC clock and stay in their `timestamp(6)` columns. Sessions keep `Instant` and
  `timestamp with time zone`.
- **This departs from #74's proposal that holds and expiries use `Instant`.** Retyping about twelve
  columns would not be expand-only (ADR-007), and no consumer outside the JVM reads them yet.
- **Rows written before this change are not converted.** Nothing is deployed, and the Supabase data
  is not kept (#97).

**3. There is one `Clock` bean, and it is UTC** (`SecurityConfig.systemClock`).

- Services, adapters and schedulers inject it. Domain methods and repository ports take `now` as a
  parameter, as `Session.touch(Instant)` already did.
- No production code reads the system clock or the JVM's default zone.

**4. `platform.time-zone` is the platform's home zone** (`PLATFORM_TIME_ZONE`, default
`Asia/Jerusalem`). It is bound to `PlatformTimeProperties` and validated at startup (ADR-003). It is:

- the zone a new event defaults to, and the zone seeded events are created in;
- the zone the UI shows recorded timestamps and "today" in.

Changing it never moves an existing event's times.

**5. Sales owns the hold duration, and catalog records it.** `ReservationService` passes
`holdUntil = addedAt + hold` to catalog through `InventoryCommandPort`, and each reserved `Seat`
records it. `Seat.reservedUntil` stays informational:

- the sweeper enforces expiry on the cart line;
- renewing the hold at checkout entry does not move it.

**6. Enforcement.**

- `HexagonalRulesTest` fails the build when production code:
  - calls `now()` or `now(ZoneId)` on a `java.time` type, which reads the system clock;
  - reads the default zone (`ZoneId.systemDefault`, `TimeZone.getDefault`,
    `Clock.systemDefaultZone`);
  - creates a system clock anywhere but `SecurityConfig`.
- `ClockConfigTest` pins the bean to UTC.
- CI runs the suite twice, with the JVM in UTC and in `Asia/Jerusalem`.
- `System.currentTimeMillis()` is not banned. Its three uses are zone-free: two browser countdown
  deadlines and a cache TTL.

## Consequences

**What this makes easy:**

- **Tests pin time.** A test passes a fixed `Clock`, or a `now`, and gets the same answer on any
  machine in any zone.
- **Completion and scheduling are correct** in every server zone and across daylight-saving
  changes.
- **The migration is additive:** one column with a default, no retyped column and no data
  conversion.
- **Producers see and choose the zone,** and buyers see it next to every show time.

**What this makes hard, and what is accepted:**

- **Comparisons convert in Java.** SQL cannot ask "which shows ended before now" across zones. The
  completion sweeper loads its candidates and checks each in Java.
- **Ordering across zones must convert.** The earliest show is found by instant, never by wall
  time.
- **Three time representations coexist:** zoned wall time for shows, UTC wall time for records,
  and `Instant` for sessions. Each has one documented meaning, and the ArchUnit rule keeps "now"
  in one place.
- **The UI shows recorded timestamps in one zone,** not the viewer's.
- **Zone abbreviations are ambiguous.** "IST" also means India Standard Time; the event form shows
  the full zone id.

**Revisit when:**

- a public API or export needs zone-explicit timestamps: retype the record columns to
  `timestamp with time zone` with an expand-and-contract migration;
- viewers need times in their own zone: read the browser's zone instead of `platform.time-zone`;
- a Venue aggregate appears: move the zone onto it;
- the completion sweeper needs SQL-side filtering at scale: add a derived `ends_at` instant column;
- a single event spans venues in different zones (a tour): move the zone onto the show date.

## Alternatives considered

- **Store show times as `Instant` (`timestamp with time zone`).** Rejected:
  - Changing the column type is not expand-only.
  - The conversion depends on the database session's zone.
  - A future change to a zone's rules would shift the producer's wall time.
- **`Instant` for every timestamp.** Rejected for now: about twelve columns would need dual-write
  migrations, and nothing needs them yet. It is the first item under Revisit when.
- **The zone on `VenueMap`.** Rejected: the map may be null while show dates exist, and
  reconfiguring the map would silently change what the show times mean.
- **A zone on each `ShowDate`.** Rejected: an event has one venue, so one zone.
- **Derive the zone from `Location`.** Rejected: a country or city does not determine one zone, and
  nothing here geocodes.
- **Set the JVM's default zone.** Rejected: depending on the JVM's zone is the bug, and it would
  still differ between machines.
- **`hibernate.jdbc.time_zone`.** Rejected: a global JDBC setting that changes how every timestamp
  is read and written, and solves neither show-time meaning nor clock injection.

**Dependencies:** none added.
