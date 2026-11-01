# ADR-018: A member refund stands when its ticket flips fail

- **Status:** Accepted
- **Date:** 2026-11-01
- **Issue:** #187 (code-review scan, sales partition); related #130, #44, ADR-001, ADR-017

## Context

`RefundService.requestRefund` refunds a member's order in one transaction. It takes the receipt's row lock, calls the payment gateway's `refund` inside the transaction by design (upstream #410), and saves the receipt as refunded. Two steps then follow:
1. the order's tickets are flipped to REFUNDED, or VOIDED;
2. their places are returned to stock (`InventoryCommandPort.returnSoldToStock`, ADR-017).

Until #187, the ticket flips ran with no transaction and outside any `try`. A failed save left some tickets flipped and others not. It also propagated to the member as a failure, although the money was back, and it skipped the stock return. A retry was then told "already refunded".

Forces:
- **The gateway refund cannot be undone.** Once it has answered, the money is back with the member.
- **Valid tickets on free seats sell a seat twice.** A ticket still PAID or ISSUED admits its holder, so its place must not return to stock.
- **A retry cannot finish the job.** The receipt already says refunded, so a second request is refused, and there is no repair path yet (#130 redesigns the member refund).

## Decision

- **The ticket flips run in one transaction of their own, after the refund's has committed.** All of an order's flips commit together or not at all. They never run inside the refund's transaction: rolling that back over a failed flip would leave money returned with a receipt that does not record it, and a retry would refund again.
- **If the flips fail, the refund still stands and is reported as done.** The failure is logged at ERROR, naming the order. The tickets stay valid and their places stay SOLD, so no seat can be sold twice.
- **The stock is returned only after the flips commit.**

## Consequences

- **Easier:**
  - The member is never told a refund failed when the money is back.
  - No seat is freed while a valid ticket holds it.
  - The flips are all or nothing.
- **Harder:**
  - **An order can be refunded while its tickets stay valid.** Until it is repaired, the tickets still admit their holders and the places stay SOLD, and an ERROR line is the only record (SLR.1).
  - **The repair is manual until #130.**

## Alternatives considered

- **Flip the tickets inside the refund's transaction.** Rejected: a failed flip would roll back the receipt's refund after the gateway has paid it, and a retry would refund again. ADR-017 records the same rejection for the stock return.
- **Report the refund as failed when the flips fail.** Rejected: the money is back, and the member's retry is refused as already refunded, so the message would be false and leave the member nowhere to go.
- **Return the stock even when the flips fail.** Rejected: it frees seats whose tickets still admit their holders.

## Revisit when

- #130 redesigns the member refund. A refund state per line (ADR-001), or a reconciliation step, could finish or repair the flips instead of only logging them.
- A reconciliation job exists that finds refunded orders with valid tickets.
