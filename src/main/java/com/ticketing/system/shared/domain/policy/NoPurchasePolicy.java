package com.ticketing.system.shared.domain.policy; // shared-kernel policy vocabulary (moved here from sales.domain)

/**
 * The null-object purchase policy: imposes no restriction, so every purchase is allowed.
 *
 * <p>Used as the default when an event or company has no explicit rules, and as the fallback the
 * JSON converter returns for a missing/blank stored policy.
 */
public class NoPurchasePolicy implements PurchasePolicy {

    @Override
    public boolean isSatisfiedBy(PurchaseContext context) {
        return true;
    }

    @Override
    public String getFailureMessage() {
        return "";
    }

    // Every NoPurchasePolicy imposes the same nothing, so all of them are equal.
    @Override
    public boolean equals(Object other) {
        return other instanceof NoPurchasePolicy;
    }

    @Override
    public int hashCode() {
        return NoPurchasePolicy.class.hashCode();
    }
}
