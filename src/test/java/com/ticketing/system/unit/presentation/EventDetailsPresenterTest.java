package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.InventoryZoneDTO;
import com.ticketing.system.shared.dto.LocationDTO;
import com.ticketing.system.shared.dto.VenueMapDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.shared.exception.CompanyClosedException;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.NullVenueMapException;
import com.ticketing.system.ui.presenters.catalog.EventDetailsPresenter;

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
                EventStatus.ON_SALE, List.of(), List.of("Coldplay"), 75.0);
    }

    private static VenueMapDTO venueMap() {
        InventoryZoneDTO zone = new InventoryZoneDTO(
                1, "Floor", "STANDING", 100, 80, 0, 0, 90.0, List.of(), null);
        return new VenueMapDTO(5, new LocationDTO("Israel", "Tel Aviv"), 4, 4, List.of(zone));
    }

    @Test
    void load_success_carriesEventAndZones() {
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
    void load_eventNotFound_returnsNotFound() {
        when(catalogService.getEventDetail(CRED, EVENT_ID)).thenThrow(new EventNotFoundException("nope"));

        EventDetailsPresenter.Outcome outcome = presenter.load(CRED, EVENT_ID);

        assertInstanceOf(EventDetailsPresenter.Outcome.NotFound.class, outcome);
    }

    @Test
    void load_companyClosed_returnsNotFound() {
        when(catalogService.getEventDetail(CRED, EVENT_ID)).thenThrow(new CompanyClosedException("closed"));

        EventDetailsPresenter.Outcome outcome = presenter.load(CRED, EVENT_ID);

        assertInstanceOf(EventDetailsPresenter.Outcome.NotFound.class, outcome);
    }

    @Test
    void load_invalidToken_returnsFailure() {
        when(catalogService.getEventDetail(CRED, EVENT_ID)).thenThrow(new InvalidTokenException());

        EventDetailsPresenter.Outcome outcome = presenter.load(CRED, EVENT_ID);

        assertInstanceOf(EventDetailsPresenter.Outcome.Failure.class, outcome);
    }

    // An event with no (accessible) venue map still shows its detail — zones fall back to empty.
    @Test
    void load_venueMapMissing_stillSuccessWithEmptyZones() {
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
}
