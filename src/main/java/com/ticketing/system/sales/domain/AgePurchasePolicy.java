package com.ticketing.system.sales.domain;

import com.ticketing.system.shared.InvariantChecked;

public class AgePurchasePolicy implements PurchasePolicy, InvariantChecked {

    private final int minimumAge;

    public AgePurchasePolicy(int minimumAge) {
        this.minimumAge = minimumAge;
        checkInvariants();
    }

    @Override
    public void checkInvariants() {
        if (minimumAge < 0) {
            throw new IllegalStateException("AgePurchasePolicy invariant violated: minimumAge cannot be negative (was " + minimumAge + ")");
        }
    }

    @Override
    public boolean isSatisfiedBy(PurchaseContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Purchase context cannot be null");
        }

        Integer buyerAge = context.getBuyerAge();

        if (buyerAge == null) {
            // Age unknown (e.g. a guest at reserve time) — defer the check to checkout,
            // where the age is collected; an unknown age at checkout still fails.
            return context.getStage() == PurchaseStage.RESERVE;
        }

        return buyerAge >= minimumAge;
    }

    @Override
    public String getFailureMessage() {
        return "You must be at least " + minimumAge + " years old to buy tickets";
    }
    public int getMinimumAge() { return minimumAge; }
}
