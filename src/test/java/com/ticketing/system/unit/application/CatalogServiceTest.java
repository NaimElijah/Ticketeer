package com.ticketing.system.unit.application;
import com.ticketing.system.organization.application.service.CompanyRatings;
import com.ticketing.system.identity.application.service.AuthenticationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.EventSummaryDTO;
import com.ticketing.system.shared.dto.SearchResultDTO;
import com.ticketing.system.shared.dto.VenueMapDTO;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.domain.InventorySelection;
import com.ticketing.system.catalog.domain.InventoryZone;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.catalog.domain.StandingZone;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.VenueMap;
import com.ticketing.system.shared.exception.CompanyClosedException;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.NullVenueMapException;

import com.ticketing.system.shared.dto.InventoryZoneDTO;
import com.ticketing.system.shared.dto.SeatDTO;
import com.ticketing.system.catalog.domain.Seat;
import com.ticketing.system.catalog.domain.SeatedZone;

class CatalogServiceTest {

    private SessionManager mockSessionManager;
    private EventRepository mockEventRepository;
    private ProductionCompanyRepository mockCompanyRepository;
    private CatalogService catalogService;

    private static final String VALID_TOKEN = "valid-token";
    private static final int EVENT_ID = 1;
    private static final Location LOCATION = new Location("Belgium", "Brussels");

    @BeforeEach
    void setUp() {
        mockSessionManager = mock(SessionManager.class);
        mockEventRepository = mock(EventRepository.class);
        mockCompanyRepository = mock(ProductionCompanyRepository.class);
        catalogService = new CatalogService(
                mockSessionManager,
                mockEventRepository,
                mockCompanyRepository);
    }

    @Test
    @Disabled("UC-3: browseEventCatalog returns active events from active companies")
    void givenActiveCompanies_whenBrowse_thenReturnsActiveEvents() {
    }

    @Test
    @Disabled("UC-3: events from closed companies excluded")
    void givenClosedCompany_whenBrowse_thenEventsExcluded() {
    }

    // -------------------------------------------------------------------------
    // UC-7: searchGlobal
    // -------------------------------------------------------------------------

    // UC-7: searchGlobal throws InvalidTokenException for an invalid token
    @Test
    void givenInvalidToken_whenSearchGlobal_thenThrowsInvalidTokenException() {
        when(mockSessionManager.validateToken(VALID_TOKEN)).thenReturn(false);
        CatalogSearchFiltersDTO filters = emptyFilters();

        assertThrows(InvalidTokenException.class,
                () -> catalogService.searchGlobal(VALID_TOKEN, filters));
    }

    // UC-7: searchGlobal propagates SessionExpiredException from the session
    // manager
    @Test
    void givenExpiredToken_whenSearchGlobal_thenThrowsSessionExpiredException() {
        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenThrow(new InvalidTokenException());
        CatalogSearchFiltersDTO filters = emptyFilters();

        assertThrows(InvalidTokenException.class,
                () -> catalogService.searchGlobal(VALID_TOKEN, filters));
    }

    // UC-7: searchGlobal returns all matching events when no company-rating filters
    // are set
    @Test
    void givenValidTokenAndNoFilters_whenSearchGlobal_thenReturnsAllMatchingEvents() {
        CatalogSearchFiltersDTO filters = emptyFilters();
        Event event1 = createMockEvent(1, 10);
        Event event2 = createMockEvent(2, 20);
        ProductionCompany company1 = createMockCompany("Company A", 4.5);
        ProductionCompany company2 = createMockCompany("Company B", 3.0);

        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        when(mockEventRepository.searchONSALE(filters)).thenReturn(List.of(event1, event2));
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(company1);
        when(mockCompanyRepository.getCompanyById(20)).thenReturn(company2);

        List<EventSummaryDTO> results = catalogService.searchGlobal(VALID_TOKEN, filters);

        assertEquals(2, results.size());
    }

    // UC-7: searchGlobal excludes events whose company rating is below
    // minCompanyRating
    @Test
    void givenMinCompanyRatingFilter_whenSearchGlobal_thenExcludesLowRatedCompanies() {
        CatalogSearchFiltersDTO filters = new CatalogSearchFiltersDTO(
                null, null, null, null, null, null, null, null, null, null, null, 3.5, null);
        Event event1 = createMockEvent(1, 10);
        Event event2 = createMockEvent(2, 20);
        ProductionCompany highRated = createMockCompany("Company A", 4.5);
        ProductionCompany lowRated = createMockCompany("Company B", 2.0);

        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        when(mockEventRepository.searchONSALE(filters)).thenReturn(List.of(event1, event2));
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(highRated);
        when(mockCompanyRepository.getCompanyById(20)).thenReturn(lowRated);
        // Company rating is derived from the company's events: 4.5 (>=3.5) vs 2.0 (<3.5).
        Event company10Event = eventRated(4.5);
        Event company20Event = eventRated(2.0);
        when(mockEventRepository.findByCompanyId(10)).thenReturn(List.of(company10Event));
        when(mockEventRepository.findByCompanyId(20)).thenReturn(List.of(company20Event));

        List<EventSummaryDTO> results = catalogService.searchGlobal(VALID_TOKEN, filters);

        assertEquals(1, results.size());
        assertEquals("Event 1", results.get(0).name());
    }

    // UC-7: searchGlobal excludes events whose company rating is above
    // maxCompanyRating
    @Test
    void givenMaxCompanyRatingFilter_whenSearchGlobal_thenExcludesHighRatedCompanies() {
        CatalogSearchFiltersDTO filters = new CatalogSearchFiltersDTO(
                null, null, null, null, null, null, null, null, null, null, null, null, 3.0);
        Event event1 = createMockEvent(1, 10);
        Event event2 = createMockEvent(2, 20);
        ProductionCompany highRated = createMockCompany("Company A", 4.5);
        ProductionCompany lowRated = createMockCompany("Company B", 2.0);

        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        when(mockEventRepository.searchONSALE(filters)).thenReturn(List.of(event1, event2));
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(highRated);
        when(mockCompanyRepository.getCompanyById(20)).thenReturn(lowRated);
        // Company rating is derived from the company's events: 4.5 (>3.0) vs 2.0 (<=3.0).
        Event company10Event = eventRated(4.5);
        Event company20Event = eventRated(2.0);
        when(mockEventRepository.findByCompanyId(10)).thenReturn(List.of(company10Event));
        when(mockEventRepository.findByCompanyId(20)).thenReturn(List.of(company20Event));

        List<EventSummaryDTO> results = catalogService.searchGlobal(VALID_TOKEN, filters);

        assertEquals(1, results.size());
        assertEquals("Event 2", results.get(0).name());
    }

    // UC-7: searchGlobal returns an empty list when the repository returns no
    // events
    @Test
    void givenValidTokenAndEmptyRepository_whenSearchGlobal_thenReturnsEmptyList() {
        CatalogSearchFiltersDTO filters = emptyFilters();
        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        when(mockEventRepository.searchONSALE(filters)).thenReturn(List.of());

        List<EventSummaryDTO> results = catalogService.searchGlobal(VALID_TOKEN, filters);

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    // -------------------------------------------------------------------------
    // UC-7: searchByCompany
    // -------------------------------------------------------------------------

    // UC-7: searchByCompany throws InvalidTokenException for an invalid token
    @Test
    void givenInvalidToken_whenSearchByCompany_thenThrowsInvalidTokenException() {
        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(false);
        CatalogSearchFiltersDTO filters = emptyFilters();

        assertThrows(InvalidTokenException.class,
                () -> catalogService.searchByCompany(VALID_TOKEN, 10, filters));
    }

    // UC-7: searchByCompany propagates SessionExpiredException from the session
    // manager
    @Test
    void givenExpiredToken_whenSearchByCompany_thenThrowsSessionExpiredException() {
        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenThrow(new InvalidTokenException());
        CatalogSearchFiltersDTO filters = emptyFilters();

        assertThrows(InvalidTokenException.class,
                () -> catalogService.searchByCompany(VALID_TOKEN, 10, filters));
    }

    // UC-7: searchByCompany returns only events belonging to the requested company
    @Test
    void givenValidTokenAndCompanyId_whenSearchByCompany_thenReturnsOnlyThatCompanysEvents() {
        CatalogSearchFiltersDTO filters = emptyFilters();
        Event event1 = createMockEvent(1, 10);
        Event event2 = createMockEvent(2, 20);
        ProductionCompany company = createMockCompany("Company A", 4.5);

        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        when(mockEventRepository.searchONSALE(filters)).thenReturn(List.of(event1, event2));
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(company);

        List<EventSummaryDTO> results = catalogService.searchByCompany(VALID_TOKEN, 10, filters);

        assertEquals(1, results.size());
        assertEquals("Event 1", results.get(0).name());
    }

    // UC-7: searchByCompany does NOT apply the company-rating filter (II.2.3.2)
    @Test
    void givenCompanyRatingFilter_whenSearchByCompany_thenCompanyRatingFilterNotApplied() {
        // minCompanyRating=4.0 but the company rating is only 1.5 — must still be
        // returned
        CatalogSearchFiltersDTO filters = new CatalogSearchFiltersDTO(
                null, null, null, null, null, null, null, null, null, null, null, 4.0, null);
        Event event1 = createMockEvent(1, 10);
        ProductionCompany lowRated = createMockCompany("Company A", 1.5);

        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        when(mockEventRepository.searchONSALE(any())).thenReturn(List.of(event1));
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(lowRated);

        List<EventSummaryDTO> results = catalogService.searchByCompany(VALID_TOKEN, 10, filters);

        assertEquals(1, results.size());
    }

    // UC-7: searchByCompany returns an empty list when no events match the given
    // company id
    @Test
    void givenValidTokenAndNoMatchingCompanyEvents_whenSearchByCompany_thenReturnsEmptyList() {
        CatalogSearchFiltersDTO filters = emptyFilters();
        Event event1 = createMockEvent(1, 20); // belongs to a different company
        ProductionCompany company10 = createMockCompany("Company A", 4.5);

        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(company10);
        when(mockEventRepository.searchONSALE(filters)).thenReturn(List.of(event1));

        List<EventSummaryDTO> results = catalogService.searchByCompany(VALID_TOKEN, 10, filters);

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    // UC-8: getEventVenueMap returns correct venue map for event
    @Test
    void givenValidTokenAndEventWithVenueMap_whenGetEventVenueMap_thenReturnsCorrectVenueMapDTO() {
        InventoryZone zone = new StandingZone(10, "Floor", 200, 50);
        VenueMap venueMap = new VenueMap(5, LOCATION, List.of(zone));
        Event mockEvent = mock(Event.class);
        when(mockEvent.getVenueMap()).thenReturn(venueMap);
        when(mockEvent.getCompanyId()).thenReturn(10);

        ProductionCompany mockCompany = mock(ProductionCompany.class);
        when(mockCompany.getStatus()).thenReturn(CompanyStatus.ACTIVE);
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(mockCompany);

        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        when(mockEventRepository.findById(EVENT_ID)).thenReturn(mockEvent);

        VenueMapDTO result = catalogService.getEventVenueMap(VALID_TOKEN, EVENT_ID);

        assertNotNull(result);
        assertEquals(5, result.venueMapId());
        assertEquals(1, result.inventoryZones().size());
        assertEquals(10, result.inventoryZones().get(0).getId());
        assertEquals("Floor", result.inventoryZones().get(0).getName());
        assertEquals(200, result.inventoryZones().get(0).getCapacity());
        assertEquals(50, result.inventoryZones().get(0).getPrice());
    }

    // UC-8: getEventVenueMap throws InvalidTokenException when credential is
    // invalid
    // (including expired). Phase 4.3 of the auth rework unified the rejection path
    // —
    // validateCredential collapses "expired" and "invalid" into a single false
    // return,
    // so Catalog can no longer distinguish the two. Callers who need that
    // distinction
    // should call AuthenticationService directly.
    @Test
    void givenInvalidOrExpiredCredential_whenGetEventVenueMap_thenThrowsInvalidTokenException() {
        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(false);

        assertThrows(InvalidTokenException.class,
                () -> catalogService.getEventVenueMap(VALID_TOKEN, EVENT_ID));
    }

    // UC-8: getEventVenueMap throws EventNotFoundException when event does not
    // exist
    @Test
    void givenValidTokenAndNonExistentEvent_whenGetEventVenueMap_thenThrowsEventNotFoundException() {
        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        when(mockEventRepository.findById(EVENT_ID)).thenReturn(null);

        assertThrows(EventNotFoundException.class,
                () -> catalogService.getEventVenueMap(VALID_TOKEN, EVENT_ID));
    }

    // UC-8: getEventVenueMap throws NullVenueMapException when event has no venue
    // map
    @Test
    void givenValidTokenAndEventWithNullVenueMap_whenGetEventVenueMap_thenThrowsNullVenueMapException() {
        Event mockEvent = mock(Event.class);
        when(mockEvent.getVenueMap()).thenReturn(null);
        when(mockEvent.getCompanyId()).thenReturn(10);

        ProductionCompany mockCompany = mock(ProductionCompany.class);
        when(mockCompany.getStatus()).thenReturn(CompanyStatus.ACTIVE);
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(mockCompany);

        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        when(mockEventRepository.findById(EVENT_ID)).thenReturn(mockEvent);

        assertThrows(NullVenueMapException.class,
                () -> catalogService.getEventVenueMap(VALID_TOKEN, EVENT_ID));
    }

    // UC-8: mapper preserves all zones and their insertion order for a multi-zone
    // venue map
    @Test
    void givenMultiZoneVenueMap_whenGetEventVenueMap_thenAllZonesReturnedInOrder() {
        InventoryZone zone1 = new StandingZone(1, "Floor", 100, 50);
        InventoryZone zone2 = new StandingZone(2, "Balcony", 80, 30);
        InventoryZone zone3 = new StandingZone(3, "VIP", 20, 150);
        VenueMap venueMap = new VenueMap(5, LOCATION, List.of(zone1, zone2, zone3));

        Event mockEvent = mock(Event.class);
        when(mockEvent.getVenueMap()).thenReturn(venueMap);
        when(mockEvent.getCompanyId()).thenReturn(10);

        ProductionCompany mockCompany = mock(ProductionCompany.class);
        when(mockCompany.getStatus()).thenReturn(CompanyStatus.ACTIVE);
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(mockCompany);

        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        when(mockEventRepository.findById(EVENT_ID)).thenReturn(mockEvent);

        VenueMapDTO result = catalogService.getEventVenueMap(VALID_TOKEN, EVENT_ID);

        assertEquals(3, result.inventoryZones().size());
        assertEquals(1, result.inventoryZones().get(0).getId());
        assertEquals(2, result.inventoryZones().get(1).getId());
        assertEquals(3, result.inventoryZones().get(2).getId());
    }

    // UC-8: a venue map with no zones still produces a valid (non-null, empty-list)
    // DTO
    @Test
    void givenZeroZoneVenueMap_whenGetEventVenueMap_thenReturnsDTOWithEmptyZoneList() {
        VenueMap venueMap = new VenueMap(5, LOCATION, List.of());

        Event mockEvent = mock(Event.class);
        when(mockEvent.getVenueMap()).thenReturn(venueMap);
        when(mockEvent.getCompanyId()).thenReturn(10);

        ProductionCompany mockCompany = mock(ProductionCompany.class);
        when(mockCompany.getStatus()).thenReturn(CompanyStatus.ACTIVE);
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(mockCompany);

        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        when(mockEventRepository.findById(EVENT_ID)).thenReturn(mockEvent);

        VenueMapDTO result = catalogService.getEventVenueMap(VALID_TOKEN, EVENT_ID);

        assertNotNull(result);
        assertNotNull(result.inventoryZones());
        assertTrue(result.inventoryZones().isEmpty());
    }

    // UC-8: null or blank token must raise InvalidTokenException, not
    // SessionExpiredException
    @Test
    void givenNullToken_whenGetEventVenueMap_thenThrowsInvalidTokenException() {
        when(mockSessionManager.validateCredential(null)).thenReturn(false);

        assertThrows(InvalidTokenException.class,
                () -> catalogService.getEventVenueMap(null, EVENT_ID));
    }

    @Test
    void givenBlankToken_whenGetEventVenueMap_thenThrowsInvalidTokenException() {
        when(mockSessionManager.validateCredential("")).thenReturn(false);

        assertThrows(InvalidTokenException.class,
                () -> catalogService.getEventVenueMap("", EVENT_ID));
    }

    @Test
    void givenSeatedZone_whenGetVenueMap_thenPerSeatStatusesReturned() {
        SeatedZone seatedZone = new SeatedZone(
                5,
                "Orchestra",
                120.0,
                List.of(
                        new Seat("A1", 0, 0),
                        new Seat("A2", 1, 0),
                        new Seat("A3", 2, 0)));

        seatedZone.reserve(InventorySelection.seated(List.of("A1"), "test-order"));
        seatedZone.reserve(InventorySelection.seated(List.of("A2"), "test-order"));
        seatedZone.confirmSale(InventorySelection.seated(List.of("A2"), "test-order"));

        VenueMap venueMap = new VenueMap(
                1,
                LOCATION,
                List.of(seatedZone));

        Event event = createMockEvent(EVENT_ID, 10);
        ProductionCompany company = createMockCompany("Company A", 4.5);

        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        when(mockEventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap()).thenReturn(venueMap);
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(company);
        when(company.getStatus()).thenReturn(CompanyStatus.ACTIVE);

        VenueMapDTO result = catalogService.getEventVenueMap(VALID_TOKEN, EVENT_ID);

        assertEquals(1, result.inventoryZones().size());

        InventoryZoneDTO zoneDTO = result.inventoryZones().get(0);

        assertEquals(5, zoneDTO.getId());
        assertEquals("Orchestra", zoneDTO.getName());
        assertEquals("SEATED", zoneDTO.getZoneType());
        assertEquals(3, zoneDTO.getCapacity());
        assertEquals(1, zoneDTO.getAvailableAmount());
        assertEquals(1, zoneDTO.getReservedAmount());
        assertEquals(1, zoneDTO.getSoldAmount());

        assertEquals(3, zoneDTO.getSeats().size());

        SeatDTO a1 = zoneDTO.getSeats().stream()
                .filter(seat -> seat.label().equals("A1"))
                .findFirst()
                .orElseThrow();

        SeatDTO a2 = zoneDTO.getSeats().stream()
                .filter(seat -> seat.label().equals("A2"))
                .findFirst()
                .orElseThrow();

        SeatDTO a3 = zoneDTO.getSeats().stream()
                .filter(seat -> seat.label().equals("A3"))
                .findFirst()
                .orElseThrow();

        assertEquals("RESERVED", a1.status());
        assertEquals("SOLD", a2.status());
        assertEquals("AVAILABLE", a3.status());
    }

    @Test
    void givenStandingZone_whenGetVenueMap_thenZoneCountAvailabilityReturned() {
        StandingZone standingZone = new StandingZone(5, "General Admission", 10, 50.0);
        standingZone.reserve(InventorySelection.standing(3));

        VenueMap venueMap = new VenueMap(
                1,
                LOCATION,
                List.of(standingZone));

        Event event = createMockEvent(EVENT_ID, 10);
        ProductionCompany company = createMockCompany("Company A", 4.5);

        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        when(mockEventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap()).thenReturn(venueMap);
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(company);
        when(company.getStatus()).thenReturn(CompanyStatus.ACTIVE);

        VenueMapDTO result = catalogService.getEventVenueMap(VALID_TOKEN, EVENT_ID);

        InventoryZoneDTO zoneDTO = result.inventoryZones().get(0);

        assertEquals("STANDING", zoneDTO.getZoneType());
        assertEquals(10, zoneDTO.getCapacity());
        assertEquals(7, zoneDTO.getAvailableAmount());
        assertEquals(3, zoneDTO.getReservedAmount());
        assertEquals(0, zoneDTO.getSoldAmount());
        assertTrue(zoneDTO.getSeats().isEmpty());
    }

    // -------------------------------------------------------------------------
    // #272 / UC-8: getEventDetail (public single-event read for the buyer page)
    // -------------------------------------------------------------------------

    // getEventDetail returns the full detail (incl. lineup) for an ON_SALE event of
    // an active company.
    @Test
    void givenValidCredentialAndOnSaleEvent_whenGetEventDetail_thenReturnsDetailWithLineup() {
        Event event = detailEvent(EVENT_ID, 10, EventStatus.ON_SALE);
        ProductionCompany company = createMockCompany("Acme Live", 4.5);

        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        when(mockEventRepository.findById(EVENT_ID)).thenReturn(event);
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(company);

        EventDetailDTO result = catalogService.getEventDetail(VALID_TOKEN, EVENT_ID);

        assertNotNull(result);
        assertEquals("Event " + EVENT_ID, result.name());
        assertEquals(EventStatus.ON_SALE, result.status());
        assertEquals("Acme Live", result.companyName());
        assertEquals(List.of("Artist A", "Artist B"), result.artistsNames());
        assertEquals("Brussels", result.location().city());
    }

    // A SOLD_OUT event is still publicly viewable (the page shows a badge +
    // disabled purchase).
    @Test
    void givenSoldOutEvent_whenGetEventDetail_thenReturnsDetail() {
        Event event = detailEvent(EVENT_ID, 10, EventStatus.SOLD_OUT);
        ProductionCompany company = createMockCompany("Acme Live", 4.5);

        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        when(mockEventRepository.findById(EVENT_ID)).thenReturn(event);
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(company);

        EventDetailDTO result = catalogService.getEventDetail(VALID_TOKEN, EVENT_ID);

        assertEquals(EventStatus.SOLD_OUT, result.status());
    }

    // Invalid/expired credential is rejected before any lookup.
    @Test
    void givenInvalidCredential_whenGetEventDetail_thenThrowsInvalidTokenException() {
        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(false);

        assertThrows(InvalidTokenException.class,
                () -> catalogService.getEventDetail(VALID_TOKEN, EVENT_ID));
    }

    // A missing event yields EventNotFoundException.
    @Test
    void givenNonExistentEvent_whenGetEventDetail_thenThrowsEventNotFoundException() {
        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        when(mockEventRepository.findById(EVENT_ID)).thenReturn(null);

        assertThrows(EventNotFoundException.class,
                () -> catalogService.getEventDetail(VALID_TOKEN, EVENT_ID));
    }

    // Events of a closed/inactive company are hidden.
    @Test
    void givenInactiveCompany_whenGetEventDetail_thenThrowsCompanyClosedException() {
        Event event = detailEvent(EVENT_ID, 10, EventStatus.ON_SALE);
        ProductionCompany company = mock(ProductionCompany.class);
        when(company.getStatus()).thenReturn(CompanyStatus.INACTIVE);

        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        when(mockEventRepository.findById(EVENT_ID)).thenReturn(event);
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(company);

        assertThrows(CompanyClosedException.class,
                () -> catalogService.getEventDetail(VALID_TOKEN, EVENT_ID));
    }

    // DRAFT / SCHEDULED events are not yet public — treated as not found.
    @Test
    void givenDraftEvent_whenGetEventDetail_thenThrowsEventNotFoundException() {
        Event event = detailEvent(EVENT_ID, 10, EventStatus.DRAFT);
        ProductionCompany company = createMockCompany("Acme Live", 4.5);

        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        when(mockEventRepository.findById(EVENT_ID)).thenReturn(event);
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(company);

        assertThrows(EventNotFoundException.class,
                () -> catalogService.getEventDetail(VALID_TOKEN, EVENT_ID));
    }

    // -------------------------------------------------------------------------
    // V2-LANDING-01: featured / upcomingOnSale (guest-facing landing rows)
    // -------------------------------------------------------------------------

    // featured returns the ON_SALE events of active companies, best-rated first,
    // capped at the limit.
    @Test
    void givenOnSaleEvents_whenFeatured_thenReturnsBestRatedFirstWithinLimit() {
        ProductionCompany company = createMockCompany("Acme", 4.5);
        when(company.getCompanyId()).thenReturn(10);
        Event low = onSaleEvent(1, 10, 3.0);
        Event high = onSaleEvent(2, 10, 5.0);
        Event mid = onSaleEvent(3, 10, 4.0);

        when(mockCompanyRepository.findActive()).thenReturn(List.of(company));
        when(mockEventRepository.findActiveByCompany(10)).thenReturn(List.of(low, high, mid));
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(company);

        List<EventSummaryDTO> results = catalogService.featured(2);

        assertEquals(2, results.size());
        assertEquals("Event 2", results.get(0).name()); // rating 5.0
        assertEquals("Event 3", results.get(1).name()); // rating 4.0
    }

    // upcomingOnSale returns SCHEDULED events of active companies only, soonest
    // show date first.
    @Test
    void givenScheduledEvents_whenUpcomingOnSale_thenSoonestFirstAndActiveCompaniesOnly() {
        ProductionCompany active = createMockCompany("Acme", 4.5);
        ProductionCompany inactive = mock(ProductionCompany.class);
        when(inactive.getStatus()).thenReturn(CompanyStatus.INACTIVE);

        Event later = scheduledEvent(2, 10, LocalDateTime.of(2026, 8, 1, 20, 0));
        Event soon = scheduledEvent(1, 10, LocalDateTime.of(2026, 7, 1, 20, 0));
        Event hidden = mock(Event.class); // belongs to an inactive company → excluded
        when(hidden.getCompanyId()).thenReturn(20);

        when(mockEventRepository.findByStatus(EventStatus.SCHEDULED))
                .thenReturn(List.of(later, soon, hidden));
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(active);
        when(mockCompanyRepository.getCompanyById(20)).thenReturn(inactive);

        List<EventSummaryDTO> results = catalogService.upcomingOnSale(5);

        assertEquals(2, results.size());
        assertEquals("Event 1", results.get(0).name()); // earliest show date
        assertEquals("Event 2", results.get(1).name());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // V2-SEARCH-01 (#281): top-bar search across events + artists + venues
    // -------------------------------------------------------------------------

    // A minimal ON_SALE event for search: name, company, artists, and a venue at
    // LOCATION
    // ("Brussels, Belgium"). search() never reads status/rating/category, so we
    // leave those unstubbed.
    private Event searchEvent(int id, int companyId, String name, List<String> artists) {
        Event e = mock(Event.class);
        when(e.getId()).thenReturn(id);
        when(e.getName()).thenReturn(name);
        when(e.getCompanyId()).thenReturn(companyId);
        when(e.getArtistsNames()).thenReturn(artists);
        when(e.getVenueMap()).thenReturn(new VenueMap(id, LOCATION, List.of()));
        return e;
    }

    @Test
    void givenInvalidCredential_whenSearch_thenThrowsInvalidTokenException() {
        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(false);

        assertThrows(InvalidTokenException.class,
                () -> catalogService.search(VALID_TOKEN, "coldplay", 8));
    }

    @Test
    void givenBlankQuery_whenSearch_thenReturnsEmpty() {
        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);

        assertTrue(catalogService.search(VALID_TOKEN, "   ", 8).isEmpty());
    }

    @Test
    void givenEventNameMatch_whenSearch_thenEventRowReturned() {
        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        Event e = searchEvent(1, 10, "Coldplay Live", List.of("Coldplay"));
        ProductionCompany company = createMockCompany("Live Nation", 4.5);
        when(mockEventRepository.findByStatus(EventStatus.ON_SALE)).thenReturn(List.of(e));
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(company);

        List<SearchResultDTO> results = catalogService.search(VALID_TOKEN, "coldplay live", 8);

        assertEquals(1, results.size());
        assertEquals("EVENT", results.get(0).type());
        assertEquals("Coldplay Live", results.get(0).title());
        assertEquals(1, results.get(0).eventId());
    }

    @Test
    void givenArtistMatch_whenSearch_thenArtistRowReturned() {
        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        Event e = searchEvent(2, 10, "Mystery Show", List.of("Coldplay", "Support Act"));
        ProductionCompany company = createMockCompany("Live Nation", 4.5);
        when(mockEventRepository.findByStatus(EventStatus.ON_SALE)).thenReturn(List.of(e));
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(company);

        List<SearchResultDTO> results = catalogService.search(VALID_TOKEN, "coldplay", 8);

        assertEquals(1, results.size());
        assertEquals("ARTIST", results.get(0).type());
        assertEquals("Coldplay", results.get(0).title());
        assertEquals(2, results.get(0).eventId());
    }

    @Test
    void givenVenueMatch_whenSearch_thenVenueRowReturned() {
        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        Event e = searchEvent(3, 10, "Some Gig", List.of("Nobody"));
        ProductionCompany company = createMockCompany("Live Nation", 4.5);
        when(mockEventRepository.findByStatus(EventStatus.ON_SALE)).thenReturn(List.of(e));
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(company);

        List<SearchResultDTO> results = catalogService.search(VALID_TOKEN, "brussels", 8);

        assertEquals(1, results.size());
        assertEquals("VENUE", results.get(0).type());
        assertEquals("Brussels, Belgium", results.get(0).title());
        assertEquals(3, results.get(0).eventId());
    }

    @Test
    void givenInactiveCompany_whenSearch_thenEventExcluded() {
        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        Event e = searchEvent(1, 10, "Coldplay Live", List.of("Coldplay"));
        when(mockEventRepository.findByStatus(EventStatus.ON_SALE)).thenReturn(List.of(e));
        ProductionCompany inactive = mock(ProductionCompany.class);
        when(inactive.getStatus()).thenReturn(CompanyStatus.INACTIVE);
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(inactive);

        assertTrue(catalogService.search(VALID_TOKEN, "coldplay", 8).isEmpty());
    }

    @Test
    void givenManyMatches_whenSearch_thenCappedToLimit() {
        when(mockSessionManager.validateCredential(VALID_TOKEN)).thenReturn(true);
        List<Event> many = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            many.add(searchEvent(i, 10, "Show " + i, List.of()));
        }
        ProductionCompany company = createMockCompany("Live Nation", 4.5);
        when(mockEventRepository.findByStatus(EventStatus.ON_SALE)).thenReturn(many);
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(company);

        assertEquals(3, catalogService.search(VALID_TOKEN, "show", 3).size());
    }

    // -------------------------------------------------------------------------
    // Browse filter options: onSaleCountries / onSaleCitiesInCountry
    // -------------------------------------------------------------------------

    @Test
    void onSaleCountries_distinctSortedAndVisibleOnly() {
        // Build mocks into locals BEFORE stubbing the repos — helper calls invoke when(...) themselves,
        // which Mockito rejects if nested inside an ongoing thenReturn(...) stub.
        Event e1 = locatedEvent(1, 10, "Israel", "Tel Aviv");
        Event e2 = locatedEvent(2, 10, "Israel", "Haifa");
        Event e3 = locatedEvent(3, 20, "USA", "New York");
        ProductionCompany a = createMockCompany("A", 4.0);
        ProductionCompany b = createMockCompany("B", 4.0);
        when(mockEventRepository.searchONSALE(any())).thenReturn(List.of(e1, e2, e3));
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(a);
        when(mockCompanyRepository.getCompanyById(20)).thenReturn(b);

        assertEquals(List.of("Israel", "USA"), catalogService.onSaleCountries());
    }

    @Test
    void onSaleCountries_excludesInactiveCompanyEvents() {
        Event e1 = locatedEvent(1, 10, "Israel", "Tel Aviv");
        Event e2 = locatedEvent(2, 20, "USA", "New York");
        ProductionCompany active = createMockCompany("A", 4.0);
        ProductionCompany inactive = mock(ProductionCompany.class);
        when(inactive.getStatus()).thenReturn(CompanyStatus.INACTIVE);
        when(mockEventRepository.searchONSALE(any())).thenReturn(List.of(e1, e2));
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(active);
        when(mockCompanyRepository.getCompanyById(20)).thenReturn(inactive);

        assertEquals(List.of("Israel"), catalogService.onSaleCountries());
    }

    @Test
    void onSaleCountries_skipsEventsWithoutLocation() {
        Event noLoc = mock(Event.class);
        when(noLoc.getCompanyId()).thenReturn(10);
        when(noLoc.getVenueMap()).thenReturn(null);
        ProductionCompany a = createMockCompany("A", 4.0);
        when(mockEventRepository.searchONSALE(any())).thenReturn(List.of(noLoc));
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(a);

        assertTrue(catalogService.onSaleCountries().isEmpty());
    }

    @Test
    void onSaleCitiesInCountry_filtersToCountryDistinctSorted() {
        Event e1 = locatedEvent(1, 10, "Israel", "Tel Aviv");
        Event e2 = locatedEvent(2, 10, "Israel", "Haifa");
        Event e4 = locatedEvent(4, 10, "Israel", "Tel Aviv");   // duplicate city
        Event e3 = locatedEvent(3, 20, "USA", "New York");
        ProductionCompany a = createMockCompany("A", 4.0);
        ProductionCompany b = createMockCompany("B", 4.0);
        when(mockEventRepository.searchONSALE(any())).thenReturn(List.of(e1, e2, e4, e3));
        when(mockCompanyRepository.getCompanyById(10)).thenReturn(a);
        when(mockCompanyRepository.getCompanyById(20)).thenReturn(b);

        assertEquals(List.of("Haifa", "Tel Aviv"), catalogService.onSaleCitiesInCountry("Israel"));
    }

    @Test
    void onSaleCitiesInCountry_nullCountry_isEmpty() {
        assertTrue(catalogService.onSaleCitiesInCountry(null).isEmpty());
    }

    private Event locatedEvent(int id, int companyId, String country, String city) {
        Event e = mock(Event.class);
        when(e.getCompanyId()).thenReturn(companyId);
        when(e.getVenueMap()).thenReturn(new VenueMap(id, new Location(country, city), List.of()));
        return e;
    }

    private CatalogSearchFiltersDTO emptyFilters() {
        return new CatalogSearchFiltersDTO(
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private Event createMockEvent(int id, int companyId) {
        Event mockEvent = mock(Event.class);
        when(mockEvent.getId()).thenReturn(id);
        when(mockEvent.getName()).thenReturn("Event " + id);
        when(mockEvent.getStatus()).thenReturn(EventStatus.ON_SALE);
        when(mockEvent.getRating()).thenReturn(4.0);
        when(mockEvent.getCategory()).thenReturn(EventCategory.MUSIC);
        when(mockEvent.getCompanyId()).thenReturn(companyId);
        InventoryZone zone = new StandingZone(1, "Floor", 100, 50);
        VenueMap venueMap = new VenueMap(id, LOCATION, List.of(zone));
        when(mockEvent.getVenueMap()).thenReturn(venueMap);
        when(mockEvent.getShowDates()).thenReturn(List.of());
        return mockEvent;
    }

    private ProductionCompany createMockCompany(String name, double rating) {
        ProductionCompany mockCompany = mock(ProductionCompany.class);
        when(mockCompany.getName()).thenReturn(name);
        when(mockCompany.getRating()).thenReturn(rating);
        when(mockCompany.getStatus()).thenReturn(CompanyStatus.ACTIVE);
        return mockCompany;
    }

    // A bare event carrying only a rating — company rating is derived from these (CompanyRatings).
    private Event eventRated(double rating) {
        Event e = mock(Event.class);
        when(e.getRating()).thenReturn(rating);
        return e;
    }

    // A fully-stubbed event for getEventDetail tests (mapper reads
    // name/desc/rating/category/
    // lineup/location/showDates/status).
    private Event detailEvent(int id, int companyId, EventStatus status) {
        Event mockEvent = mock(Event.class);
        when(mockEvent.getId()).thenReturn(id);
        when(mockEvent.getName()).thenReturn("Event " + id);
        when(mockEvent.getStatus()).thenReturn(status);
        when(mockEvent.getRating()).thenReturn(4.5);
        when(mockEvent.getDescription()).thenReturn("Description for event " + id);
        when(mockEvent.getCategory()).thenReturn(EventCategory.MUSIC);
        when(mockEvent.getCompanyId()).thenReturn(companyId);
        when(mockEvent.getArtistsNames()).thenReturn(List.of("Artist A", "Artist B"));
        when(mockEvent.getVenueMap()).thenReturn(new VenueMap(id, LOCATION, List.of()));
        when(mockEvent.getShowDates()).thenReturn(List.of());
        return mockEvent;
    }

    // An ON_SALE event with an explicit rating (for featured ordering). No show
    // dates needed —
    // the mapper handles an empty schedule.
    private Event onSaleEvent(int id, int companyId, Double rating) {
        Event mockEvent = mock(Event.class);
        when(mockEvent.getId()).thenReturn(id);
        when(mockEvent.getName()).thenReturn("Event " + id);
        when(mockEvent.getStatus()).thenReturn(EventStatus.ON_SALE);
        when(mockEvent.getRating()).thenReturn(rating);
        when(mockEvent.getCategory()).thenReturn(EventCategory.MUSIC);
        when(mockEvent.getCompanyId()).thenReturn(companyId);
        when(mockEvent.getVenueMap()).thenReturn(
                new VenueMap(id, LOCATION, List.of(new StandingZone(1, "Floor", 100, 50))));
        when(mockEvent.getShowDates()).thenReturn(List.of());
        return mockEvent;
    }

    // A SCHEDULED event with a single show date at {start} (for upcomingOnSale
    // ordering).
    private Event scheduledEvent(int id, int companyId, LocalDateTime start) {
        Event mockEvent = mock(Event.class);
        when(mockEvent.getId()).thenReturn(id);
        when(mockEvent.getName()).thenReturn("Event " + id);
        when(mockEvent.getStatus()).thenReturn(EventStatus.SCHEDULED);
        when(mockEvent.getRating()).thenReturn(4.0);
        when(mockEvent.getCategory()).thenReturn(EventCategory.MUSIC);
        when(mockEvent.getCompanyId()).thenReturn(companyId);
        when(mockEvent.getVenueMap()).thenReturn(
                new VenueMap(id, LOCATION, List.of(new StandingZone(1, "Floor", 100, 50))));
        ShowDate showDate = mock(ShowDate.class);
        when(showDate.getStartTime()).thenReturn(start);
        when(showDate.getEndTime()).thenReturn(start.plusHours(2));
        when(mockEvent.getShowDates()).thenReturn(List.of(showDate));
        return mockEvent;
    }
}
