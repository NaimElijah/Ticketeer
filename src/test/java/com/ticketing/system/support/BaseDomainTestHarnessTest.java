package com.ticketing.system.support;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.InvariantChecked;

/**
 * {@link BaseDomainTest} fails a test that leaves a tracked aggregate broken (#189). Without this, a harness
 * that never ran, or swallowed the violation, would pass every domain test that relies on it.
 *
 * <p>The JUnit launcher is not on the test classpath, so the check JUnit runs after each test is called
 * directly, and its {@code @AfterEach} is pinned separately.
 */
class BaseDomainTestHarnessTest {

    private static final String VIOLATION = "sold places exceed the zone's capacity";

    @Test
    void GivenATrackedAggregateWithABrokenInvariant_WhenTheTestEnds_ThenTheHarnessFails() {
        IllegalStateException violation = new IllegalStateException(VIOLATION);
        Probe test = new Probe();
        test.register(() -> {
            throw violation;
        });

        AssertionError failed = assertThrows(AssertionError.class, test::verifyTrackedInvariants,
            "a tracked aggregate whose invariant breaks must fail the test");

        assertEquals(1, failed.getSuppressed().length, "one violation, reported once: " + failed);
        Throwable reported = failed.getSuppressed()[0];
        assertTrue(reported.getMessage().contains(VIOLATION), "the failure must say what broke: " + reported);
        assertSame(violation, reported.getCause(), "the violation is kept as the cause");
    }

    @Test
    void GivenATrackedAggregateWhoseInvariantsHold_WhenTheTestEnds_ThenTheHarnessPasses() {
        Probe test = new Probe();
        test.register(() -> { });

        assertDoesNotThrow(test::verifyTrackedInvariants);
    }

    @Test
    void GivenTheHarness_WhenJUnitLooksForWhatRunsAfterEachTest_ThenItFindsTheInvariantCheck() throws NoSuchMethodException {
        assertTrue(BaseDomainTest.class.getDeclaredMethod("verifyTrackedInvariants").isAnnotationPresent(AfterEach.class),
            "without @AfterEach JUnit never runs the check, and every tracked aggregate goes unchecked");
    }

    // A domain test of its own, so the check runs as it would after one of its methods.
    private static final class Probe extends BaseDomainTest {

        void register(InvariantChecked aggregate) {
            track(aggregate);
        }
    }
}
