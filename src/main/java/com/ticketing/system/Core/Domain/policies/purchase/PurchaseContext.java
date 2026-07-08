package com.ticketing.system.Core.Domain.policies.purchase;

import com.ticketing.system.shared.InvariantChecked;

public class PurchaseContext implements InvariantChecked {

    private final int buyerId;
    private final Integer buyerAge;
    private final int eventId;
    private final int companyId;
    private final int quantity;

    private final PurchaseStage stage;

    /** Final-checkout context (every rule enforced). */
    public PurchaseContext(int buyerId, Integer buyerAge, int eventId, int companyId, int quantity) {
        this(buyerId, buyerAge, eventId, companyId, quantity, PurchaseStage.CHECKOUT);
    }

    public PurchaseContext(int buyerId, Integer buyerAge, int eventId, int companyId, int quantity,
            PurchaseStage stage) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        this.buyerId = buyerId;
        this.buyerAge = buyerAge;
        this.eventId = eventId;
        this.companyId = companyId;
        this.quantity = quantity;
        this.stage = stage == null ? PurchaseStage.CHECKOUT : stage;
        checkInvariants();
    }

    public PurchaseStage getStage() {
        return stage;
    }

    @Override
    public void checkInvariants() {
        if (quantity <= 0) {
            throw new IllegalStateException("PurchaseContext invariant violated: quantity must be positive (was " + quantity + ")");
        }
    }

    public int getBuyerId() {
        return buyerId;
    }

    public Integer getBuyerAge() {
        return buyerAge;
    }

    public int getEventId() {
        return eventId;
    }

    public int getCompanyId() {
        return companyId;
    }

    public int getQuantity() {
        return quantity;
    }
}