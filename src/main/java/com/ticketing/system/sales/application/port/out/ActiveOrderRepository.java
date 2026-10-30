package com.ticketing.system.sales.application.port.out;
import com.ticketing.system.sales.domain.ActiveOrder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.ticketing.system.shared.IRepository;

/**
 * Aggregate-root entry point for the {@link ActiveOrder} aggregate.
 *
 * <p>
 * Carts have dual identity (D9a):
 * <ul>
 * <li>Member carts are looked up by userId.</li>
 * <li>Guest carts are looked up by sessionId.</li>
 * </ul>
 * Both methods return {@link Optional} to make absence explicit.
 *
 * <p>The {@link IRepository} contract uses {@link String} as the lock-id type
 * — callers should normalize to {@code "user:" + userId} or {@code "sess:" + sessionId}
 * when calling {@link #lockForUpdate(String)} to avoid id collisions between
 * the two identity spaces.
 */
public interface ActiveOrderRepository extends IRepository<ActiveOrder, String> {

    /**
     * Stores the cart as its buyer's one cart (business rule §1 #8). Another cart of the same identity, a
     * member's by userId or a guest's by sessionId, is deleted if it holds nothing.
     *
     * @throws com.ticketing.system.shared.exception.ConcurrentReservationException if another cart of the
     *     same identity still holds lines, or another request stored the member's cart first. Deleting a
     *     cart that holds lines would leave its seats reserved under an order key no cart holds, so its
     *     lines must be released first (as the login merge does), or the request retried.
     */
    void save(ActiveOrder activeOrder);

    /**
     * Member-cart lookup. Returns {@code null} if no Member cart exists for
     * {@code userId} — preserved as the legacy signature for existing
     * callers (ReservationService, CheckoutService).
     */
    ActiveOrder getByUserId(int userId);

    /** Guest-cart lookup, or active-session Member-cart lookup. */
    Optional<ActiveOrder> getBySessionId(String sessionId);

    /** Delete by reference. Idempotent: deleting an unknown cart is a no-op. */
    void delete(ActiveOrder activeOrder);

    /**
     * Sweep query for UC-2 / Phase 5: returns carts holding any item whose hold had run out by
     * {@code now}, that is, added at or before {@code now} minus the hold duration.
     *
     * @param now the sweeper's current time, as UTC wall time from the injected clock (ADR-008)
     */
    List<ActiveOrder> findExpired(LocalDateTime now);

    // #372 — full enumeration for the boot-time integrity scan (<=1 active order per buyer/event).
    List<ActiveOrder> findAll();
}
