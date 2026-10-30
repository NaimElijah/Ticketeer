package com.ticketing.system.shared.domain.policy; // shared-kernel policy vocabulary (moved here from sales.domain)

import java.util.Objects;

import com.ticketing.system.shared.InvariantChecked;

/**
 * Composite purchase policy satisfied only when BOTH child policies are satisfied (logical AND).
 *
 * <p>Its refusal for a context names every unmet rule and none of the met ones
 * ({@link #getFailureMessage(PurchaseContext)}); {@link #getFailureMessage()} names every rule.
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
        return join(leftPolicy.getFailureMessage(), rightPolicy.getFailureMessage());
    }

    // A child the context satisfies is left out, so the buyer reads only the rules they broke.
    @Override
    public String getFailureMessage(PurchaseContext context) {
        String left = leftPolicy.isSatisfiedBy(context) ? null : leftPolicy.getFailureMessage(context);
        String right = rightPolicy.isSatisfiedBy(context) ? null : rightPolicy.getFailureMessage(context);
        return join(left, right);
    }

    private static String join(String left, String right) {
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

    @Override
    public boolean equals(Object other) {
        return other instanceof AndPurchasePolicy that
                && leftPolicy.equals(that.leftPolicy) && rightPolicy.equals(that.rightPolicy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(leftPolicy, rightPolicy);
    }
}
