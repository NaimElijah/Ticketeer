package com.ticketing.system.sales.adapter.out.identity;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.ticketing.system.identity.application.port.out.CartRestorationPort;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.shared.dto.ActiveOrderDTO;

import lombok.extern.slf4j.Slf4j;

/**
 * Sales-side implementation of identity's {@link CartRestorationPort}. It lets the
 * identity login flow (UC-12) restore and merge a member's persistent cart (UC-13 /
 * D9a) by delegating to sales-internal collaborators, so the source dependency points
 * {@code sales -> identity} (acyclic) and identity imports no sales type.
 *
 * <p>The adapter carries no {@code @Transactional} of its own: it is called from
 * {@code AuthenticationService.login}, which is already {@code @Transactional}, so the
 * lock/lookup/save merge below runs inside that login transaction (atomic with the
 * session promotion) — preserving the pre-migration behavior.
 */
@Component
@Slf4j
public class CartRestorationAdapter implements CartRestorationPort {

    // Sales use-case service that builds the enriched cart DTO for the login response.
    private final ReservationService reservationService;
    // Sales aggregate-root port for the ActiveOrder (cart) aggregate.
    private final ActiveOrderRepository activeOrderRepository;

    /**
     * Wires the adapter to the sales collaborators it delegates to.
     *
     * @param reservationService    sales service that restores/enriches the cart DTO
     * @param activeOrderRepository sales port used to lock, look up and save carts
     */
    public CartRestorationAdapter(ReservationService reservationService,
                                  ActiveOrderRepository activeOrderRepository) {
        this.reservationService = reservationService;       // store for restore delegation
        this.activeOrderRepository = activeOrderRepository; // store for the merge flow
    }

    /** {@inheritDoc} */
    @Override
    public ActiveOrderDTO restoreActiveOrder(int userId) {
        // Delegate to the sales service, which enriches cart lines with event names.
        return reservationService.restoreActiveOrder(userId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>D9a cart wiring at promotion time. Two cases:
     * <ol>
     * <li>A Guest cart was bound to this session (user filled it while browsing)
     * &rarr; claim it for the now-authenticated user.</li>
     * <li>No Guest cart this session, but an orphaned Member cart exists from a
     * previous logout &rarr; re-attach it to the new session.</li>
     * </ol>
     * If both happen to exist, the Guest cart wins (most recent intent); the
     * {@code MemoryActiveOrderRepository}'s save() collapses identity by userId,
     * so the stale Member cart is replaced automatically.
     */
    @Override
    public void mergeGuestCartOnPromotion(String sessionId, int userId) {
        String guestKey = "sess:" + sessionId; // canonical lock key for the guest cart
        String userKey  = "user:" + userId;    // canonical lock key for the member cart

        // Lock both keys in lexicographic order so every caller acquires them in the
        // same sequence, preventing deadlocks with ReservationService and the
        // SessionAndOrderSweeper which use the same key convention.
        String firstKey  = guestKey.compareTo(userKey) <= 0 ? guestKey : userKey; // lower key acquired first
        String secondKey = guestKey.compareTo(userKey) <= 0 ? userKey  : guestKey; // higher key acquired second

        activeOrderRepository.lockForUpdate(firstKey);  // acquire the first lock
        activeOrderRepository.lockForUpdate(secondKey); // acquire the second lock
        try {
            Optional<ActiveOrder> guestCart = activeOrderRepository.getBySessionId(sessionId); // look up guest cart
            if (guestCart.isPresent() && guestCart.get().isGuest()) { // a genuine guest cart to promote
                guestCart.get().attachToUser(userId);        // claim it for the member
                activeOrderRepository.save(guestCart.get()); // persist the promotion
                log.debug("cart promoted to member userId={} sid={}", userId, sessionId);
                return; // guest cart wins (most recent intent) — done
            }
            ActiveOrder priorMemberCart = activeOrderRepository.getByUserId(userId); // orphaned member cart?
            if (priorMemberCart != null) {                    // restore a prior member cart onto the new session
                priorMemberCart.attachToSession(sessionId);   // re-bind to the fresh session
                activeOrderRepository.save(priorMemberCart);  // persist the re-attach
                log.debug("member cart restored userId={} sid={}", userId, sessionId);
            }
        } finally {
            activeOrderRepository.unlock(secondKey); // release in reverse acquisition order
            activeOrderRepository.unlock(firstKey);  // release the first lock last
        }
    }
}
