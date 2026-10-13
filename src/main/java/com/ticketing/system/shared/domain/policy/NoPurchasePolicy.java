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
}