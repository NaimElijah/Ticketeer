package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.ui.support.ServiceErrors;

import ch.qos.logback.classic.Level;
import jakarta.persistence.PersistenceException;

import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.InventoryZoneDTO;
import com.ticketing.system.shared.dto.LocationDTO;
import com.ticketing.system.shared.dto.VenueMapDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.NullVenueMapException;
import com.ticketing.system.ui.presenters.catalog.EventDetailsPresenter;
import com.ticketing.system.shared.exception.SessionExpiredException;

class EventDetailsPresenterTest {

    private static final String CRED = "cred";
    private static final int EVENT_ID = 1;

    private CatalogService catalogService;
    private EventDetailsPresenter presenter;

    @BeforeEach
    void setUp() {
        catalogService = mock(CatalogService.class);
        presenter = new EventDetailsPresenter(catalogService);
    }

    private static EventDetailDTO detail() {
        return new EventDetailDTO("1", "Coldplay", 4.8, "Music of the Spheres",
                EventCategory.MUSIC, new Location("Israel", "Tel Aviv"), "10", "Live Nation",
                EventStatus.ON_SALE, List.of(), ZoneId.of("Asia/Jerusalem"), List.of("Coldplay"), 75.0);
    }

    private static VenueMapDTO venueMap() {
        InventoryZoneDTO zone = new InventoryZoneDTO(
                1, "Floor", "STANDING", 100, 80, 0, 0, 90.0, List.of(), null);
        return new VenueMapDTO(5, new LocationDTO("Israel", "Tel Aviv"), 4, 4, List.of(zone));
    }

    @Test
    void GivenAnEventWithAVenueMap_WhenLoad_ThenCarriesEventZonesGridAndCompanyRating() {
        EventDetailDTO detail = detail();
        when(catalogService.getEventDetail(CRED, EVENT_ID)).thenReturn(detail);
        when(catalogService.getEventVenueMap(CRED, EVENT_ID)).thenReturn(venueMap());
        when(catalogService.companyRating(10)).thenReturn(4.7); // detail()'s companyId is "10"

        EventDetailsPresenter.Outcome outcome = presenter.load(CRED, EVENT_ID);

        EventDetailsPresenter.Outcome.Success ok =
                assertInstanceOf(EventDetailsPresenter.Outcome.Success.class, outcome);
        assertSame(detail, ok.event());
        assertEquals(1, ok.zones().size());
        assertEquals(4, ok.gridRows());
        assertEquals(4, ok.gridCols());
        assertEquals(4.7, ok.companyRating(), 0.0001);
    }

    @Test
    void GivenTheEventIsNotFound_WhenLoad_ThenNotFoundSaysItIsNotAvailable() {
        when(catalogService.getEventDetail(CRED, EVENT_ID)).thenThrow(new EventNotFoundException(EVENT_ID));

        EventDetailsPresenter.Outcome outcome = presenter.load(CRED, EVENT_ID);

        assertEquals(new EventDetailsPresenter.Outcome.NotFound("This event isn't available."), outcome);
    }

    @Test
    void GivenAnInvalidToken_WhenLoad_ThenFailureAsksTheVisitorToRefresh() {
        when(catalogService.getEventDetail(CRED, EVENT_ID)).thenThrow(new InvalidTokenException());

        EventDetailsPresenter.Outcome outcome = presenter.load(CRED, EVENT_ID);

        assertEquals(new EventDetailsPresenter.Outcome.Failure("Your session has expired - please refresh and try again."),
                outcome);
    }

    // An event with no (accessible) venue map still shows its detail — zones fall back to empty.
    @Test
    void GivenAMissingVenueMap_WhenLoad_ThenStillSuccessWithEmptyZonesAndTheDefaultGrid() {
        EventDetailDTO detail = detail();
        when(catalogService.getEventDetail(CRED, EVENT_ID)).thenReturn(detail);
        when(catalogService.getEventVenueMap(CRED, EVENT_ID)).thenThrow(new NullVenueMapException(EVENT_ID));

        EventDetailsPresenter.Outcome outcome = presenter.load(CRED, EVENT_ID);

        EventDetailsPresenter.Outcome.Success ok =
                assertInstanceOf(EventDetailsPresenter.Outcome.Success.class, outcome);
        assertSame(detail, ok.event());
        assertTrue(ok.zones().isEmpty());
        assertEquals(3, ok.gridRows());
        assertEquals(3, ok.gridCols());
    }

    // ---- UI-B3: a failed load reads as a failure, not as an event without a map (#187) ----
    // A missing map (NullVenueMapException) still shows the detail with no zones:
    // GivenAMissingVenueMap_WhenLoad_ThenStillSuccessWithEmptyZonesAndTheDefaultGrid above.

    @Test
    void GivenTheStoreIsDown_WhenLoad_ThenFailureCarriesTheOutageMessage() {
        when(catalogService.getEventDetail(CRED, EVENT_ID))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        EventDetailsPresenter.Outcome outcome = presenter.load(CRED, EVENT_ID);

        EventDetailsPresenter.Outcome.Failure failure =
                assertInstanceOf(EventDetailsPresenter.Outcome.Failure.class, outcome);
        assertEquals(ServiceErrors.DB_UNAVAILABLE_MESSAGE, failure.message());
    }

    @Test
    void GivenTheStoreFailsWhileLoadingTheVenueMap_WhenLoad_ThenFailureNotAnEventWithoutAMap() {
        when(catalogService.getEventDetail(CRED, EVENT_ID)).thenReturn(detail());
        when(catalogService.getEventVenueMap(CRED, EVENT_ID))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        EventDetailsPresenter.Outcome outcome = presenter.load(CRED, EVENT_ID);

        EventDetailsPresenter.Outcome.Failure failure = assertInstanceOf(EventDetailsPresenter.Outcome.Failure.class,
                outcome, "a store failure must not read as an event with no venue map, but was: " + outcome);
        assertEquals(ServiceErrors.DB_UNAVAILABLE_MESSAGE, failure.message());
    }

    // ---- UI-B5: an internal failure goes through ServiceErrors (#187) ----

    @Test
    void GivenAnInternalFailure_WhenLoad_ThenFailureCarriesTheGenericMessageAndServiceErrorsLogsIt() {
        when(catalogService.getEventDetail(CRED, EVENT_ID))
                .thenThrow(new PersistenceException("could not prepare statement; table [events]"));

        try (LogCapture serviceErrorsLog = LogCapture.of(ServiceErrors.class)) {
            EventDetailsPresenter.Outcome outcome = presenter.load(CRED, EVENT_ID);

            EventDetailsPresenter.Outcome.Failure failure =
                    assertInstanceOf(EventDetailsPresenter.Outcome.Failure.class, outcome);
            assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, failure.message());
            assertEquals(List.of(PersistenceException.class.getName()),
                    serviceErrorsLog.throwableClassNamesAt(Level.ERROR),
                    "the internal failure must be logged at ERROR with its exception by ServiceErrors");
        }
    }

    // ---- an expired session reads as a dead one, as a revoked token does (ADR-025, #189) ----

    @Test
    void GivenAnExpiredSession_WhenLoad_ThenFailureAsksTheVisitorToRefresh() {
        when(catalogService.getEventDetail(CRED, EVENT_ID)).thenThrow(new SessionExpiredException());

        assertEquals(new EventDetailsPresenter.Outcome.Failure("Your session has expired - please refresh and try again."),
                presenter.load(CRED, EVENT_ID));
    }
}
