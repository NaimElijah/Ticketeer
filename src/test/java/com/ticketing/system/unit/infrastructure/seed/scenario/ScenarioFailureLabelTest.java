package com.ticketing.system.unit.infrastructure.seed.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ticketing.system.bootstrap.config.SeedProperties;
import com.ticketing.system.bootstrap.dev.seed.SeedHarness;
import com.ticketing.system.bootstrap.dev.seed.scenario.ScenarioRunner;
import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.testutil.LogCapture;

/**
 * A failing step is named {@code L<n> <op> <first argument>}, so the failure's log lines, the end-of-run
 * report and the fail-fast abort carry no later argument: a {@code register} line's password and email
 * never reach the log (SLR.2, #187).
 */
class ScenarioFailureLabelTest {

    // ---- Defect 5: a failing step's label carries only its first argument (#187) ----

    private static final String PASSWORD = "Sup3rSecretPassw0rd";
    private static final String EMAIL = "alice.private@example.test";
    private static final String REGISTER_LINE = "register alice " + PASSWORD + " " + EMAIL + " 34\n";
    private static final String LABEL = "L1 register alice";
    private static final String GUEST_SESSION_ID = "guest-sid";

    @TempDir
    Path dir;

    private final ScenarioRunnerFixture fixture = new ScenarioRunnerFixture();
    private LogCapture runnerLogs;
    private LogCapture harnessLogs;

    @BeforeEach
    void registrationFails() {
        when(fixture.authenticationService.startGuestSession())
                .thenReturn(new GuestSessionDTO(GUEST_SESSION_ID, ScenarioRunnerFixture.NOW));
        doThrow(new IllegalStateException("registration refused"))
                .when(fixture.authenticationService).register(any(RegisterRequestDTO.class));
        runnerLogs = LogCapture.of(ScenarioRunner.class);
        harnessLogs = LogCapture.of(SeedHarness.class);
    }

    @AfterEach
    void detachLogCaptures() {
        runnerLogs.close();
        harnessLogs.close();
    }

    @Test
    void GivenFailingRegisterLine_WhenScenarioRuns_ThenNeitherThePasswordNorTheEmailIsLogged() throws IOException {
        ScenarioRunner runner = runnerFor(false);

        runner.run(null);

        int labelled = harnessLogs.eventsContaining(LABEL).size() + runnerLogs.eventsContaining(LABEL).size();
        assertTrue(labelled > 0, "precondition: the failed step must be logged under its label " + LABEL);
        assertEquals(List.of(), harnessLogs.eventsContaining(PASSWORD), "the step's failure log must not carry the password");
        assertEquals(List.of(), harnessLogs.eventsContaining(EMAIL), "the step's failure log must not carry the email");
        assertEquals(List.of(), runnerLogs.eventsContaining(PASSWORD), "the scenario report must not carry the password");
        assertEquals(List.of(), runnerLogs.eventsContaining(EMAIL), "the scenario report must not carry the email");
    }

    @Test
    void GivenFailingRegisterLineUnderFailFast_WhenScenarioRuns_ThenTheAbortMessageCarriesNeitherThePasswordNorTheEmail()
            throws IOException {
        ScenarioRunner runner = runnerFor(true);

        SeedHarness.SeedAbortException abort = assertThrows(SeedHarness.SeedAbortException.class, () -> runner.run(null));

        assertFalse(abort.getMessage().contains(PASSWORD),
                "the abort that stops the boot is logged, so it must not carry the password: " + abort.getMessage());
        assertFalse(abort.getMessage().contains(EMAIL),
                "the abort that stops the boot is logged, so it must not carry the email: " + abort.getMessage());
        assertEquals(List.of(), runnerLogs.eventsContaining(PASSWORD), "the scenario report must not carry the password");
        assertEquals(List.of(), harnessLogs.eventsContaining(PASSWORD), "the step's failure log must not carry the password");
    }

    private ScenarioRunner runnerFor(boolean failFast) throws IOException {
        return fixture.runner(SeedProperties.Mode.RESEED, failFast, ScenarioRunnerFixture.scenarioFile(dir, REGISTER_LINE));
    }
}
