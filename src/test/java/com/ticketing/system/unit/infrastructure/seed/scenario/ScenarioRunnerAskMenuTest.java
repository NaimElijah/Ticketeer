package com.ticketing.system.unit.infrastructure.seed.scenario;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.bootstrap.config.SeedProperties;
import com.ticketing.system.bootstrap.dev.seed.scenario.ScenarioRunner;
import com.ticketing.system.testutil.LogCapture;

/**
 * At the {@code seed.mode=ask} menu, a scenario answer that names no committed scenario is refused and
 * asked again, so a typo does not stop the boot (#187).
 *
 * <p>The menu reads {@link System#in}; the test feeds it the answers and keeps the menu's prompts off
 * the build output. The scenario services are mocks, so every step of the chosen scenario fails, and
 * the run is not fail-fast so that does not matter: what is checked is which scenario ran.
 */
class ScenarioRunnerAskMenuTest {

    // ---- Defect 13: an unknown scenario answer at the ask menu is asked again (#187) ----

    private static final String ANSWERS = "reseed\nno-such-scenario\nreview\n";
    private static final String CHOSEN_SCENARIO = "scenarios/review.scenario";
    private static final String CONFIGURED_SCENARIO = "classpath:scenarios/demo.scenario";
    private static final String ANSWERS_ENDING_AFTER_UNKNOWN = "reseed\nno-such-scenario\n";
    private static final String ANSWERS_ENDING_AT_SCENARIO = "reseed\n";
    private static final String DEFAULT_SCENARIO = "scenarios/demo.scenario";

    private final ScenarioRunnerFixture fixture = new ScenarioRunnerFixture();
    private final InputStream originalIn = System.in;
    private final PrintStream originalOut = System.out;
    private LogCapture logs;

    @BeforeEach
    void feedTheMenu() {
        System.setIn(new ByteArrayInputStream(ANSWERS.getBytes(StandardCharsets.UTF_8)));
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        logs = LogCapture.of(ScenarioRunner.class);
    }

    @AfterEach
    void restore() {
        logs.close();
        System.setIn(originalIn);
        System.setOut(originalOut);
    }

    @Test
    void GivenAnUnknownScenarioAnswerThenAValidOne_WhenTheAskMenuRuns_ThenTheValidScenarioRuns() {
        ScenarioRunner runner = fixture.runner(SeedProperties.Mode.ASK, false, CONFIGURED_SCENARIO);

        assertDoesNotThrow(() -> runner.run(null), "a mistyped scenario name at the menu must not stop the boot");

        assertFalse(logs.eventsContaining(CHOSEN_SCENARIO).isEmpty(),
                "after the unknown answer the menu must ask again and run the scenario chosen next, " + CHOSEN_SCENARIO);
    }

    @Test
    void GivenAnUnknownScenarioAnswerThenTheConsoleEnds_WhenTheAskMenuRuns_ThenTheBootStopsNamingTheValidChoices() {
        System.setIn(new ByteArrayInputStream(ANSWERS_ENDING_AFTER_UNKNOWN.getBytes(StandardCharsets.UTF_8)));
        ScenarioRunner runner = fixture.runner(SeedProperties.Mode.ASK, false, CONFIGURED_SCENARIO);

        IllegalStateException stopped = assertThrows(IllegalStateException.class, () -> runner.run(null),
                "an unknown answer with no answer after it must stop the boot, not run the default scenario");

        assertTrue(stopped.getMessage().contains("demo") && stopped.getMessage().contains("review"),
                "the message must name the valid choices, but was: " + stopped.getMessage());
        assertTrue(logs.eventsContaining(DEFAULT_SCENARIO).isEmpty(), "the default scenario must not run");
    }

    // With no console the menu takes each question's default, as before: an answer that never came is not a typo.
    @Test
    void GivenTheConsoleEndsAtTheScenarioQuestion_WhenTheAskMenuRuns_ThenTheDefaultScenarioRuns() {
        System.setIn(new ByteArrayInputStream(ANSWERS_ENDING_AT_SCENARIO.getBytes(StandardCharsets.UTF_8)));
        ScenarioRunner runner = fixture.runner(SeedProperties.Mode.ASK, false, CONFIGURED_SCENARIO);

        assertDoesNotThrow(() -> runner.run(null));

        assertFalse(logs.eventsContaining(DEFAULT_SCENARIO).isEmpty(),
                "a console that ends before the scenario question is answered gets the default, " + DEFAULT_SCENARIO);
    }
}
