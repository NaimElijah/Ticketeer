package com.ticketing.system.shared.domain.policy; // shared-kernel policy vocabulary (moved here from sales.domain so catalog/organization can depend on it without an upward edge)

/**
 * A rule that decides whether a purchase (reserve or checkout) is allowed for a given context.
 *
 * <p>Root of the purchase-policy Composite family (And/Or over Age/Max/Min/None leaves). Lives in
 * the shared kernel because it is cross-context vocabulary: an {@code Event} and a
 * {@code ProductionCompany} both own purchase rules, so the type must sit below both contexts.
 */
public interface PurchasePolicy {

    boolean isSatisfiedBy(PurchaseContext context);

    String getFailureMessage();
}