package com.ticketing.system.unit.infrastructure.seed.scenario;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
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
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.shared.dto.GuestSessionDTO;

/**
 * {@code seed.mode=reset} reads the scenario before it wipes, so a scenario that cannot be run stops the
 * boot with the data still there. And it replays the scenario even when its users already exist: the
 * wipe removes them, so the reseed's skip does not apply (#187).
 */
class ScenarioResetTest {

    // ---- F2: reset reads the scenario before it wipes (#187) ----

    private static final String REFUSED_SCENARIO = "expect-error reserve alice e1 standing qty=1\n";
    private static final String REGISTERING_SCENARIO = "register alice password123 alice@demo.test 30\n";
    private static final String EXISTING_USERNAME = "alice";
    private static final String PREVIOUS_BOOT_HASH = "$2a$10$previousbootpreviousbootpreviousbootpreviousbo";
    private static final int AGE = 30;
    private static final String GUEST_SESSION_ID = "guest-sid";

    @TempDir
    Path dir;

    private final ScenarioRunnerFixture fixture = new ScenarioRunnerFixture();

    @BeforeEach
    void allowTheWipe() {
        fixture.assumeYes = true;
    }

    @Test
    void GivenAScenarioTheParserRefuses_WhenReset_ThenTheBootStopsAndNothingIsWiped() throws IOException {
        ScenarioRunner runner = runnerFor(REFUSED_SCENARIO);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> runner.run(null),
                "a scenario the parser refuses must stop the boot");

        assertTrue(refused.getMessage().contains("line 1"), "precondition: the parser refused it, but was: "
                + refused.getMessage());
        verify(fixture.repoCleaner, never()).clearAll();
    }

    @Test
    void GivenOneOfTheScenariosUsersAlreadyExists_WhenReset_ThenItWipesAndRunsTheScenario() throws IOException {
        fixture.users.save(new User(fixture.users.nextId(), EXISTING_USERNAME, EXISTING_USERNAME + "@previous.boot",
                PREVIOUS_BOOT_HASH, AGE));
        when(fixture.authenticationService.startGuestSession())
                .thenReturn(new GuestSessionDTO(GUEST_SESSION_ID, ScenarioRunnerFixture.NOW));
        ScenarioRunner runner = runnerFor(REGISTERING_SCENARIO);

        assertDoesNotThrow(() -> runner.run(null));

        verify(fixture.repoCleaner).clearAll();
        verify(fixture.authenticationService)
                .register(argThat(request -> request.username().equals(EXISTING_USERNAME)));
    }

    private ScenarioRunner runnerFor(String scenario) throws IOException {
        return fixture.runner(SeedProperties.Mode.RESET, false, ScenarioRunnerFixture.scenarioFile(dir, scenario));
    }
}
