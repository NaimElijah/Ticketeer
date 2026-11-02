package com.ticketing.system.unit.infrastructure.seed.scenario;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ticketing.system.bootstrap.config.SeedProperties;
import com.ticketing.system.bootstrap.dev.seed.scenario.ScenarioRunner;
import com.ticketing.system.governance.config.PlatformAdminProperties;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.OutreachRequestDTO;

/**
 * {@code login-admin <alias>}, with no username or password, signs in the configured default admin
 * with the configured credentials ({@code platform.admin.*}), so a committed scenario runs whatever
 * password the deployment configures. The three-argument form still signs in the admin the file names
 * (#187).
 */
class ScenarioAdminSignInTest {

    // ---- Defect 1: login-admin signs in the configured default admin (#187) ----

    private static final String CONFIGURED_USERNAME = "platform.root";
    // Deliberately not the committed default, which a run outside dev and test refuses.
    private static final String CONFIGURED_PASSWORD = "configured-admin-password-not-the-default";
    private static final String FILE_USERNAME = "ops.admin";
    private static final String FILE_PASSWORD = "password-written-in-the-file";
    private static final String ADMIN_TOKEN = "admin-jwt";
    private static final int ADMIN_ID = 1;
    private static final long TOKEN_EXPIRY_EPOCH_MILLIS = 0L;

    @TempDir
    Path dir;

    private final ScenarioRunnerFixture fixture = new ScenarioRunnerFixture();

    @BeforeEach
    void configureTheDefaultAdmin() {
        fixture.platformAdmin = new PlatformAdminProperties(CONFIGURED_USERNAME, CONFIGURED_PASSWORD);
        when(fixture.authenticationService.signInAsAdmin(anyString(), anyString()))
                .thenReturn(new AuthTokenDTO(ADMIN_TOKEN, TOKEN_EXPIRY_EPOCH_MILLIS, ADMIN_ID, CONFIGURED_USERNAME, true));
    }

    @Test
    void GivenLoginAdminWithOnlyAnAlias_WhenScenarioRuns_ThenTheConfiguredAdminSignsInWithTheConfiguredPassword()
            throws IOException {
        ScenarioRunner runner = runnerFor("login-admin root\n");

        assertDoesNotThrow(() -> runner.run(null), "login-admin <alias> alone must be a valid step");

        verify(fixture.authenticationService).signInAsAdmin(CONFIGURED_USERNAME, CONFIGURED_PASSWORD);
    }

    @Test
    void GivenAdminSignedInByAliasOnly_WhenALaterStepActsAsThatAlias_ThenItUsesTheAdminsToken() throws IOException {
        ScenarioRunner runner = runnerFor("login-admin root\nannounce root title=\"Notice\" body=\"Hello\"\n");

        assertDoesNotThrow(() -> runner.run(null), "the alias signed in by login-admin <alias> must be usable");

        verify(fixture.messagingService).sendOutreach(eq(ADMIN_TOKEN), any(OutreachRequestDTO.class));
    }

    @Test
    void GivenLoginAdminWithUsernameAndPassword_WhenScenarioRuns_ThenTheFilesCredentialsAreUsed() throws IOException {
        ScenarioRunner runner = runnerFor("login-admin ops " + FILE_USERNAME + " " + FILE_PASSWORD + "\n");

        assertDoesNotThrow(() -> runner.run(null), "the three-argument form must stay valid");

        verify(fixture.authenticationService).signInAsAdmin(FILE_USERNAME, FILE_PASSWORD);
        verify(fixture.authenticationService, never()).signInAsAdmin(CONFIGURED_USERNAME, CONFIGURED_PASSWORD);
    }

    private ScenarioRunner runnerFor(String scenario) throws IOException {
        return fixture.runner(SeedProperties.Mode.RESEED, true, ScenarioRunnerFixture.scenarioFile(dir, scenario));
    }
}
