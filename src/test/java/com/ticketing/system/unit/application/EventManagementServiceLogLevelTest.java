package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import ch.qos.logback.classic.Level;

import com.ticketing.system.catalog.application.dto.EventCreationDTO;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.application.port.out.EventSalesHistoryPort;
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
import com.ticketing.system.shared.exception.CompanyNotFoundException;
import com.ticketing.system.shared.exception.RefundFailedException;
import com.ticketing.system.testutil.LogCapture;

/**
 * {@code EventManagementService} leaves ERROR to whoever handles a failure it rethrows (#187). A failure
 * it logs at ERROR and rethrows is logged again by its caller and by {@code LoggingAspect}, so one
 * refund failure paged as several; a missing company is the caller's mistake, not an actionable fault.
 */
class EventManagementServiceLogLevelTest {

    private static final String OWNER_TOKEN = "owner-token";
    private static final int OWNER_ID = 1;
    private static final int COMPANY_ID = 100;
    private static final int EVENT_ID = 10;
    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
    private static final LocalDateTime SHOW_START = LocalDateTime.of(2099, 6, 1, 18, 0);
    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");
    private static final VenueLimitsProperties SHIPPED_VENUE_LIMITS =
            new VenueLimitsProperties(12, 49_500, 1_000_000, 100_000);

    private final EventRepository events = mock(EventRepository.class);
    private final ProductionCompanyRepository companies = mock(ProductionCompanyRepository.class);
    private final SessionManager sessions = mock(SessionManager.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

    private EventManagementService eventManagement;
    private LogCapture serviceLog;

    @BeforeEach
    void anOwnerOfAScheduledEvent() {
        CompanyMembershipService membership =
                new CompanyMembershipService(new MemoryCompanyAppointmentRepository(), Clock.systemUTC());
        membership.addFounderAppointment(OWNER_ID, COMPANY_ID);
        eventManagement = new EventManagementService(events, companies, sessions, mock(EventSalesHistoryPort.class),
                users, membership, publisher, Clock.fixed(NOW, ZoneOffset.UTC), SHIPPED_VENUE_LIMITS);

        when(sessions.validateToken(OWNER_TOKEN)).thenReturn(true);
        when(sessions.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
        when(users.getUserById(OWNER_ID)).thenReturn(new User(OWNER_ID, "owner", "owner@example.com", "hash", 40));
        when(events.findById(EVENT_ID)).thenReturn(scheduledEvent());
        serviceLog = LogCapture.of(EventManagementService.class);
    }

    @AfterEach
    void detachLog() {
        serviceLog.close();
    }

    @Test
    void GivenTheRefundFails_WhenTheEventIsCancelled_ThenTheFailurePropagatesWithoutAnErrorLoggedHere() {
        RefundFailedException refundFailure = new RefundFailedException(99, "gateway declined");
        doThrow(refundFailure).when(publisher).publishEvent(any(Object.class));

        assertSame(refundFailure, assertThrows(RefundFailedException.class,
                () -> eventManagement.cancelEventAndRefund(OWNER_TOKEN, EVENT_ID)));

        assertNoErrorLogged();
    }

    @Test
    void GivenTheCancellationFailsUnexpectedly_WhenTheEventIsCancelled_ThenTheFailurePropagatesWithoutAnErrorLoggedHere() {
        IllegalStateException unexpected = new IllegalStateException("listener broke");
        doThrow(unexpected).when(publisher).publishEvent(any(Object.class));

        assertSame(unexpected, assertThrows(IllegalStateException.class,
                () -> eventManagement.cancelEventAndRefund(OWNER_TOKEN, EVENT_ID)));

        assertNoErrorLogged();
    }

    @Test
    void GivenNoSuchCompany_WhenAnEventIsAdded_ThenNoErrorIsLogged() {
        when(companies.getCompanyById(COMPANY_ID)).thenThrow(new CompanyNotFoundException(COMPANY_ID));

        assertThrows(CompanyNotFoundException.class, () -> eventManagement.addEvent(OWNER_TOKEN, creationRequest()));

        assertNoErrorLogged();
    }

    private void assertNoErrorLogged() {
        List<String> errors = serviceLog.messagesAt(Level.ERROR);
        assertTrue(errors.isEmpty(), "EventManagementService should log no ERROR for a failure it rethrows; got " + errors);
    }

    private static EventCreationDTO creationRequest() {
        return new EventCreationDTO(COMPANY_ID, "Gala", "desc", List.of("Artist A"), EventCategory.CONCERT, 4.5,
                new Location("Belgium", "Brussels"), List.of(new ShowDate(SHOW_START, SHOW_START.plusHours(3))),
                BRUSSELS, null);
    }

    private static Event scheduledEvent() {
        return new Event(EVENT_ID, "Gala", 4.0, List.of("Artist A"), EventCategory.CONCERT, COMPANY_ID,
                EventStatus.SCHEDULED,
                new VenueMap(EVENT_ID, new Location("Belgium", "Brussels"),
                        List.of(new StandingZone(1, "Floor", 100, 50))),
                List.of(new ShowDate(SHOW_START, SHOW_START.plusHours(3))), BRUSSELS,
                new NoPurchasePolicy(), new DiscountPolicy(0));
    }
}
