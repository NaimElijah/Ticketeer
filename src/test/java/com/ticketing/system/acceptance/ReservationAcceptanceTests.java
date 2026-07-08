package com.ticketing.system.acceptance;
import com.ticketing.system.identity.domain.User;

import com.ticketing.system.Core.Application.dto.InventorySelectionDTO;
import com.ticketing.system.Core.Application.dto.ReservationResultDTO;
import com.ticketing.system.Core.Application.interfaces.INotificationService;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.Core.Application.interfaces.ISystemMetrics;
import com.ticketing.system.Core.Application.services.ReservationService;
import com.ticketing.system.Core.Application.services.SystemAdminService;
import com.ticketing.system.Core.Domain.ActiveOrder.ActiveOrder;
import com.ticketing.system.Core.Domain.ActiveOrder.CartLineItem;
import com.ticketing.system.Core.Domain.ActiveOrder.IActiveOrderRepository;
import com.ticketing.system.Core.Domain.events.Event;
import com.ticketing.system.Core.Domain.events.IEventRepository;
import com.ticketing.system.Core.Domain.events.InventorySelection;
import com.ticketing.system.Core.Domain.events.InventoryZone;
import com.ticketing.system.Core.Domain.events.Seat;
import com.ticketing.system.Core.Domain.events.SeatStatus;
import com.ticketing.system.Core.Domain.events.SeatedZone;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.identity.application.port.out.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class ReservationAcceptanceTests {

    private ReservationService reservationService;

    private IEventRepository eventRepository;
    private IActiveOrderRepository activeOrderRepository;
    private SessionManager sessionManager;
    private INotificationService notificationService;

    private Event event;
    private InventoryZone zone;
    private ActiveOrder activeOrder;

    @BeforeEach
    void setUp() {
        eventRepository = mock(IEventRepository.class);
        activeOrderRepository = mock(IActiveOrderRepository.class);
        sessionManager = mock(SessionManager.class);
        notificationService = mock(INotificationService.class);

        SystemAdminService systemAdminService = mock(SystemAdminService.class);
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

        event = mock(Event.class, RETURNS_DEEP_STUBS);
        zone = mock(InventoryZone.class);
        activeOrder = new ActiveOrder(1);

        when(sessionManager.validateToken("validToken")).thenReturn(true);
        when(sessionManager.extractUserId("validToken")).thenReturn(1);

        when(eventRepository.findById(100)).thenReturn(event);
        when(event.getVenueMap().getZone(1)).thenReturn(zone);

        when(zone.getAvailableAmount()).thenReturn(10);
        when(zone.getprice()).thenReturn(50.0);

        when(activeOrderRepository.getByUserId(1)).thenReturn(activeOrder);
    }

    @Test
    void GivenValidMemberReservation_WhenreserveStandingTicketsForMember_ThenReturnReservationResult() {
        ReservationResultDTO result = reservationService.reserveForMember(
                "validToken",
                100,
                1,
                InventorySelectionDTO.standing(2)
        );

        assertEquals(2, result.getQuantity());
    }

    @Test
    void GivenInvalidToken_WhenreserveStandingTicketsForMember_ThenThrowException() {
        when(sessionManager.validateToken("badToken")).thenReturn(false);

        String result;

        try {
            reservationService.reserveForMember(
                    "badToken",
                    100,
                    1,
                    InventorySelectionDTO.standing(2)
            );

            result = "NO_EXCEPTION";

        } catch (Exception e) {
            result = e.getMessage();
        }

        assertEquals("Invalid or expired authentication token", result);
    }

    @Test
    void GivenQuantityIsZero_WhenreserveStandingTicketsForMember_ThenThrowException() {
        String result;

        try {
            reservationService.reserveForMember(
                    "validToken",
                    100,
                    1,
                    InventorySelectionDTO.standing(0)
            );

            result = "NO_EXCEPTION";

        } catch (Exception e) {
            result = e.getMessage();
        }

        assertEquals("Quantity must be positive", result);
    }

    @Test
    void GivenEventNotFound_WhenreserveStandingTicketsForMember_ThenThrowException() {
        when(eventRepository.findById(100)).thenReturn(null);

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

        assertEquals("Event not found: 100", result);
    }

    @Test
    void GivenZoneNotFound_WhenreserveStandingTicketsForMember_ThenThrowException() {
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
void GivenNotEnoughTickets_WhenreserveStandingTicketsForMember_ThenThrowException() {
    doThrow(new IllegalStateException("Only 1 tickets left"))
            .when(event).reserveInventory(eq(1), argThat(s -> s.isStandingSelection() && s.getQuantity() == 2));

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

    assertEquals("Only 1 tickets left", result);
}

    // @Test
    // void GivenUserAlreadyHasReservationForEvent_WhenreserveStandingTicketsForMember_ThenThrowException() {
    //     activeOrder.addStandingReservation(100, 1, 1, 50.0, java.time.LocalDateTime.now());

    //     String result;

    //     try {
    //         reservationService.reserveStandingTicketsForMember(
    //                 "validToken",
    //                 100,
    //                 1,
    //                 2
    //         );

    //         result = "NO_EXCEPTION";

    //     } catch (Exception e) {
    //         result = e.getMessage();
    //     }

    //     assertEquals("User already has an active order for this event", result);
    // }

    @Test
    void GivenValidGuestReservation_WhenReserveTicketsForGuest_ThenReturnReservationResult() {
        when(activeOrderRepository.getBySessionId("guest-session"))
                .thenReturn(Optional.empty());

        ReservationResultDTO result = reservationService.reserveForGuest(
                "guest-session",
                100,
                1,
                InventorySelectionDTO.standing(3)
        );

        assertEquals(3, result.getQuantity());
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
    void GivenValidReservedTickets_WhenremoveReservedStandingSpotsForMember_ThenReturnReservationResult() {
        activeOrder.addStandingReservation(100, 1, 3, 50.0, java.time.LocalDateTime.now());

        ReservationResultDTO result = reservationService.removeForMember(
                "validToken",
                100,
                1,
                InventorySelectionDTO.standing(2)
        );

        assertEquals(2, result.getQuantity());
    }

    @Test
    void GivenRemoveQuantityIsZero_WhenremoveReservedStandingSpotsForMember_ThenThrowException() {
        String result;

        try {
            reservationService.removeForMember(
                    "validToken",
                    100,
                    1,
                    InventorySelectionDTO.standing(0)
            );

            result = "NO_EXCEPTION";

        } catch (Exception e) {
            result = e.getMessage();
        }

        assertEquals("Quantity must be positive", result);
    }


    
    @Test
    void GivenNoActiveOrder_WhenremoveReservedStandingSpotsForMember_ThenThrowException() {
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

    @Test
    void GivenActiveOrderDoesNotContainEvent_WhenremoveReservedStandingSpotsForMember_ThenThrowException() {
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

        assertEquals("Active order does not contain this event", result);
    }

    @Test
    void GivenNotEnoughReservedTickets_WhenremoveReservedStandingSpotsForMember_ThenThrowException() {
        activeOrder.addStandingReservation(100, 1, 1, 50.0, java.time.LocalDateTime.now());

        String result;

        try {
            reservationService.removeForMember(
                    "validToken",
                    100,
                    1,
                    InventorySelectionDTO.standing(2)
            );

            result = "NO_EXCEPTION";

        } catch (Exception e) {
            result = e.getMessage();
        }

        assertEquals("Not enough reserved tickets to remove", result);
    }




    // more acceptance tests: seated venue maps

    // A real SeatedZone wired into the deep-stub event lets these exercise the actual per-seat
    // locking; no dedicated test-data builder is required.

    @Test
    void GivenMemberSelectsAvailableSeats_WhenReserveSeats_ThenSeatsAreLockedAndCartShowsSeatNumbers() {
        SeatedZone seatedZone = new SeatedZone(1, "Orchestra", 120.0,
                List.of(new Seat("A1", 0, 0), new Seat("A2", 1, 0), new Seat("A3", 2, 0)));
        when(event.getVenueMap().getZone(1)).thenReturn(seatedZone);
        doAnswer(inv -> {
            InventorySelection selection = inv.getArgument(1);
            seatedZone.reserve(selection);
            return null;
        }).when(event).reserveInventory(eq(1), any(InventorySelection.class));

        ReservationResultDTO result = reservationService.reserveForMember(
                "validToken", 100, 1, InventorySelectionDTO.seated(List.of("A1", "A2")));

        assertEquals(List.of("A1", "A2"), result.getSeatNumbers());
        assertEquals(SeatStatus.RESERVED, seatedZone.getSeatStatus("A1"));
        assertEquals(SeatStatus.RESERVED, seatedZone.getSeatStatus("A2"));
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A3"));

        assertEquals(List.of("A1", "A2"),
                activeOrder.getItems().stream().map(CartLineItem::getSeatNumber).toList());
    }

    @Test
    void GivenTwoMembersSelectSameSeat_WhenReserveConcurrently_ThenOnlyOneReservationSucceeds()
            throws InterruptedException {

        int numberOfThreads = 2;
        String contestedSeat = "A1";

        SeatedZone seatedZone = new SeatedZone(1, "Orchestra", 120.0,
                List.of(new Seat("A1", 0, 0), new Seat("A2", 1, 0)));
        when(event.getVenueMap().getZone(1)).thenReturn(seatedZone);
        doAnswer(inv -> {
            InventorySelection selection = inv.getArgument(1);
            seatedZone.reserve(selection);
            return null;
        }).when(event).reserveInventory(eq(1), any(InventorySelection.class));

        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i = i + 1) {
            final String token = "member-token-" + i;
            final int userId = i + 1;

            when(sessionManager.validateToken(token)).thenReturn(true);
            when(sessionManager.extractUserId(token)).thenReturn(userId);
            when(activeOrderRepository.getByUserId(userId)).thenReturn(null);

            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    reservationService.reserveForMember(token, 100, 1, InventorySelectionDTO.seated(List.of(contestedSeat)));

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
        assertEquals(SeatStatus.RESERVED, seatedZone.getSeatStatus(contestedSeat));
        assertEquals(1, seatedZone.getReservedAmount());
    }

    @Test
    void GivenGuestSelectsSeats_WhenReserveSeats_ThenGuestCartContainsSeatNumbers() {
        SeatedZone seatedZone = new SeatedZone(1, "Balcony", 80.0,
                List.of(new Seat("C1", 0, 0), new Seat("C2", 1, 0)));
        when(event.getVenueMap().getZone(1)).thenReturn(seatedZone);
        doAnswer(inv -> {
            InventorySelection selection = inv.getArgument(1);
            seatedZone.reserve(selection);
            return null;
        }).when(event).reserveInventory(eq(1), any(InventorySelection.class));
        when(activeOrderRepository.getBySessionId("guest-session")).thenReturn(Optional.empty());

        ReservationResultDTO result = reservationService.reserveForGuest(
                "guest-session", 100, 1, InventorySelectionDTO.seated(List.of("C1", "C2")));

        assertEquals(List.of("C1", "C2"), result.getSeatNumbers());
        assertEquals(SeatStatus.RESERVED, seatedZone.getSeatStatus("C1"));
        assertEquals(SeatStatus.RESERVED, seatedZone.getSeatStatus("C2"));

        ArgumentCaptor<ActiveOrder> orderCaptor = ArgumentCaptor.forClass(ActiveOrder.class);
        verify(activeOrderRepository).save(orderCaptor.capture());
        ActiveOrder savedGuestOrder = orderCaptor.getValue();
        assertTrue(savedGuestOrder.isGuest());
        assertEquals(List.of("C1", "C2"),
                savedGuestOrder.getItems().stream().map(CartLineItem::getSeatNumber).toList());
    }
}