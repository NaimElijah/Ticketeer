package com.ticketing.system.unit.infrastructure.seed.scenario;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ticketing.system.bootstrap.config.SeedProperties;
import com.ticketing.system.bootstrap.dev.seed.scenario.ScenarioRunner;
import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.organization.application.dto.ProductionCompanyDTO;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.shared.dto.LoginDTO;

/**
 * A scenario's enum values are read the same whatever the JVM's default locale: under Turkish rules an
 * {@code i} upper-cases to a dotted {@code İ}, so {@code configure_venue} would not name
 * {@link Permission#CONFIGURE_VENUE} (#187).
 *
 * <p>The application services are the fixture's mocks, arranged so every step passes.
 */
class ScenarioOpsLocaleTest {

    // ---- F6: a scenario's permission names do not depend on the default locale (#187) ----

    private static final String SCENARIO = """
            register alice password123 alice@demo.test 30
            register bob password123 bob@demo.test 30
            login alice
            open-company alice live "Live Nation"
            appoint-manager alice live bob perms=configure_venue
            """;
    private static final String GUEST_SESSION_ID = "guest-sid";
    private static final String OWNER_TOKEN = "alice-jwt";
    private static final int OWNER_ID = 1;
    private static final int COMPANY_ID = 3;
    private static final String COMPANY_NAME = "Live Nation";
    private static final String COMPANY_STATUS = "ACTIVE";
    private static final long TOKEN_EXPIRY_EPOCH_MILLIS = 0L;
    private static final String STORED_HASH = "$2a$10$storedhashstoredhashstoredhashstoredhashstoredha";

    @TempDir
    Path dir;

    private final ScenarioRunnerFixture fixture = new ScenarioRunnerFixture();
    private Locale originalLocale;

    @BeforeEach
    void everyStepPassesUnderATurkishDefaultLocale() {
        when(fixture.authenticationService.startGuestSession())
                .thenReturn(new GuestSessionDTO(GUEST_SESSION_ID, ScenarioRunnerFixture.NOW));
        doAnswer(call -> {
            RegisterRequestDTO request = call.getArgument(0);
            fixture.users.save(new User(fixture.users.nextId(), request.username(), request.email(),
                    STORED_HASH, request.age()));
            return null;
        }).when(fixture.authenticationService).register(any(RegisterRequestDTO.class));
        when(fixture.authenticationService.login(any())).thenReturn(
                new LoginDTO(new AuthTokenDTO(OWNER_TOKEN, TOKEN_EXPIRY_EPOCH_MILLIS, OWNER_ID, "alice"), null, List.of()));
        when(fixture.companyManagementService.registerCompany(eq(OWNER_TOKEN), any())).thenReturn(
                new ProductionCompanyDTO(COMPANY_ID, COMPANY_NAME, COMPANY_NAME, COMPANY_STATUS, OWNER_ID));
        originalLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr"));
    }

    @AfterEach
    void restoreTheDefaultLocale() {
        Locale.setDefault(originalLocale);
    }

    @Test
    void GivenTurkishDefaultLocale_WhenAScenarioNamesAPermissionInLowerCase_ThenItIsThatPermission() throws IOException {
        ScenarioRunner runner = fixture.runner(SeedProperties.Mode.RESEED, true,
                ScenarioRunnerFixture.scenarioFile(dir, SCENARIO));

        assertDoesNotThrow(() -> runner.run(null), "every step, the appointment included, must pass");

        verify(fixture.companyManagementService).appointManager(eq(OWNER_TOKEN),
                argThat(request -> request.permissions().equals(List.of(Permission.CONFIGURE_VENUE))));
    }
}
