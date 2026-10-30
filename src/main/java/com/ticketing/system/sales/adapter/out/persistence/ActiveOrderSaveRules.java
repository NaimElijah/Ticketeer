package com.ticketing.system.sales.adapter.out.persistence;

/** What both cart adapters tell a buyer whose save {@code ActiveOrderRepository.save} refuses. */
final class ActiveOrderSaveRules {

    /** The buyer's cart changed in another request, typically another tab, so this one must be retried. */
    static final String ANOTHER_CART_HOLDS_LINES =
            "Your cart changed in another tab or window. Please try again.";

    private ActiveOrderSaveRules() { }
}
