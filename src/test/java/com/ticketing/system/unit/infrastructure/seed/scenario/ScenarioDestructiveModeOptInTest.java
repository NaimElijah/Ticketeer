package com.ticketing.system.unit.infrastructure.seed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ticketing.system.bootstrap.config.SeedProperties;
import com.ticketing.system.bootstrap.dev.seed.scenario.ScenarioRunner;
import com.ticketing.system.testutil.LogCapture;

import ch.qos.logback.classic.Level;

/**
 * {@code seed.mode=wipe} and {@code reset} delete every business row, so they run only with the explicit
 * opt-in {@code seed.assume-yes=true}. Without it the runner logs what it would delete and refuses: nothing
 * is wiped, the market is not opened and no scenario step runs. With it, the wipe happens.
 */
class ScenarioDestructiveModeOptInTest {

    private static final String REGISTERING_SCENARIO = "register alice password123 alice@demo.test 30\n";
    private static final String REFUSAL = "REFUSED";

    @TempDir
    Path dir;

    private final ScenarioRunnerFixture fixture = new ScenarioRunnerFixture();
    private LogCapture logs;

    @BeforeEach
    void captureTheRunnersLog() {
        logs = LogCapture.of(ScenarioRunner.class);
    }

    @AfterEach
    void detachLogCapture() {
        logs.close();
    }

    @Test
    void GivenWipeWithoutTheOptIn_WhenRun_ThenNothingIsWiped() throws IOException {
        fixture.assumeYes = false;

        runnerFor(SeedProperties.Mode.WIPE).run(null);

        verify(fixture.repoCleaner, never()).clearAll();
        assertThat(logs.messagesAt(Level.WARN)).as("the refusal is logged")
                .anyMatch(line -> line.contains("seed.mode=wipe " + REFUSAL));
    }

    @Test
    void GivenResetWithoutTheOptIn_WhenRun_ThenNothingIsWipedAndNoStepRuns() throws IOException {
        fixture.assumeYes = false;

        runnerFor(SeedProperties.Mode.RESET).run(null);

        verify(fixture.repoCleaner, never()).clearAll();
        fixture.verifyNoScenarioStepRan();
        verifyNoInteractions(fixture.systemAdminService);
        assertThat(logs.messagesAt(Level.WARN)).as("the refusal is logged")
                .anyMatch(line -> line.contains("seed.mode=reset " + REFUSAL));
    }

    @Test
    void GivenWipeWithTheOptIn_WhenRun_ThenTheDatabaseIsWipedAndNoStepRuns() throws IOException {
        fixture.assumeYes = true;

        runnerFor(SeedProperties.Mode.WIPE).run(null);

        verify(fixture.repoCleaner).clearAll();
        fixture.verifyNoScenarioStepRan();
        verify(fixture.systemAdminService, never()).openMarket(any());
        assertThat(logs.messagesAt(Level.WARN)).as("an allowed wipe is not a refusal")
                .noneMatch(line -> line.contains(REFUSAL));
    }

    private ScenarioRunner runnerFor(SeedProperties.Mode mode) throws IOException {
        return fixture.runner(mode, true, ScenarioRunnerFixture.scenarioFile(dir, REGISTERING_SCENARIO));
    }
}
