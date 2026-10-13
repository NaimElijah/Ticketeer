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
 * buyers may hold at once and that blocks only structural/lifecycle edits. Under the {@code jpa}
 * profile these locks are no-ops and correctness comes from JPA {@code @Version} optimistic locking;
 * under the in-memory profile they are real reentrant read/write locks. Methods here fall into two
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
 *   <li>Pure reads ({@link #priceTicket}, {@link #eventName}, {@link #validatePurchasePolicy},
 *       {@link #validateEventsOnSale}) take no lock.</li>
 * </ul>
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
     * @param buyerId                the buyer's user id, or {@code -1} for a guest (policy context)
     * @param buyerAge               the buyer's age, or {@code null} if unknown (policy context)
     * @param totalQuantityForPolicy the buyer's total quantity for this event after this add (MAX cap)
     * @return the zone's unit price, for the caller to record on its cart line
     */
    double reserve(int eventId, int zoneId, InventorySelectionDTO selection, String orderKey,
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
     */
    void restore(int eventId, int zoneId, InventorySelectionDTO selection, String orderKey);

    /**
     * Returns previously SOLD inventory to AVAILABLE stock (member/cancellation refund) for the given
     * flat lines. SELF-LOCKING per event and fully best-effort: each event is locked, mutated, and
     * saved independently, and any per-zone or per-event failure is logged and swallowed so a
     * stock-return hiccup never fails an already-committed refund.
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
     * Validates that every given event still exists and is sellable (ON_SALE or SOLD_OUT). Pure read.
     *
     * @param eventIds the events to check
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
     * grouping by event and zone and saving each event once. Best-effort per zone: a zone that can no
     * longer be released is logged and skipped so the rest still roll back. CALLER-LOCKED: the caller
     * must hold the event buyer-lock for every affected event.
     *
     * @param lines    the held lines to release
     * @param orderKey the owning order's key
     */
    void releaseHeld(List<InventoryLineDTO> lines, String orderKey);
}
