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
import com.ticketing.system.catalog.domain.InventorySelection;
import com.ticketing.system.catalog.domain.InventoryZone;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.catalog.domain.StandingZone;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link ReservationService}'s reserve and remove (UC-9, II.2.5, II.2.7) through its public methods, with its
 * outbound ports doubled and the real {@link InventoryService} beneath it: what the buyer's cart and the zone
 * hold after each call. A plain unit suite, so it lives here rather than under {@code acceptance/}, whose
 * classes boot Spring (NaimElijah/Ticketeer#189); {@link ReservationServiceTest} holds the service's other unit
 * tests. Two of these overlap a test there, with stronger checks on what the cart and the zone hold afterwards.
 */
class ReservationServiceOutcomesTest {

    // Holds are stamped and judged at this instant, as UTC wall time from the injected clock (ADR-008).
    private static final Instant T0 = Instant.parse("2027-07-15T17:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(T0, ZoneOffset.UTC);
    // The age of every member these tests reserve for; no event here has an age policy.
    private static final int MEMBER_AGE = 30;
    private static final int EVENT_ID = 100;
    private static final int COMPANY_ID = 100;
    private static final int ZONE_ID = 1;
    // A zone of the event that is not ZONE_ID, for a venue without the zone a request names.
    private static final int OTHER_ZONE_ID = 2;
    private static final int USER_ID = 1;
    private static final String MEMBER_TOKEN = "validToken";
    private static final double PRICE = 50.0;

    private ReservationService reservationService;

    private EventRepository eventRepository;
    private ActiveOrderRepository activeOrderRepository;
    private SessionManager sessionManager;

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        activeOrderRepository = mock(ActiveOrderRepository.class);
        sessionManager = mock(SessionManager.class);

        MarketGate marketGate = mock(MarketGate.class);
        when(marketGate.isOpen()).thenReturn(true);

        // Catalog owns inventory mutation behind InventoryCommandPort. The REAL InventoryService runs over
        // the mock event port, which hands out real events, so every reservation reaches a real zone.
        InventoryService inventoryService = new InventoryService(
                eventRepository, CompanyRepositories.everyCompanyWithoutAPolicy(), TestTransactions.recordingManager());
        reservationService = new ReservationService(
                inventoryService,
                activeOrderRepository,
                sessionManager,
                mock(ApplicationEventPublisher.class),
                UserRepositories.everyMemberExistsAged(MEMBER_AGE),
                marketGate,
                mock(ISystemMetrics.class),
                Clock.fixed(T0, ZoneOffset.UTC)
        );

        when(sessionManager.validateToken(MEMBER_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(MEMBER_TOKEN)).thenReturn(USER_ID);
    }

    // The venue map refuses an unknown zone without naming it; the inventory service names it, so the
    // buyer's refusal says which zone was not found.
    @Test
    void GivenAZoneTheEventDoesNotHave_WhenReserveForMember_ThenRefusedNamingTheZoneAndNothingIsHeldOrSaved() {
        StandingZone balcony = new StandingZone(OTHER_ZONE_ID, "Balcony", 10, PRICE);
        givenEventOnSaleWith(balcony);
        ActiveOrder cart = new ActiveOrder(USER_ID, NOW);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(cart);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> reservationService.reserveForMember(MEMBER_TOKEN, EVENT_ID, ZONE_ID,
                        InventorySelectionDTO.standing(2)));

        assertEquals("Zone not found: " + ZONE_ID, refused.getMessage());
        assertEquals(0, balcony.getReservedAmount(), "a refused reservation must hold nothing");
        assertTrue(cart.isEmpty(), "a refused reservation must add no cart line");
        verify(activeOrderRepository, never()).save(any());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void GivenAnEmptySessionId_WhenReserveForGuest_ThenRefusedAsMissingAndNothingIsTouched() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> reservationService.reserveForGuest("", EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(3)));

        assertEquals("Missing session ID", refused.getMessage());
        verify(sessionManager, never()).validateGuestSession(any());
        verifyNoInteractions(activeOrderRepository, eventRepository);
    }

    @Test
    void GivenAMemberHoldingThree_WhenRemoveTwoForMember_ThenOneStaysHeldAndInTheCart() {
        StandingZone vip = new StandingZone(ZONE_ID, "VIP", 10, PRICE);
        givenEventOnSaleWith(vip);
        ActiveOrder cart = new ActiveOrder(USER_ID, NOW);
        cart.addStandingReservation(EVENT_ID, ZONE_ID, 3, PRICE, NOW);
        vip.reserve(InventorySelection.standing(3, cart.getOrderKey()));
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(cart);

        ReservationResultDTO result = reservationService.removeForMember(MEMBER_TOKEN, EVENT_ID, ZONE_ID,
                InventorySelectionDTO.standing(2));

        assertEquals(2, result.getQuantity());
        assertEquals(1, vip.getReservedAmount(), "the zone must still hold the one ticket left in the cart");
        assertEquals(9, vip.getAvailableAmount(), "the two removed tickets must go back to stock");
        assertEquals(1, cart.getItems().size(), "one line must stay in the cart");
        verify(activeOrderRepository).save(cart);
    }

    @Test
    void GivenAMemberWithNoCart_WhenRemoveForMember_ThenRefusedAsNotFoundAndNothingIsReleased() {
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(null);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> reservationService.removeForMember(MEMBER_TOKEN, EVENT_ID, ZONE_ID,
                        InventorySelectionDTO.standing(1)));

        assertEquals("Active order not found", refused.getMessage());
        verifyNoInteractions(eventRepository);
        verify(activeOrderRepository, never()).save(any());
    }

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
