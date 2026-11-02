# ADR-022: `System.currentTimeMillis()` is a system-clock read, banned like the others

- **Status:** Accepted
- **Date:** 2026-11-02
- **Issue:** #187 (ui scan, UI-B17); supersedes the last bullet of [ADR-008](ADR-008-event-time-zones-and-one-clock.md)'s point 6

## Context

ADR-008 makes every "now" in production code come from the `Clock` bean, and `HexagonalRulesTest` fails
the build on the other system-clock reads. It left `System.currentTimeMillis()` out on purpose: its three
uses were zone-free, and a zone-free read looked harmless. The three uses were:

- `LkTopBar.cart`, which hid the cart countdown chip once its deadline had passed;
- `MainLayout.rebuildTopBar`, which aged the cart badge's 10-second session cache and turned the badge's
  remaining seconds into a deadline for the browser countdown;
- `CheckoutView.syncDynamicUI`, which turned the order's remaining seconds into the hold countdown's
  deadline.

Zone-freedom is half of ADR-008's reason. The other half is that tests pin time: a test passes a fixed
`Clock` and gets the same answer on any machine at any hour. A `currentTimeMillis()` read cannot be
pinned, so a test of the chip or the badge cache depends on when it runs. And nothing in the code marked
which reads were zone-free, so the exemption had no edge to stop the next use.

## Decision

- `HexagonalRulesTest`'s ADR-008 clock rule also fails on a call to, or a method reference of,
  `System.currentTimeMillis()` in production code. Self-tests prove it fails on both.
- The three uses read the `Clock` bean. `MainLayout` and `CheckoutView` take the `Clock` and call
  `clock.millis()`. `LkTopBar.cart` reads no clock; its caller passes the seconds left, computed from the bean.
- `System.nanoTime()` stays allowed. It measures a duration and means nothing as a wall-clock instant;
  `LoggingAspect` times calls with it. A self-test proves the rule lets it through.

## Consequences

**What this makes easy:**

- One rule with no exemptions: every wall-clock reading in production code comes from the `Clock` bean.
- Tests of the countdown chip and the badge cache can pin time with `Clock.fixed`.

**What this makes hard:**

- A component that needs "now" takes it from its caller or takes the `Clock`, which is one more
  constructor parameter (`MainLayout`, `CheckoutView`).
- The badge cache's age is now the difference of two `Clock` readings, so a wall-clock jump can make the
  10-second cache expire early or late once. That is harmless for a cart badge. A cache whose age must
  survive clock jumps measures it with `System.nanoTime()`.

The browser never sees a deadline: the cart, checkout and top-bar countdowns count down the seconds left
that the server computes from the bean (`LkCountdown`, UI-A13 in the same scan), so the browser's own
clock plays no part.

## Alternatives considered

- **Keep the exemption, as ADR-008 wrote it.** Rejected: tests cannot pin these reads, and an exemption
  with no visible edge grows.
- **Ban it, but exempt the badge cache's age as a duration.** Rejected: the age came from the same
  reading as the deadline, and an exemption per call site is the list this rule avoids. A true duration
  uses `System.nanoTime()`.

**Revisit when** a use needs the wall-clock instant where no `Clock` bean can reach, such as a static
initialiser or a logging component. Name that exemption narrowly in the rule, with its reason, and record
it here.
