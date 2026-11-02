package com.ticketing.system.unit.infrastructure.seed.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.qos.logback.classic.Level;

import com.ticketing.system.bootstrap.config.SeedProperties;
import com.ticketing.system.bootstrap.dev.seed.scenario.ScenarioRunner;
import com.ticketing.system.testutil.LogCapture;

/**
 * A committed scenario registers accounts whose passwords are published in the repository, so running one
 * outside the {@code dev} profile logs one WARN before its first step, saying the database should not be
 * reachable by others (#187). It is a warning, not a refusal: that decision is #78's.
 *
 * <p>The application services are the fixture's mocks, so the scenario's steps fail; the run is not
 * fail-fast, and only the WARN is checked.
 */
class ScenarioCommittedOutsideDevWarningTest {

    // ---- F5: a committed scenario run outside dev is warned about (#187) ----

    private static final String COMMITTED_SCENARIO = "classpath:scenarios/review.scenario";
    private static final String OWN_SCENARIO = "register owner password-of-my-own owner@example.test 30\n";
    private static final String DEV_PROFILE = "dev";
    private static final String WARNING_MARKER = "published in the repository";

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
    void GivenNoDevProfile_WhenACommittedScenarioRuns_ThenOneWarningIsLogged() {
        fixture.runner(SeedProperties.Mode.RESEED, false, COMMITTED_SCENARIO).run(null);

        assertEquals(1, warningsAboutPublishedPasswords().size(),
                "a committed scenario run outside dev must be warned about once, but the WARNs were "
                        + logs.messagesAt(Level.WARN));
    }

    @Test
    void GivenTheDevProfile_WhenACommittedScenarioRuns_ThenNoSuchWarningIsLogged() {
        fixture.environment.setActiveProfiles(DEV_PROFILE);

        fixture.runner(SeedProperties.Mode.RESEED, false, COMMITTED_SCENARIO).run(null);

        assertEquals(List.of(), warningsAboutPublishedPasswords(), "dev runs on an in-memory database by design");
    }

    // A scenario of one's own carries passwords one chose, not ones the repository publishes.
    @Test
    void GivenNoDevProfile_WhenAScenarioFromAFileRuns_ThenNoSuchWarningIsLogged() throws IOException {
        fixture.runner(SeedProperties.Mode.RESEED, false, ScenarioRunnerFixture.scenarioFile(dir, OWN_SCENARIO))
                .run(null);

        assertEquals(List.of(), warningsAboutPublishedPasswords(), "only a committed scenario is warned about");
    }

    private List<String> warningsAboutPublishedPasswords() {
        return logs.messagesAt(Level.WARN).stream().filter(message -> message.contains(WARNING_MARKER)).toList();
    }
}
