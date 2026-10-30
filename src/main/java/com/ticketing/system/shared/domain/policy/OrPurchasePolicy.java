package com.ticketing.system.shared.domain.policy; // shared-kernel policy vocabulary (moved here from sales.domain)

import java.util.Objects;

import com.ticketing.system.shared.InvariantChecked;

/**
 * Composite purchase policy satisfied when EITHER child policy is satisfied (logical OR).
 */
public class OrPurchasePolicy implements PurchasePolicy, InvariantChecked {

    private final PurchasePolicy leftPolicy;
    private final PurchasePolicy rightPolicy;

    public OrPurchasePolicy(PurchasePolicy leftPolicy, PurchasePolicy rightPolicy) {
        this.leftPolicy = leftPolicy;
        this.rightPolicy = rightPolicy;
        checkInvariants();
    }

    @Override
    public void checkInvariants() {
        if (leftPolicy == null || rightPolicy == null) {
            throw new IllegalStateException("OrPurchasePolicy invariant violated: both policies must be non-null");
        }
    }

    @Override
    public boolean isSatisfiedBy(PurchaseContext context) {
        return leftPolicy.isSatisfiedBy(context) || rightPolicy.isSatisfiedBy(context);
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

        return left + " OR " + right;
    }
    public PurchasePolicy getLeftPolicy()  { return leftPolicy; }
    public PurchasePolicy getRightPolicy() { return rightPolicy; }

    @Override
    public boolean equals(Object other) {
        return other instanceof OrPurchasePolicy that
                && leftPolicy.equals(that.leftPolicy) && rightPolicy.equals(that.rightPolicy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(leftPolicy, rightPolicy);
    }
}
