package com.ticketing.system.unit.application;

import com.ticketing.system.testutil.CompanyRepositories;
import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.shared.dto.ReservationResultDTO;
import org.springframework.context.ApplicationEventPublisher;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.shared.metrics.ISystemMetrics;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.sales.application.port.out.MarketGate;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.catalog.domain.DiscountPolicy;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.application.service.InventoryService;
import com.ticketing.system.catalog.domain.InventoryZone;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.SeatedZone;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.catalog.domain.VenueMap;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;
import com.ticketing.system.testutil.TestTransactions;
import com.ticketing.system.testutil.UserRepositories;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link ReservationService}'s reserve and remove (UC-9, II.2.5, II.2.7) through its public methods, with its
 * outbound ports doubled and the real {@link InventoryService} beneath it: what the buyer's cart and the zone
 * hold after each call. A plain unit suite, so it lives here rather than under {@code acceptance/}, whose
 * classes boot Spring (NaimElijah/Ticketeer#189); {@link ReservationServiceTest} holds the service's other unit
 * tests.
 */
class ReservationServiceOutcomesTest {

    // Holds are stamped and judged at this instant, as UTC wall time from the injected clock (ADR-008).
    private static final Instant T0 = Instant.parse("2027-07-15T17:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(T0, ZoneOffset.UTC);
    // The age of every member these tests reserve for; no event here has an age policy.
    private static final int MEMBER_AGE = 30;
    private static final int EVENT_ID = 100;
    private static final int COMPANY_ID = 100;

    private ReservationService reservationService;

    private EventRepository eventRepository;
    private ActiveOrderRepository activeOrderRepository;
    private SessionManager sessionManager;
    private ApplicationEventPublisher eventPublisher;

    private Event event;
    private InventoryZone zone;
    private ActiveOrder activeOrder;

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        activeOrderRepository = mock(ActiveOrderRepository.class);
        sessionManager = mock(SessionManager.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        MarketGate marketGate = mock(MarketGate.class);
        when(marketGate.isOpen()).thenReturn(true);

        // Catalog owns inventory mutation behind InventoryCommandPort; wire the real InventoryService
        // onto the same mock event store so the existing event/zone stubs drive behaviour a layer down.
        InventoryService inventoryService = new InventoryService(
                eventRepository, CompanyRepositories.everyCompanyWithoutAPolicy(), TestTransactions.recordingManager());
        reservationService = new ReservationService(
                inventoryService,
                activeOrderRepository,
                sessionManager,
                eventPublisher,
                UserRepositories.everyMemberExistsAged(MEMBER_AGE),
                marketGate,
                mock(ISystemMetrics.class),
                Clock.fixed(T0, ZoneOffset.UTC)
        );

        event = mock(Event.class, RETURNS_DEEP_STUBS);
        zone = mock(InventoryZone.class);
        activeOrder = new ActiveOrder(1, NOW);

        when(sessionManager.validateToken("validToken")).thenReturn(true);
        when(sessionManager.extractUserId("validToken")).thenReturn(1);
        // "guest-session" names a live guest session; reserveForGuest accepts only those (#185).
        when(sessionManager.validateCredential("guest-session")).thenReturn(true);
        when(sessionManager.validateGuestSession("guest-session")).thenReturn(true);

        when(eventRepository.findById(100)).thenReturn(event);
        when(event.getVenueMap().getZone(1)).thenReturn(zone);

        when(zone.getAvailableAmount()).thenReturn(10);
        when(zone.getprice()).thenReturn(50.0);

        when(activeOrderRepository.getByUserId(1)).thenReturn(activeOrder);
    }

    @Test
    void GivenZoneNotFound_WhenReserveStandingTicketsForMember_ThenThrowException() {
        when(event.getVenueMap().getZone(1)).thenThrow(new IllegalArgumentException("Zone not found: " + 1));

        String result;

        try {
            reservationService.reserveForMember(
                    "validToken",
                    100,
                    1,
                    InventorySelectionDTO.standing(2)
            );

            result = "NO_EXCEPTION";

        } catch (Exception e) {
            result = e.getMessage();
        }

        assertEquals("Zone not found: 1", result);
    }

 
    @Test
    void GivenMissingGuestSession_WhenReserveTicketsForGuest_ThenThrowException() {
        String result;

        try {
            reservationService.reserveForGuest(
                    "",
                    100,
                    1,
                    InventorySelectionDTO.standing(3)
            );

            result = "NO_EXCEPTION";

        } catch (Exception e) {
            result = e.getMessage();
        }

        assertEquals("Missing session ID", result);
    }

    @Test
    void GivenValidReservedTickets_WhenRemoveReservedStandingSpotsForMember_ThenReturnReservationResult() {
        activeOrder.addStandingReservation(100, 1, 3, 50.0, NOW);

        ReservationResultDTO result = reservationService.removeForMember(
                "validToken",
                100,
                1,
                InventorySelectionDTO.standing(2)
        );

        assertEquals(2, result.getQuantity());
    }


    
    @Test
    void GivenNoActiveOrder_WhenRemoveReservedStandingSpotsForMember_ThenThrowException() {
        when(activeOrderRepository.getByUserId(1)).thenReturn(null);

        String result;

        try {
            reservationService.removeForMember(
                    "validToken",
                    100,
                    1,
                    InventorySelectionDTO.standing(1)
            );

            result = "NO_EXCEPTION";

        } catch (Exception e) {
            result = e.getMessage();
        }

        assertEquals("Active order not found", result);
    }




    // Seated venue maps: a real event on sale holds the real SeatedZone, so these run the event's own
    // reservation checks and the zone's per-seat locking.

    // A real event on sale whose venue holds the zone given; the event port hands it out.
    private Event givenEventOnSaleWith(InventoryZone inventoryZone) {
        Event onSale = new Event(EVENT_ID, "Concert", 4.5, List.of("Artist"), EventCategory.CONCERT, COMPANY_ID,
                EventStatus.SCHEDULED, new VenueMap(1, new Location("Israel", "Tel Aviv"), List.of(inventoryZone)),
                List.of(new ShowDate(NOW.plusDays(10), NOW.plusDays(10).plusHours(2))), ZoneId.of("Asia/Jerusalem"),
                new NoPurchasePolicy(), new DiscountPolicy(0));
        onSale.transitionToOnSale();
        when(eventRepository.findById(EVENT_ID)).thenReturn(onSale);
        return onSale;
    }
}
