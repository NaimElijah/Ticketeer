# ADR-004: Deliver notifications after commit on a background thread, without an outbox

- **Status:** Accepted
- **Date:** 2026-10-18
- **Issue:** #29 (dispatch notifications after commit, off the request thread)

## Context

Sales, organization, catalog and messaging publish integration notices (`shared.event.*Notice`).
The notifications context consumes them in `NotificationEventListener` and stores a `PENDING`
`Notification` row, which `NotificationPollingScheduler` later pushes to the UI.

Before this decision the listener was a plain synchronous `@EventListener`:

- **A notification ran inside the publisher's thread and transaction.** A slow channel delayed
  checkout, reservation and company operations (SLR.5).
- **A notification could outlive its use case.** A notice published inside a transaction that then
  rolled back still produced a notification under the in-memory repositories.
- **Publishers defended themselves.** Each one wrapped `publishEvent` in a swallow-and-log
  try/catch, so a notification failure could not fail the use case. That hid real failures in
  every publisher.

Spring Modulith offers two ready answers. `@ApplicationModuleListener` (async, after commit, in its
own transaction) composes a `@TransactionalEventListener` without fallback execution, so it skips
events published outside a transaction. The copy on this classpath
(`org.springframework.modulith.ApplicationModuleListener`, spring-modulith-api 1.4.1) is deprecated
for removal; the supported one lives in `spring-modulith-events-api`, which the project does not
depend on. The event-publication registry is an outbox: it persists every event before commit
and replays incomplete ones after a crash. The V3 appendix says not to persist event-like data.

## Decision

**`NotificationEventListener` consumes every notice after the publisher's transaction commits, on
a dedicated background thread:** `@Async("notificationExecutor")` on the class, and
`@TransactionalEventListener(fallbackExecution = true)` (phase `AFTER_COMMIT`) on each method.

- **After commit.** A notice published inside a transaction that rolls back is never delivered.
  This includes the reservation-failure notices, which `ReservationService` publishes inside the
  transaction it then rolls back. Under JPA their rows were already rolled back before this
  decision, and the UI shows the failure synchronously. Whether those alerts must be sent belongs
  to #63.
- **Fallback execution.** `CheckoutService` and `EventCancellationRefundService` publish after
  their transactions have ended. With no transaction active, the listener runs at once, still on
  the background thread.
- **One background thread.** The `notificationExecutor` is a single-thread pool with a bounded
  queue, so notices are stored in the order they were submitted: publish order within one thread,
  commit order across concurrent transactions. Storing one is a single row insert.
- **A full queue drops the notice and logs a WARN.** It never throws into the publisher and never
  runs on the publisher's thread. Running it on the caller would block the use case, and during
  `afterCompletion` the caller's committed transaction is still bound, so the notification's own
  `@Transactional` work would join it and its insert would be lost.
- **Publishers no longer catch notification failures.** A failure in the listener cannot reach
  them. It surfaces in the async uncaught-exception handler's ERROR log.
- **No outbox and no event-publication registry.** A notice in the queue is lost if the process
  stops before it is stored. That covers a crash, a shutdown past the executor's wait, or a full
  queue.
- **Boot's own `applicationTaskExecutor` stays.** Spring Boot 3.5 stops creating it once any
  `Executor` bean exists, so `spring.task.execution.mode: force` keeps it.

## Consequences

**What this makes easy:**

- **A slow or failing notification channel cannot delay, fail or roll back** a reservation,
  checkout, cancellation or company operation.
- **Notifications describe only committed work.**
- **Publishers are plain again:** one `publishEvent` call each, with no defensive try/catch.

**What this makes hard, and what is accepted:**

- **Delivery is at most once.** A crash or an overflowing queue loses notices without a retry. A
  notification here is an in-app alert about something the buyer or producer can also see on the
  relevant screen.
- **Tests that read notifications must wait for them.** They poll with Awaitility and a deadline.
- **Log context does not follow the notice.** MDC values such as a correlation id (#30) do not
  reach the `notify-` thread until a `TaskDecorator` copies them.
- **A synchronous listener on a notice would reintroduce the coupling.** Any new consumer of a
  `*Notice` must follow the same pattern.

**Revisit when any of these happens:**

- A notice drives an external side effect that must not be lost, such as transactional email
  (#102).
- Guaranteed notification delivery becomes a requirement.
- The application runs as more than one instance, so an in-memory queue no longer sees all work.

## Alternatives considered

- **Modulith's event-publication registry (an outbox).** Rejected for now: it persists event data,
  which the V3 appendix rules out, and adds a table and a replay job for alerts that tolerate loss.
- **`@ApplicationModuleListener`.** Rejected: without fallback execution it would silently drop the
  notices that checkout and event cancellation publish after their transactions end. The copy on
  this classpath is also deprecated for removal, and the supported one needs the extra
  `spring-modulith-events-api` dependency.
- **Keep the synchronous listener and the publishers' try/catch blocks.** Rejected: a slow channel
  still blocks the use case, and a rolled-back transaction can still notify.
- **`AFTER_COMPLETION` for the failure notices,** so that a failure raised inside a rolled-back
  transaction is still delivered. Rejected: it delivers notices about rolled-back work, and it ties
  the listener to the shape of each publisher's transaction. A failure alert that must be sent
  should be published after the transaction ends, as checkout does.
- **A caller-runs rejection policy.** Rejected: it blocks the publisher when the queue is full, and
  its insert would join the publisher's already-committed transaction.
