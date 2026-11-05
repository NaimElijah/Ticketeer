package com.ticketing.system.testutil;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LogCapture#assertNeverLogged} proves an absence only when the path logged something, and finds a
 * value wherever an event carries it, a suppressed throwable included (#189).
 */
class LogCaptureTest {

    private static final Logger LOG = LoggerFactory.getLogger(LogCaptureTest.class);
    private static final String SECRET = "dana.levi@example.com";

    private LogCapture logs;

    @BeforeEach
    void attach() {
        logs = LogCapture.of(LogCaptureTest.class);
    }

    @AfterEach
    void detach() {
        logs.close();
    }

    @Test
    void GivenNothingLogged_WhenAssertingAValueIsNeverLogged_ThenTheAssertionFails() {
        AssertionError refused = assertThrows(AssertionError.class, () -> logs.assertNeverLogged(SECRET),
            "with nothing recorded the absence proves nothing, so the assertion must not pass");

        assertTrue(refused.getMessage().contains("nothing was logged"), "the failure must say why: " + refused.getMessage());
    }

    @Test
    void GivenEventsThatDoNotCarryTheValue_WhenAssertingItIsNeverLogged_ThenTheAssertionPasses() {
        LOG.warn("search failed, query length={}", SECRET.length());

        assertDoesNotThrow(() -> logs.assertNeverLogged(SECRET));
    }

    @Test
    void GivenAnEventWhoseMessageCarriesTheValue_WhenAssertingItIsNeverLogged_ThenTheAssertionFails() {
        LOG.debug("search failed for {}", SECRET.toUpperCase(Locale.ROOT));

        assertThrows(AssertionError.class, () -> logs.assertNeverLogged(SECRET),
            "the value must be found in the message, whatever its case");
    }

    @Test
    void GivenTheValueOnlyInACause_WhenAssertingItIsNeverLogged_ThenTheAssertionFails() {
        LOG.error("search failed", new IllegalStateException("wrapped", new IllegalArgumentException(SECRET)));

        assertThrows(AssertionError.class, () -> logs.assertNeverLogged(SECRET));
    }

    @Test
    void GivenTheValueOnlyInASuppressedException_WhenAssertingItIsNeverLogged_ThenTheAssertionFails() {
        IllegalStateException failure = new IllegalStateException("search failed");
        failure.addSuppressed(new IllegalArgumentException("close failed for " + SECRET));
        LOG.error("search failed", failure);

        assertThrows(AssertionError.class, () -> logs.assertNeverLogged(SECRET),
            "a value in a suppressed exception reaches the log as well");
    }

    @Test
    void GivenTheValueInTheCauseOfASuppressedException_WhenAssertingItIsNeverLogged_ThenTheAssertionFails() {
        IllegalStateException failure = new IllegalStateException("search failed");
        failure.addSuppressed(new IllegalStateException("retry failed", new IllegalArgumentException(SECRET)));
        LOG.error("search failed", failure);

        assertThrows(AssertionError.class, () -> logs.assertNeverLogged(SECRET));
    }
}
