package com.ticketing.system.shared.domain.policy;

import com.ticketing.system.shared.InvariantChecked;

/**
 * Immutable snapshot of the facts a {@link PurchasePolicy} evaluates: buyer id/age, event, company,
 * quantity, and the {@link PurchaseStage} (reserve vs checkout) the check runs at.
 */
public class PurchaseContext implements InvariantChecked {

    private final int buyerId;
    private final Integer buyerAge;
    private final int eventId;
    private final int companyId;
    private final int quantity;

    private final PurchaseStage stage;

    /**
     * Creates the context of a final checkout, where every rule is enforced. The parameters are those of
     * {@link #PurchaseContext(int, Integer, int, int, int, PurchaseStage)}.
     *
     * @throws IllegalArgumentException if {@code quantity} is not positive
     */
    public PurchaseContext(int buyerId, Integer buyerAge, int eventId, int companyId, int quantity) {
        this(buyerId, buyerAge, eventId, companyId, quantity, PurchaseStage.CHECKOUT);
    }

    /**
     * Creates the context of a purchase check at {@code stage}.
     *
     * @param buyerId the member's id, or -1 for a guest
     * @param buyerAge the buyer's age in years, or {@code null} when it is not known yet, as for a guest
     *        at reserve time
     * @param eventId the event being bought
     * @param companyId the company that owns the event
     * @param quantity how many tickets the purchase holds; positive
     * @param stage the stage the check runs at; {@code null} means {@link PurchaseStage#CHECKOUT}
     * @throws IllegalArgumentException if {@code quantity} is not positive
     */
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

    /**
     * The buyer's age in years.
     *
     * @return the age, or {@code null} when it is not known
     */
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