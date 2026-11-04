# ADR-024: Tests read time from a clock, never the JVM's

- **Status:** Accepted
- **Date:** 2026-11-05
- **Issue:** #189 (test-suite scan); narrows the "Tests pin time" consequence of [ADR-008](ADR-008-event-time-zones-and-one-clock.md) for `@SpringBootTest` classes

## Context

The working rule, in `.claude/CLAUDE.md`, is that a test passes `Clock.fixed(...)` and stamps its data from the same
instant, because mixing the real clock with a fixed one passes in one JVM zone and fails in another. ADR-008 states only
the consequence, "Tests pin time". No ADR decided the rule; this one does.

At #189 (`bf0eb3e8`), test code held 247 clock reads, not counting the deliberate fixtures in `HexagonalRulesTest`: 237
no-arg `now()` on `java.time` types, 8 `Clock.systemUTC()` and 2 `System.currentTimeMillis()`.

The rule can't hold as written in a `@SpringBootTest`. Under the `test` profile the `Clock` bean is `Clock.systemUTC()`, and
no `@SpringBootTest` pins it. A test that stamps from a fixed constant while the application reads the real clock breaks in
several ways:

- `EventManagementService` refuses a show date in the past, so a past constant fails, and a future one fails on the day it passes.
- `CompanyAnalyticsService`'s 30-day figures drop a receipt once its constant ages out.
- An assertion against the wall clock, such as `expiresAt > constant`, passes forever and tests nothing.

The written rule alone leaks. No clock read reached `dev` in the 17 merges after ADR-008 landed on 2026-10-22, but that is
because review caught them: in those 17 PRs, 7 commits across 4 PRs added reads (13 net), and each was removed by a fix commit in
its own PR. Each one cost a review finding and a commit.

## Decision

Classify each read by where the stamp flows. The first match wins:

1. **A stamp that meets no clock reader** is a per-class constant, even in a Spring context.
2. **A test that controls a clock** stamps its data from that clock's instant, and uses `Clock.fixed`, or `AdjustableClock` where
   time must move. Three `@DataJpaTest` slices declare a fixed `Clock` bean this way.
3. **A `@SpringBootTest` whose stamp meets the application's clock** injects the bean and stamps with `LocalDateTime.now(clock)`. The
   bean stays real. A show date is wall time at the venue (ADR-008), so it reads `LocalDateTime.now(clock.withZone(eventZone))`.

`HexagonalRulesTest` runs its two clock rules over the test classes too, leaving out only the fixtures nested in it.

## Consequences

**What this makes easy:**

- A clock read added to a test fails in CI, on the required check, with no review round.
- A test's data no longer depends on the JVM's zone.
- A show date that passes through `addEvent` in a `@SpringBootTest` no longer starts failing as the calendar moves on.

**What this makes hard:**

- A `@SpringBootTest` gets zone independence, not time independence. It still reads a moving clock, a deliberate departure from the
  global "Deterministic always" for those classes, until something pulls a pinned bean (see below).
- The rule sees reads, not classification. A constant used where the application does read a clock passes it, so review still has to catch that.
- The rule imports every test class on each run, in `HexagonalRulesTest` as ADR-023's rule does in `TestConventionsTest`.

The Asia/Jerusalem job is no substitute for the rule. It passed with all 247 reads present, because it catches a read only when an assertion's
margin is under the zone's offset, and it is not a required check yet (#200).

## Alternatives considered

- **Leave it to the written rule and review.** Rejected: 7 commits in 4 of 17 PRs show that the rule leaks and that review pays for it, and a rule
  costs a few seconds with no new dependency.
- **Pin the `Clock` bean, for the `test` profile or per class.** Rejected for now, because nothing pulls it: no `@SpringBootTest` is known to need frozen time,
  or to have failed because of when it ran. It also has costs. A clock frozen across a context ties timestamps that code sorts by, such as `getLastMessageAt` in
  `MessagingService`; that effect is untested. `AdjustableClock.withZone` throws, and `SeatedShowFixture` and `EventUseCaseTransactionJpaTest` call `clock.withZone`. Adopting it
  per class forks the shared cached context, which costs memory on a machine already killed for it.
- **Put the rule in `TestConventionsTest`.** Rejected: these are the same two rules that production code answers to, and their self-tests live in `HexagonalRulesTest`.
- **A shared constants class for test instants.** Rejected: a per-class constant is enough, and a shared one couples unrelated tests.

**Revisit when** a `@SpringBootTest` needs frozen time, or a test fails because of the time it ran. Then pin the bean, with an ADR that deals with the context
cache, the `notify-` thread and the ordering ties. Revisit the rule itself if the import pushes `HexagonalRulesTest` past 30 seconds in CI.
