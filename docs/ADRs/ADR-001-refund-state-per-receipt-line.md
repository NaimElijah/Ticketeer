# ADR-001: Refund state per receipt line, with resumable event-cancellation refunds

- **Status:** Accepted
- **Date:** 2026-10-15
- **Issue:** #20 (cancellation refunds not atomic or resumable); applied by #21 (line-level revenue)

## Context

Cancelling an event (UC-19) must refund every buyer automatically (I.3, UC-4), keep the platform
consistent under concurrency (SLR.1), and survive the loss of the payment service (SLR.5). Three
forces shape the solution:

- **The payment gateway is external.** V3-TX-02 keeps external calls outside database transactions:
  a slow or failing gateway must not hold row locks or connections open.
- **One order can span several events and companies.** Each line records its own event, but the
  order had a single refunded flag. A run could not tell which lines it had already paid back, and
  cancelling one event marked the whole order refunded.
- **Before this decision, a failure stranded buyers.**
  - The event's CANCELED state committed first.
  - The refunds then ran with no transaction and no record of progress.
  - A gateway failure on one receipt left the remaining buyers unrefunded.
  - Cancelling again was a no-op, so they were never refunded.
  - Removing that early return alone would have refunded every receipt a second time.

## Decision

**Refund state lives on each `ReceiptLine`** as a `LineRefundState`. Only the owning `OrderReceipt`
moves a line between states:

| State | Meaning |
|---|---|
| `NONE` | Paid and not refunded. |
| `PENDING` | Claimed by a refund that is in flight at the gateway. |
| `REFUNDED` | Refunded, and the refund is recorded on the receipt. |

**`EventCancellationRefundService` refunds a cancelled event receipt by receipt, in three steps:**

1. **Claim.** A short transaction holds the receipt's lock (`SELECT … FOR UPDATE` under JPA). It
   moves the event's `NONE` lines to `PENDING` and commits. A concurrent or repeated run finds
   nothing to claim and skips the receipt.
2. **Refund.** The service calls the gateway outside any transaction, then validates the result.
   Lines that cost nothing need no gateway call.
3. **Record.** A second short transaction moves the lines to `REFUNDED`, adds the `REFUND` record,
   and flips that receipt's tickets for the event. All of this commits together.

**What happens to the claim depends on what the gateway's failure says about the money:**

| Outcome | Raised as | Claim |
|---|---|---|
| The gateway declined | `RefundFailedException` | Released (`PENDING` back to `NONE`). |
| The request never reached the gateway (connection refused, connect timeout) | `RefundGatewayUnreachableException` | Released. |
| The gateway may have refunded without a usable answer (read timeout, malformed or mismatched result, unexpected error) | `RefundOutcomeUnknownException` | Kept `PENDING`, logged at ERROR. |

In every case the run stops. The receipts refunded so far stay refunded, and the event stays
CANCELED.

**Cancelling a CANCELED event republishes `EventCancelledEvent`,** which resumes with the receipts
that are still unrefunded. Catalog keeps no knowledge of sales; the existing event direction stays.

**A whole-order member refund (`RefundService`) is refused before the gateway call** once any line
is `PENDING` or `REFUNDED`. Without that check, it could charge back money a cancellation is already
returning.

**The `refund_state` column is nullable, and a null reads as `NONE`.** Hibernate's `ddl-auto: update`
cannot add a NOT NULL column to populated tables, and versioned migrations do not exist yet (#31).

**A receipt refunded before this decision counts as fully refunded.** Such a row has the
order-level refunded flag set and no refund state on any line. Treating its lines as `NONE` would
let a member refund or a cancellation pay it back a second time. Counting them as `REFUNDED` fails
closed: at worst a refund is withheld, never paid twice.

## Consequences

**What this makes easy:**

- **At most once.** Each line is refunded at most once, across retries and concurrent runs. This is
  proved on JPA by `EventCancellationRefundConcurrencyTest`. A line whose refund was declined or never
  sent is refunded by a later run; a line whose outcome is unknown is never retried automatically.
- **Resumable.** A gateway outage partway through an event no longer strands buyers: the owner
  cancels again and the run continues where it stopped.
- **Correct per-event refunds.** A multi-event order refunds only the cancelled event's lines.
- **A basis for line-level reporting.** Revenue and refund status can come from the lines themselves
  (#21).

**What this makes hard, and what is accepted:**

- **A receipt can stay `PENDING` and need manual recovery.** This happens if the gateway's outcome is
  unknown, if the process dies or the database fails between the claim and the record, or if a
  refused claim cannot be released. Without gateway idempotency a retry could refund the same money
  twice, so a stale claim is never retried automatically. The service logs these cases at ERROR
  when it is still running to see them.
- **A WSEP timeout counts as unknown.** WSEP's HTTP client reports every transport failure the same
  way; only a connect failure proves the request was never sent, so only that is released.
- **The run stops at the first failure** instead of carrying on with the remaining receipts. A
  failing gateway usually fails for everyone, and the retry resumes cleanly.
- **There is no automatic retry.** The owner re-invokes cancel from the event management screen.
- **WSEP cannot refund part of a charge.** It reverses the whole charge by id, so refunding one event
  of a multi-event order over-refunds at the payment service even though the receipt records only
  that event's lines. The Stripe adapter (#98) must send the partial amount.

**Revisit when either of these happens:**

- The payment adapter supports idempotency keys (#98, #99). A stale `PENDING` claim can then be
  re-tried safely with the same key.
- Refund volume or gateway latency makes manual cancel-again retries impractical.

## Alternatives considered

- **Hold the receipt lock across the gateway call, as `RefundService` does (#410).** Rejected: it
  breaks V3-TX-02. A slow gateway would hold row locks for every receipt of a large event.
- **Keep the order-level flag and skip receipts already flagged.** Rejected: a multi-event order
  flagged by one cancellation would never be refunded for its other events. It also cannot express
  an in-flight refund, so concurrent runs would still refund twice.
- **Continue past a failed receipt and report all failures at the end.** Rejected for now. It hammers
  a gateway that is already failing, and the retry gives the same outcome.
- **Retry stale `PENDING` claims after a timeout.** Rejected until the gateway is idempotent: a crash
  after the gateway refunded but before the record step would refund the buyer twice.
