# ADR-019: One notice per business event, one transaction per alert

- **Status:** Accepted
- **Date:** 2026-11-01
- **Issue:** #187 (messaging scan); extends [ADR-004](ADR-004-no-outbox-for-notifications.md)

## Context

Publishers raise alerts by publishing `shared.event` notices. `NotificationEventListener` consumes
them after commit on one `notify-` thread, whose queue holds 1,000 notices and drops the overflow
(ADR-004).

An admin outreach published one `NewMessageNotice` per recipient. A send to every member therefore
queued one notice per member. Past 1,000 the queue dropped them: the send lost its own alerts, and
any other user's alert that arrived while the queue was full. A test sending to 1,500 members lost
460.

The first fix published one notice per send and stored all its alerts in one transaction. That made
an all-members send one unbounded persistence context on the single `notify-` thread, and one failed
insert lost every alert of the send. Nothing needs a send's alerts to be stored together: each is an
in-app alert about a conversation the member can open in the Support Inbox anyway.

## Decision

- **A publisher publishes one notice per business event, carrying its recipients.** An outreach
  publishes one `OutreachSentNotice`, listing each recipient with the conversation opened for them.
- **The consumer stores one alert per recipient, each on its own transaction.**
  `NotificationService.notifyOutreach` runs without a transaction (`NOT_SUPPORTED`) and calls
  `NotificationDispatchService.dispatch` once per recipient, in order, so each alert commits through
  the dispatcher's proxy.
- **A store failure stops the fan-out.** The alerts already stored stay, and the rest are lost. The
  exception propagates to the async handler, which logs it, as for any notice (ADR-004).

### Accepted exceptions

Two publishers still publish one notice per recipient. Each is bounded, so the queue does not
overflow:

- **`EventCancellationRefundService`** publishes one `EventCancelledNotice` per refunded receipt.
  Each notice follows a WSEP refund call, so notices arrive no faster than the gateway answers, and
  the `notify-` thread stores each with one row insert, far faster than that. **Revisit** if refunds
  stop going one gateway call per receipt (a batch refund), or if storing an alert gets slower than
  a refund.
- **`MessagingService.notifyCompanyOwners`** publishes one `NewMessageNotice` per company owner. A
  company has a handful of owners. **Revisit** if a company can have many owners, or a notice goes to
  all of a company's staff.

## Consequences

**What this makes easy:**

- **A send takes one place in the queue,** however many recipients it has, so it cannot crowd out
  its own alerts or anyone else's.
- **Memory stays flat.** Each alert's transaction ends before the next begins, so no persistence
  context grows with the recipient count.
- **One bad insert costs one alert,** and the ones before it, which are already committed.

**What this makes hard, and what is accepted:**

- **A store failure loses the rest of the send's alerts.** Delivery was already at most once
  (ADR-004); a failure part-way through now loses the tail of one send rather than the whole send.
- **A large send holds the `notify-` thread for one insert per recipient.** Other notices wait
  behind it, in order.
- **The dispatcher logs each alert at INFO,** so a large send writes one line per recipient.

## Alternatives considered

- **One transaction for the batch.** Rejected: all-or-nothing for alerts that need no atomicity, and
  an unbounded persistence context on the single `notify-` thread.
- **Chunked transactions** (for example, a commit every few hundred alerts). Rejected for now: no
  throughput need has been measured. Revisit when a fan-out's duration matters.
- **A larger queue.** Rejected: it trades the bound on memory for room that still does not grow with
  the member count.
- **A notice per recipient.** Rejected: it overflows the 1,000-notice queue, which is the defect.

## Revisit when

- Guaranteed notification delivery is required: an outbox then replaces this (ADR-004's trigger).
- A fan-out outlasts the executor's shutdown wait (`NotificationAsyncConfig`), so a shutdown abandons
  part of a send.
- Either accepted exception's trigger above occurs.
