package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ticketing.system.catalog.application.dto.EventCreationDTO;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.application.port.out.EventSalesHistoryPort;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.application.service.EventDisplayQueryService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.catalog.config.VenueLimitsProperties;
import com.ticketing.system.catalog.domain.DiscountPolicy;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.catalog.domain.StandingZone;
import com.ticketing.system.catalog.domain.VenueMap;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.organization.adapter.out.persistence.MemoryCompanyAppointmentRepository;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.application.service.CompanyMembershipService;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;
import com.ticketing.system.shared.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.shared.exception.CompanyNotFoundException;
import com.ticketing.system.shared.exception.EventNotFoundException;

/**
 * A repository that fails is not a missing record (#187). The catalog services looked up companies and
 * events inside {@code catch (RuntimeException e)}, so a database outage came out as "company not
 * found", as a company that is not publicly visible, or as an unknown event, and nothing reported it.
 * Each path must let an unexpected failure out as it is, and keep its answer for the not-found
 * exception the adapters actually throw.
 */
class CatalogRepositoryFailurePropagationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-31T12:00:00Z"), ZoneOffset.UTC);
    private static final String TOKEN = "owner-token";
    private static final int OWNER_ID = 1;
    private static final int COMPANY_ID = 100;
    private static final int EVENT_ID = 10;
    private static final int ZONE_ID = 1;
    private static final LocalDateTime SHOW_START = LocalDateTime.of(2099, 6, 1, 18, 0);
    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");
    private static final VenueLimitsProperties SHIPPED_VENUE_LIMITS =
            new VenueLimitsProperties(12, 49_500, 1_000_000, 100_000);

    private final EventRepository events = mock(EventRepository.class);
    private final ProductionCompanyRepository companies = mock(ProductionCompanyRepository.class);
    private final SessionManager sessions = mock(SessionManager.class);
    private final UserRepository users = mock(UserRepository.class);

    private final DataAccessResourceFailureException outage =
            new DataAccessResourceFailureException("database unreachable");

    private EventManagementService eventManagement;
    private CatalogService catalog;
    private EventDisplayQueryService eventDisplay;

    @BeforeEach
    void setUp() {
        eventManagement = new EventManagementService(events, companies, sessions, mock(EventSalesHistoryPort.class),
                users, new CompanyMembershipService(new MemoryCompanyAppointmentRepository(), CLOCK),
                mock(ApplicationEventPublisher.class), CLOCK, SHIPPED_VENUE_LIMITS);
        catalog = new CatalogService(sessions, events, companies);
        eventDisplay = new EventDisplayQueryService(events);

        when(sessions.validateToken(TOKEN)).thenReturn(true);
        when(sessions.extractUserId(TOKEN)).thenReturn(OWNER_ID);
        when(sessions.validateCredential(TOKEN)).thenReturn(true);
        when(users.getUserById(OWNER_ID)).thenReturn(new User(OWNER_ID, "owner", "owner@example.com", "hash", 40));
    }

    // ---- EventManagementService.addEvent: the owning company's lookup ----

    @Test
    void GivenTheCompanyStoreFails_WhenAnEventIsAdded_ThenTheFailurePropagates() {
        when(companies.getCompanyById(COMPANY_ID)).thenThrow(outage);

        assertSame(outage, assertThrows(RuntimeException.class,
                () -> eventManagement.addEvent(TOKEN, creationRequest())),
                "a database failure must not be reported as a missing company");
    }

    @Test
    void GivenNoSuchCompany_WhenAnEventIsAdded_ThenCompanyNotFound() {
        when(companies.getCompanyById(COMPANY_ID)).thenThrow(new CompanyNotFoundException(COMPANY_ID));

        assertThrows(CompanyNotFoundException.class, () -> eventManagement.addEvent(TOKEN, creationRequest()));
    }

    // ---- EventManagementService venue edits: the company lookup that authorises them ----

    @Test
    void GivenTheCompanyStoreFails_WhenAZoneIsRemoved_ThenTheFailurePropagates() {
        when(companies.getCompanyById(COMPANY_ID)).thenThrow(outage);

        assertSame(outage, assertThrows(RuntimeException.class,
                () -> eventManagement.removeInventoryZone(TOKEN, COMPANY_ID, EVENT_ID, ZONE_ID)),
                "a database failure must not be reported as a missing company");
    }

    @Test
    void GivenNoSuchCompany_WhenAZoneIsRemoved_ThenCompanyNotFound() {
        when(companies.getCompanyById(COMPANY_ID)).thenThrow(new CompanyNotFoundException(COMPANY_ID));

        assertThrows(CompanyNotFoundException.class,
                () -> eventManagement.removeInventoryZone(TOKEN, COMPANY_ID, EVENT_ID, ZONE_ID));
    }

    // ---- CatalogService: a company's public visibility ----

    @Test
    void GivenTheCompanyStoreFails_WhenUpcomingEventsAreListed_ThenTheFailurePropagates() {
        when(events.findByStatus(EventStatus.SCHEDULED)).thenReturn(List.of(scheduledEvent()));
        when(companies.getCompanyById(COMPANY_ID)).thenThrow(outage);

        assertSame(outage, assertThrows(RuntimeException.class, () -> catalog.upcomingOnSale(10)),
                "a database failure must not hide the company's events as if it were not public");
    }

    @Test
    void GivenTheEventsCompanyIsMissing_WhenUpcomingEventsAreListed_ThenTheEventIsLeftOut() {
        when(events.findByStatus(EventStatus.SCHEDULED)).thenReturn(List.of(scheduledEvent()));
        when(companies.getCompanyById(COMPANY_ID)).thenThrow(new CompanyNotFoundException(COMPANY_ID));

        assertTrue(catalog.upcomingOnSale(10).isEmpty(), "an event of a missing company is not public");
    }

    // ---- CatalogService: a company-scoped search's company ----

    @Test
    void GivenTheCompanyStoreFails_WhenACompanyIsSearched_ThenTheFailurePropagates() {
        when(companies.getCompanyById(COMPANY_ID)).thenThrow(outage);

        assertSame(outage, assertThrows(RuntimeException.class,
                () -> catalog.searchByCompany(TOKEN, COMPANY_ID, CatalogSearchFiltersDTO.empty())),
                "a database failure must not be reported as a missing company");
    }

    @Test
    void GivenNoSuchCompany_WhenACompanyIsSearched_ThenCompanyNotFound() {
        when(companies.getCompanyById(COMPANY_ID)).thenThrow(new CompanyNotFoundException(COMPANY_ID));

        assertThrows(CompanyNotFoundException.class,
                () -> catalog.searchByCompany(TOKEN, COMPANY_ID, CatalogSearchFiltersDTO.empty()));
    }

    // ---- EventDisplayQueryService: the event another context displays ----

    @Test
    void GivenTheEventStoreFails_WhenAnEventIsDescribed_ThenTheFailurePropagates() {
        when(events.findById(EVENT_ID)).thenThrow(outage);

        assertSame(outage, assertThrows(RuntimeException.class, () -> eventDisplay.describeEvent(EVENT_ID)),
                "a database failure must not be reported as an unknown event");
    }

    @Test
    void GivenNoSuchEvent_WhenAnEventIsDescribed_ThenNothingIsDescribed() {
        when(events.findById(EVENT_ID)).thenThrow(new EventNotFoundException(EVENT_ID));

        assertNull(eventDisplay.describeEvent(EVENT_ID));
    }

    private static EventCreationDTO creationRequest() {
        return new EventCreationDTO(COMPANY_ID, "Gala", "desc", List.of("Artist A"), EventCategory.CONCERT, 4.5,
                new Location("Belgium", "Brussels"), List.of(new ShowDate(SHOW_START, SHOW_START.plusHours(3))),
                BRUSSELS, null);
    }

    private static Event scheduledEvent() {
        return new Event(EVENT_ID, "Upcoming", 4.0, List.of("Artist A"), EventCategory.CONCERT, COMPANY_ID,
                EventStatus.SCHEDULED,
                new VenueMap(EVENT_ID, new Location("Belgium", "Brussels"),
                        List.of(new StandingZone(ZONE_ID, "Floor", 100, 50))),
                List.of(new ShowDate(SHOW_START, SHOW_START.plusHours(3))), BRUSSELS,
                new NoPurchasePolicy(), new DiscountPolicy(0));
    }
}
