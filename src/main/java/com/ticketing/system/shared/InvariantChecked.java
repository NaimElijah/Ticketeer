package com.ticketing.system.shared;

/**
 * Implemented by aggregate roots that publish a runtime invariant check.
 *
 * <p>{@link #checkInvariants()} asserts the structural rules an aggregate
 * must satisfy at every observable state. It is intended to be called:
 *
 * <ul>
 *   <li>Inside the aggregate's own methods after each state-changing operation
 *       (defensive, optional — production overhead is one method call).</li>
 *   <li>From tests via the {@code BaseDomainTest} harness, which calls it
 *       automatically on every tracked aggregate after each test.</li>
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
