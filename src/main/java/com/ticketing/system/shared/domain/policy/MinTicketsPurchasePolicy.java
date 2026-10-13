package com.ticketing.system.shared.domain.policy; // shared-kernel policy vocabulary (moved here from sales.domain)

import com.ticketing.system.shared.InvariantChecked;

/**
 * Leaf purchase policy enforcing a lower bound on ticket count. Skipped at the reserve stage (the
 * cart is still being built up) and enforced at checkout.
 */
public class MinTicketsPurchasePolicy implements PurchasePolicy, InvariantChecked {

    private final int minimumTickets;

    public MinTicketsPurchasePolicy(int minimumTickets) {
        this.minimumTickets = minimumTickets;
        checkInvariants();
    }

    @Override
    public void checkInvariants() {
        if (minimumTickets < 0) {
            throw new IllegalStateException("MinTicketsPurchasePolicy invariant violated: minimumTickets cannot be negative (was " + minimumTickets + ")");
        }
    }

    @Override
    public boolean isSatisfiedBy(PurchaseContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Purchase context cannot be null");
        }

        // The minimum is only enforced at checkout — at reserve the cart is still being built up.
        if (context.getStage() == PurchaseStage.RESERVE) {
            return true;
        }

        return context.getQuantity() >= minimumTickets;
    }

    @Override
    public String getFailureMessage() {
        return "You must buy at least " + minimumTickets + " tickets";
        
    }
    public int getMinimumTickets() { return minimumTickets; }
}
