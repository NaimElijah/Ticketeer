package com.ticketing.system.shared.domain.policy;

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

    /**
     * Tells whether {@code context} meets this policy.
     *
     * @param context the purchase to check; a leaf policy other than {@link NoPurchasePolicy} refuses {@code null}
     * @return {@code true} if the purchase is allowed
     * @throws IllegalArgumentException if a leaf policy is given a {@code null} context
     */
    boolean isSatisfiedBy(PurchaseContext context);

    /**
     * The refusal text for this policy as a whole, written for the buyer. A composite names every rule it
     * holds; see {@link #getFailureMessage(PurchaseContext)} for the rules a purchase broke.
     *
     * @return the text, empty for a policy with no rule
     */
    String getFailureMessage();

    /**
     * The refusal to show for {@code context}, naming only the rules it breaks. A leaf has one rule,
     * so this is {@link #getFailureMessage()}. The default names the policy's own rule, which for a
     * composite is every rule it holds, so a composite must override it to leave out the rules the
     * context meets.
     *
     * @param context the purchase this policy refused
     * @return the reason for the refusal, possibly empty when no rule has a message
     */
    default String getFailureMessage(PurchaseContext context) {
        return getFailureMessage();
    }
}