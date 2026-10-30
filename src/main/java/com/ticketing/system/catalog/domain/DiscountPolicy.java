package com.ticketing.system.catalog.domain;

import java.time.LocalDateTime;

import com.ticketing.system.shared.InvariantChecked;

/**
 * An event's percentage discount: an immutable value with value equality. {@code Event} stores it
 * through {@link DiscountPolicyJsonConverter}, and Hibernate compares a converted copy with
 * {@code equals} to decide whether the column changed, so without value equality every flush rewrote
 * the event row (#186, see {@link com.ticketing.system.shared.domain.policy.PurchasePolicy}).
 */
public class DiscountPolicy implements InvariantChecked { //? Note: but not in implementation plan  <------------   <-----------    <---------------

    private final double discountPercentage;  // Discount percentage (0-100)

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

    public double apply(double price) {
        return price * (1 - discountPercentage / 100);
    }

    public double calculateFinalPrice(double basePricePerTicket, int quantity) {
        return apply(basePricePerTicket) * quantity;
    }
    
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
