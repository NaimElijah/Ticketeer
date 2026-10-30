package com.ticketing.system.shared.domain.policy; // shared-kernel policy vocabulary (moved here from sales.domain so catalog/organization can depend on it without an upward edge)

/**
 * A rule that decides whether a purchase (reserve or checkout) is allowed for a given context.
 *
 * <p>Root of the purchase-policy Composite family (And/Or over Age/Max/Min/None leaves). Lives in
 * the shared kernel because it is cross-context vocabulary: an {@code Event} and a
 * {@code ProductionCompany} both own purchase rules, so the type must sit below both contexts.
 *
 * <p>Implementations are immutable values with value equality. Both owners store their policy through
 * {@link PurchasePolicyJsonConverter}, and Hibernate decides whether such a column changed by comparing
 * a converted copy with {@code equals}: a policy without value equality reads as changed on every flush,
 * which rewrote the owner's row and bumped its version on every buyer operation (#186).
 */
public interface PurchasePolicy {

    boolean isSatisfiedBy(PurchaseContext context);

    String getFailureMessage();

    /**
     * The refusal to show for {@code context}, naming only the rules it breaks. A leaf has one rule,
     * so this is {@link #getFailureMessage()}; a composite overrides it to leave out what the
     * context satisfies.
     *
     * @param context the purchase this policy refused
     * @return the reason for the refusal, possibly empty when no rule has a message
     */
    default String getFailureMessage(PurchaseContext context) {
        return getFailureMessage();
    }
}