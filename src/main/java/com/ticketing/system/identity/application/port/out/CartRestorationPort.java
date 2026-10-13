package com.ticketing.system.identity.application.port.out;

import com.ticketing.system.shared.dto.ActiveOrderDTO;

/**
 * Outbound port through which the identity context asks the sales context to
 * restore or merge a member's persistent cart at login time (UC-13 / D9a),
 * without identity depending on any sales type.
 *
 * <p>Identity owns this port; a sales-side adapter implements it, so the source
 * dependency points {@code sales -> identity} (identity imports no sales type),
 * keeping the bounded-context graph acyclic.
 */
public interface CartRestorationPort {

    /**
     * Restores the member's persistent active order (cart) for display in the
     * login response (UC-13).
     *
     * @param userId the id of the member who just authenticated
     * @return the member's restored cart as a shared DTO, or {@code null} if none exists
     */
    ActiveOrderDTO restoreActiveOrder(int userId);

    /**
     * Merges any guest cart bound to {@code sessionId} into the member's cart at
     * promotion time, or re-attaches an orphaned member cart to the new session
     * (D9a). Invoked from within the caller's login transaction, so the merge is
     * atomic with the session promotion.
     *
     * @param sessionId the guest session id being promoted to a member session
     * @param userId    the id of the member the session is promoted to
     */
    void mergeGuestCartOnPromotion(String sessionId, int userId);
}
