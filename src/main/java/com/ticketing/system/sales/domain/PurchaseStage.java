package com.ticketing.system.sales.domain;

/**
 * The point in the buyer flow at which a purchase policy is evaluated.
 *
 * <p>Distinct from {@code ActiveOrderStatus} (the order aggregate's lifecycle):
 * this is purely a parameter to one policy evaluation, so the policy rules stay
 * decoupled from the order aggregate.
 *
 * <ul>
 *   <li>{@link #RESERVE} — a tentative check while the cart is still being built.
 *       The minimum-tickets rule is skipped (you build up to the minimum), and the
 *       age rule is skipped when the age isn't known yet (a guest, whose age is
 *       only collected at checkout). Maximum-tickets, and age for a member, are
 *       still enforced.</li>
 *   <li>{@link #CHECKOUT} — the final check; every rule is enforced.</li>
 * </ul>
 */
public enum PurchaseStage {
    RESERVE,
    CHECKOUT
}
