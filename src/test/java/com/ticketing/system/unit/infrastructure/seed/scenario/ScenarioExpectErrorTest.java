package com.ticketing.system.unit.infrastructure.seed.scenario;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ticketing.system.bootstrap.config.SeedProperties;
import com.ticketing.system.bootstrap.dev.seed.SeedHarness;
import com.ticketing.system.bootstrap.dev.seed.scenario.ScenarioCommand;
import com.ticketing.system.bootstrap.dev.seed.scenario.ScenarioParser;
import com.ticketing.system.bootstrap.dev.seed.scenario.ScenarioRunner;
import com.ticketing.system.testutil.LogCapture;

/**
 * {@code expect-error <op> <args…> error=<ExceptionSimpleName>} passes only when the wrapped operation
 * throws that exception; any other exception fails the step and the failure names both types, so a
 * negative check proves the guard it means to show and not whatever failed first (#187).
 *
 * <p>{@code login ghost} fails in the scenario engine itself with an {@link IllegalArgumentException}
 * (the alias was never registered), before any service is called.
 */
class ScenarioExpectErrorTest {

    // ---- Defect 4: expect-error names the exception it expects (#187) ----

    private static final String THROWN_TYPE = "IllegalArgumentException";
    private static final String OTHER_TYPE = "IllegalStateException";
    private static final String SCENARIO_WITH_A_BARE_EXPECT_ERROR =
            "register alice password123 alice@demo.test 30\nexpect-error login ghost\n";
    private static final String SUPERCLASS_OF_THROWN_TYPE = "RuntimeException";
    private static final String EXPECT_ERROR = "expect-error";
    private static final String ERROR_ARGUMENT = "error";
    private static final List<String> COMMITTED_SCENARIOS =
            List.of("/scenarios/demo.scenario", "/scenarios/review.scenario");

    @TempDir
    Path dir;

    private final ScenarioRunnerFixture fixture = new ScenarioRunnerFixture();
    private LogCapture logs;

    @AfterEach
    void detachLogCapture() {
        if (logs != null) {
            logs.close();
        }
    }

    @Test
    void GivenExpectErrorNamingTheThrownType_WhenScenarioRuns_ThenTheStepPasses() throws IOException {
        ScenarioRunner runner = runnerFor("expect-error login ghost error=" + THROWN_TYPE + "\n", true);

        assertDoesNotThrow(() -> runner.run(null), "the wrapped operation threw the named type, so the step passes");
    }

    @Test
    void GivenExpectErrorNamingAnotherType_WhenScenarioRunsUnderFailFast_ThenTheStepFails() throws IOException {
        ScenarioRunner runner = runnerFor("expect-error login ghost error=" + OTHER_TYPE + "\n", true);

        assertThrows(SeedHarness.SeedAbortException.class, () -> runner.run(null),
                "the wrapped operation threw " + THROWN_TYPE + ", not the named " + OTHER_TYPE + ", so the step fails");
    }

    // IllegalArgumentException is a RuntimeException, but the name must match exactly.
    @Test
    void GivenExpectErrorNamingASuperclassOfTheThrownType_WhenScenarioRunsUnderFailFast_ThenTheStepFails()
            throws IOException {
        ScenarioRunner runner = runnerFor("expect-error login ghost error=" + SUPERCLASS_OF_THROWN_TYPE + "\n", true);

        assertThrows(SeedHarness.SeedAbortException.class, () -> runner.run(null),
                "the wrapped operation threw " + THROWN_TYPE + ", a subclass of the named " + SUPERCLASS_OF_THROWN_TYPE
                        + ", which does not count, so the step fails");
    }

    @Test
    void GivenExpectErrorNamingAnotherType_WhenScenarioRuns_ThenTheReportedFailureNamesBothTypes() throws IOException {
        ScenarioRunner runner = runnerFor("expect-error login ghost error=" + OTHER_TYPE + "\n", false);
        logs = LogCapture.of(ScenarioRunner.class);

        runner.run(null);

        List<String> namingBoth = logs.eventsContaining(OTHER_TYPE).stream()
                .filter(event -> event.contains(THROWN_TYPE))
                .toList();
        assertFalse(namingBoth.isEmpty(),
                "the scenario report must name the expected " + OTHER_TYPE + " and the thrown " + THROWN_TYPE
                        + ", but no logged event named both");
    }

    @Test
    void GivenTheCommittedScenarios_WhenParsed_ThenEveryExpectErrorNamesTheExceptionItExpects() throws IOException {
        List<String> withoutType = new ArrayList<>();
        int expectations = 0;
        for (String scenario : COMMITTED_SCENARIOS) {
            for (ScenarioCommand command : new ScenarioParser().parse(read(scenario))) {
                if (EXPECT_ERROR.equals(command.op())) {
                    expectations++;
                    if (command.named(ERROR_ARGUMENT) == null) {
                        withoutType.add(scenario + ":" + command.line());
                    }
                }
            }
        }

        assertFalse(expectations == 0, "the committed scenarios hold no expect-error line to check");
        assertEquals(List.of(), withoutType, "every committed expect-error must name its exception with error=<Type>");
    }

    // ---- F6: the runner refuses a bare expect-error before any step, whatever seed.fail-fast says (#187) ----

    @Test
    void GivenAScenarioWithABareExpectError_WhenReseededWithoutFailFast_ThenTheBootStopsNamingTheLineAndNoStepRuns()
            throws IOException {
        ScenarioRunner runner = runnerFor(SCENARIO_WITH_A_BARE_EXPECT_ERROR, false);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> runner.run(null),
                "a bare expect-error must stop the boot even when seed.fail-fast is off");

        assertTrue(refused.getMessage().contains("line 2"), "the refusal must name the line, but was: "
                + refused.getMessage());
        fixture.verifyNoScenarioStepRan();
    }

    private ScenarioRunner runnerFor(String scenario, boolean failFast) throws IOException {
        return fixture.runner(SeedProperties.Mode.RESEED, failFast, ScenarioRunnerFixture.scenarioFile(dir, scenario));
    }

    private static String read(String classpathLocation) throws IOException {
        try (InputStream in = ScenarioExpectErrorTest.class.getResourceAsStream(classpathLocation)) {
            assertNotNull(in, classpathLocation + " is not on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
