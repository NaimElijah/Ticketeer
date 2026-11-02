# ADR-015: Sales hold a shared lock on their events, and Phase 3 runs again after a lost race

- **Status:** Accepted. Extended by [ADR-017](ADR-017-inventory-cleanup-steps-retry-each-event-in-its-own-transaction.md): the same retry runs inventory's clean-up steps, one event at a time.
- **Date:** 2026-10-30
- **Issue:** #186 (persistence scan); related #171, #227, #108

## Context

On the Jpa adapters, concurrency is optimistic: `lockForUpdate` and `lockForBuyerOperation` are no-ops, and each aggregate's `@Version` decides who wins. Checkout's Phase 3 commits a sale in one transaction. It checks that each event is still on sale, saves the tickets and the receipt, and confirms the reserved inventory as sold.

Forces:
- **A cancel must refund every sale on its event.** It commits CANCELED and then refunds the receipts it can see. A Phase 3 that has passed its on-sale check but not yet committed is invisible to that refund, and nothing made the cancel wait for it. The buyer was charged for a cancelled event and never refunded.
  - Until #186 a stray write hid this: every buyer operation rewrote the event row and bumped its version, so such a Phase 3 failed on the version instead. That write also made any two buyers in one event conflict, and it is gone.
- **Sales in one event must not serialise.** Under a sales-open spike, checkouts of one event are the common case. An exclusive lock on the event would queue every one of them.
- **Two sales in one standing zone both write the zone's counts,** so the second to commit loses on the zone's `@Version` although the zone had room for both. A loss is reported after the charge, so it costs a refund.
- **The Memory adapters already serialise these cases** through the buyer and structural locks, so they need nothing new.

## Decision

- **A sale holds a shared row lock on each of its events until it commits.** `EventRepository.findForSale` loads the event with `PESSIMISTIC_READ` (`FOR SHARE` on PostgreSQL). It requires the caller's transaction (`MANDATORY`), and checkout Phase 3's `validateEventsOnSale` calls it first, in sorted event order. Sales share the lock, so they run together. A write to the event row waits for every sale holding it: a cancel's CANCELED, a lifecycle edit, the completion sweeper, or a buyer operation that flips the event between ON_SALE and SOLD_OUT (reserving its last place, or a release, expiry or refund that frees one). The cancel's refund then sees the sale's receipt.
- **A sale never writes the event row,** not even when it sells the last places, so it cannot deadlock by upgrading the shared lock (`BuyerOperationEventVersionJpaTest`). Purchase and discount policies have value equality, so Hibernate no longer rewrites the event or company row on every flush.
- **Phase 3 runs again after a lost race, up to three attempts in all** (`CheckoutService.runPhase3`). It retries on any `ConcurrencyFailureException`: a lost `@Version` race in a standing zone, a deadlock victim, a lock-wait timeout. Phase 3 is database work only, re-reads and re-validates everything, and rolls back whole, so running it again is safe. After the last attempt, the checkout fails and refunds as before.

## Consequences

- **Easier:**
  - A cancel that lands during a sale refunds it.
  - Concurrent sales in one event do not conflict on the event, and seated sales do not conflict at all.
  - Two or three overlapping sales in one standing zone all succeed.
- **Harder:**
  - **H2 has no shared row lock.** Hibernate's H2 dialect maps `PESSIMISTIC_READ` to `FOR UPDATE`, so in dev and in the H2 suites the Phase 3s of one event run one after another. Races that need them to overlap are tested on PostgreSQL only (`PostgresConcurrentCheckoutsOneZoneIT`, `PostgresCancelDuringCheckoutIT`).
  - **A writer can be kept waiting.** PostgreSQL does not queue a new shared lock behind a writer that is already waiting, so during a long run of overlapping sales a cancel, a lifecycle edit or a sell-out flip waits until they stop. That wait is bounded by `database.socket-timeout`, after which the writer fails: the buyer taking an event's last place sees the service as unavailable, and a sweeper tick that reopens a sold-out event rolls back every release in that tick. Revisit at the load test (#108).
  - **The retry has a bound.** More than three sales overlapping in one standing zone can still exhaust it and refund. Queueing standing-zone sales is #227.
  - **Each shared lock writes the event row's lock metadata,** so a hot event row is touched by every sale.

## Alternatives considered

- **Make Phase 3 bump the event's version** (`OPTIMISTIC_FORCE_INCREMENT`), so a concurrent cancel conflicts with it. Rejected: every two sales of one event would then conflict with each other too, which is the failure #186 removed.
- **Take an exclusive lock on the event in Phase 3.** Rejected: it queues every sale of an event behind the others, during exactly the spike where they all arrive.
- **Have the cancel's refund also look for receipts committed after it.** Rejected: there is no point at which "after" ends, and a late receipt would need a sweeper of its own.
- **Lock each standing zone in Phase 3 instead of retrying.** Not rejected: it is the better fix for busy standing zones, and is #227. It needs the zone locked before the event graph loads, and a lock order with the event's lock that cannot deadlock.

## Revisit when

- The load test (#108) shows cancels or lifecycle edits waiting too long behind sales.
- #227 queues standing-zone sales.
- A second application instance, or a database without shared row locks, is introduced.
