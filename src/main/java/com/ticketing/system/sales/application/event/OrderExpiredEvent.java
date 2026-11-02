package com.ticketing.system.sales.application.event;

/**
 * Published by SessionAndOrderSweeper when it expires an active order, inside the sweep's transaction.
 * {@code CheckoutPresenter} hears it once that transaction commits and tells the owner's open checkout
 * views, so a sweep that rolls back announces nothing.
 *
 * @param userId    0 for guest carts
 * @param sessionId null for member carts
 */
public record OrderExpiredEvent(int userId, String sessionId) {

    /**
     * Leaves out the guest session id, which is the guest's credential, so a logged event or an
     * exception message built from one does not carry it (SLR.2).
     */
    @Override
    public String toString() {
        return "OrderExpiredEvent[userId=" + userId + "]";
    }
}
