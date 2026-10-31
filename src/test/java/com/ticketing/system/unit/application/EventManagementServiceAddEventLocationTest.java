package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

import com.ticketing.system.catalog.application.dto.EventCreationDTO;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.application.port.out.EventSalesHistoryPort;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.catalog.config.VenueLimitsProperties;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.organization.adapter.out.persistence.MemoryCompanyAppointmentRepository;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.application.service.CompanyMembershipService;
import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.shared.exception.DomainException;

/**
 * An event is refused without a venue location, before anything is saved (#187). {@code addEvent} built
 * the event's venue map from the request's location unchecked, and the DTO mappers dereference it, so
 * one event stored without a location broke every listing that maps it.
 */
class EventManagementServiceAddEventLocationTest {

    private static final String OWNER_TOKEN = "owner-token";
    private static final int OWNER_ID = 1;
    private static final int COMPANY_ID = 100;
    private static final int NEW_EVENT_ID = 11;
    private static final int NEW_VENUE_MAP_ID = 2;
    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
    private static final LocalDateTime SHOW_START = LocalDateTime.of(2099, 6, 1, 18, 0);
    private static final VenueLimitsProperties SHIPPED_VENUE_LIMITS =
            new VenueLimitsProperties(12, 49_500, 1_000_000, 100_000);

    private final EventRepository events = mock(EventRepository.class);
    private final ProductionCompanyRepository companies = mock(ProductionCompanyRepository.class);
    private final SessionManager sessions = mock(SessionManager.class);
    private final UserRepository users = mock(UserRepository.class);

    private EventManagementService eventManagement;

    @BeforeEach
    void anOwnerWhoMayAddEvents() {
        CompanyMembershipService membership =
                new CompanyMembershipService(new MemoryCompanyAppointmentRepository(), Clock.systemUTC());
        membership.addFounderAppointment(OWNER_ID, COMPANY_ID);
        eventManagement = new EventManagementService(events, companies, sessions, mock(EventSalesHistoryPort.class),
                users, membership, mock(ApplicationEventPublisher.class), Clock.fixed(NOW, ZoneOffset.UTC),
                SHIPPED_VENUE_LIMITS);

        when(sessions.validateToken(OWNER_TOKEN)).thenReturn(true);
        when(sessions.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
        when(users.getUserById(OWNER_ID)).thenReturn(new User(OWNER_ID, "owner", "owner@example.com", "hash", 40));
        when(companies.getCompanyById(COMPANY_ID)).thenReturn(
                new ProductionCompany(COMPANY_ID, OWNER_ID, "Company", CompanyStatus.ACTIVE, "desc", 4.5));
        when(events.nextId()).thenReturn(NEW_EVENT_ID);
        when(events.nextVenueMapId()).thenReturn(NEW_VENUE_MAP_ID);
    }

    @Test
    void GivenNoVenueLocation_WhenAnEventIsAdded_ThenItIsRefusedAndNothingIsSaved() {
        EventCreationDTO withoutLocation = new EventCreationDTO(COMPANY_ID, "Gala", "desc", List.of("Artist A"),
                EventCategory.CONCERT, 4.5, null, List.of(new ShowDate(SHOW_START, SHOW_START.plusHours(3))),
                ZoneId.of("Europe/Brussels"), null);

        RuntimeException refusal = assertThrows(RuntimeException.class,
                () -> eventManagement.addEvent(OWNER_TOKEN, withoutLocation));

        assertTrue(refusal instanceof IllegalArgumentException || refusal instanceof DomainException,
                "a missing location should be refused as invalid input, but got " + refusal);
        verify(events, never()).save(any());
    }
}
