package com.ticketing.system.unit.application;

import com.ticketing.system.Core.Application.dto.ReservationResultDTO;
import com.ticketing.system.Core.Application.interfaces.INotificationService;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.Core.Application.interfaces.ISystemMetrics;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.Core.Application.services.SystemAdminService;
import com.ticketing.system.shared.exception.MarketNotOpenException;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.domain.InventorySelection;
import com.ticketing.system.catalog.domain.InventoryZone;
import com.ticketing.system.catalog.domain.StandingZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.Optional;

import java.util.ArrayList;
import java.util.List;

import org.mockito.ArgumentCaptor;

import com.ticketing.system.Core.Application.dto.ActiveOrderDTO;
import com.ticketing.system.Core.Application.dto.InventorySelectionDTO;
import com.ticketing.system.sales.domain.CartLineItem;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.catalog.domain.DiscountPolicy;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.Seat;
import com.ticketing.system.catalog.domain.SeatStatus;
import com.ticketing.system.catalog.domain.SeatedZone;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.catalog.domain.VenueMap;
import com.ticketing.system.sales.domain.NoPurchasePolicy;
import com.ticketing.system.sales.domain.PurchasePolicy;

public class ReservationServiceTest {

    private EventRepository eventRepository;
    private ActiveOrderRepository activeOrderRepository;
    private SessionManager sessionManager;
    private INotificationService notificationService;
    private SystemAdminService systemAdminService;

    private ReservationService reservationService;

    private Event event;
    private InventoryZone zone;
    private ActiveOrder activeOrder;

    private final String VALID_TOKEN = "valid-token";
    private final int USER_ID = 1;
    private final int EVENT_ID = 10;
    private final int ZONE_ID = 5;
    private final int QUANTITY = 2;

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        activeOrderRepository = mock(ActiveOrderRepository.class);
        sessionManager = mock(SessionManager.class);
        notificationService = mock(INotificationService.class);

        event = mock(Event.class, RETURNS_DEEP_STUBS);
        zone = mock(InventoryZone.class);
        activeOrder = mock(ActiveOrder.class);

        systemAdminService = mock(SystemAdminService.class);
        when(systemAdminService.isMarketOpen()).thenReturn(true);

        reservationService = new ReservationService(
                eventRepository,
                activeOrderRepository,
                sessionManager,
                notificationService,
                mock(ProductionCompanyRepository.class),
                mock(UserRepository.class),
                systemAdminService,
                mock(ISystemMetrics.class)
        );
    }

    // --- UC-32 / I.2.1: holding tickets gated on an OPEN market ---

    @Test
    void GivenMarketClosed_WhenReserveForMember_ThenThrows() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(systemAdminService.isMarketOpen()).thenReturn(false);

        assertThrows(MarketNotOpenException.class,
                () -> reservationService.reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                        InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenValidRequest_WhenremoveReservedStandingSpots_ThenReturnReservationResult() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(ZONE_ID)).thenReturn(zone);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(activeOrder);
        when(activeOrder.hasReservationForEvent(EVENT_ID)).thenReturn(true);
        when(activeOrder.countTickets(EVENT_ID, ZONE_ID)).thenReturn(QUANTITY);

        ReservationResultDTO result = reservationService.removeForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                InventorySelectionDTO.standing(QUANTITY));

        assertEquals(EVENT_ID, result.getEventId());
    }

    @Test
    void GivenMissingToken_WhenremoveReservedStandingSpots_ThenThrowException() {
        assertThrows(IllegalArgumentException.class, () -> reservationService.removeForMember(null, EVENT_ID, ZONE_ID,
                InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenInvalidToken_WhenremoveReservedStandingSpots_ThenThrowException() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> reservationService.removeForMember(VALID_TOKEN, EVENT_ID,
                ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenInvalidQuantity_WhenremoveReservedStandingSpots_ThenThrowException() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);

        assertThrows(IllegalArgumentException.class, () -> reservationService.removeForMember(VALID_TOKEN, EVENT_ID,
                ZONE_ID, InventorySelectionDTO.standing(0)));
    }

    @Test
    void GivenEventDoesNotExist_WhenremoveReservedStandingSpots_ThenThrowException() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> reservationService.removeForMember(VALID_TOKEN, EVENT_ID,
                ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenZoneDoesNotExist_WhenremoveReservedStandingSpots_ThenThrowException() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(ZONE_ID)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> reservationService.removeForMember(VALID_TOKEN, EVENT_ID,
                ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenActiveOrderDoesNotExist_WhenremoveReservedStandingSpots_ThenThrowException() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(ZONE_ID)).thenReturn(zone);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> reservationService.removeForMember(VALID_TOKEN, EVENT_ID,
                ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenOrderDoesNotContainEvent_WhenremoveReservedStandingSpots_ThenThrowException() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(ZONE_ID)).thenReturn(zone);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(activeOrder);

        doThrow(new IllegalArgumentException("Active order does not contain this event"))
                .when(activeOrder)
                .validateContainsReservation(eq(EVENT_ID), eq(ZONE_ID), any(InventorySelection.class));

        Exception exception = assertThrows(IllegalArgumentException.class, () -> reservationService
                .removeForMember(VALID_TOKEN, EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));

        assertEquals("Active order does not contain this event", exception.getMessage());
    }

    @Test
    void GivenNotEnoughReservedTickets_WhenremoveReservedStandingSpots_ThenThrowException() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(ZONE_ID)).thenReturn(zone);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(activeOrder);

        doThrow(new IllegalArgumentException("Not enough reserved tickets to remove"))
                .when(activeOrder)
                .validateContainsReservation(eq(EVENT_ID), eq(ZONE_ID), any(InventorySelection.class));

        Exception exception = assertThrows(IllegalArgumentException.class, () -> reservationService
                .removeForMember(VALID_TOKEN, EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));

        assertEquals("Not enough reserved tickets to remove", exception.getMessage());
    }

    @Test
    void GivenValidMemberRequest_WhenreserveStandingTicketsForMember_ThenReturnReservationResult() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(ZONE_ID)).thenReturn(zone);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(activeOrder);

        when(activeOrder.hasReservationForEvent(EVENT_ID)).thenReturn(false);
        when(zone.getAvailableAmount()).thenReturn(10);
        when(zone.getprice()).thenReturn(100.0);

        ReservationResultDTO result = reservationService.reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                InventorySelectionDTO.standing(QUANTITY));

        assertEquals(EVENT_ID, result.getEventId());
    }

    @Test
    void GivenMissingToken_WhenreserveStandingTicketsForMember_ThenThrowException() {
        assertThrows(IllegalArgumentException.class, () -> reservationService.reserveForMember(null, EVENT_ID, ZONE_ID,
                InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenInvalidToken_WhenreserveStandingTicketsForMember_ThenThrowException() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> reservationService.reserveForMember(VALID_TOKEN, EVENT_ID,
                ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenInvalidUserId_WhenreserveStandingTicketsForMember_ThenThrowException() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(0);

        assertThrows(IllegalArgumentException.class, () -> reservationService.reserveForMember(VALID_TOKEN, EVENT_ID,
                ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenInvalidQuantity_WhenreserveStandingTicketsForMember_ThenThrowException() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);

        assertThrows(IllegalArgumentException.class, () -> reservationService.reserveForMember(VALID_TOKEN, EVENT_ID,
                ZONE_ID, InventorySelectionDTO.standing(0)));
    }

    @Test
    void GivenEventDoesNotExist_WhenreserveStandingTicketsForMember_ThenThrowException() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> reservationService.reserveForMember(VALID_TOKEN, EVENT_ID,
                ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenZoneDoesNotExist_WhenreserveStandingTicketsForMember_ThenThrowException() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(ZONE_ID))
                .thenThrow(new IllegalArgumentException("Zone not found: " + ZONE_ID));

        assertThrows(IllegalArgumentException.class, () -> reservationService.reserveForMember(VALID_TOKEN, EVENT_ID,
                ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenNotEnoughTickets_WhenreserveStandingTicketsForMember_ThenThrowException() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(ZONE_ID)).thenReturn(zone);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(activeOrder);

        when(activeOrder.hasReservationForEvent(EVENT_ID)).thenReturn(false);

        doThrow(new IllegalStateException("remaining 1 tickets available"))
                .when(event).reserveInventory(eq(ZONE_ID), any(InventorySelection.class));

        assertThrows(IllegalStateException.class,
                () -> reservationService.reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                        InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenNoActiveOrder_WhenreserveStandingTicketsForMember_ThenCreateNewOrderAndReturnResult() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(ZONE_ID)).thenReturn(zone);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(null);

        when(zone.getAvailableAmount()).thenReturn(10);
        when(zone.getprice()).thenReturn(100.0);

        ReservationResultDTO result = reservationService.reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                InventorySelectionDTO.standing(QUANTITY));

        assertEquals(EVENT_ID, result.getEventId());
    }

    @Test
    void GivenValidGuestRequest_WhenreserveStandingTicketsForGuest_ThenReturnReservationResult() {
        String sessionId = "guest-session";

        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(ZONE_ID)).thenReturn(zone);
        when(activeOrderRepository.getBySessionId(sessionId)).thenReturn(Optional.of(activeOrder));

        when(activeOrder.hasReservationForEvent(EVENT_ID)).thenReturn(false);
        when(zone.getAvailableAmount()).thenReturn(10);
        when(zone.getprice()).thenReturn(100.0);

        ReservationResultDTO result = reservationService.reserveForGuest(sessionId, EVENT_ID, ZONE_ID,
                InventorySelectionDTO.standing(QUANTITY));

        assertEquals(EVENT_ID, result.getEventId());
    }

    @Test
    void GivenMissingSessionId_WhenreserveStandingTicketsForGuest_ThenThrowException() {
        assertThrows(IllegalArgumentException.class, () -> reservationService.reserveForGuest(null, EVENT_ID, ZONE_ID,
                InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenBlankSessionId_WhenreserveStandingTicketsForGuest_ThenThrowException() {
        assertThrows(IllegalArgumentException.class, () -> reservationService.reserveForGuest("   ", EVENT_ID, ZONE_ID,
                InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenInvalidQuantity_WhenreserveStandingTicketsForGuest_ThenThrowException() {
        String sessionId = "guest-session";

        assertThrows(IllegalArgumentException.class, () -> reservationService.reserveForGuest(sessionId, EVENT_ID,
                ZONE_ID, InventorySelectionDTO.standing(0)));
    }

    @Test
    void GivenEventDoesNotExist_WhenreserveStandingTicketsForGuest_ThenThrowException() {
        String sessionId = "guest-session";

        when(eventRepository.findById(EVENT_ID)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> reservationService.reserveForGuest(sessionId, EVENT_ID,
                ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenZoneDoesNotExist_WhenreserveStandingTicketsForGuest_ThenThrowException() {
        String sessionId = "guest-session";

        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(ZONE_ID))
                .thenThrow(new IllegalArgumentException("Zone not found: " + ZONE_ID));

        assertThrows(IllegalArgumentException.class,
                () -> reservationService.reserveForGuest(sessionId, EVENT_ID, ZONE_ID,
                        InventorySelectionDTO.standing(QUANTITY)));
    }

    @Test
    void GivenNotEnoughTickets_WhenreserveStandingTicketsForGuest_ThenThrowException() {
        String sessionId = "guest-session";

        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(ZONE_ID)).thenReturn(zone);
        when(activeOrderRepository.getBySessionId(sessionId)).thenReturn(Optional.of(activeOrder));

        when(activeOrder.hasReservationForEvent(EVENT_ID)).thenReturn(false);

        doThrow(new IllegalStateException("remaining 1 tickets available"))
                .when(event).reserveInventory(eq(ZONE_ID), any(InventorySelection.class));
        Exception exception = assertThrows(IllegalStateException.class, () -> reservationService
                .reserveForGuest(sessionId, EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(QUANTITY)));

        assertEquals("remaining 1 tickets available", exception.getMessage());
    }

    @Test
    void GivenNoActiveOrder_WhenreserveStandingTicketsForGuest_ThenCreateNewOrderAndReturnResult() {
        String sessionId = "guest-session";

        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(ZONE_ID)).thenReturn(zone);
        when(activeOrderRepository.getBySessionId(sessionId)).thenReturn(Optional.empty());

        when(zone.getAvailableAmount()).thenReturn(10);
        when(zone.getprice()).thenReturn(100.0);

        ReservationResultDTO result = reservationService.reserveForGuest(sessionId, EVENT_ID, ZONE_ID,
                InventorySelectionDTO.standing(QUANTITY));

        assertEquals(EVENT_ID, result.getEventId());
    }

    @Test
    void GivenManyGuestsReserveSameZoneConcurrently_WhenreserveStandingTicketsForGuest_ThenDoNotOverReserve()
            throws InterruptedException {
        int capacity = 5;
        int numberOfThreads = 20;
        int quantityPerRequest = 1;

        InventoryZone realZone = new StandingZone(ZONE_ID, "VIP", capacity, 100.0);

        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(ZONE_ID)).thenReturn(realZone);
        doAnswer(inv -> {
            InventorySelection selection = inv.getArgument(1);
            realZone.reserve(selection);
            return null;
        }).when(event).reserveInventory(eq(ZONE_ID), any(InventorySelection.class));

        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i = i + 1) {
            final String sessionId = "guest-session-" + i;

            when(activeOrderRepository.getBySessionId(sessionId)).thenReturn(Optional.empty());

            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    reservationService.reserveForGuest(sessionId, EVENT_ID, ZONE_ID,
                            InventorySelectionDTO.standing(quantityPerRequest));

                    successCount.incrementAndGet();

                } catch (Exception e) {
                    failureCount.incrementAndGet();

                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();

        boolean finished = doneLatch.await(5, TimeUnit.SECONDS);
        executorService.shutdown();

        assertEquals(true, finished);
        assertEquals(capacity, successCount.get());
        assertEquals(numberOfThreads - capacity, failureCount.get());
        assertEquals(0, realZone.getAvailableAmount());
        assertEquals(capacity, realZone.getReservedAmount());
    }

    @Test
    void GivenManyMembersReserveSameZoneConcurrently_WhenreserveStandingTicketsForMember_ThenDoNotOverReserve()
            throws InterruptedException {

        int capacity = 5;
        int numberOfThreads = 20;
        int quantityPerRequest = 1;

        InventoryZone realZone = new StandingZone(ZONE_ID, "VIP", capacity, 100.0);

        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(ZONE_ID)).thenReturn(realZone);
        doAnswer(inv -> {
            InventorySelection selection = inv.getArgument(1);
            realZone.reserve(selection);
            return null;
        }).when(event).reserveInventory(eq(ZONE_ID), any(InventorySelection.class));

        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i = i + 1) {
            final String token = "valid-token-" + i;
            final int userId = i + 1;

            when(sessionManager.validateToken(token)).thenReturn(true);
            when(sessionManager.extractUserId(token)).thenReturn(userId);
            when(activeOrderRepository.getByUserId(userId)).thenReturn(null);

            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    reservationService.reserveForMember(token, EVENT_ID, ZONE_ID,
                            InventorySelectionDTO.standing(quantityPerRequest));

                    successCount.incrementAndGet();

                } catch (Exception e) {
                    failureCount.incrementAndGet();

                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();

        boolean finished = doneLatch.await(5, TimeUnit.SECONDS);
        executorService.shutdown();

        assertEquals(true, finished);
        assertEquals(capacity, successCount.get());
        assertEquals(numberOfThreads - capacity, failureCount.get());
        assertEquals(0, realZone.getAvailableAmount());
        assertEquals(capacity, realZone.getReservedAmount());
    }

    @Test
    void GivenManyThreadsremoveReservedStandingSpotsForMemberConcurrently_WhenremoveReservedStandingSpots_ThenDoNotOverRelease()
            throws InterruptedException {

        int initialReservedTickets = 5;
        int capacity = 10;
        int numberOfThreads = 20;
        int quantityPerRemove = 1;

        ActiveOrder realActiveOrder = new ActiveOrder(USER_ID);
        InventoryZone realZone = new StandingZone(ZONE_ID, "VIP", capacity, 100.0);
        realZone.reserve(InventorySelection.standing(initialReservedTickets, realActiveOrder.getOrderKey()));

        realActiveOrder.addStandingReservation(
                EVENT_ID,
                ZONE_ID,
                initialReservedTickets,
                100.0,
                LocalDateTime.now());

        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(ZONE_ID)).thenReturn(realZone);
        doAnswer(inv -> {
            InventorySelection selection = inv.getArgument(1);
            realZone.release(selection);
            return null;
        }).when(event).releaseInventory(eq(ZONE_ID), any(InventorySelection.class));
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(realActiveOrder);

        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i = i + 1) {
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    reservationService.removeForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                            InventorySelectionDTO.standing(quantityPerRemove));

                    successCount.incrementAndGet();

                } catch (Exception e) {
                    failureCount.incrementAndGet();

                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();

        boolean finished = doneLatch.await(5, TimeUnit.SECONDS);
        executorService.shutdown();

        assertEquals(true, finished);
        assertEquals(initialReservedTickets, successCount.get());
        assertEquals(numberOfThreads - initialReservedTickets, failureCount.get());
        assertEquals(0, realActiveOrder.countTickets(EVENT_ID, ZONE_ID));
        assertEquals(0, realZone.getReservedAmount());
        assertEquals(capacity, realZone.getAvailableAmount());
    }

    // ---- Seated-zone concurrency ----------------------------------------
    // These isolate SeatedZone's per-seat ReentrantLock (the real guarantee against
    // double-booking) by reserving against a real SeatedZone while the event is a
    // deep-stub
    // whose reserveInventory delegates to the zone — same approach as the standing
    // tests above.

    @Test
    void GivenManyMembersSelectSameSeatConcurrently_WhenReserveSeatsForMember_ThenOnlyOneSucceeds()
            throws InterruptedException {

        int numberOfThreads = 20;
        String contestedSeat = "A1";

        SeatedZone realZone = new SeatedZone(
                ZONE_ID, "Orchestra", 120.0,
                List.of(new Seat("A1", 0, 0), new Seat("A2", 1, 0), new Seat("A3", 2, 0)));

        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(ZONE_ID)).thenReturn(realZone);
        doAnswer(inv -> {
            InventorySelection selection = inv.getArgument(1);
            realZone.reserve(selection);
            return null;
        }).when(event).reserveInventory(eq(ZONE_ID), any(InventorySelection.class));

        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i = i + 1) {
            final String token = "valid-token-" + i;
            final int userId = i + 1;

            when(sessionManager.validateToken(token)).thenReturn(true);
            when(sessionManager.extractUserId(token)).thenReturn(userId);
            when(activeOrderRepository.getByUserId(userId)).thenReturn(null);

            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    reservationService.reserveForMember(token, EVENT_ID, ZONE_ID,
                            InventorySelectionDTO.seated(List.of(contestedSeat)));

                    successCount.incrementAndGet();

                } catch (Exception e) {
                    failureCount.incrementAndGet();

                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();

        boolean finished = doneLatch.await(5, TimeUnit.SECONDS);
        executorService.shutdown();

        assertEquals(true, finished);
        assertEquals(1, successCount.get());
        assertEquals(numberOfThreads - 1, failureCount.get());
        assertEquals(SeatStatus.RESERVED, realZone.getSeatStatus(contestedSeat));
        assertEquals(1, realZone.getReservedAmount());
    }

    @Test
    void GivenManyGuestsSelectSameSeatConcurrently_WhenReserveSeatsForGuest_ThenOnlyOneSucceeds()
            throws InterruptedException {

        int numberOfThreads = 20;
        String contestedSeat = "A1";

        SeatedZone realZone = new SeatedZone(
                ZONE_ID, "Orchestra", 120.0,
                List.of(new Seat("A1", 0, 0), new Seat("A2", 1, 0), new Seat("A3", 2, 0)));

        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(ZONE_ID)).thenReturn(realZone);
        doAnswer(inv -> {
            InventorySelection selection = inv.getArgument(1);
            realZone.reserve(selection);
            return null;
        }).when(event).reserveInventory(eq(ZONE_ID), any(InventorySelection.class));

        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i = i + 1) {
            final String sessionId = "guest-session-" + i;

            when(activeOrderRepository.getBySessionId(sessionId)).thenReturn(Optional.empty());

            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    reservationService.reserveForGuest(sessionId, EVENT_ID, ZONE_ID,
                            InventorySelectionDTO.seated(List.of(contestedSeat)));

                    successCount.incrementAndGet();

                } catch (Exception e) {
                    failureCount.incrementAndGet();

                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();

        boolean finished = doneLatch.await(5, TimeUnit.SECONDS);
        executorService.shutdown();

        assertEquals(true, finished);
        assertEquals(1, successCount.get());
        assertEquals(numberOfThreads - 1, failureCount.get());
        assertEquals(SeatStatus.RESERVED, realZone.getSeatStatus(contestedSeat));
        assertEquals(1, realZone.getReservedAmount());
    }

    @Test
    void GivenManyThreadsReserveDistinctSeatsConcurrently_WhenReserveSeatsForMember_ThenAllSucceed()
            throws InterruptedException {

        int numberOfThreads = 20;

        List<Seat> seats = new ArrayList<>();
        for (int i = 0; i < numberOfThreads; i = i + 1) {
            seats.add(new Seat("A" + (i + 1), i, 0));
        }
        SeatedZone realZone = new SeatedZone(ZONE_ID, "Orchestra", 120.0, seats);

        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(ZONE_ID)).thenReturn(realZone);
        doAnswer(inv -> {
            InventorySelection selection = inv.getArgument(1);
            realZone.reserve(selection);
            return null;
        }).when(event).reserveInventory(eq(ZONE_ID), any(InventorySelection.class));

        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i = i + 1) {
            final String token = "valid-token-" + i;
            final int userId = i + 1;
            final String seatLabel = "A" + (i + 1);

            when(sessionManager.validateToken(token)).thenReturn(true);
            when(sessionManager.extractUserId(token)).thenReturn(userId);
            when(activeOrderRepository.getByUserId(userId)).thenReturn(null);

            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    reservationService.reserveForMember(token, EVENT_ID, ZONE_ID,
                            InventorySelectionDTO.seated(List.of(seatLabel)));

                    successCount.incrementAndGet();

                } catch (Exception e) {
                    failureCount.incrementAndGet();

                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();

        boolean finished = doneLatch.await(5, TimeUnit.SECONDS);
        executorService.shutdown();

        assertEquals(true, finished);
        assertEquals(numberOfThreads, successCount.get());
        assertEquals(0, failureCount.get());
        assertEquals(numberOfThreads, realZone.getReservedAmount());
        assertEquals(0, realZone.getAvailableAmount());
    }

    // NOTE: the in-checkout expiry guard formerly tested here against the
    // now-removed
    // ReservationService.expireActiveOrders() lives on the live path and is covered
    // by
    // SessionAndOrderSweeperTest.GivenExpiredCartInCheckoutProgress_WhenSweepExpiredOrders_ThenDoNotReleaseOrDelete.

    @Test
    void GivenValidMemberSeatSelection_WhenReserveSeatsForMember_ThenSeatsReservedAndCartContainsSeatNumbers() {
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
    void GivenValidGuestSeatSelection_WhenReserveSeatsForGuest_ThenSeatsReservedAndGuestCartSaved() {
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
    void GivenStandingZone_WhenReserveSeatsForMember_ThenThrowsException() {
        StandingZone standingZone = new StandingZone(ZONE_ID, "General Admission", 10, 50.0);
        Event realEvent = createEventWithZone(standingZone);

        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(realEvent);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(null);

        realEvent.transitionToOnSale();
        assertThrows(IllegalArgumentException.class, () -> reservationService.reserveForMember(VALID_TOKEN, EVENT_ID,
                ZONE_ID, InventorySelectionDTO.seated(List.of("A1"))));

        assertEquals(10, standingZone.getAvailableAmount());
        verify(activeOrderRepository, never()).save(any());
    }

    @Test
    void GivenSeatedZone_WhenreserveStandingTicketsForMemberWithQuantityOnly_ThenThrowsException() {
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
        assertThrows(IllegalArgumentException.class, () -> reservationService.reserveForMember(VALID_TOKEN, EVENT_ID,
                ZONE_ID, InventorySelectionDTO.standing(1)));

        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A1"));
        verify(activeOrderRepository, never()).save(any());
    }

    @Test
    void GivenCartSaveFails_WhenReserveSeatsForMember_ThenReservedSeatsAreRolledBack() {
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
        doThrow(new RuntimeException("save failed")).when(activeOrderRepository).save(any(ActiveOrder.class));

        assertThrows(RuntimeException.class, () -> reservationService.reserveForMember(VALID_TOKEN, EVENT_ID, ZONE_ID,
                InventorySelectionDTO.seated(List.of("A1", "A2"))));

        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A1"));
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A2"));
    }

    @Test
    void GivenReservedSeatsInCart_WhenRemoveReservedSeats_ThenExactSeatsReleasedAndRemovedFromCart() {
        SeatedZone seatedZone = new SeatedZone(
                ZONE_ID,
                "Orchestra",
                120.0,
                List.of(
                        new Seat("A1", 0, 0),
                        new Seat("A2", 1, 0),
                        new Seat("A3", 2, 0)));
        Event realEvent = createEventWithZone(seatedZone);
        ActiveOrder realOrder = new ActiveOrder(USER_ID);
        seatedZone.reserve(InventorySelection.seated(List.of("A1", "A2", "A3"), realOrder.getOrderKey()));

        realOrder.addSeatedReservation(EVENT_ID, ZONE_ID, List.of("A1", "A2", "A3"), 120.0, LocalDateTime.now());

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
    void GivenSuccessfulMemberReservation_WhenReserve_ThenNotifyAfterUnlocking() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);

        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(ZONE_ID)).thenReturn(zone);
        when(zone.getprice()).thenReturn(50.0);

        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(activeOrder);
        when(activeOrder.getOrderKey()).thenReturn("order-1");

        reservationService.reserveForMember(
                VALID_TOKEN,
                EVENT_ID,
                ZONE_ID,
                InventorySelectionDTO.standing(QUANTITY));

        org.mockito.InOrder inOrder = inOrder(eventRepository, activeOrderRepository, notificationService);

        inOrder.verify(eventRepository).unlockBuyerOperation(EVENT_ID);
        inOrder.verify(activeOrderRepository).unlock("user:" + USER_ID);
        inOrder.verify(notificationService)
                .notifyTicketReservationSuccess(USER_ID, EVENT_ID, ZONE_ID, QUANTITY);
    }

    // ---------------------------------------------------------------------
    // Checkout-entry timer renewal (reset every reserved ticket to the full window)
    // ---------------------------------------------------------------------

    @Test
    void GivenValidCartNearExpiry_WhenRenewReservationsForMemberCheckout_ThenTimersResetToFullWindow() {
        ActiveOrder realOrder = new ActiveOrder(USER_ID);
        realOrder.addStandingReservation(EVENT_ID, ZONE_ID, 2, 50.0, LocalDateTime.now().minusMinutes(9));
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(realOrder);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);

        ActiveOrderDTO dto = reservationService.renewReservationsForMemberCheckout(USER_ID);

        assertNotNull(dto);
        assertTrue(dto.remainingSecondsBeforeExpiry() > 590,
                "timers should be reset close to the full 10-minute window");
        verify(activeOrderRepository).lockForUpdate("user:" + USER_ID);
        verify(activeOrderRepository).unlock("user:" + USER_ID);
        verify(activeOrderRepository).save(realOrder);
    }

    @Test
    void GivenExpiredItem_WhenRenewReservationsForMemberCheckout_ThenTimersNotResetAndNotSaved() {
        ActiveOrder realOrder = new ActiveOrder(USER_ID);
        realOrder.addStandingReservation(EVENT_ID, ZONE_ID, 1, 50.0, LocalDateTime.now().minusMinutes(20));
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(realOrder);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);

        ActiveOrderDTO dto = reservationService.renewReservationsForMemberCheckout(USER_ID);

        assertEquals(0, dto.remainingSecondsBeforeExpiry(), "already-expired cart must not be revived");
        verify(activeOrderRepository, never()).save(any());
        verify(activeOrderRepository).unlock("user:" + USER_ID);
    }

    @Test
    void GivenCheckoutInProgress_WhenRenewReservationsForMemberCheckout_ThenTimersNotResetAndNotSaved() {
        ActiveOrder realOrder = new ActiveOrder(USER_ID);
        realOrder.addStandingReservation(EVENT_ID, ZONE_ID, 1, 50.0, LocalDateTime.now().minusMinutes(9));
        realOrder.markCheckoutInProgress();
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(realOrder);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);

        ActiveOrderDTO dto = reservationService.renewReservationsForMemberCheckout(USER_ID);

        assertTrue(dto.remainingSecondsBeforeExpiry() < 120, "in-checkout cart timers must be left untouched");
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
        ActiveOrder realOrder = ActiveOrder.forGuest(sessionId);
        realOrder.addStandingReservation(EVENT_ID, ZONE_ID, 1, 50.0, LocalDateTime.now().minusMinutes(9));
        when(activeOrderRepository.getBySessionId(sessionId)).thenReturn(Optional.of(realOrder));
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);

        ActiveOrderDTO dto = reservationService.renewReservationsForGuestCheckout(sessionId);

        assertNotNull(dto);
        assertTrue(dto.remainingSecondsBeforeExpiry() > 590);
        verify(activeOrderRepository).lockForUpdate("sess:" + sessionId);
        verify(activeOrderRepository).unlock("sess:" + sessionId);
        verify(activeOrderRepository).save(realOrder);
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
        return new Event(
                EVENT_ID,
                "Concert",
                4.5,
                List.of("Artist"),
                EventCategory.CONCERT,
                100,
                EventStatus.SCHEDULED,
                new VenueMap(1, new Location("Israel", "Tel Aviv"), List.of(inventoryZone)),
                List.of(new ShowDate(LocalDateTime.now().plusDays(10), LocalDateTime.now().plusDays(10).plusHours(2))),
                acceptingPurchasePolicy(),
                noDiscountPolicy());
    }

}