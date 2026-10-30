package com.ticketing.system.unit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.domain.DiscountPolicy;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.catalog.domain.StandingZone;
import com.ticketing.system.catalog.domain.VenueMap;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.shared.dto.VenueMapDTO;
import com.ticketing.system.shared.exception.EventNotFoundException;

/**
 * #185 / UC-8: the venue map is part of an event's public page, so {@code getEventVenueMap} shows it only
 * when {@code getEventDetail} would show the event. A DRAFT or SCHEDULED event is not public yet.
 */
class CatalogServiceVenueMapVisibilityTest {

    private static final String CREDENTIAL = "guest-session-id";
    private static final int EVENT_ID = 7;
    private static final int COMPANY_ID = 70;
    private static final int VENUE_MAP_ID = 3;
    private static final int ZONE_ID = 1;
    private static final ZoneId VENUE_ZONE = ZoneId.of("Asia/Jerusalem");
    private static final LocalDateTime SHOW_START = LocalDateTime.of(2030, 6, 1, 20, 0);

    private EventRepository eventRepository;
    private CatalogService catalogService;

    @BeforeEach
    void setUp() {
        SessionManager sessionManager = mock(SessionManager.class);
        eventRepository = mock(EventRepository.class);
        ProductionCompanyRepository companyRepository = mock(ProductionCompanyRepository.class);
        catalogService = new CatalogService(sessionManager, eventRepository, companyRepository);

        when(sessionManager.validateCredential(CREDENTIAL)).thenReturn(true);
        when(companyRepository.getCompanyById(COMPANY_ID)).thenReturn(new ProductionCompany(
                COMPANY_ID, 1, "Acme Live", CompanyStatus.ACTIVE, "An active production company", 4.5));
    }

    @Test
    void GivenDraftEvent_WhenGetEventVenueMap_ThenRefusedLikeGetEventDetail() {
        storeEventWithStatus(EventStatus.DRAFT);

        assertRefusedLikeGetEventDetail();
    }

    @Test
    void GivenScheduledEvent_WhenGetEventVenueMap_ThenRefusedLikeGetEventDetail() {
        storeEventWithStatus(EventStatus.SCHEDULED);

        assertRefusedLikeGetEventDetail();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = EventStatus.class, names = {"ON_SALE", "SOLD_OUT", "CANCELED", "COMPLETED"})
    void GivenPubliclyVisibleEvent_WhenGetEventVenueMap_ThenReturnsItsMap(EventStatus status) {
        storeEventWithStatus(status);

        VenueMapDTO map = catalogService.getEventVenueMap(CREDENTIAL, EVENT_ID);

        assertThat(map.venueMapId()).isEqualTo(VENUE_MAP_ID);
        assertThat(map.inventoryZones()).extracting(zone -> zone.getId()).containsExactly(ZONE_ID);
    }

    private void assertRefusedLikeGetEventDetail() {
        assertThatThrownBy(() -> catalogService.getEventDetail(CREDENTIAL, EVENT_ID))
                .as("precondition: getEventDetail hides this event")
                .isInstanceOf(EventNotFoundException.class);
        assertThatThrownBy(() -> catalogService.getEventVenueMap(CREDENTIAL, EVENT_ID))
                .as("getEventVenueMap must hide the event's map the same way getEventDetail hides the event")
                .isInstanceOf(EventNotFoundException.class);
    }

    private void storeEventWithStatus(EventStatus status) {
        VenueMap venueMap = new VenueMap(VENUE_MAP_ID, new Location("Israel", "Tel Aviv"),
                List.of(new StandingZone(ZONE_ID, "Floor", 100, 50)));
        Event event = new Event(EVENT_ID, "Concert", 4.5, List.of("Artist"), EventCategory.CONCERT, COMPANY_ID,
                status, venueMap, List.of(new ShowDate(SHOW_START, SHOW_START.plusHours(2))), VENUE_ZONE,
                null, new DiscountPolicy(0));
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
    }
}
