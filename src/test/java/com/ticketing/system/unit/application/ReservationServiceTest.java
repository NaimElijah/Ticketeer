package com.ticketing.system.unit.application;

import com.ticketing.system.testutil.CompanyRepositories;
import com.ticketing.system.shared.dto.ReservationResultDTO;
import org.springframework.context.ApplicationEventPublisher;
import com.ticketing.system.shared.event.ReservationRemovalFailedNotice;
import com.ticketing.system.shared.event.TicketReservationFailedNotice;
import com.ticketing.system.shared.event.TicketReservationSucceededNotice;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.UserFacingErrors;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.shared.metrics.ISystemMetrics;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.sales.application.port.out.MarketGate;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.PolicyViolationException;
import com.ticketing.system.shared.exception.MarketNotOpenException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.application.service.InventoryService;
import com.ticketing.system.catalog.domain.InventorySelection;
import com.ticketing.system.catalog.domain.InventoryZone;
import com.ticketing.system.catalog.domain.StandingZone;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import jakarta.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.Optional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.mockito.ArgumentCaptor;

import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.shared.dto.BuyerContextDTO;
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.testutil.TestTransactions;
import com.ticketing.system.testutil.UserRepositories;
import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.catalog.application.port.in.InventoryCommandPort;
import com.ticketing.system.sales.domain.CartLineItem;
import com.ticketing.system.catalog.domain.DiscountPolicy;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.Seat;
import com.ticketing.system.catalog.domain.SeatStatus;
import com.ticketing.system.catalog.domain.SeatedZone;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.catalog.domain.VenueMap;
import com.ticketing.system.shared.domain.policy.MaxTicketsPurchasePolicy;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;
import com.ticketing.system.shared.domain.policy.PurchasePolicy;

public class ReservationServiceTest {

    private EventRepository eventRepository;
    private ActiveOrderRepository activeOrderRepository;
    private SessionManager sessionManager;
    private ApplicationEventPublisher eventPublisher;
    private MarketGate marketGate;

    private ReservationService reservationService;

    private final String VALID_TOKEN = "valid-token";
    private final int USER_ID = 1;
    private final int EVENT_ID = 10;
    private final int ZONE_ID = 5;
    private final int QUANTITY = 2;
    // Holds are stamped and judged at this instant, as UTC wall time from the injected clock (ADR-008).
    private static final Instant T0 = Instant.parse("2027-07-15T17:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(T0, ZoneOffset.UTC);
    // Every guest session id these tests use starts with this and names a live guest session.
    private static final String LIVE_GUEST_SESSION_PREFIX = "guest-session";
    // The age of every member these tests reserve for; no event here has an age policy.
    private static final int MEMBER_AGE = 30;
    private static final double PRICE = 100.0;
    // A zone of the event that is not ZONE_ID, for a venue without the zone a request names.
    private static final int OTHER_ZONE_ID = 6;
    // An event a cart line still names after the event was deleted.
    private static final int DELETED_EVENT_ID = 11;

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        activeOrderRepository = mock(ActiveOrderRepository.class);
        sessionManager = mock(SessionManager.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        marketGate = mock(MarketGate.class);
        when(marketGate.isOpen()).thenReturn(true);
        // Every guest path accepts only the raw id of a live guest session (#185, ADR-012 decision 4).
        when(sessionManager.validateGuestSession(startsWith(LIVE_GUEST_SESSION_PREFIX))).thenReturn(true);

        // Catalog owns inventory mutation behind InventoryCommandPort. The REAL InventoryService runs over
        // the mock event port, which hands out real events, so every reservation reaches a real zone.
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
    }

    // --- UC-32 / I.2.1: holding tickets gated on an OPEN market ---

    @Test
    void GivenMarketClosed_WhenReserveForMember_ThenThrows() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(marketGate.isOpen()).thenReturn(false);

        assertThrows(MarketNotOpenException.class,
                () -> reservationService.reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                        InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenAMemberCartHoldingTwo_WhenRemoveBothForMember_ThenTheHoldsGoBackToStockAndTheCartIsSavedEmpty() {
        givenSignedInMember();
        StandingZone vip = standingZoneOf(10);
        givenEventOnSaleWith(vip);
        ActiveOrder cart = givenMemberCartHolding(vip, QUANTITY);

        ReservationResultDTO result = reservationService.removeForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                InventorySelectionDTO.standing(QUANTITY));

        assertEquals(QUANTITY, result.getQuantity());
        assertEquals(0, vip.getReservedAmount(), "the removed tickets must go back to stock");
        assertEquals(10, vip.getAvailableAmount());
        assertTrue(cart.isEmpty(), "the removed tickets must leave the cart");
        verify(activeOrderRepository).save(cart);
    }

    @Test
    void GivenMissingToken_WhenRemoveForMember_ThenThrowException() {
        assertThrows(IllegalArgumentException.class, () -> reservationService.removeForMember(null, EVENT_ID, ZONE_ID,
                InventorySelectionDTO.standing(QUANTITY)));
    }

    // The real session manager answers false only for a blank token, which is refused before it is asked,
    // and throws for any other it rejects. This guards the service's own check of the answer.
    @Test
    void GivenTheSessionManagerAnswersFalseForAToken_WhenRemoveForMember_ThenRefusedAsInvalidAndNothingIsTouched() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(false);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> reservationService.removeForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                        InventorySelectionDTO.standing(QUANTITY)));

        assertEquals("Invalid or expired authentication token", refused.getMessage());
        verifyNoInteractions(activeOrderRepository, eventRepository);
    }

    @Test
    void GivenAZeroQuantitySelection_WhenRemoveForMember_ThenRefusedBeforeTheCartIsTouched() {
        givenSignedInMember();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> reservationService.removeForMember(VALID_TOKEN, EVENT_ID, ZONE_ID, zeroQuantitySelection()));

        assertEquals("Quantity must be positive", refused.getMessage());
        verifyNoInteractions(activeOrderRepository, eventRepository);
    }

    @Test
    void GivenEventDoesNotExist_WhenRemoveForMember_ThenThrowException() {
        // The member's cart holds the line, so the removal reaches the event lookup.
        memberWithReservedStandingTickets();
        when(eventRepository.findById(EVENT_ID)).thenThrow(new EventNotFoundException(EVENT_ID));

        assertThrows(EventNotFoundException.class, () -> reservationService.removeForMember(VALID_TOKEN, EVENT_ID,
                ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenACartLineInAZoneTheEventNoLongerHas_WhenRemoveForMember_ThenZoneNotFoundAndTheCartKeepsTheLine() {
        // The cart holds the line, so the removal gets past the cart's own check to the zone lookup.
        memberWithReservedStandingTickets();
        givenEventOnSaleWith(new StandingZone(OTHER_ZONE_ID, "Balcony", 10, PRICE));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> reservationService.removeForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                        InventorySelectionDTO.standing(QUANTITY)));

        assertEquals("Zone not found: " + ZONE_ID, refused.getMessage());
        assertEquals(QUANTITY, activeOrderRepository.getByUserId(USER_ID).getItems().size(),
                "a refused removal must leave the cart's lines in place");
        verify(activeOrderRepository, never()).save(any());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void GivenActiveOrderDoesNotExist_WhenRemoveForMember_ThenThrowException() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> reservationService.removeForMember(VALID_TOKEN, EVENT_ID,
                ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenOrderDoesNotContainEvent_WhenRemoveForMember_ThenRefusedAndNothingIsReleased() {
        InventoryCommandPort inventory = mock(InventoryCommandPort.class);
        memberWithEmptyCart();

        Exception exception = assertThrows(IllegalArgumentException.class, () -> reservationServiceOver(inventory)
                .removeForMember(VALID_TOKEN, EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));

        assertEquals("Active order does not contain this event", exception.getMessage());
        verifyNoInteractions(inventory);
        verify(activeOrderRepository, never()).save(any());
    }

    @Test
    void GivenNotEnoughReservedTickets_WhenRemoveForMember_ThenRefusedAndNothingIsReleased() {
        InventoryCommandPort inventory = mock(InventoryCommandPort.class);
        ActiveOrder cartHoldingOne = memberWithEmptyCart();
        cartHoldingOne.addStandingReservation(EVENT_ID, ZONE_ID, 1, PRICE, NOW);

        Exception exception = assertThrows(IllegalArgumentException.class, () -> reservationServiceOver(inventory)
                .removeForMember(VALID_TOKEN, EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));

        assertEquals("Not enough reserved tickets to remove", exception.getMessage());
        verifyNoInteractions(inventory);
        assertEquals(1, cartHoldingOne.getItems().size(), "a refused removal must leave the cart's line in place");
        verify(activeOrderRepository, never()).save(any());
    }

    // Each reserved seat records when its hold runs out: the configured hold after the clock, the
    // same instant the cart line expires at (#74; it used to be a hard-coded 10 minutes from the JVM clock).
    @Test
    void GivenConfiguredHoldOfSevenMinutes_WhenReserveForMember_ThenEachSeatIsHeldUntilTheClockPlusSeven() {
        Duration configuredHold = CartLineItem.getExpirationLimit();
        CartLineItem.setExpirationLimit(Duration.ofMinutes(7));
        try {
            SeatedZone stalls = new SeatedZone(ZONE_ID, "Stalls", 100.0,
                    List.of(new Seat("A1", 0, 0), new Seat("A2", 1, 0)));
            when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
            when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
            givenEventOnSaleWith(stalls);

            reservationService.reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                    InventorySelectionDTO.seated(List.of("A1", "A2")));

            assertEquals(NOW.plusMinutes(7), stalls.getSeatByLabel("A1").getReservedUntil());
            assertEquals(NOW.plusMinutes(7), stalls.getSeatByLabel("A2").getReservedUntil());
        } finally {
            CartLineItem.setExpirationLimit(configuredHold);
        }
    }

    @Test
    void GivenFixedClock_WhenReserveForMember_ThenTheLineIsAddedAtTheClockAndExpiresOneHoldLater() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        givenEventOnSaleWith(standingZoneOf(10));

        ReservationResultDTO result = reservationService.reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                InventorySelectionDTO.standing(QUANTITY));

        assertEquals(Collections.nCopies(QUANTITY, NOW), savedCart().getItems().stream().map(CartLineItem::getAddedAt).toList(),
                "each of the " + QUANTITY + " lines must be added at the clock");
        assertEquals(NOW.plus(CartLineItem.getExpirationLimit()), result.getReservationExpiresAt());
    }

    @Test
    void GivenAMemberWithAnEmptyCart_WhenReserveTwoForMember_ThenTheZoneHoldsThemAndTheCartIsSavedWithTwoLines() {
        StandingZone vip = standingZoneOf(10);
        givenEventOnSaleWith(vip);
        ActiveOrder cart = memberWithEmptyCart();

        ReservationResultDTO result = reservationService.reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                InventorySelectionDTO.standing(QUANTITY));

        assertEquals(QUANTITY, result.getQuantity());
        assertEquals(QUANTITY, vip.getReservedAmount());
        assertSame(cart, savedCart());
        assertEquals(Collections.nCopies(QUANTITY, PRICE),
                cart.getItems().stream().map(CartLineItem::getPriceAtReservation).toList(),
                "each line must record the zone's price");
    }

    @Test
    void GivenMissingToken_WhenReserveForMember_ThenThrowException() {
        assertThrows(IllegalArgumentException.class, () -> reservationService.reserveForMember(null, EVENT_ID, ZONE_ID,
                InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenARevokedToken_WhenReserveForMember_ThenTheSessionManagersRefusalPropagatesAndNothingIsTouched() {
        InvalidTokenException revoked = new InvalidTokenException("revoked");
        when(sessionManager.validateToken(VALID_TOKEN)).thenThrow(revoked);

        assertSame(revoked, assertThrows(InvalidTokenException.class,
                () -> reservationService.reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                        InventorySelectionDTO.standing(QUANTITY))));

        verifyNoInteractions(activeOrderRepository, eventRepository);
    }

    // A member's token names a positive user id; this guards the service's own check of the id.
    @Test
    void GivenATokenNamingNoPositiveUserId_WhenReserveForMember_ThenRefusedAsAnInvalidBuyerAndNothingIsTouched() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(0);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> reservationService.reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                        InventorySelectionDTO.standing(QUANTITY)));

        assertEquals("Invalid buyer id", refused.getMessage());
        verifyNoInteractions(activeOrderRepository, eventRepository);
    }

    @Test
    void GivenAZeroQuantitySelection_WhenReserveForMember_ThenRefusedBeforeTheCartIsTouched() {
        givenSignedInMember();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> reservationService.reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID, zeroQuantitySelection()));

        assertEquals("Quantity must be positive", refused.getMessage());
        verifyNoInteractions(activeOrderRepository, eventRepository);
    }

    @Test
    void GivenEventDoesNotExist_WhenReserveForMember_ThenThrowException() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(eventRepository.findById(EVENT_ID)).thenThrow(new EventNotFoundException(EVENT_ID));

        assertThrows(EventNotFoundException.class, () -> reservationService.reserveForMember(VALID_TOKEN, EVENT_ID,
                ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenZoneDoesNotExist_WhenReserveForMember_ThenThrowException() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        givenEventOnSaleWith(new StandingZone(OTHER_ZONE_ID, "Balcony", 10, PRICE));

        assertThrows(IllegalArgumentException.class, () -> reservationService.reserveForMember(VALID_TOKEN, EVENT_ID,
                ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenOneTicketLeft_WhenReserveTwoForMember_ThenRefusedAndNothingIsHeldOrSaved() {
        StandingZone lastTicket = standingZoneOf(1);
        givenEventOnSaleWith(lastTicket);
        ActiveOrder cart = memberWithEmptyCart();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> reservationService.reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                        InventorySelectionDTO.standing(QUANTITY)));

        assertEquals("remaining 1 tickets available", refused.getMessage());
        assertEquals(1, lastTicket.getAvailableAmount());
        assertEquals(0, lastTicket.getReservedAmount());
        assertTrue(cart.isEmpty(), "a refused reservation must add no cart line");
        verify(activeOrderRepository, never()).save(any());
    }

    @Test
    void GivenAMemberWithNoCart_WhenReserveForMember_ThenANewCartOfTheMemberIsSavedHoldingTheTickets() {
        givenSignedInMember();
        StandingZone vip = standingZoneOf(10);
        givenEventOnSaleWith(vip);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(null);

        reservationService.reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(QUANTITY));

        ActiveOrder created = savedCart();
        assertTrue(created.isMember());
        assertEquals(USER_ID, created.getUserId());
        assertEquals(QUANTITY, created.getItems().size());
        assertEquals(QUANTITY, vip.getReservedAmount());
    }

    @Test
    void GivenAGuestWithAnEmptyCart_WhenReserveTwoForGuest_ThenTheZoneHoldsThemAndTheCartIsSavedWithTwoLines() {
        String sessionId = "guest-session";
        StandingZone vip = standingZoneOf(10);
        givenEventOnSaleWith(vip);
        ActiveOrder cart = ActiveOrder.forGuest(sessionId, NOW);
        when(activeOrderRepository.getBySessionId(sessionId)).thenReturn(Optional.of(cart));

        ReservationResultDTO result = reservationService.reserveForGuest(sessionId, EVENT_ID, ZONE_ID,
                InventorySelectionDTO.standing(QUANTITY));

        assertEquals(QUANTITY, result.getQuantity());
        assertEquals(QUANTITY, vip.getReservedAmount());
        assertSame(cart, savedCart());
        assertEquals(QUANTITY, cart.getItems().size());
    }

    @Test
    void GivenAGuestSessionIdThatIsNotLive_WhenReserveForGuest_ThenSessionExpiredAndNothingIsReserved() {
        String deadSessionId = "expired-or-never-issued";

        assertThrows(SessionExpiredException.class, () -> reservationService.reserveForGuest(deadSessionId,
                EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));

        verify(eventRepository, never()).findById(anyInt());
        verify(activeOrderRepository, never()).save(any());
    }

    @Test
    void GivenAGuestSessionIdThatIsNotLive_WhenRemoveForGuest_ThenSessionExpired() {
        String deadSessionId = "expired-or-never-issued";

        assertThrows(SessionExpiredException.class, () -> reservationService.removeForGuest(deadSessionId,
                EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));

        verify(activeOrderRepository, never()).save(any());
    }

    @Test
    void GivenMissingSessionId_WhenReserveForGuest_ThenThrowException() {
        assertThrows(IllegalArgumentException.class, () -> reservationService.reserveForGuest(null, EVENT_ID, ZONE_ID,
                InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenBlankSessionId_WhenReserveForGuest_ThenThrowException() {
        assertThrows(IllegalArgumentException.class, () -> reservationService.reserveForGuest("   ", EVENT_ID, ZONE_ID,
                InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenAZeroQuantitySelection_WhenReserveForGuest_ThenRefusedBeforeTheCartIsTouched() {
        String sessionId = "guest-session";

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> reservationService.reserveForGuest(sessionId, EVENT_ID, ZONE_ID, zeroQuantitySelection()));

        assertEquals("Quantity must be positive", refused.getMessage());
        verifyNoInteractions(activeOrderRepository, eventRepository);
    }

    @Test
    void GivenEventDoesNotExist_WhenReserveForGuest_ThenThrowException() {
        String sessionId = "guest-session";

        when(eventRepository.findById(EVENT_ID)).thenThrow(new EventNotFoundException(EVENT_ID));

        assertThrows(EventNotFoundException.class, () -> reservationService.reserveForGuest(sessionId, EVENT_ID,
                ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenZoneDoesNotExist_WhenReserveForGuest_ThenThrowException() {
        String sessionId = "guest-session";

        givenEventOnSaleWith(new StandingZone(OTHER_ZONE_ID, "Balcony", 10, PRICE));

        assertThrows(IllegalArgumentException.class,
                () -> reservationService.reserveForGuest(sessionId, EVENT_ID, ZONE_ID,
                        InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenOneTicketLeft_WhenReserveTwoForGuest_ThenRefusedAndNothingIsHeldOrSaved() {
        String sessionId = "guest-session";
        StandingZone lastTicket = standingZoneOf(1);
        givenEventOnSaleWith(lastTicket);
        ActiveOrder cart = ActiveOrder.forGuest(sessionId, NOW);
        when(activeOrderRepository.getBySessionId(sessionId)).thenReturn(Optional.of(cart));

        Exception exception = assertThrows(IllegalStateException.class, () -> reservationService
                .reserveForGuest(sessionId, EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));

        assertEquals("remaining 1 tickets available", exception.getMessage());
        assertEquals(1, lastTicket.getAvailableAmount());
        assertEquals(0, lastTicket.getReservedAmount());
        assertTrue(cart.isEmpty(), "a refused reservation must add no cart line");
        verify(activeOrderRepository, never()).save(any());
    }

    @Test
    void GivenAGuestWithNoCart_WhenReserveForGuest_ThenANewCartForTheGuestsSessionIsSavedHoldingTheTickets() {
        String sessionId = "guest-session";
        StandingZone vip = standingZoneOf(10);
        givenEventOnSaleWith(vip);
        when(activeOrderRepository.getBySessionId(sessionId)).thenReturn(Optional.empty());

        reservationService.reserveForGuest(sessionId, EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(QUANTITY));

        ActiveOrder created = savedCart();
        assertTrue(created.isGuest());
        assertEquals(sessionId, created.getSessionId());
        assertEquals(QUANTITY, created.getItems().size());
        assertEquals(QUANTITY, vip.getReservedAmount());
    }

    @Test
    void GivenManyGuestsReserveSameZoneConcurrently_WhenReserveForGuest_ThenDoNotOverReserve()
            throws InterruptedException {
        int capacity = 5;
        int numberOfThreads = 20;
        int quantityPerRequest = 1;

        InventoryZone realZone = new StandingZone(ZONE_ID, "VIP", capacity, 100.0);

        givenEventOnSaleWith(realZone);

        List<Runnable> attempts = new ArrayList<>();
        for (int i = 0; i < numberOfThreads; i = i + 1) {
            final String sessionId = "guest-session-" + i;

            when(activeOrderRepository.getBySessionId(sessionId)).thenReturn(Optional.empty());

            attempts.add(() -> reservationService.reserveForGuest(sessionId, EVENT_ID, ZONE_ID,
                    InventorySelectionDTO.standing(quantityPerRequest)));
        }

        RaceOutcome outcome = raceAll(attempts);

        assertEquals(List.of(), outcome.unexpected(), "only a refusal may end an attempt that loses the race");
        assertEquals(capacity, outcome.successes());
        assertEquals(numberOfThreads - capacity, outcome.refusals());
        assertEquals(0, realZone.getAvailableAmount());
        assertEquals(capacity, realZone.getReservedAmount());
    }

    @Test
    void GivenManyMembersReserveSameZoneConcurrently_WhenReserveForMember_ThenDoNotOverReserve()
            throws InterruptedException {

        int capacity = 5;
        int numberOfThreads = 20;
        int quantityPerRequest = 1;

        InventoryZone realZone = new StandingZone(ZONE_ID, "VIP", capacity, 100.0);

        givenEventOnSaleWith(realZone);

        List<Runnable> attempts = new ArrayList<>();
        for (int i = 0; i < numberOfThreads; i = i + 1) {
            final String token = "valid-token-" + i;
            final int userId = i + 1;

            when(sessionManager.validateToken(token)).thenReturn(true);
            when(sessionManager.extractUserId(token)).thenReturn(userId);
            when(activeOrderRepository.getByUserId(userId)).thenReturn(null);

            attempts.add(() -> reservationService.reserveForMember(token, EVENT_ID, ZONE_ID,
                    InventorySelectionDTO.standing(quantityPerRequest)));
        }

        RaceOutcome outcome = raceAll(attempts);

        assertEquals(List.of(), outcome.unexpected(), "only a refusal may end an attempt that loses the race");
        assertEquals(capacity, outcome.successes());
        assertEquals(numberOfThreads - capacity, outcome.refusals());
        assertEquals(0, realZone.getAvailableAmount());
        assertEquals(capacity, realZone.getReservedAmount());
    }

    @Test
    void GivenManyThreadsRemovingFromOneMembersCartConcurrently_WhenRemoveForMember_ThenDoNotOverRelease()
            throws InterruptedException {

        int initialReservedTickets = 5;
        int capacity = 10;
        int numberOfThreads = 20;
        int quantityPerRemove = 1;

        ActiveOrder realActiveOrder = new ActiveOrder(USER_ID, NOW);
        InventoryZone realZone = new StandingZone(ZONE_ID, "VIP", capacity, 100.0);
        realZone.reserve(InventorySelection.standing(initialReservedTickets, realActiveOrder.getOrderKey()));

        realActiveOrder.addStandingReservation(
                EVENT_ID,
                ZONE_ID,
                initialReservedTickets,
                100.0,
                NOW);

        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        givenEventOnSaleWith(realZone);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(realActiveOrder);

        List<Runnable> attempts = new ArrayList<>();
        for (int i = 0; i < numberOfThreads; i = i + 1) {
            attempts.add(() -> reservationService.removeForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                    InventorySelectionDTO.standing(quantityPerRemove)));
        }

        RaceOutcome outcome = raceAll(attempts);

        assertEquals(List.of(), outcome.unexpected(), "only a refusal may end an attempt that loses the race");
        assertEquals(initialReservedTickets, outcome.successes());
        assertEquals(numberOfThreads - initialReservedTickets, outcome.refusals());
        assertEquals(0, realActiveOrder.countTickets(EVENT_ID, ZONE_ID, NOW));
        assertEquals(0, realZone.getReservedAmount());
        assertEquals(capacity, realZone.getAvailableAmount());
    }

    // ---- Seated-zone concurrency ----------------------------------------
    // These exercise SeatedZone's per-seat ReentrantLock (the real guarantee against
    // double-booking): the order and event locks are mock no-ops here, so only the
    // zone's own locks stand between the buyers — as in the standing tests above.

    @Test
    void GivenManyMembersSelectSameSeatConcurrently_WhenReserveForMember_ThenOnlyOneSucceeds()
            throws InterruptedException {

        int numberOfThreads = 20;
        String contestedSeat = "A1";

        SeatedZone realZone = new SeatedZone(
                ZONE_ID, "Orchestra", 120.0,
                List.of(new Seat("A1", 0, 0), new Seat("A2", 1, 0), new Seat("A3", 2, 0)));

        givenEventOnSaleWith(realZone);

        List<Runnable> attempts = new ArrayList<>();
        for (int i = 0; i < numberOfThreads; i = i + 1) {
            final String token = "valid-token-" + i;
            final int userId = i + 1;

            when(sessionManager.validateToken(token)).thenReturn(true);
            when(sessionManager.extractUserId(token)).thenReturn(userId);
            when(activeOrderRepository.getByUserId(userId)).thenReturn(null);

            attempts.add(() -> reservationService.reserveForMember(token, EVENT_ID, ZONE_ID,
                    InventorySelectionDTO.seated(List.of(contestedSeat))));
        }

        RaceOutcome outcome = raceAll(attempts);

        assertEquals(List.of(), outcome.unexpected(), "only a refusal may end an attempt that loses the race");
        assertEquals(1, outcome.successes());
        assertEquals(numberOfThreads - 1, outcome.refusals());
        assertEquals(SeatStatus.RESERVED, realZone.getSeatStatus(contestedSeat));
        assertEquals(1, realZone.getReservedAmount());
    }

    @Test
    void GivenManyGuestsSelectSameSeatConcurrently_WhenReserveForGuest_ThenOnlyOneSucceeds()
            throws InterruptedException {

        int numberOfThreads = 20;
        String contestedSeat = "A1";

        SeatedZone realZone = new SeatedZone(
                ZONE_ID, "Orchestra", 120.0,
                List.of(new Seat("A1", 0, 0), new Seat("A2", 1, 0), new Seat("A3", 2, 0)));

        givenEventOnSaleWith(realZone);

        List<Runnable> attempts = new ArrayList<>();
        for (int i = 0; i < numberOfThreads; i = i + 1) {
            final String sessionId = "guest-session-" + i;

            when(activeOrderRepository.getBySessionId(sessionId)).thenReturn(Optional.empty());

            attempts.add(() -> reservationService.reserveForGuest(sessionId, EVENT_ID, ZONE_ID,
                    InventorySelectionDTO.seated(List.of(contestedSeat))));
        }

        RaceOutcome outcome = raceAll(attempts);

        assertEquals(List.of(), outcome.unexpected(), "only a refusal may end an attempt that loses the race");
        assertEquals(1, outcome.successes());
        assertEquals(numberOfThreads - 1, outcome.refusals());
        assertEquals(SeatStatus.RESERVED, realZone.getSeatStatus(contestedSeat));
        assertEquals(1, realZone.getReservedAmount());
    }

    @Test
    void GivenManyThreadsReserveDistinctSeatsConcurrently_WhenReserveForMember_ThenAllSucceed()
            throws InterruptedException {

        int numberOfThreads = 20;

        List<Seat> seats = new ArrayList<>();
        for (int i = 0; i < numberOfThreads; i = i + 1) {
            seats.add(new Seat("A" + (i + 1), i, 0));
        }
        SeatedZone realZone = new SeatedZone(ZONE_ID, "Orchestra", 120.0, seats);

        givenEventOnSaleWith(realZone);

        List<Runnable> attempts = new ArrayList<>();
        for (int i = 0; i < numberOfThreads; i = i + 1) {
            final String token = "valid-token-" + i;
            final int userId = i + 1;
            final String seatLabel = "A" + (i + 1);

            when(sessionManager.validateToken(token)).thenReturn(true);
            when(sessionManager.extractUserId(token)).thenReturn(userId);
            when(activeOrderRepository.getByUserId(userId)).thenReturn(null);

            attempts.add(() -> reservationService.reserveForMember(token, EVENT_ID, ZONE_ID,
                    InventorySelectionDTO.seated(List.of(seatLabel))));
        }

        RaceOutcome outcome = raceAll(attempts);

        assertEquals(List.of(), outcome.unexpected(), "only a refusal may end an attempt that loses the race");
        assertEquals(numberOfThreads, outcome.successes());
        assertEquals(0, outcome.refusals());
        assertEquals(numberOfThreads, realZone.getReservedAmount());
        assertEquals(0, realZone.getAvailableAmount());
    }

    // NOTE: the in-checkout expiry guard formerly tested here against the
    // now-removed
    // ReservationService.expireActiveOrders() lives on the live path and is covered
    // by
    // SessionAndOrderSweeperTest.GivenExpiredCartInCheckoutProgress_WhenSweepExpiredOrders_ThenDoNotReleaseOrDelete.

    @Test
    void GivenValidMemberSeatSelection_WhenReserveForMember_ThenSeatsReservedAndCartContainsSeatNumbers() {
        SeatedZone seatedZone = new SeatedZone(
                ZONE_ID,
                "Orchestra",
                120.0,
                List.of(
                        new Seat("A1", 0, 0),
                        new Seat("A2", 1, 0),
                        new Seat("A3", 2, 0)));
        Event realEvent = createEventWithZone(seatedZone);

        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(realEvent);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(null);

        realEvent.transitionToOnSale();
        ReservationResultDTO result = reservationService.reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                InventorySelectionDTO.seated(List.of("A1", "A2")));

        assertEquals(EVENT_ID, result.getEventId());
        assertEquals(ZONE_ID, result.getZoneId());
        assertEquals(2, result.getQuantity());
        assertEquals(List.of("A1", "A2"), result.getSeatNumbers());

        assertEquals(SeatStatus.RESERVED, seatedZone.getSeatStatus("A1"));
        assertEquals(SeatStatus.RESERVED, seatedZone.getSeatStatus("A2"));
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A3"));

        ArgumentCaptor<ActiveOrder> orderCaptor = ArgumentCaptor.forClass(ActiveOrder.class);
        verify(activeOrderRepository).save(orderCaptor.capture());

        ActiveOrder savedOrder = orderCaptor.getValue();
        assertEquals(2, savedOrder.getItems().size());
        assertEquals(List.of("A1", "A2"),
                savedOrder.getItems().stream().map(CartLineItem::getSeatNumber).toList());
    }

    @Test
    void GivenValidGuestSeatSelection_WhenReserveForGuest_ThenSeatsReservedAndGuestCartSaved() {
        String sessionId = "guest-session-1";

        SeatedZone seatedZone = new SeatedZone(
                ZONE_ID,
                "Orchestra",
                120.0,
                List.of(
                        new Seat("B1", 0, 0),
                        new Seat("B2", 1, 0)));
        Event realEvent = createEventWithZone(seatedZone);

        when(eventRepository.findById(EVENT_ID)).thenReturn(realEvent);
        when(activeOrderRepository.getBySessionId(sessionId)).thenReturn(Optional.empty());

        realEvent.transitionToOnSale();
        ReservationResultDTO result = reservationService.reserveForGuest(sessionId, EVENT_ID, ZONE_ID,
                InventorySelectionDTO.seated(List.of("B1", "B2")));

        assertEquals(EVENT_ID, result.getEventId());
        assertEquals(ZONE_ID, result.getZoneId());
        assertEquals(2, result.getQuantity());
        assertEquals(List.of("B1", "B2"), result.getSeatNumbers());

        assertEquals(SeatStatus.RESERVED, seatedZone.getSeatStatus("B1"));
        assertEquals(SeatStatus.RESERVED, seatedZone.getSeatStatus("B2"));

        ArgumentCaptor<ActiveOrder> orderCaptor = ArgumentCaptor.forClass(ActiveOrder.class);
        verify(activeOrderRepository).save(orderCaptor.capture());

        ActiveOrder savedOrder = orderCaptor.getValue();
        assertTrue(savedOrder.isGuest());
        assertEquals(sessionId, savedOrder.getSessionId());
        assertEquals(List.of("B1", "B2"),
                savedOrder.getItems().stream().map(CartLineItem::getSeatNumber).toList());
    }

    @Test
    void GivenASeatSelectionForAStandingZone_WhenReserveForMember_ThenThrowsException() {
        StandingZone standingZone = new StandingZone(ZONE_ID, "General Admission", 10, 50.0);
        Event realEvent = createEventWithZone(standingZone);

        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(realEvent);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(null);

        realEvent.transitionToOnSale();
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> reservationService.reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                        InventorySelectionDTO.seated(List.of("A1"))));
        assertEquals("Standing zone cannot reserve specific seats", refused.getMessage());

        assertEquals(10, standingZone.getAvailableAmount());
        verify(activeOrderRepository, never()).save(any());
    }

    @Test
    void GivenAQuantityOnlySelectionForASeatedZone_WhenReserveForMember_ThenThrowsException() {
        SeatedZone seatedZone = new SeatedZone(
                ZONE_ID,
                "Orchestra",
                120.0,
                List.of(new Seat("A1", 0, 0)));
        Event realEvent = createEventWithZone(seatedZone);

        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(realEvent);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(null);

        realEvent.transitionToOnSale();
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> reservationService.reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                        InventorySelectionDTO.standing(1)));
        assertEquals("Seated zone requires selected seat numbers", refused.getMessage());

        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A1"));
        verify(activeOrderRepository, never()).save(any());
    }

    @Test
    void GivenCartSaveFails_WhenReserveForMember_ThenReservedSeatsAreRolledBack() {
        SeatedZone seatedZone = new SeatedZone(
                ZONE_ID,
                "Orchestra",
                120.0,
                List.of(
                        new Seat("A1", 0, 0),
                        new Seat("A2", 1, 0)));
        Event realEvent = createEventWithZone(seatedZone);

        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(realEvent);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(null);
        realEvent.transitionToOnSale();
        RuntimeException saveFailed = new RuntimeException("save failed");
        doThrow(saveFailed).when(activeOrderRepository).save(any(ActiveOrder.class));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> reservationService.reserveForMember(
                VALID_TOKEN, EVENT_ID, ZONE_ID, InventorySelectionDTO.seated(List.of("A1", "A2"))));

        assertSame(saveFailed, thrown, "the cart save's own failure must propagate, after the seats were held");
        // The reserve's save, then the rollback's save of the cart with the line taken back out.
        verify(activeOrderRepository, times(2)).save(any(ActiveOrder.class));
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A1"));
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A2"));
    }

    @Test
    void GivenReservedSeatsInCart_WhenRemoveForMember_ThenExactSeatsReleasedAndRemovedFromCart() {
        SeatedZone seatedZone = new SeatedZone(
                ZONE_ID,
                "Orchestra",
                120.0,
                List.of(
                        new Seat("A1", 0, 0),
                        new Seat("A2", 1, 0),
                        new Seat("A3", 2, 0)));
        Event realEvent = createEventWithZone(seatedZone);
        ActiveOrder realOrder = new ActiveOrder(USER_ID, NOW);
        seatedZone.reserve(InventorySelection.seated(List.of("A1", "A2", "A3"), realOrder.getOrderKey()));

        realOrder.addSeatedReservation(EVENT_ID, ZONE_ID, List.of("A1", "A2", "A3"), 120.0, NOW);

        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(realEvent);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(realOrder);

        ReservationResultDTO result = reservationService.removeForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                InventorySelectionDTO.seated(List.of("A2")));

        assertEquals(1, result.getQuantity());
        assertEquals(List.of("A2"), result.getSeatNumbers());

        assertEquals(SeatStatus.RESERVED, seatedZone.getSeatStatus("A1"));
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A2"));
        assertEquals(SeatStatus.RESERVED, seatedZone.getSeatStatus("A3"));

        assertEquals(List.of("A1", "A3"),
                realOrder.getItems().stream().map(CartLineItem::getSeatNumber).toList());

        verify(activeOrderRepository).save(realOrder);
        verify(eventRepository).save(realEvent);
    }

    @Test
    void GivenSuccessfulMemberReservation_WhenReserveForMember_ThenOneSuccessNoticeNamesTheReservation() {
        givenSignedInMember();
        givenEventOnSaleWith(standingZoneOf(10));

        reservationService.reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(QUANTITY));

        assertEquals(new TicketReservationSucceededNotice(USER_ID, EVENT_ID, ZONE_ID, QUANTITY),
                publishedNotice(TicketReservationSucceededNotice.class));
    }

    // ---------------------------------------------------------------------
    // Checkout-entry timer renewal (reset every reserved ticket to the full window)
    // ---------------------------------------------------------------------

    @Test
    void GivenValidCartNearExpiry_WhenRenewReservationsForMemberCheckout_ThenTimersResetToFullWindow() {
        ActiveOrder realOrder = new ActiveOrder(USER_ID, NOW);
        realOrder.addStandingReservation(EVENT_ID, ZONE_ID, 2, 50.0, aHoldStartWithOneMinuteLeft());
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(realOrder);
        givenEventOnSaleWith(standingZoneOf(10));

        ActiveOrderDTO dto = reservationService.renewReservationsForMemberCheckout(USER_ID);

        assertEquals(CartLineItem.getExpirationLimit().toSeconds(), dto.remainingSecondsBeforeExpiry(),
                "every hold must restart at the clock, with the full window ahead of it");
        assertEquals(List.of(NOW, NOW), realOrder.getItems().stream().map(CartLineItem::getAddedAt).toList());
        verify(activeOrderRepository).lockForUpdate("user:" + USER_ID);
        verify(activeOrderRepository).unlock("user:" + USER_ID);
        verify(activeOrderRepository).save(realOrder);
    }

    @Test
    void GivenExpiredItem_WhenRenewReservationsForMemberCheckout_ThenTimersNotResetAndNotSaved() {
        ActiveOrder realOrder = new ActiveOrder(USER_ID, NOW);
        realOrder.addStandingReservation(EVENT_ID, ZONE_ID, 1, 50.0, aHoldStartThatHasRunOut());
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(realOrder);
        givenEventOnSaleWith(standingZoneOf(10));

        ActiveOrderDTO dto = reservationService.renewReservationsForMemberCheckout(USER_ID);

        assertEquals(0, dto.remainingSecondsBeforeExpiry(), "already-expired cart must not be revived");
        verify(activeOrderRepository, never()).save(any());
        verify(activeOrderRepository).unlock("user:" + USER_ID);
    }

    @Test
    void GivenCheckoutInProgress_WhenRenewReservationsForMemberCheckout_ThenTimersNotResetAndNotSaved() {
        ActiveOrder realOrder = new ActiveOrder(USER_ID, NOW);
        realOrder.addStandingReservation(EVENT_ID, ZONE_ID, 1, 50.0, aHoldStartWithOneMinuteLeft());
        realOrder.markCheckoutInProgress();
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(realOrder);
        givenEventOnSaleWith(standingZoneOf(10));

        ActiveOrderDTO dto = reservationService.renewReservationsForMemberCheckout(USER_ID);

        assertEquals(Duration.ofMinutes(1).toSeconds(), dto.remainingSecondsBeforeExpiry(),
                "in-checkout cart timers must be left untouched");
        verify(activeOrderRepository, never()).save(any());
    }

    @Test
    void GivenNoOrder_WhenRenewReservationsForMemberCheckout_ThenReturnsNullAndDoesNotSave() {
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(null);

        ActiveOrderDTO dto = reservationService.renewReservationsForMemberCheckout(USER_ID);

        assertNull(dto);
        verify(activeOrderRepository, never()).save(any());
        verify(activeOrderRepository).unlock("user:" + USER_ID);
    }

    @Test
    void GivenValidGuestCartNearExpiry_WhenRenewReservationsForGuestCheckout_ThenTimersResetToFullWindow() {
        String sessionId = "guest-session";
        ActiveOrder realOrder = ActiveOrder.forGuest(sessionId, NOW);
        realOrder.addStandingReservation(EVENT_ID, ZONE_ID, 1, 50.0, aHoldStartWithOneMinuteLeft());
        when(activeOrderRepository.getBySessionId(sessionId)).thenReturn(Optional.of(realOrder));
        givenEventOnSaleWith(standingZoneOf(10));

        ActiveOrderDTO dto = reservationService.renewReservationsForGuestCheckout(sessionId);

        assertEquals(CartLineItem.getExpirationLimit().toSeconds(), dto.remainingSecondsBeforeExpiry(),
                "every hold must restart at the clock, with the full window ahead of it");
        verify(activeOrderRepository).lockForUpdate("sess:" + sessionId);
        verify(activeOrderRepository).unlock("sess:" + sessionId);
        verify(activeOrderRepository).save(realOrder);
    }

    // ---------------------------------------------------------------------
    // Abandoning a cart releases every hold it has, then deletes it
    // ---------------------------------------------------------------------

    @Test
    void GivenACartHoldingStandingAndSeatedTickets_WhenAbandonActiveOrder_ThenEveryHoldIsReleasedAndTheCartDeleted() {
        StandingZone vip = standingZoneOf(10);
        SeatedZone stalls = stallsWithSeatsA1AndA2();
        givenEventOnSaleWith(vip, stalls);
        ActiveOrder cart = givenMemberCartHolding(vip, QUANTITY);
        holdSeatsFor(cart, stalls, List.of("A1"));

        reservationService.abandonActiveOrder(BuyerContextDTO.member(USER_ID));

        assertEquals(0, vip.getReservedAmount(), "the cart's standing holds must go back to stock");
        assertEquals(10, vip.getAvailableAmount());
        assertEquals(SeatStatus.AVAILABLE, stalls.getSeatStatus("A1"), "the cart's seat must go back to stock");
        verify(activeOrderRepository).delete(cart);
        verify(activeOrderRepository).unlock("user:" + USER_ID);
    }

    @Test
    void GivenACartInCheckout_WhenAbandonActiveOrder_ThenRefusedAndItsHoldsAndLinesAreKept() {
        StandingZone vip = standingZoneOf(10);
        givenEventOnSaleWith(vip);
        ActiveOrder cart = givenMemberCartHolding(vip, QUANTITY);
        cart.markCheckoutInProgress();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> reservationService.abandonActiveOrder(BuyerContextDTO.member(USER_ID)));

        assertEquals("Cannot abandon active order while checkout is in progress", refused.getMessage());
        assertEquals(QUANTITY, vip.getReservedAmount(), "a cart being paid for must keep its holds");
        assertEquals(QUANTITY, cart.getItems().size());
        verify(activeOrderRepository, never()).delete(any());
        verify(activeOrderRepository).unlock("user:" + USER_ID);
    }

    @Test
    void GivenACartWhoseFirstLineIsOfADeletedEvent_WhenAbandonActiveOrder_ThenTheOtherHoldsAreReleasedAndTheCartDeleted() {
        StandingZone vip = standingZoneOf(10);
        givenEventOnSaleWith(vip);
        ActiveOrder cart = new ActiveOrder(USER_ID, NOW);
        cart.addStandingReservation(DELETED_EVENT_ID, ZONE_ID, 1, PRICE, NOW);
        cart.addStandingReservation(EVENT_ID, ZONE_ID, QUANTITY, PRICE, NOW);
        vip.reserve(InventorySelection.standing(QUANTITY, cart.getOrderKey()));
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(cart);
        when(eventRepository.findById(DELETED_EVENT_ID)).thenThrow(new EventNotFoundException(DELETED_EVENT_ID));

        assertDoesNotThrow(() -> reservationService.abandonActiveOrder(BuyerContextDTO.member(USER_ID)));

        assertEquals(0, vip.getReservedAmount(), "a release that fails must not stop the lines after it");
        verify(activeOrderRepository).delete(cart);
    }

    // ---------------------------------------------------------------------
    // A removal whose cart save fails after the release puts the hold and the line back
    // ---------------------------------------------------------------------

    @Test
    void GivenTheCartSaveFailsAfterTheRelease_WhenRemoveSeatForMember_ThenTheSeatIsHeldAgainAndItsLineReAdded() {
        SeatedZone stalls = new SeatedZone(ZONE_ID, "Stalls", PRICE, List.of(new Seat("A1", 0, 0), new Seat("A2", 1, 0)));
        givenEventOnSaleWith(stalls);
        ActiveOrder cart = memberWithEmptyCart();
        cart.addSeatedReservation(EVENT_ID, ZONE_ID, List.of("A1", "A2"), PRICE, NOW);
        stalls.reserve(InventorySelection.seated(List.of("A1", "A2"), cart.getOrderKey()));
        DataAccessResourceFailureException saveFailure = new DataAccessResourceFailureException("cart store down");
        doThrow(saveFailure).doNothing().when(activeOrderRepository).save(cart);

        assertSame(saveFailure, assertThrows(DataAccessResourceFailureException.class,
                () -> reservationService.removeForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                        InventorySelectionDTO.seated(List.of("A2")))));

        assertEquals(SeatStatus.RESERVED, stalls.getSeatStatus("A2"), "the released seat must be held again");
        assertEquals(cart.getOrderKey(), stalls.getSeatByLabel("A2").getReservedByOrderKey());
        assertEquals(NOW.plus(CartLineItem.getExpirationLimit()), stalls.getSeatByLabel("A2").getReservedUntil(),
                "the restored hold runs a full hold from the clock");
        assertEquals(List.of("A1", "A2"),
                cart.getItems().stream().map(CartLineItem::getSeatNumber).sorted().toList(),
                "the removed line must be back in the cart");
        verify(activeOrderRepository, times(2)).save(cart);
    }

    // ---------------------------------------------------------------------
    // The purchase policy's maximum counts what the cart already holds for the event
    // ---------------------------------------------------------------------

    private static final int MAX_TICKETS_PER_BUYER = 4;
    private static final int HELD_BEFORE = 2;

    @Test
    void GivenTwoTicketsHeldUnderAMaxOfFour_WhenReserveThreeMoreForMember_ThenRefusedByThePolicyAndNothingMoreIsHeld() {
        StandingZone vip = standingZoneOf(10);
        givenEventOnSaleWithPolicy(new MaxTicketsPurchasePolicy(MAX_TICKETS_PER_BUYER), vip);
        givenSignedInMember();
        ActiveOrder cart = givenMemberCartHolding(vip, HELD_BEFORE);

        assertThrows(PolicyViolationException.class, () -> reservationService.reserveForMember(VALID_TOKEN,
                EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(MAX_TICKETS_PER_BUYER - HELD_BEFORE + 1)),
                "splitting a purchase must not get past the maximum");

        assertEquals(HELD_BEFORE, vip.getReservedAmount());
        assertEquals(HELD_BEFORE, cart.getItems().size());
        verify(activeOrderRepository, never()).save(any());
    }

    @Test
    void GivenTwoTicketsHeldUnderAMaxOfFour_WhenReserveTwoMoreForMember_ThenAllFourAreHeld() {
        StandingZone vip = standingZoneOf(10);
        givenEventOnSaleWithPolicy(new MaxTicketsPurchasePolicy(MAX_TICKETS_PER_BUYER), vip);
        givenSignedInMember();
        ActiveOrder cart = givenMemberCartHolding(vip, HELD_BEFORE);

        reservationService.reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                InventorySelectionDTO.standing(MAX_TICKETS_PER_BUYER - HELD_BEFORE));

        assertEquals(MAX_TICKETS_PER_BUYER, vip.getReservedAmount());
        assertEquals(MAX_TICKETS_PER_BUYER, cart.getItems().size());
        verify(activeOrderRepository).save(cart);
    }

    // ---------------------------------------------------------------------
    // removeLine routes a member token to the member's cart and anything else to a guest's
    // ---------------------------------------------------------------------

    private static final String MEMBER_JWT = "header.payload.signature";

    @Test
    void GivenAMemberToken_WhenRemoveLine_ThenTheLineLeavesTheMembersCart() {
        when(sessionManager.validateToken(MEMBER_JWT)).thenReturn(true);
        when(sessionManager.extractUserId(MEMBER_JWT)).thenReturn(USER_ID);
        StandingZone vip = standingZoneOf(10);
        givenEventOnSaleWith(vip);
        ActiveOrder cart = givenMemberCartHolding(vip, QUANTITY);

        reservationService.removeLine(MEMBER_JWT, EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(1));

        assertEquals(QUANTITY - 1, cart.getItems().size());
        assertEquals(QUANTITY - 1, vip.getReservedAmount());
        verify(activeOrderRepository, never()).getBySessionId(any());
    }

    @Test
    void GivenAGuestSessionId_WhenRemoveLine_ThenTheLineLeavesTheGuestsCart() {
        String sessionId = "guest-session";
        StandingZone vip = standingZoneOf(10);
        givenEventOnSaleWith(vip);
        ActiveOrder cart = givenGuestCartHolding(sessionId, vip, QUANTITY);

        reservationService.removeLine(sessionId, EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(1));

        assertEquals(QUANTITY - 1, cart.getItems().size());
        assertEquals(QUANTITY - 1, vip.getReservedAmount());
        verify(sessionManager, never()).validateToken(any());
    }

    @Test
    void GivenARevokedMemberToken_WhenRemoveLine_ThenTheRefusalPropagatesAndNoGuestCartIsTried() {
        InvalidTokenException revoked = new InvalidTokenException("revoked");
        when(sessionManager.validateToken(MEMBER_JWT)).thenThrow(revoked);

        assertSame(revoked, assertThrows(InvalidTokenException.class,
                () -> reservationService.removeLine(MEMBER_JWT, EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(1))));

        verifyNoInteractions(activeOrderRepository, eventRepository);
    }

    @Test
    void GivenAGuestCartHoldingTwo_WhenRemoveOneForGuest_ThenOneHoldIsReleasedAndOneLineRemoved() {
        String sessionId = "guest-session";
        StandingZone vip = standingZoneOf(10);
        givenEventOnSaleWith(vip);
        ActiveOrder cart = givenGuestCartHolding(sessionId, vip, QUANTITY);

        reservationService.removeForGuest(sessionId, EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(1));

        assertEquals(QUANTITY - 1, vip.getReservedAmount());
        assertEquals(QUANTITY - 1, cart.getItems().size());
        verify(activeOrderRepository).save(cart);
    }

    // ---------------------------------------------------------------------
    // The closed market stops new holds for every buyer, and never stops a removal (UC-32 / I.2.1)
    // ---------------------------------------------------------------------

    @Test
    void GivenMarketClosed_WhenReserveForGuest_ThenMarketNotOpenAndNothingIsHeld() {
        when(marketGate.isOpen()).thenReturn(false);

        assertThrows(MarketNotOpenException.class, () -> reservationService.reserveForGuest("guest-session",
                EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));

        verifyNoInteractions(activeOrderRepository, eventRepository);
    }

    @Test
    void GivenMarketClosed_WhenRemoveForMember_ThenTheLineIsStillRemovedAndItsHoldReleased() {
        when(marketGate.isOpen()).thenReturn(false);
        givenSignedInMember();
        StandingZone vip = standingZoneOf(10);
        givenEventOnSaleWith(vip);
        ActiveOrder cart = givenMemberCartHolding(vip, QUANTITY);

        reservationService.removeForMember(VALID_TOKEN, EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(QUANTITY));

        assertTrue(cart.isEmpty(), "a closed market must not keep a buyer from emptying their cart");
        assertEquals(0, vip.getReservedAmount());
    }

    // ---------------------------------------------------------------------
    // Request arguments, and a cart line whose event is gone
    // ---------------------------------------------------------------------

    @Test
    void GivenAZeroEventId_WhenReserveForMember_ThenRefusedBeforeTheCartIsTouched() {
        givenSignedInMember();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> reservationService.reserveForMember(VALID_TOKEN, 0, ZONE_ID, InventorySelectionDTO.standing(1)));

        assertEquals("eventId must be positive", refused.getMessage());
        verifyNoInteractions(activeOrderRepository, eventRepository);
    }

    @Test
    void GivenAZeroZoneId_WhenReserveForMember_ThenRefusedBeforeTheCartIsTouched() {
        givenSignedInMember();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> reservationService.reserveForMember(VALID_TOKEN, EVENT_ID, 0, InventorySelectionDTO.standing(1)));

        assertEquals("zoneId must be positive", refused.getMessage());
        verifyNoInteractions(activeOrderRepository, eventRepository);
    }

    @Test
    void GivenACartLineOfADeletedEvent_WhenRestoreActiveOrder_ThenTheLineIsNamedUnknownEvent() {
        ActiveOrder cart = new ActiveOrder(USER_ID, NOW);
        cart.addStandingReservation(DELETED_EVENT_ID, ZONE_ID, 1, PRICE, NOW);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(cart);
        when(eventRepository.findById(DELETED_EVENT_ID)).thenThrow(new EventNotFoundException(DELETED_EVENT_ID));

        ActiveOrderDTO restored = reservationService.restoreActiveOrder(USER_ID);

        assertEquals(List.of("Unknown Event"), restored.lines().stream().map(ActiveOrderDTO.CartLineDTO::eventName).toList());
    }

    // --- A guest's session id is the guest's credential, so no log line carries it (#185) ---

    private static final String LOGGED_GUEST_SID = LIVE_GUEST_SESSION_PREFIX + "-9e2a41c7-not-for-logs";

    private LogCapture logs;

    @AfterEach
    void detachLogCapture() {
        if (logs != null) {
            logs.close();
        }
    }

    @Test
    void GivenGuestWithoutCart_WhenReserveForGuest_ThenNoLogEventCarriesTheSessionId() {
        givenEventOnSaleWith(standingZoneOf(10));
        when(activeOrderRepository.getBySessionId(LOGGED_GUEST_SID)).thenReturn(Optional.empty());
        logs = LogCapture.of(ReservationService.class);

        reservationService.reserveForGuest(LOGGED_GUEST_SID, EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(QUANTITY));

        assertTrue(logs.eventCount() > 0, "control: this path logs, so the capture sees the service's logger");
        assertEquals(List.of(), logs.eventsContaining(LOGGED_GUEST_SID),
                "a guest reservation must not log the guest's session id");
    }

    @Test
    void GivenGuestCartInCheckout_WhenReserveForGuest_ThenNoLogEventCarriesTheSessionId() {
        ActiveOrder guestCartInCheckout = ActiveOrder.forGuest(LOGGED_GUEST_SID, NOW);
        guestCartInCheckout.addStandingReservation(EVENT_ID, ZONE_ID, 1, PRICE, NOW);
        guestCartInCheckout.markCheckoutInProgress();
        when(activeOrderRepository.getBySessionId(LOGGED_GUEST_SID)).thenReturn(Optional.of(guestCartInCheckout));
        logs = LogCapture.of(ReservationService.class);

        assertThrows(IllegalStateException.class, () -> reservationService.reserveForGuest(LOGGED_GUEST_SID,
                EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));

        assertTrue(logs.eventCount() > 0, "control: this path logs, so the capture sees the service's logger");
        assertEquals(List.of(), logs.eventsContaining(LOGGED_GUEST_SID),
                "a rejected guest reservation must not log the guest's session id");
    }

    @Test
    void GivenGuestWithoutCart_WhenAbandonActiveOrder_ThenNoLogEventCarriesTheSessionId() {
        when(activeOrderRepository.getBySessionId(LOGGED_GUEST_SID)).thenReturn(Optional.empty());
        logs = LogCapture.of(ReservationService.class);

        reservationService.abandonActiveOrder(BuyerContextDTO.guest(LOGGED_GUEST_SID));

        assertTrue(logs.eventCount() > 0, "control: this path logs, so the capture sees the service's logger");
        assertEquals(List.of(), logs.eventsContaining(LOGGED_GUEST_SID),
                "abandoning a guest's missing cart must not log the guest's session id");
    }

    @Test
    void GivenGuestCart_WhenAbandonActiveOrder_ThenNoLogEventCarriesTheSessionId() {
        ActiveOrder guestCart = ActiveOrder.forGuest(LOGGED_GUEST_SID, NOW);
        when(activeOrderRepository.getBySessionId(LOGGED_GUEST_SID)).thenReturn(Optional.of(guestCart));
        logs = LogCapture.of(ReservationService.class);

        reservationService.abandonActiveOrder(BuyerContextDTO.guest(LOGGED_GUEST_SID));

        assertTrue(logs.eventCount() > 0, "control: this path logs, so the capture sees the service's logger");
        assertEquals(List.of(), logs.eventsContaining(LOGGED_GUEST_SID),
                "abandoning a guest's cart must not log the guest's session id");
    }

    @Test
    void GivenGuestCart_WhenRenewReservationsForGuestCheckout_ThenNoLogEventCarriesTheSessionId() {
        ActiveOrder guestCart = ActiveOrder.forGuest(LOGGED_GUEST_SID, NOW);
        guestCart.addStandingReservation(EVENT_ID, ZONE_ID, 1, 50.0, aHoldStartWithOneMinuteLeft());
        when(activeOrderRepository.getBySessionId(LOGGED_GUEST_SID)).thenReturn(Optional.of(guestCart));
        givenEventOnSaleWith(standingZoneOf(10));
        logs = LogCapture.of(ReservationService.class);

        reservationService.renewReservationsForGuestCheckout(LOGGED_GUEST_SID);

        assertTrue(logs.eventCount() > 0, "control: this path logs, so the capture sees the service's logger");
        assertEquals(List.of(), logs.eventsContaining(LOGGED_GUEST_SID),
                "renewing a guest's holds must not log the guest's session id");
    }

    // --- #187: a reserve that fails after the hold rolls back best-effort and rethrows its own failure.
    // A rollback step that fails too is swallowed, so it must leave a WARN carrying its exception, or
    // the seats or cart line it left behind have no trace in production. ---

    private static final String RESERVE_FAILURE = "reserve-failed-after-the-hold-under-test";
    private static final String RELEASE_FAILURE = "release-of-the-hold-failed-under-test";
    private static final String CART_ROLLBACK_FAILURE = "cart-line-rollback-failed-under-test";

    @Test
    void GivenReleaseFailsWhileRollingBackAFailedReserve_WhenReserveForMember_ThenAWarnCarriesTheReleaseFailure() {
        InventoryCommandPort inventory = mock(InventoryCommandPort.class);
        ReservationService service = reservationServiceOver(inventory);
        ActiveOrder cart = memberWithEmptyCart();
        // The cart save fails after the hold; the cart rollback's own save succeeds, so only the release fails.
        doThrow(new IllegalStateException(RESERVE_FAILURE)).doNothing().when(activeOrderRepository).save(cart);
        when(inventory.release(anyInt(), anyInt(), any(), any())).thenThrow(new IllegalStateException(RELEASE_FAILURE));
        logs = LogCapture.of(ReservationService.class);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));

        assertEquals(RESERVE_FAILURE, thrown.getMessage(), "the reserve's own failure must propagate, not the release's");
        verify(inventory).release(eq(EVENT_ID), eq(ZONE_ID), any(), eq(cart.getOrderKey()));
        assertWarnCarrying(RELEASE_FAILURE);
    }

    @Test
    void GivenCartRollbackFailsAfterAFailedReserve_WhenReserveForMember_ThenAWarnCarriesTheCartRollbackFailure() {
        InventoryCommandPort inventory = mock(InventoryCommandPort.class);
        ReservationService service = reservationServiceOver(inventory);
        ActiveOrder cart = memberWithEmptyCart();
        // The cart save fails after the hold, and so does the save that would undo the cart line.
        doThrow(new IllegalStateException(RESERVE_FAILURE))
                .doThrow(new IllegalStateException(CART_ROLLBACK_FAILURE))
                .when(activeOrderRepository).save(cart);
        logs = LogCapture.of(ReservationService.class);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));

        assertEquals(RESERVE_FAILURE, thrown.getMessage(),
                "the reserve's own failure must propagate, not the cart rollback's");
        verify(inventory).release(eq(EVENT_ID), eq(ZONE_ID), any(), eq(cart.getOrderKey()));
        assertWarnCarrying(CART_ROLLBACK_FAILURE);
    }

    // --- #187: a failed reservation's notice reaches the member's notification, so its reason carries a
    // refusal's own message, and only a generic reason for any other failure (the rule ServiceErrors
    // applies in the UI). ---

    private static final String REFUSAL = "refusal-reason-under-test";
    private static final String INTERNAL_FAILURE_TEXT = "internal-failure-text-under-test";
    private static final String GENERIC_REASON = UserFacingErrors.GENERIC_MESSAGE;

    @Test
    void GivenTheInventoryRefusesTheReservation_WhenReserveForMember_ThenTheFailureNoticeCarriesTheRefusalsReason() {
        InventoryCommandPort inventory = mock(InventoryCommandPort.class);
        memberWithoutCart();
        BusinessRuleViolationException refusal = new BusinessRuleViolationException(REFUSAL);
        whenReserving(inventory).thenThrow(refusal);

        assertSame(refusal, assertThrows(BusinessRuleViolationException.class,
                () -> reservationServiceOver(inventory).reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                        InventorySelectionDTO.standing(QUANTITY))));

        assertEquals(REFUSAL, publishedNotice(TicketReservationFailedNotice.class).reason());
    }

    @Test
    void GivenAnInternalFailureWhileReserving_WhenReserveForMember_ThenTheFailureNoticeCarriesOnlyTheGenericReason() {
        InventoryCommandPort inventory = mock(InventoryCommandPort.class);
        memberWithoutCart();
        PersistenceException internal = new PersistenceException(INTERNAL_FAILURE_TEXT);
        whenReserving(inventory).thenThrow(internal);

        assertSame(internal, assertThrows(PersistenceException.class,
                () -> reservationServiceOver(inventory).reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                        InventorySelectionDTO.standing(QUANTITY))));

        String reason = publishedNotice(TicketReservationFailedNotice.class).reason();
        assertEquals(GENERIC_REASON, reason, "an internal failure's text must not reach the member's notification");
        assertFalse(reason.contains(INTERNAL_FAILURE_TEXT));
    }

    @Test
    void GivenTheInventoryRefusesTheRemoval_WhenRemoveForMember_ThenTheFailureNoticeCarriesTheRefusalsReason() {
        InventoryCommandPort inventory = mock(InventoryCommandPort.class);
        memberWithReservedStandingTickets();
        BusinessRuleViolationException refusal = new BusinessRuleViolationException(REFUSAL);
        when(inventory.release(anyInt(), anyInt(), any(), any())).thenThrow(refusal);

        assertSame(refusal, assertThrows(BusinessRuleViolationException.class,
                () -> reservationServiceOver(inventory).removeForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                        InventorySelectionDTO.standing(QUANTITY))));

        assertEquals(REFUSAL, publishedNotice(ReservationRemovalFailedNotice.class).reason());
    }

    @Test
    void GivenAnInternalFailureWhileRemoving_WhenRemoveForMember_ThenTheFailureNoticeCarriesOnlyTheGenericReason() {
        InventoryCommandPort inventory = mock(InventoryCommandPort.class);
        memberWithReservedStandingTickets();
        DataAccessResourceFailureException internal = new DataAccessResourceFailureException(INTERNAL_FAILURE_TEXT);
        when(inventory.release(anyInt(), anyInt(), any(), any())).thenThrow(internal);

        assertSame(internal, assertThrows(DataAccessResourceFailureException.class,
                () -> reservationServiceOver(inventory).removeForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                        InventorySelectionDTO.standing(QUANTITY))));

        String reason = publishedNotice(ReservationRemovalFailedNotice.class).reason();
        assertEquals(GENERIC_REASON, reason, "an internal failure's text must not reach the member's notification");
        assertFalse(reason.contains(INTERNAL_FAILURE_TEXT));
    }

    private org.mockito.stubbing.OngoingStubbing<Double> whenReserving(InventoryCommandPort inventory) {
        return when(inventory.reserve(anyInt(), anyInt(), any(), any(), any(), anyInt(), any(), anyInt()));
    }

    // A member with no cart yet: the service creates a real ActiveOrder for them.
    private void memberWithoutCart() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(null);
    }

    private void memberWithReservedStandingTickets() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        ActiveOrder cart = new ActiveOrder(USER_ID, NOW);
        cart.addStandingReservation(EVENT_ID, ZONE_ID, QUANTITY, 50.0, NOW);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(cart);
    }

    private <T> T publishedNotice(Class<T> type) {
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(published.capture());
        List<T> notices = published.getAllValues().stream().filter(type::isInstance).map(type::cast).toList();
        assertEquals(1, notices.size(), "expected one " + type.getSimpleName() + ", published: "
                + published.getAllValues());
        return notices.get(0);
    }

    // The same service as setUp's, with the inventory port as a mock so a test can make it fail.
    private ReservationService reservationServiceOver(InventoryCommandPort inventory) {
        return new ReservationService(inventory, activeOrderRepository, sessionManager, eventPublisher,
                UserRepositories.everyMemberExistsAged(MEMBER_AGE), marketGate, mock(ISystemMetrics.class),
                Clock.fixed(T0, ZoneOffset.UTC));
    }

    // The configured hold is a static set at startup, so hold starts are read from it, not hard-coded.
    private static LocalDateTime aHoldStartWithOneMinuteLeft() {
        return NOW.minus(CartLineItem.getExpirationLimit()).plusMinutes(1);
    }

    private static LocalDateTime aHoldStartThatHasRunOut() {
        return NOW.minus(CartLineItem.getExpirationLimit()).minusMinutes(1);
    }

    // A selection of no tickets. The DTO's factory refuses one, so this uses the constructor, which a
    // caller can still reach, to get past the factory to the service's own quantity check.
    private static InventorySelectionDTO zeroQuantitySelection() {
        return new InventorySelectionDTO(0, List.of());
    }

    // A signed-in member whose real cart is still empty.
    private ActiveOrder memberWithEmptyCart() {
        givenSignedInMember();
        ActiveOrder cart = new ActiveOrder(USER_ID, NOW);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(cart);
        return cart;
    }

    private void givenSignedInMember() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
    }

    private StandingZone standingZoneOf(int capacity) {
        return new StandingZone(ZONE_ID, "VIP", capacity, PRICE);
    }

    // A real event on sale whose venue holds the zones given; the event port hands it out.
    private Event givenEventOnSaleWith(InventoryZone... inventoryZones) {
        return givenEventOnSaleWithPolicy(acceptingPurchasePolicy(), inventoryZones);
    }

    private Event givenEventOnSaleWithPolicy(PurchasePolicy purchasePolicy, InventoryZone... inventoryZones) {
        Event onSale = createEventWithPolicyAndZones(purchasePolicy, inventoryZones);
        onSale.transitionToOnSale();
        when(eventRepository.findById(EVENT_ID)).thenReturn(onSale);
        return onSale;
    }

    // Seats A1 and A2, in the zone OTHER_ZONE_ID, so it can sit beside the standing zone ZONE_ID.
    private static SeatedZone stallsWithSeatsA1AndA2() {
        return new SeatedZone(OTHER_ZONE_ID, "Stalls", PRICE, List.of(new Seat("A1", 0, 0), new Seat("A2", 1, 0)));
    }

    private void holdSeatsFor(ActiveOrder cart, SeatedZone seated, List<String> seats) {
        cart.addSeatedReservation(EVENT_ID, seated.getId(), seats, PRICE, NOW);
        seated.reserve(InventorySelection.seated(seats, cart.getOrderKey()));
    }

    // A live guest's real cart, holding `quantity` standing tickets of the zone, which holds them for it.
    private ActiveOrder givenGuestCartHolding(String sessionId, StandingZone standing, int quantity) {
        ActiveOrder cart = ActiveOrder.forGuest(sessionId, NOW);
        cart.addStandingReservation(EVENT_ID, ZONE_ID, quantity, PRICE, NOW);
        standing.reserve(InventorySelection.standing(quantity, cart.getOrderKey()));
        when(activeOrderRepository.getBySessionId(sessionId)).thenReturn(Optional.of(cart));
        return cart;
    }

    // The member's real cart, holding `quantity` standing tickets of the zone, which holds them for it.
    private ActiveOrder givenMemberCartHolding(StandingZone standing, int quantity) {
        ActiveOrder cart = new ActiveOrder(USER_ID, NOW);
        cart.addStandingReservation(EVENT_ID, ZONE_ID, quantity, PRICE, NOW);
        standing.reserve(InventorySelection.standing(quantity, cart.getOrderKey()));
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(cart);
        return cart;
    }

    private ActiveOrder savedCart() {
        ArgumentCaptor<ActiveOrder> saved = ArgumentCaptor.forClass(ActiveOrder.class);
        verify(activeOrderRepository).save(saved.capture());
        return saved.getValue();
    }

    private void assertWarnCarrying(String failure) {
        List<String> carrying = logs.eventsContaining(failure);
        assertTrue(carrying.stream().anyMatch(line -> line.startsWith("WARN ")),
                "expected a WARN from ReservationService carrying '" + failure + "'; events carrying it: " + carrying);
    }

    // How long a race may take to get ready, and then to finish, before the test fails. A run that stalls
    // fails either way, so the bound only has to be generous for a slow CI runner.
    private static final long RACE_AWAIT_SECONDS = 20;

    /** What a race of simultaneous attempts came to. */
    private record RaceOutcome(int successes, int refusals, List<Throwable> unexpected) { }

    // Starts every attempt at once and waits for all of them. A lost race ends in one of the refusals this
    // path throws: the zone's or the event's IllegalStateException (no stock left, the seat is taken, the
    // event sold out) or the cart's IllegalArgumentException (it no longer holds the line). Anything else,
    // a NullPointerException say, is collected as unexpected rather than counted as a lost race.
    private static RaceOutcome raceAll(List<Runnable> attempts) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(attempts.size());
        CountDownLatch ready = new CountDownLatch(attempts.size());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts.size());
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger refusals = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        try {
            for (Runnable attempt : attempts) {
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        if (!start.await(RACE_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                            unexpected.add(new AssertionError("the race was never started"));
                            return;
                        }
                        attempt.run();
                        successes.incrementAndGet();
                    } catch (IllegalStateException | IllegalArgumentException refusal) {
                        refusals.incrementAndGet();
                    } catch (Throwable t) {
                        unexpected.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(RACE_AWAIT_SECONDS, TimeUnit.SECONDS), "the attempts did not all get ready in time");
            start.countDown();
            assertTrue(done.await(RACE_AWAIT_SECONDS, TimeUnit.SECONDS), "the attempts did not all finish in time");
        } finally {
            pool.shutdownNow();
        }
        return new RaceOutcome(successes.get(), refusals.get(), List.copyOf(unexpected));
    }

    // helper test methods:

    private PurchasePolicy acceptingPurchasePolicy() {
        return new NoPurchasePolicy();
    }

    private DiscountPolicy noDiscountPolicy() {
        return new DiscountPolicy(0) {
            @Override
            public double calculate(int quantity, Double priceAtOneTicketReservation, LocalDateTime now) {
                return quantity * priceAtOneTicketReservation;
            }

            @Override
            public double calculatePriceforoneticket(int quantity, Double priceAtOneTicketReservation,
                    LocalDateTime now) {
                return priceAtOneTicketReservation;
            }
        };
    }

    private Event createEventWithZone(InventoryZone inventoryZone) {
        return createEventWithPolicyAndZones(acceptingPurchasePolicy(), inventoryZone);
    }

    private Event createEventWithPolicyAndZones(PurchasePolicy purchasePolicy, InventoryZone... inventoryZones) {
        return new Event(
                EVENT_ID,
                "Concert",
                4.5,
                List.of("Artist"),
                EventCategory.CONCERT,
                100,
                EventStatus.SCHEDULED,
                new VenueMap(1, new Location("Israel", "Tel Aviv"), List.of(inventoryZones)),
                List.of(new ShowDate(NOW.plusDays(10), NOW.plusDays(10).plusHours(2))),
                ZoneId.of("Asia/Jerusalem"),
                purchasePolicy,
                noDiscountPolicy());
    }
}
