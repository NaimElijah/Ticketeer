package com.ticketing.system.Core.Domain.ActiveOrder;

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
public interface IActiveOrderRepository extends IRepository<ActiveOrder, String> {

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

    /** Sweep query for UC-2 / Phase 5: returns carts with any expired items. */
    List<ActiveOrder> findExpired();

    // #372 — full enumeration for the boot-time integrity scan (<=1 active order per buyer/event).
    List<ActiveOrder> findAll();
}
