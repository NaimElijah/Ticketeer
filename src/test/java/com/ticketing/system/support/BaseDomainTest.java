package com.ticketing.system.support;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;

import com.ticketing.system.shared.InvariantChecked;

/**
 * Base class for domain-aggregate unit tests. Each test that mutates an
 * aggregate should register it via {@link #track(InvariantChecked)} once
 * (typically in the {@code @BeforeEach} or right after creation). After the
 * test method finishes — whether by passing or throwing — the harness calls
 * {@link InvariantChecked#checkInvariants()} on every tracked aggregate.
 *
 * <p>This catches the case where a test asserts the outcome of one operation
 * but leaves the aggregate in a structurally broken state that future tests
 * (or production code) would later trip over.
 *
 * <p>Typical usage:
 *
 * <pre>
 * class MyAggregateTest extends BaseDomainTest {
 *     MyAggregate agg;
 *
 *     {@literal @}BeforeEach
 *     void setUp() {
 *         agg = track(new MyAggregate(...));
 *     }
 *
 *     {@literal @}Test
 *     void doesSomething() {
 *         agg.mutate();
 *         assertEquals(expected, agg.getValue());
 *         // harness automatically calls agg.checkInvariants() after this method
 *     }
 * }
 * </pre>
 */
public abstract class BaseDomainTest {

    private final List<InvariantChecked> tracked = new ArrayList<>();

    /**
     * Register an aggregate so its invariants are checked after the current
     * test method completes. Returns the same instance for fluent assignment.
     *
     * @return the same aggregate passed in (or {@code null} if {@code null})
     */
    protected final <T extends InvariantChecked> T track(T aggregate) {
        if (aggregate != null) {
            tracked.add(aggregate);
        }
        return aggregate;
    }

    /**
     * Drop a previously-tracked aggregate (e.g. if a test legitimately leaves
     * it in a state that {@code checkInvariants} would reject — rare).
     */
    protected final void untrack(InvariantChecked aggregate) {
        tracked.remove(aggregate);
    }

    @AfterEach
    final void verifyTrackedInvariants() {
        List<AssertionError> failures = new ArrayList<>();
        for (InvariantChecked aggregate : tracked) {
            try {
                aggregate.checkInvariants();
            } catch (RuntimeException violation) {
                failures.add(new AssertionError(
                        "Invariant violated on " + aggregate.getClass().getSimpleName()
                                + ": " + violation.getMessage(),
                        violation));
            }
        }
        tracked.clear();
        if (!failures.isEmpty()) {
            AssertionError combined = new AssertionError(
                    failures.size() + " invariant violation(s) after test");
            failures.forEach(combined::addSuppressed);
            throw combined;
        }
    }
}
