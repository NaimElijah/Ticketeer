package com.ticketing.system.unit.infrastructure.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.domain.InventorySelection;
import com.ticketing.system.shared.metrics.ISystemMetrics;
import com.ticketing.system.shared.metrics.MetricType;
import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.domain.Session;
import com.ticketing.system.Infrastructure.scheduling.SessionAndOrderSweeper;


import com.ticketing.system.catalog.domain.DiscountPolicy;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.InventoryZone;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.sales.domain.PurchasePolicy;
import com.ticketing.system.catalog.domain.Seat;
import com.ticketing.system.catalog.domain.SeatStatus;
import com.ticketing.system.catalog.domain.SeatedZone;
import com.ticketing.system.catalog.domain.StandingZone;
import com.ticketing.system.catalog.domain.VenueMap;
import com.ticketing.system.sales.domain.NoPurchasePolicy;

class SessionAndOrderSweeperTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private SessionRepository sessionRepo;
    private ActiveOrderRepository orderRepo;
    private EventRepository eventRepo;
    private Clock fixedClock;
    private ApplicationEventPublisher eventPublisher;
    private ISystemMetrics systemMetrics;
    private SessionAndOrderSweeper sweeper;

    @BeforeEach
    void setUp() {
        sessionRepo    = mock(SessionRepository.class);
        orderRepo      = mock(ActiveOrderRepository.class);
        eventRepo      = mock(EventRepository.class);
        fixedClock     = Clock.fixed(T0, ZoneOffset.UTC);
        eventPublisher = mock(ApplicationEventPublisher.class);
        systemMetrics  = mock(ISystemMetrics.class);
        sweeper = new SessionAndOrderSweeper(sessionRepo, orderRepo, eventRepo, fixedClock, eventPublisher, systemMetrics);

        // Defaults: nothing expired.
        when(sessionRepo.findExpiredBefore(any())).thenReturn(List.of());
        when(orderRepo.findExpired()).thenReturn(List.of());
        when(orderRepo.getBySessionId(any())).thenReturn(Optional.empty());
    }

    // ---------------------------------------------------------------------
    // Empty / no-op sweeps
    // ---------------------------------------------------------------------

    @Test
    void emptySweep_noWorkDone() {
        sweeper.sweep();

        verify(sessionRepo).findExpiredBefore(T0);
        verify(orderRepo).findExpired();
        verify(sessionRepo, never()).delete(any());
        verify(orderRepo, never()).delete(any());
    }

    // ---------------------------------------------------------------------
    // Session sweep — Guest cleanup with cart
    // ---------------------------------------------------------------------

    @Test
    void expiredGuestSessionWithCart_deletesSessionAndCart_releasesTickets() {
        Session guest = new Session("guest-sid", null, T0.minusSeconds(7200), T0.minusSeconds(60));
        when(sessionRepo.findExpiredBefore(T0)).thenReturn(List.of(guest));

        ActiveOrder cart = ActiveOrder.forGuest("guest-sid");
        cart.addStandingReservation(1, 10, 2, 50.0, LocalDateTime.now());  // 2 tickets, event=1, zone=10
        when(orderRepo.getBySessionId("guest-sid")).thenReturn(Optional.of(cart));

        Event event = mock(Event.class);
        when(eventRepo.findById(1)).thenReturn(event);

        int cleaned = sweeper.sweepExpiredSessions(T0);

        assertEquals(1, cleaned);
        verify(orderRepo).delete(cart);
        verify(sessionRepo).delete("guest-sid");
        verify(event).releaseInventory(eq(10), argThat(s -> s.isStandingSelection() && s.getQuantity() == 2));     // 2 tickets in zone 10 released
        verify(eventRepo).save(event);
    }

    @Test
    void expiredGuestSessionWithoutCart_deletesSessionOnly() {
        Session guest = new Session("orphan-sid", null, T0.minusSeconds(7200), T0.minusSeconds(60));
        when(sessionRepo.findExpiredBefore(T0)).thenReturn(List.of(guest));
        when(orderRepo.getBySessionId("orphan-sid")).thenReturn(Optional.empty());

        sweeper.sweepExpiredSessions(T0);

        verify(sessionRepo).delete("orphan-sid");
        verify(orderRepo, never()).delete(any());
        verify(eventRepo, never()).save(any());
        // A swept guest session counts as a visitor exit (analytics, UC-46).
        verify(systemMetrics).record(MetricType.VISITOR_EXIT);
    }

    // ---------------------------------------------------------------------
    // Session sweep — Member cleanup (D9a: cart preserved)
    // ---------------------------------------------------------------------

    @Test
    void expiredMemberSession_deletesSession_butPreservesCart_D9a() {
        Session member = new Session("member-sid", 5, T0.minusSeconds(86400 + 60),
                T0.minusSeconds(60));
        when(sessionRepo.findExpiredBefore(T0)).thenReturn(List.of(member));

        sweeper.sweepExpiredSessions(T0);

        verify(sessionRepo).delete("member-sid");
        // Member's cart MUST survive the session expiry — restored on next login.
        verify(orderRepo, never()).getBySessionId(any());
        verify(orderRepo, never()).delete(any());
        verify(eventRepo, never()).save(any());
        // A member session expiry is NOT a visitor exit (entry was counted at guest start).
        verify(systemMetrics, never()).record(MetricType.VISITOR_EXIT);
    }

    // ---------------------------------------------------------------------
    // Cart sweep — line-item expiry deletes cart regardless of owner
    // ---------------------------------------------------------------------

    @Test
    void cartWithExpiredItems_deletedAndTicketsReleased() {
        ActiveOrder expiredCart = ActiveOrder.forMember(5, "sid-1");
        expiredCart.addStandingReservation(2, 20, 3, 30.0, LocalDateTime.now().minusMinutes(30));
        when(orderRepo.findExpired()).thenReturn(List.of(expiredCart));

        Event event = mock(Event.class);
        when(eventRepo.findById(2)).thenReturn(event);

        int cleaned = sweeper.sweepExpiredOrders();

        assertEquals(1, cleaned);
        verify(orderRepo).delete(expiredCart);
        verify(event).releaseInventory(eq(20), argThat(s -> s.isStandingSelection() && s.getQuantity() == 3));
        verify(eventRepo).save(event);
    }

    @Test
    void cartWithExpiredItemsAcrossMultipleEvents_releasesPerZoneAggregated() {
        ActiveOrder cart = ActiveOrder.forGuest("sid-multi");
        cart.addStandingReservation(1, 10, 2, 50.0, LocalDateTime.now().minusMinutes(30));   // 2 in event=1 zone=10
        cart.addStandingReservation(1, 20, 1, 75.0, LocalDateTime.now().minusMinutes(30));   // 1 in event=1 zone=20
        cart.addStandingReservation(2, 30, 1, 100.0, LocalDateTime.now().minusMinutes(30));  // 1 in event=2 zone=30
        when(orderRepo.findExpired()).thenReturn(List.of(cart));

        Event event1 = mock(Event.class);
        Event event2 = mock(Event.class);
        when(eventRepo.findById(1)).thenReturn(event1);
        when(eventRepo.findById(2)).thenReturn(event2);

        sweeper.sweepExpiredOrders();

        verify(event1).releaseInventory(eq(10), argThat(s -> s.isStandingSelection() && s.getQuantity() == 2));
        verify(event1).releaseInventory(eq(20), argThat(s -> s.isStandingSelection() && s.getQuantity() == 1));
        verify(event2).releaseInventory(eq(30), argThat(s -> s.isStandingSelection() && s.getQuantity() == 1));
        verify(eventRepo, times(1)).save(event1);  // saved once after both zones updated
        verify(eventRepo, times(1)).save(event2);
        verify(orderRepo).delete(cart);
    }

    @Test
    void cartWithEventMissingFromRepo_skipsReleaseGracefully() {
        ActiveOrder cart = ActiveOrder.forGuest("sid-1");
        cart.addStandingReservation(99, 10, 1, 25.0, LocalDateTime.now().minusMinutes(30));
        when(orderRepo.findExpired()).thenReturn(List.of(cart));
        when(eventRepo.findById(99)).thenReturn(null);  // event vanished

        sweeper.sweepExpiredOrders();

        // Cart is still deleted even though the inventory release was a no-op.
        verify(orderRepo).delete(cart);
        verify(eventRepo, never()).save(any());
    }

    // ---------------------------------------------------------------------
    // Combined sweep — both passes
    // ---------------------------------------------------------------------

    @Test
    void combinedSweep_handlesSessionsAndOrders_logsAggregate() {
        Session guest = new Session("sid-g", null, T0.minusSeconds(3600), T0.minusSeconds(60));
        when(sessionRepo.findExpiredBefore(T0)).thenReturn(List.of(guest));
        when(orderRepo.getBySessionId("sid-g")).thenReturn(Optional.empty());

        ActiveOrder expiredCart = ActiveOrder.forMember(5, "sid-m");
        expiredCart.addStandingReservation(1, 10, 1, 30.0, LocalDateTime.now().minusMinutes(30));
        when(orderRepo.findExpired()).thenReturn(List.of(expiredCart));

        Event event = mock(Event.class);
        when(eventRepo.findById(1)).thenReturn(event);

        sweeper.sweep();  // entry point — drives both passes

        verify(sessionRepo).delete("sid-g");
        verify(orderRepo).delete(expiredCart);
        verify(event).releaseInventory(eq(10), argThat(s -> s.isStandingSelection() && s.getQuantity() == 1));
    }

    @Test
    void emptyOrderItems_releaseStandingSpotsIsNoOp() {
        // Defensive: an ActiveOrder with no line items shouldn't blow up.
        ActiveOrder emptyCart = ActiveOrder.forGuest("sid-empty");
        when(orderRepo.findExpired()).thenReturn(List.of(emptyCart));

        sweeper.sweepExpiredOrders();

        verify(orderRepo).delete(emptyCart);
        verify(eventRepo, never()).findById(anyInt());
        verify(eventRepo, never()).save(any());
    }



    






    @Test
    void expiredSeatedOrder_releasesExactReservedSeats() {
        ActiveOrder expiredCart = ActiveOrder.forMember(5, "sid-1");

        SeatedZone seatedZone = new SeatedZone(
                20,
                "Orchestra",
                120.0,
                List.of(
                        new Seat("A1", 0, 0),
                        new Seat("A2", 1, 0),
                        new Seat("A3", 2, 0)));

        seatedZone.reserve(InventorySelection.seated(List.of("A1", "A2"), expiredCart.getOrderKey()));

        Event event = createEventWithZones(2, List.of(seatedZone));

        expiredCart.addSeatedReservation(
                2,
                20,
                List.of("A1", "A2"),
                120.0,
                LocalDateTime.now().minusMinutes(30));

        when(orderRepo.findExpired()).thenReturn(List.of(expiredCart));
        when(eventRepo.findById(2)).thenReturn(event);

        int cleaned = sweeper.sweepExpiredOrders();

        assertEquals(1, cleaned);
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A1"));
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A2"));
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A3"));

        verify(orderRepo).delete(expiredCart);
        verify(eventRepo).save(event);
    }
    


    @Test
    void expiredMixedOrder_releasesStandingQuantityAndExactSeatedSeats() {
        ActiveOrder expiredCart = ActiveOrder.forGuest("sid-mixed");

        StandingZone standingZone = new StandingZone(10, "General Admission", 5, 50.0);
        SeatedZone seatedZone = new SeatedZone(
                20,
                "Orchestra",
                120.0,
                List.of(
                        new Seat("A1", 0, 0),
                        new Seat("A2", 1, 0)));

        standingZone.reserve(InventorySelection.standing(2, expiredCart.getOrderKey()));
        seatedZone.reserve(InventorySelection.seated(List.of("A1"), expiredCart.getOrderKey()));

        Event event = createEventWithZones(1, List.of(standingZone, seatedZone));

        expiredCart.addStandingReservation(1, 10, 2, 50.0, LocalDateTime.now().minusMinutes(30));
        expiredCart.addSeatedReservation(1, 20, List.of("A1"), 120.0, LocalDateTime.now().minusMinutes(30));

        when(orderRepo.findExpired()).thenReturn(List.of(expiredCart));
        when(eventRepo.findById(1)).thenReturn(event);

        sweeper.sweepExpiredOrders();

        assertEquals(5, standingZone.getAvailableAmount());
        assertEquals(0, standingZone.getReservedAmount());

        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A1"));
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A2"));

        verify(orderRepo).delete(expiredCart);
        verify(eventRepo).save(event);
    }
    


    @Test
    void expiredGuestSessionWithSeatedCart_releasesExactSeatsAndDeletesCart() {
        Session guest = new Session("guest-seated-sid", null, T0.minusSeconds(7200), T0.minusSeconds(60));
        when(sessionRepo.findExpiredBefore(T0)).thenReturn(List.of(guest));

        ActiveOrder cart = ActiveOrder.forGuest("guest-seated-sid");

        SeatedZone seatedZone = new SeatedZone(
                20,
                "Orchestra",
                120.0,
                List.of(
                        new Seat("A1", 0, 0),
                        new Seat("A2", 1, 0)
                )
        );

        seatedZone.reserve(InventorySelection.seated(List.of("A1"), cart.getOrderKey()));

        Event event = createEventWithZones(1, List.of(seatedZone));

        cart.addSeatedReservation(1, 20, List.of("A1"), 120.0, LocalDateTime.now());

        when(orderRepo.getBySessionId("guest-seated-sid")).thenReturn(Optional.of(cart));
        when(eventRepo.findById(1)).thenReturn(event);

        int cleaned = sweeper.sweepExpiredSessions(T0);

        assertEquals(1, cleaned);
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A1"));
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A2"));

        verify(orderRepo).delete(cart);
        verify(sessionRepo).delete("guest-seated-sid");
        verify(eventRepo).save(event);
    }




    @Test
    void GivenExpiredCartInCheckoutProgress_WhenSweepExpiredOrders_ThenDoNotReleaseOrDelete() {
        ActiveOrder cart = ActiveOrder.forMember(5, "sid-checkout");
        cart.addStandingReservation(1, 10, 2, 50.0, LocalDateTime.now().minusMinutes(30));
        cart.markCheckoutInProgress();

        when(orderRepo.findExpired()).thenReturn(List.of(cart));

        int scanned = sweeper.sweepExpiredOrders();

        assertEquals(1, scanned);
        verify(orderRepo).lockForUpdate("user:5");
        verify(orderRepo).unlock("user:5");
        verify(orderRepo, never()).delete(any());
        verify(eventRepo, never()).lockForUpdate(anyInt());
        verify(eventRepo, never()).save(any());
    }










    // R1-sweeper re-check: findExpired() returned this cart, but by the time the sweeper locks it the
    // buyer has manually removed the expired line (now allowed), leaving only fresh items. The sweeper
    // must re-validate expiry under the lock and NOT destroy the salvaged cart.
    @Test
    void cartNoLongerExpiredWhenLocked_isSkippedNotDeleted() {
        ActiveOrder healthyAgain = ActiveOrder.forMember(5, "sid-healthy");
        healthyAgain.addStandingReservation(1, 10, 1, 30.0, LocalDateTime.now()); // fresh, not expired
        when(orderRepo.findExpired()).thenReturn(List.of(healthyAgain));

        int scanned = sweeper.sweepExpiredOrders();

        assertEquals(1, scanned);
        verify(orderRepo).lockForUpdate("user:5");
        verify(orderRepo).unlock("user:5");
        verify(orderRepo, never()).delete(any());
        verify(eventRepo, never()).lockForUpdate(anyInt());
        verify(eventRepo, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // RC1: one zone release failing (e.g. a double-release race with abandonActiveOrder) must not abort
    // release of the other events or the deletion of the order.
    @Test
    void zoneReleaseFailure_doesNotAbortRemainingReleasesOrDelete() {
        ActiveOrder cart = ActiveOrder.forGuest("sid-resilient");
        cart.addStandingReservation(1, 10, 1, 50.0, LocalDateTime.now().minusMinutes(30)); // event 1 -> throws
        cart.addStandingReservation(2, 20, 1, 60.0, LocalDateTime.now().minusMinutes(30)); // event 2 -> ok
        when(orderRepo.findExpired()).thenReturn(List.of(cart));

        Event event1 = mock(Event.class);
        Event event2 = mock(Event.class);
        when(eventRepo.findById(1)).thenReturn(event1);
        when(eventRepo.findById(2)).thenReturn(event2);
        doThrow(new IllegalStateException("boom")).when(event1).releaseInventory(eq(10), any());

        int cleaned = sweeper.sweepExpiredOrders();

        assertEquals(1, cleaned);
        // event2's release still ran and was persisted despite event1 throwing...
        verify(event2).releaseInventory(eq(20), any());
        verify(eventRepo).save(event2);
        verify(eventRepo, never()).save(event1); // nothing released on event1
        // ...and the order was still deleted.
        verify(orderRepo).delete(cart);
    }

    // test helper functions:

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
            public double calculatePriceforoneticket(int quantity, Double priceAtOneTicketReservation, LocalDateTime now) {
                return priceAtOneTicketReservation;
            }
        };
    }

    private Event createEventWithZones(int eventId, List<InventoryZone> zones) {
        return new Event(
                eventId,
                "Concert",
                4.5,
                List.of("Artist"),
                EventCategory.CONCERT,
                100,
                EventStatus.SCHEDULED,
                new VenueMap(1, new Location("Israel", "Tel Aviv"), zones),
                List.of(new ShowDate(LocalDateTime.now().plusDays(30), LocalDateTime.now().plusDays(30).plusHours(2))),
                acceptingPurchasePolicy(),
                noDiscountPolicy()
        );
    }


}