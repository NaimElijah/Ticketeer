# ADR-017: Inventory's clean-up steps run each event in its own transaction and retry a lost race

- **Status:** Accepted
- **Date:** 2026-11-01
- **Issue:** #187 (code-review scan, catalog partition); related #214, #130, ADR-015

## Context

`InventoryCommandPort`'s methods join the caller's transaction. Two callers use the port with no transaction of their own:
- a member refund returns the refunded places to stock (`returnSoldToStock`) after the refund has committed (`RefundService`);
- a failed checkout releases the cart's holds (`releaseHeld`) outside its Phase 3 transaction (`CheckoutService`).

Both are clean-up after the main work has committed or failed.

Forces:
- **Without a transaction, the loaded event is detached by the time it is saved.** A reservation that commits in the same zone in between bumps the zone's `@Version`, and the save loses. This was proven on H2 for both methods (`RefundStockReturnRaceJpaTest`, `ReleaseHeldRaceJpaTest`).
- **A lost refund stock return leaves places SOLD for good.** The money is already back, and nothing repairs them.
- **A lost release stopped the rollback at that event.** The cart's holds in later events stayed reserved.
- **A lost attempt commits nothing,** so running it again on a fresh load is safe.
- **Neither step may undo the main work.** The refund is final once the gateway has paid it back, and a checkout that failed has already failed.

## Decision

- **Each event gets a transaction of its own.** `returnSoldToStock` and `releaseHeld` run one event's load, mutation and save in one transaction (`TransactionTemplate`, joining a caller's transaction if one exists). The other methods of the port keep joining the caller's transaction.
- **A lost race runs the event again, up to three attempts in all,** on any `ConcurrencyFailureException`: a lost `@Version` race, a deadlock victim, or on H2 a lock timeout. Both methods share this retry (`InventoryService.runRetryingLostRaces`). It is ADR-015's Phase 3 retry, applied to one event at a time.
- **One event's failure never stops the others.**
  - `returnSoldToStock` logs every event or zone that still fails at ERROR, with the places it leaves SOLD, and returns. The refund has committed and is reported as done.
  - `releaseHeld` tries every event, then rethrows the first failure, with the later ones suppressed. The checkout then skips clearing the cart, so no hold is left without a cart line that names it.

## Consequences

- **Easier:**
  - A refund's stock return and a rollback's release survive a reservation committing beside them.
  - One deleted or failing event does not strand the others.
- **Harder:**
  - **The port has two transaction rules.** Two methods own a transaction when the caller has none; the rest join. The port's Javadoc states both.
  - **A step that gives up is not repaired.** A stock return that loses every attempt, or fails for another reason, leaves its places SOLD with only an ERROR line (SLR.1). A release that fails leaves the order as the failed checkout left it. If that checkout had frozen it, the order stays frozen, and the expiry sweeper never releases a frozen order (#214).
  - **PostgreSQL sets no `lock_timeout`,** so a long lock wait there is not a lost race. It ends at `database.socket-timeout` and is not retried.

## Alternatives considered

- **Run the ticket flips and the stock return inside the refund's transaction.** Rejected: the gateway refund runs in that transaction, so a failure after it would roll back the receipt's refund while the money stays returned. A retry would then refund again.
- **Leave the steps transaction-less and only log a lost race.** Rejected: the places stay SOLD, or the holds stay reserved, on a race the next attempt would win.
- **Retry without bound.** Rejected: a hot zone under a sales spike could keep a refund retrying indefinitely. ADR-015 chose three attempts for the same reason.

## Revisit when

- #214 adds a repair for orders left frozen, or #130 redesigns the member refund. Either can take over the clean-up these steps do.
- A reconciliation job exists that can find places SOLD with no live ticket. The stock return could then hand failures to it instead of only logging them.
- The load test (#108) shows three attempts are exhausted in practice.
