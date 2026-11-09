package com.ticketing.system.catalog.domain;

import java.time.LocalDateTime;

import com.ticketing.system.shared.InvariantChecked;

/**
 * An event's percentage discount: an immutable value with value equality. {@code Event} stores it
 * through {@link DiscountPolicyJsonConverter}, and Hibernate compares a converted copy with
 * {@code equals} to decide whether the column changed, so without value equality every flush rewrote
 * the event row (#186, see {@link com.ticketing.system.shared.domain.policy.PurchasePolicy}).
 */
public class DiscountPolicy implements InvariantChecked {

    private final double discountPercentage;  // Discount percentage (0-100)

    /**
     * Creates a policy that takes a flat percentage off a price.
     *
     * @param discountPercentage the percentage taken off, from 0 (no discount) to 100 (free)
     * @throws IllegalStateException if the percentage is outside 0 to 100
     */
    public DiscountPolicy(double discountPercentage) {
        this.discountPercentage = discountPercentage;
        checkInvariants();
    }

    @Override
    public void checkInvariants() {
        if (discountPercentage < 0 || discountPercentage > 100) {
            throw new IllegalStateException(
                    "DiscountPolicy invariant violated: discountPercentage must be between 0 and 100 (was " + discountPercentage + ")");
        }
    }

    public double getDiscountPercentage() {
        return discountPercentage;
    }

    /**
     * Applies the percentage to a price.
     *
     * @param price the price before the discount
     * @return the price after the discount
     */
    public double apply(double price) {
        return price * (1 - discountPercentage / 100);
    }

    /**
     * Prices a purchase by discounting one ticket's price and multiplying by the quantity. It does not
     * check its arguments.
     *
     * @param basePricePerTicket the price of one ticket before the discount
     * @param quantity           the number of tickets
     * @return the total after the discount
     */
    public double calculateFinalPrice(double basePricePerTicket, int quantity) {
        return apply(basePricePerTicket) * quantity;
    }

    /**
     * Prices a purchase of {@code quantity} tickets after the discount.
     *
     * @param quantity                    the number of tickets; positive
     * @param priceAtOneTicketReservation the price of one ticket when it was reserved; not negative
     * @param now                         the pricing time; this flat percentage policy does not read it
     * @return the total for all {@code quantity} tickets after the discount
     * @throws IllegalArgumentException if {@code quantity} is not positive, or the price is {@code null}
     *                                  or negative
     */
    public double calculate(int quantity, Double priceAtOneTicketReservation, LocalDateTime now) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (priceAtOneTicketReservation == null || priceAtOneTicketReservation < 0) {
            throw new IllegalArgumentException("Price must be non-negative");
        }

        double base = quantity * priceAtOneTicketReservation;
        return apply(base);
    }

    /**
     * Prices one ticket of a purchase of {@code quantity} tickets after the discount: the total from
     * {@link #calculate} divided by the quantity. Takes and refuses what {@link #calculate} does.
     *
     * @param quantity                    the number of tickets in the purchase; positive
     * @param priceAtOneTicketReservation the price of one ticket when it was reserved; not negative
     * @param now                         the pricing time; this flat percentage policy does not read it
     * @return the per-ticket price after the discount
     * @throws IllegalArgumentException if {@code quantity} is not positive, or the price is {@code null}
     *                                  or negative
     */
    public double calculatePriceforoneticket(int quantity, Double priceAtOneTicketReservation, LocalDateTime now) {
        return calculate(quantity, priceAtOneTicketReservation, now) / quantity;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DiscountPolicy that && Double.compare(discountPercentage, that.discountPercentage) == 0;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(discountPercentage);
    }
}
