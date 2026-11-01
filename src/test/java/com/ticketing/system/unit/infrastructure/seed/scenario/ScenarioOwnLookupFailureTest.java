package com.ticketing.system.unit.infrastructure.seed.scenario;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ticketing.system.bootstrap.config.SeedProperties;
import com.ticketing.system.bootstrap.dev.seed.SeedHarness;
import com.ticketing.system.bootstrap.dev.seed.scenario.ScenarioRunner;
import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.organization.application.dto.ProductionCompanyDTO;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.shared.dto.InventoryZoneDTO;
import com.ticketing.system.shared.dto.LoginDTO;
import com.ticketing.system.shared.dto.VenueMapDTO;
import com.ticketing.system.testutil.LogCapture;

/**
 * A lookup that fails because of the scenario itself, such as a zone with nothing left to reserve, throws
 * {@link IllegalArgumentException}, not the {@link IllegalStateException} the services' guards throw. So an
 * {@code expect-error … error=IllegalStateException} cannot pass on the scenario's own failure before the
 * service it means to check is even called (#187).
 *
 * <p>The application services are the fixture's mocks, arranged so every step before the negative check
 * passes and the event's only zone has nothing available.
 */
class ScenarioOwnLookupFailureTest {

    // ---- F1: the scenario's own lookup failures are IllegalArgumentException (#187) ----

    private static final String SCENARIO = """
            register alice password123 alice@demo.test 30
            login alice
            open-company alice live "Live Nation"
            add-event alice live e1 standing:1@10
            expect-error reserve alice e1 standing qty=1 error=IllegalStateException
            """;
    private static final String NEGATIVE_CHECK_LABEL = "L5 expect-error reserve";
    private static final String GUEST_SESSION_ID = "guest-sid";
    private static final String MEMBER_TOKEN = "alice-jwt";
    private static final int MEMBER_ID = 1;
    private static final int COMPANY_ID = 3;
    private static final String COMPANY_NAME = "Live Nation";
    private static final String COMPANY_STATUS = "ACTIVE";
    private static final int EVENT_ID = 7;
    private static final String EVENT_NAME = "e1";
    private static final ZoneId EVENT_ZONE = ZoneId.of("Asia/Jerusalem");
    private static final int ZONE_ID = 1;
    private static final int CAPACITY = 1;
    private static final int NOTHING_AVAILABLE = 0;
    private static final double PRICE = 10.0;
    private static final long TOKEN_EXPIRY_EPOCH_MILLIS = 0L;
    private static final String STORED_HASH = "$2a$10$storedhashstoredhashstoredhashstoredhashstoredha";

    @TempDir
    Path dir;

    private final ScenarioRunnerFixture fixture = new ScenarioRunnerFixture();
    private LogCapture logs;

    @BeforeEach
    void everyStepBeforeTheNegativeCheckPasses() {
        when(fixture.authenticationService.startGuestSession())
                .thenReturn(new GuestSessionDTO(GUEST_SESSION_ID, ScenarioRunnerFixture.NOW));
        doAnswer(call -> {
            RegisterRequestDTO request = call.getArgument(0);
            fixture.users.save(new User(fixture.users.nextId(), request.username(), request.email(),
                    STORED_HASH, request.age()));
            return null;
        }).when(fixture.authenticationService).register(any(RegisterRequestDTO.class));
        when(fixture.authenticationService.login(any())).thenReturn(
                new LoginDTO(new AuthTokenDTO(MEMBER_TOKEN, TOKEN_EXPIRY_EPOCH_MILLIS, MEMBER_ID, "alice"), null, List.of()));
        when(fixture.companyManagementService.registerCompany(eq(MEMBER_TOKEN), any())).thenReturn(
                new ProductionCompanyDTO(COMPANY_ID, COMPANY_NAME, COMPANY_NAME, COMPANY_STATUS, MEMBER_ID));
        when(fixture.eventManagementService.addEvent(eq(MEMBER_TOKEN), any())).thenReturn(new EventDetailDTO(
                String.valueOf(EVENT_ID), EVENT_NAME, null, EVENT_NAME, EventCategory.CONCERT, null,
                String.valueOf(COMPANY_ID), COMPANY_NAME, EventStatus.SCHEDULED, List.of(), EVENT_ZONE,
                List.of(EVENT_NAME), PRICE));
        InventoryZoneDTO soldOut = new InventoryZoneDTO(ZONE_ID, "Standing 1", "STANDING", CAPACITY,
                NOTHING_AVAILABLE, 0, CAPACITY, PRICE, List.of(), null);
        when(fixture.catalogService.getEventVenueMap(anyString(), eq(EVENT_ID)))
                .thenReturn(new VenueMapDTO(1, null, 1, 1, List.of(soldOut)));
        logs = LogCapture.of(ScenarioRunner.class);
    }

    @AfterEach
    void detachLogCapture() {
        logs.close();
    }

    @Test
    void GivenExpectErrorNamingIllegalStateOnAZoneWithNothingAvailable_WhenScenarioRuns_ThenTheStepFailsNamingIllegalArgument()
            throws IOException {
        ScenarioRunner runner = fixture.runner(SeedProperties.Mode.RESEED, true,
                ScenarioRunnerFixture.scenarioFile(dir, SCENARIO));

        SeedHarness.SeedAbortException abort = assertThrows(SeedHarness.SeedAbortException.class,
                () -> runner.run(null),
                "the scenario's own lookup failure must not satisfy expect-error error=IllegalStateException");

        assertTrue(abort.getMessage().contains(NEGATIVE_CHECK_LABEL),
                "precondition: the earlier steps passed and the negative check is what failed, but was: "
                        + abort.getMessage());
        assertInstanceOf(IllegalArgumentException.class, abort.getCause(),
                "the scenario's own lookup failure is an IllegalArgumentException");
        assertFalse(logs.eventsContaining("but got IllegalArgumentException").isEmpty(),
                "the step's failure must name IllegalArgumentException");
        verifyNoInteractions(fixture.reservationService);
    }
}
