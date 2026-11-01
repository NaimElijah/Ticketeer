package com.ticketing.system.unit.infrastructure.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ticketing.system.bootstrap.dev.seed.SeedHarness;
import com.ticketing.system.bootstrap.dev.seed.SeedReport;

/**
 * {@link SeedHarness#expectFailure} passes only on the exact exception type it names: a different type fails
 * the step, including a wrapper whose cause is the named type (#187).
 */
class SeedHarnessTest {

    // ---- F6: a wrapper around the named type is a different type (#187) ----

    private static final String STAGE = "scenario";
    private static final String STEP = "L1 expect-error reserve";
    private static final String NAMED_TYPE = "IllegalStateException";
    private static final boolean NOT_FAIL_FAST = false;

    @Test
    void GivenAnActionThrowingAWrapperWhoseCauseIsTheNamedType_WhenExpectFailure_ThenTheStepFails() {
        SeedReport report = new SeedReport();
        SeedHarness harness = new SeedHarness(report, NOT_FAIL_FAST);

        harness.expectFailure(STAGE, STEP, NAMED_TYPE, () -> {
            throw new RuntimeException("wrapped", new IllegalStateException("the guard"));
        });

        assertTrue(report.hasFailures(), "a wrapper is not the named type, even with the named type as its cause");
        assertEquals(0, report.count(SeedReport.Status.PASS), "the step must not pass");
    }

    @Test
    void GivenAnActionThrowingTheNamedType_WhenExpectFailure_ThenTheStepPasses() {
        SeedReport report = new SeedReport();
        SeedHarness harness = new SeedHarness(report, NOT_FAIL_FAST);

        harness.expectFailure(STAGE, STEP, NAMED_TYPE, () -> {
            throw new IllegalStateException("the guard");
        });

        assertEquals(1, report.count(SeedReport.Status.PASS), "precondition: the exact type passes");
    }
}
