# ADR-020: The market view reports the last WSEP probe; the self-heal probes on its own scheduler

- **Status:** Accepted
- **Date:** 2026-11-01
- **Issue:** #187 (governance scan); builds on [ADR-003](ADR-003-fatal-and-self-healing-init-failures.md)

## Context

The admin dashboard shows whether the payment service and the ticket issuer are reachable. It polls
`SystemAdminService.viewMarketState` every 5 s. That call used to probe WSEP each time, and so did
`closeMarket`, which is `synchronized`.

A probe is slow while WSEP hangs. One handshake makes up to `wsep.handshake-attempts` (3) attempts of
up to `wsep.request-timeout-seconds` (20 s) each, with a linear backoff of `wsep.handshake-backoff-ms`
(1 s, then 2 s) between them: about 63 s. A probe runs the payment handshake and then the issuer
handshake, so it takes about two minutes. Probing on every poll stalled the dashboard, and a close
queued behind it.

Once the probe moved off the view, nothing probed WSEP while the market was OPEN or CLOSED: the
self-heal (`MarketSelfHealScheduler`, every `market.self-heal-delay-ms`, 30 s) only tries to open a
market that is not open yet. The dashboard kept the result of the last open, however old.

All `@Scheduled` jobs ran on Boot's single shared scheduler thread, so a two-minute probe on the
self-heal's tick would delay every sweeper behind it.

## Decision

- **The market view reports the last WSEP probe instead of probing.** Initialization, open and the
  self-heal record each probe's two results and the time it finished, as one immutable snapshot.
  `viewMarketState` and `closeMarket` read it and never probe. Before any probe the time is `null` and
  the dashboard says "not checked yet".
- **The self-heal probes on every tick.** When `ensureMarketOpen()` did not probe in that tick (the
  market is OPEN or CLOSED, or an initialization or open failed before its probe), the scheduler calls
  `SystemAdminService.refreshServiceReachability()`. That method is not `synchronized`, so neither a
  view nor a close waits for its probe.
- **The self-heal runs on its own single-thread scheduler,** `marketSelfHealTaskScheduler`, which
  `SchedulingConfig` declares and the self-heal names in its `@Scheduled`.
- **The default `taskScheduler` is re-declared in `SchedulingConfig` as `@Primary`,** built from Boot's
  `ThreadPoolTaskSchedulerBuilder`. Boot's auto-configured one backs off as soon as any `TaskScheduler`
  bean exists; without the re-declaration, every other job would resolve the self-heal's scheduler.
- **Probing stays sequential:** the payment handshake, then the issuer handshake. Initialization and
  open probe both before either failure stops them.

## Consequences

**What this makes easy:**

- **The dashboard answers at once,** whatever WSEP does, and shows when its reachability was checked.
- **No sweeper waits for a probe,** since the self-heal has its own thread.
- **The reachability shown is at most one tick plus one probe old** while the self-heal runs.

**What this makes hard, and what is accepted:**

- **A probe takes about two minutes while WSEP hangs,** so the reachability shown can be that much
  older than the tick.
- **An initialization or open probes under the service's monitor,** so a close waits behind one in
  progress, an admin's or the self-heal's, for up to about two minutes while WSEP hangs.
- **The self-heal repeats its ERROR on every tick** while a failure other than a WSEP outage lasts
  (ADR-003 makes only a WSEP outage self-heal), with the exception and its stack trace each time.

## Alternatives considered

- **Probing on view.** Rejected: it stalls the dashboard's poll and, through `closeMarket`, the
  service's monitor for as long as a probe takes.
- **The self-heal on the shared scheduler.** Rejected: a probe there delays every sweeper behind it.
- **Probing both services concurrently.** Rejected for now: it halves the wait, but adds a thread per
  probe for a path that is slow only during an outage.

## Revisit when

- `spring.threads.virtual.enabled` is turned on. Boot would then build a `SimpleAsyncTaskScheduler`
  as its default, but the re-declared `taskScheduler` stays a `ThreadPoolTaskScheduler`.
- Admins report waiting on open or close during WSEP outages: then probe both services concurrently.
- Another job needs a scheduler of its own.
