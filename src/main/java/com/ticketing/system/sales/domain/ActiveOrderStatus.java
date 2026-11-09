package com.ticketing.system.sales.domain;

/**
 * Where an {@link ActiveOrder} (cart) stands in checkout. A new cart is {@code PRE_CHECKOUT}; checkout
 * freezes it as {@code CHECKOUT_IN_PROGRESS}, and a failed checkout puts it back.
 */
public enum ActiveOrderStatus {
    /** The cart is open for changes and has not been checked out. */
    PRE_CHECKOUT,
    /** A checkout is running on the cart, which refuses changes and a second checkout. */
    CHECKOUT_IN_PROGRESS
}
