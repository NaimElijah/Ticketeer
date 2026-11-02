package com.ticketing.system.unit.infrastructure.seed.scenario;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.testutil.LogCapture;

/**
 * {@code seed.mode=reseed} on a persistent database must not stop the second boot: before any step
 * runs, it checks whether a user the scenario registers already exists, and if one does it skips the
 * scenario with a WARN that names {@code seed.mode=reset} and lets the boot go on. Only the scenario's
 * own users count, since {@code DevUserSeeder} may have created others first (#187).
 */
class ScenarioReseedIdempotencyTest {

    // ---- Defect 2: reseed skips a scenario whose users already exist (#187) ----

    private static final String SCENARIO = """
            register alice password123 alice@demo.test 34
            register bob   password123 bob@demo.test   29
            """;
    private static final String SCENARIO_WITH_EXPLICIT_USERNAME =
            "register a1 password123 carol@demo.test 26 username=carol.k\n";
    private static final String PREVIOUS_BOOT_HASH = "$2a$10$previousbootpreviousbootpreviousbootpreviousbo";
    private static final int AGE = 30;
    private static final String GUEST_SESSION_ID = "guest-sid";

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
    void GivenOneOfTheScenariosUsersAlreadyExists_WhenReseed_ThenNoStepRunsAndTheBootGoesOn() throws IOException {
        existingUser("bob");
        ScenarioRunner runner = runnerFor(SCENARIO);

        assertDoesNotThrow(() -> runner.run(null), "a reseed over an already-seeded database must not stop the boot");

        fixture.verifyNoScenarioStepRan();
    }

    @Test
    void GivenOneOfTheScenariosUsersAlreadyExists_WhenReseed_ThenAWarnPointsAtReset() throws IOException {
        existingUser("bob");
        ScenarioRunner runner = runnerFor(SCENARIO);

        assertDoesNotThrow(() -> runner.run(null));

        List<String> warnings = logs.messagesAt(Level.WARN);
        assertTrue(warnings.stream().anyMatch(message -> message.contains("seed.mode=reset")),
                "skipping the scenario must be logged at WARN naming seed.mode=reset, but the WARNs were " + warnings);
    }

    @Test
    void GivenTheUsernameNamedByAScenarioLineAlreadyExists_WhenReseed_ThenNoStepRuns() throws IOException {
        existingUser("carol.k");
        ScenarioRunner runner = runnerFor(SCENARIO_WITH_EXPLICIT_USERNAME);

        assertDoesNotThrow(() -> runner.run(null));

        fixture.verifyNoScenarioStepRan();
    }

    @Test
    void GivenNoneOfTheScenariosUsersExist_WhenReseed_ThenTheScenarioRuns() throws IOException {
        registrationStoresTheUser();
        ScenarioRunner runner = runnerFor(SCENARIO);

        assertDoesNotThrow(() -> runner.run(null));

        verify(fixture.authenticationService).register(argThat(request -> request.username().equals("alice")));
        verify(fixture.authenticationService).register(argThat(request -> request.username().equals("bob")));
    }

    // DevUserSeeder may run first and create users of its own; they are not the scenario's.
    @Test
    void GivenOnlyAUserTheScenarioDoesNotRegisterExists_WhenReseed_ThenTheScenarioRuns() throws IOException {
        existingUser("dev.member");
        registrationStoresTheUser();
        ScenarioRunner runner = runnerFor(SCENARIO);

        assertDoesNotThrow(() -> runner.run(null));

        verify(fixture.authenticationService).register(argThat(request -> request.username().equals("alice")));
    }

    private void existingUser(String username) {
        fixture.users.save(new User(fixture.users.nextId(), username, username + "@previous.boot", PREVIOUS_BOOT_HASH, AGE));
    }

    // The register step reads the new user back from the repository, as AuthenticationService.register stores it.
    private void registrationStoresTheUser() {
        when(fixture.authenticationService.startGuestSession())
                .thenReturn(new GuestSessionDTO(GUEST_SESSION_ID, ScenarioRunnerFixture.NOW));
        doAnswer(call -> {
            RegisterRequestDTO request = call.getArgument(0);
            fixture.users.save(new User(fixture.users.nextId(), request.username(), request.email(),
                    PREVIOUS_BOOT_HASH, request.age()));
            return null;
        }).when(fixture.authenticationService).register(any(RegisterRequestDTO.class));
    }

    private ScenarioRunner runnerFor(String scenario) throws IOException {
        return fixture.runner(SeedProperties.Mode.RESEED, true, ScenarioRunnerFixture.scenarioFile(dir, scenario));
    }
}
