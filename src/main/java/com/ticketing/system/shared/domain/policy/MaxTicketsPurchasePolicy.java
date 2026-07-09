package com.ticketing.system.shared.domain.policy; // shared-kernel policy vocabulary (moved here from sales.domain)

import com.ticketing.system.shared.InvariantChecked;

/**
 * Leaf purchase policy enforcing an upper bound on the number of tickets in a single purchase.
 */
public class MaxTicketsPurchasePolicy implements PurchasePolicy, InvariantChecked {

    private final int maximumTickets;

    public MaxTicketsPurchasePolicy(int maximumTickets) {
        this.maximumTickets = maximumTickets;
        checkInvariants();
    }

    @Override
    public void checkInvariants() {
        if (maximumTickets < 0) {
            throw new IllegalStateException("MaxTicketsPurchasePolicy invariant violated: maximumTickets cannot be negative (was " + maximumTickets + ")");
        }
    }

    @Override
    public boolean isSatisfiedBy(PurchaseContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Purchase context cannot be null");
        }

        return context.getQuantity() <= maximumTickets;
    }

    @Override
    public String getFailureMessage() {
        return "You can buy at most " + maximumTickets + " tickets";
    }
    public int getMaximumTickets() { return maximumTickets; }
}
