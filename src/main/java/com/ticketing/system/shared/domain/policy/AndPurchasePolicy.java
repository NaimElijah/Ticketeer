package com.ticketing.system.shared.domain.policy; // shared-kernel policy vocabulary (moved here from sales.domain)

import com.ticketing.system.shared.InvariantChecked;

/**
 * Composite purchase policy satisfied only when BOTH child policies are satisfied (logical AND).
 *
 * <p>Combines its failure messages so the buyer sees every unmet rule.
 */
public class AndPurchasePolicy implements PurchasePolicy, InvariantChecked {

    private final PurchasePolicy leftPolicy;
    private final PurchasePolicy rightPolicy;

    public AndPurchasePolicy(PurchasePolicy leftPolicy, PurchasePolicy rightPolicy) {
        this.leftPolicy = leftPolicy;
        this.rightPolicy = rightPolicy;
        checkInvariants();
    }

    @Override
    public void checkInvariants() {
        if (leftPolicy == null || rightPolicy == null) {
            throw new IllegalStateException("AndPurchasePolicy invariant violated: both policies must be non-null");
        }
    }

    @Override
    public boolean isSatisfiedBy(PurchaseContext context) {
        return leftPolicy.isSatisfiedBy(context) && rightPolicy.isSatisfiedBy(context);
    }

    @Override
    public String getFailureMessage() {
        String left = leftPolicy.getFailureMessage();
        String right = rightPolicy.getFailureMessage();

        if (left == null || left.isBlank()) {
            return right == null ? "" : right;
        }
        if (right == null || right.isBlank()) {
            return left;
        }

        return left + " AND " + right;
    }
    public PurchasePolicy getLeftPolicy()  { return leftPolicy; }
    public PurchasePolicy getRightPolicy() { return rightPolicy; }
}
