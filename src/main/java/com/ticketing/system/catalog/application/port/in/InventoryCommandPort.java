package com.ticketing.system.catalog.application.port.in;

import java.time.LocalDateTime;
import java.util.List;

import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;

/**
 * Catalog inbound port giving the catalog context sole ownership of Event/InventoryZone mutation.
 *
 * <p>The sales context (reservation, checkout, refund) drives every inventory reserve / release /
 * confirm / return-to-stock through this port instead of loading a {@code catalog.domain.Event} and
 * mutating it directly. All arguments and return values are sales-safe (ids, primitives, and DTOs) —
 * no {@code catalog.domain} type crosses the boundary.
 *
 * <h2>Locking contract (read carefully — concurrency-critical)</h2>
 * The Event carries a shared "buyer operation" read lock ({@code lockForBuyerOperation}) that many
 * buyers may hold at once and that blocks only structural/lifecycle edits. On the Jpa adapters
 * these locks are no-ops and correctness comes from JPA {@code @Version} optimistic locking, except for
 * the event row lock that {@link #validateEventsOnSale} takes for a sale (ADR-015); on the
 * in-memory adapters (test suite only) they are real reentrant read/write locks. Methods here fall into two
 * groups, and each method's Javadoc states which it is:
 * <ul>
 *   <li><b>Self-locking</b> ({@link #reserve}, {@link #release}, {@link #restore},
 *       {@link #returnSoldToStock}) — the port acquires and releases the event buyer-lock internally.
 *       The caller must NOT also hold it, but MUST already hold its own higher-level lock (the active
 *       order lock) so the global "order lock before event lock" acquisition order is preserved.</li>
 *   <li><b>Caller-locked</b> ({@link #validateCanConfirmSale}, {@link #confirmSale},
 *       {@link #releaseHeld}) — the caller (checkout Phase 3) must already hold the event buyer-lock
 *       for every affected event across the whole validate → persist → confirm critical section, so
 *       these steps are atomic against structural edits. These methods therefore do NOT lock; they
 *       rely on the caller's held lock (which also satisfies the in-memory repository's
 *       save-under-lock requirement, since the same thread holds the read lock).</li>
 *   <li>Pure reads ({@link #priceTicket}, {@link #eventName}, {@link #validatePurchasePolicy}) take
 *       no lock. {@link #validateEventsOnSale} takes a row lock on each event for the caller's
 *       transaction: shared on PostgreSQL, exclusive on H2.</li>
 * </ul>
 *
 * <h2>Transaction contract</h2>
 * Every method joins the caller's transaction. A caller that needs a multi-event call to be atomic
 * must therefore call it inside its transaction. Without one, each event's save commits on its own,
 * and a failure part-way leaves the earlier events changed. Two callers do that today, both
 * best-effort steps after the main work has committed or failed: {@code RefundService} calls
 * {@link #returnSoldToStock} after the refund commits, and {@code CheckoutService}'s failure rollback
 * calls {@link #releaseHeld} outside its Phase-3 transaction. Those two methods open a transaction when
 * the caller has none: one per event, so that event's load and save commit together and a lost
 * concurrency race can run again.
 */
public interface InventoryCommandPort {

    /**
     * Reserves inventory for a single zone on behalf of an order, enforcing the effective purchase
     * policy (company AND event) at the RESERVE stage first. SELF-LOCKING: acquires the event
     * buyer-lock, validates, mutates, saves, and releases the lock. The caller must already hold the
     * active-order lock (order-before-event ordering).
     *
     * @param eventId                the event to reserve in
     * @param zoneId                 the zone within the event
     * @param selection              the standing quantity / seated seats to reserve
     * @param orderKey               the owning order's stable key (stamped onto the hold for ownership)
     * @param holdUntil              when the buyer's hold runs out (UTC wall time, ADR-008); recorded on
     *                               each reserved seat
     * @param buyerId                the buyer's user id, or {@code -1} for a guest (policy context)
     * @param buyerAge               the buyer's age, or {@code null} if unknown (policy context)
     * @param totalQuantityForPolicy the buyer's total quantity for this event after this add (MAX cap)
     * @return the zone's unit price, for the caller to record on its cart line
     */
    double reserve(int eventId, int zoneId, InventorySelectionDTO selection, String orderKey, LocalDateTime holdUntil,
            int buyerId, Integer buyerAge, int totalQuantityForPolicy);

    /**
     * Releases a previously held RESERVED selection back to AVAILABLE for a single zone. SELF-LOCKING.
     * The caller must already hold the active-order lock.
     *
     * @param eventId   the event
     * @param zoneId    the zone
     * @param selection the standing quantity / seated seats to release
     * @param orderKey  the owning order's key (ownership is verified during release)
     * @return the zone's unit price at release time (used by the caller to rebuild a cart line on rollback)
     */
    double release(int eventId, int zoneId, InventorySelectionDTO selection, String orderKey);

    /**
     * Re-reserves a selection WITHOUT re-running the purchase policy — the compensating action for a
     * remove that failed after inventory was already released. SELF-LOCKING. The caller must already
     * hold the active-order lock.
     *
     * @param eventId   the event
     * @param zoneId    the zone
     * @param selection the standing quantity / seated seats to re-reserve
     * @param orderKey  the owning order's key
     * @param holdUntil when the restored hold runs out (UTC wall time, ADR-008)
     */
    void restore(int eventId, int zoneId, InventorySelectionDTO selection, String orderKey, LocalDateTime holdUntil);

    /**
     * Returns previously SOLD inventory to AVAILABLE stock (member/cancellation refund) for the given
     * flat lines. SELF-LOCKING per event and fully best-effort: each event is locked, mutated, and
     * saved independently, and any per-zone or per-event failure is logged and swallowed so a
     * stock-return hiccup never fails an already-committed refund. Called without a transaction, each
     * event's load, mutation and save run in a transaction of their own, and one that loses a
     * concurrency race (a reservation committing in the same zone meanwhile) runs again, a bounded
     * number of times; an event whose every attempt loses keeps its places SOLD and is logged at ERROR.
     *
     * @param lines the refunded lines (seat label per line, or {@code null} for one standing unit)
     */
    void returnSoldToStock(List<InventoryLineDTO> lines);

    /**
     * Computes the final unit price for one ticket of an event via its discount policy. Pure read.
     *
     * @param eventId             the event
     * @param eventQuantity       the total quantity being bought for this event (drives quantity discounts)
     * @param priceAtReservation  the price captured when the ticket was reserved
     * @param now                 the pricing timestamp
     * @return the final per-ticket price
     */
    double priceTicket(int eventId, int eventQuantity, double priceAtReservation, LocalDateTime now);

    /**
     * Returns an event's display name. Pure read.
     *
     * @param eventId the event
     * @return the event's name
     */
    String eventName(int eventId);

    /**
     * Validates the effective purchase policy (company AND event) at the CHECKOUT stage. Pure read.
     *
     * @param eventId  the event
     * @param buyerId  the buyer's user id, or {@code -1} for a guest
     * @param buyerAge the buyer's age, or {@code null} if unknown
     * @param quantity the quantity being purchased for this event
     */
    void validatePurchasePolicy(int eventId, int buyerId, Integer buyerAge, int quantity);

    /**
     * Validates that every given event still exists and is sellable (ON_SALE or SOLD_OUT), and holds each
     * event against a lifecycle write until the caller's transaction ends ({@code EventRepository#findForSale}).
     * Checkout Phase 3 calls it first in the transaction that commits the sale, so a cancel that lands
     * meanwhile waits for the sale and then refunds it.
     *
     * @param eventIds the events to check
     * @throws org.springframework.transaction.IllegalTransactionStateException on the Jpa adapters, if
     *     the caller has no transaction
     */
    void validateEventsOnSale(List<Integer> eventIds);

    /**
     * Fail-fast, read-only ownership/status check that the given held lines can still be confirmed
     * SOLD (seats still RESERVED and owned by {@code orderKey}, enough reserved standing stock).
     * CALLER-LOCKED: the caller must hold the event buyer-lock for every affected event across this
     * check and the subsequent {@link #confirmSale}.
     *
     * @param lines    the held lines to confirm
     * @param orderKey the owning order's key
     */
    void validateCanConfirmSale(List<InventoryLineDTO> lines, String orderKey);

    /**
     * Confirms the given held lines as SOLD (RESERVED → SOLD), grouping by event and zone and saving
     * each event once. If a later event fails after earlier ones were confirmed, the already-confirmed
     * units are compensated back to AVAILABLE before the failure propagates, so no partial-SOLD state
     * is left behind. CALLER-LOCKED: the caller must hold the event buyer-lock for every affected event.
     *
     * @param lines    the held lines to confirm
     * @param orderKey the owning order's key
     */
    void confirmSale(List<InventoryLineDTO> lines, String orderKey);

    /**
     * Releases the given still-held (RESERVED) lines back to AVAILABLE during a checkout rollback,
     * grouping by event and zone and saving each event once, in a transaction of its own when the caller
     * has none, run again up to three attempts in all when it loses a concurrency race. Best-effort per
     * zone: a zone that can no longer be released is logged and skipped so the rest still roll back. A
     * deleted event is skipped. Every event is tried even when another fails. CALLER-LOCKED: the caller
     * must hold the event buyer-lock for every affected event.
     *
     * @param lines    the held lines to release
     * @param orderKey the owning order's key
     * @throws RuntimeException the first event's failure, with the later ones suppressed, once every event
     *                          has been tried, so the caller can keep the lines whose holds remain
     */
    void releaseHeld(List<InventoryLineDTO> lines, String orderKey);
}
