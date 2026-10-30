package com.ticketing.system.shared;

/**
 * Implemented by the domain types that publish a runtime invariant check: aggregate roots, the
 * entities inside them, and value types such as {@code PurchaseContext} and the purchase policies.
 *
 * <p>{@link #checkInvariants()} asserts the structural rules a type
 * must satisfy at every observable state. It is intended to be called:
 *
 * <ul>
 *   <li>Inside the aggregate's own methods after each state-changing operation
 *       (defensive, optional — production overhead is one method call).</li>
 *   <li>From tests via the {@code BaseDomainTest} harness, which calls it
 *       automatically on every tracked aggregate after each test.</li>
 *   <li>From {@code SystemIntegrityVerifier} (governance), which re-checks every loaded user,
 *       company, event and active order before the market opens.</li>
 * </ul>
 *
 * <p>The contract: throw {@link IllegalStateException} (or a more specific
 * runtime exception) with a clear message naming the violated invariant.
 * Do not return a boolean — tests want stack traces, not asserts on flags.
 */
public interface InvariantChecked {

    /**
     * Verify all invariants of this aggregate.
     *
     * @throws IllegalStateException if any invariant is violated
     */
    void checkInvariants();
}
