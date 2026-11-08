package com.ticketing.system.unit.infrastructure.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import com.ticketing.system.sales.application.event.OrderExpiredEvent;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.domain.InventorySelection;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.metrics.ISystemMetrics;
import com.ticketing.system.shared.metrics.MetricType;
import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.domain.Session;
import com.ticketing.system.bootstrap.scheduling.SessionAndOrderSweeper;


import com.ticketing.system.catalog.domain.DiscountPolicy;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.InventoryZone;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.shared.domain.policy.PurchasePolicy;
import com.ticketing.system.catalog.domain.Seat;
import com.ticketing.system.catalog.domain.SeatStatus;
import com.ticketing.system.catalog.domain.SeatedZone;
import com.ticketing.system.catalog.domain.StandingZone;
import com.ticketing.system.catalog.domain.VenueMap;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;

class SessionAndOrderSweeperTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    // Cart holds are UTC wall time read from the sweeper's clock (ADR-008), so carts are stamped relative to T0.
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(T0, ZoneOffset.UTC);
    private static final int STANDING_CAPACITY = 5;

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
        when(orderRepo.findExpired(NOW)).thenReturn(List.of());
        when(orderRepo.getBySessionId(any())).thenReturn(Optional.empty());
    }

    // ---------------------------------------------------------------------
    // Empty / no-op sweeps
    // ---------------------------------------------------------------------

    @Test
    void GivenNothingExpired_WhenSweep_ThenNoWorkDone() {
        sweeper.sweep();

        verify(sessionRepo).findExpiredBefore(T0);
        verify(orderRepo).findExpired(NOW);
        verify(sessionRepo, never()).delete(any());
        verify(orderRepo, never()).delete(any());
    }

    // ---------------------------------------------------------------------
    // Session sweep — Guest cleanup with cart
    // ---------------------------------------------------------------------

    @Test
    void GivenExpiredGuestSessionWithCart_WhenSweepExpiredSessions_ThenDeletesSessionAndCartAndReleasesTickets() {
        Session guest = new Session("guest-sid", null, T0.minusSeconds(7200), T0.minusSeconds(60));
        when(sessionRepo.findExpiredBefore(T0)).thenReturn(List.of(guest));

        ActiveOrder cart = ActiveOrder.forGuest("guest-sid", NOW);
        cart.addStandingReservation(1, 10, 2, 50.0, NOW);  // 2 tickets, event=1, zone=10
        when(orderRepo.getBySessionId("guest-sid")).thenReturn(Optional.of(cart));

        StandingZone zone = standingZoneHolding(10, cart, 2);
        Event event = createEventWithZones(1, List.of(zone));
        when(eventRepo.findById(1)).thenReturn(event);

        int cleaned = sweeper.sweepExpiredSessions(T0);

        assertEquals(1, cleaned);
        verify(orderRepo).delete(cart);
        verify(sessionRepo).delete("guest-sid");
        assertReleased(zone);
        verify(eventRepo).save(event);
    }

    @Test
    void GivenExpiredGuestSessionWithoutCart_WhenSweepExpiredSessions_ThenDeletesSessionOnlyAndRecordsVisitorExit() {
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
    void GivenExpiredMemberSession_WhenSweepExpiredSessions_ThenDeletesSessionButPreservesCartWithoutVisitorExitD9a() {
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
    void GivenCartWithExpiredItems_WhenSweepExpiredOrders_ThenDeletedAndTicketsReleased() {
        ActiveOrder expiredCart = ActiveOrder.forMember(5, "sid-1", NOW);
        expiredCart.addStandingReservation(2, 20, 3, 30.0, NOW.minusMinutes(30));
        when(orderRepo.findExpired(NOW)).thenReturn(List.of(expiredCart));

        StandingZone zone = standingZoneHolding(20, expiredCart, 3);
        Event event = createEventWithZones(2, List.of(zone));
        when(eventRepo.findById(2)).thenReturn(event);

        int cleaned = sweeper.sweepExpiredOrders(T0);

        assertEquals(1, cleaned);
        verify(orderRepo).delete(expiredCart);
        assertReleased(zone);
        verify(eventRepo).save(event);
    }

    // The expiry notice tells the owner's open checkout views (CheckoutPresenter.onOrderExpired), which find
    // a member by user id and a guest by session id.
    @Test
    void GivenAMembersCartWithExpiredItems_WhenSweepExpiredOrders_ThenPublishesOrderExpiredForThatMember() {
        ActiveOrder expiredCart = new ActiveOrder(5, NOW);
        expiredCart.addStandingReservation(2, 20, 1, 30.0, NOW.minusMinutes(30));
        when(orderRepo.findExpired(NOW)).thenReturn(List.of(expiredCart));
        when(eventRepo.findById(2)).thenReturn(createEventWithZones(2, List.of(standingZoneHolding(20, expiredCart, 1))));

        sweeper.sweepExpiredOrders(T0);

        verify(eventPublisher).publishEvent(new OrderExpiredEvent(5, null));
        verifyNoMoreInteractions(eventPublisher);
    }

    @Test
    void GivenAGuestsCartWithExpiredItems_WhenSweepExpiredOrders_ThenPublishesOrderExpiredForThatGuestsSession() {
        ActiveOrder expiredCart = ActiveOrder.forGuest("sid-expired", NOW);
        expiredCart.addStandingReservation(2, 20, 1, 30.0, NOW.minusMinutes(30));
        when(orderRepo.findExpired(NOW)).thenReturn(List.of(expiredCart));
        when(eventRepo.findById(2)).thenReturn(createEventWithZones(2, List.of(standingZoneHolding(20, expiredCart, 1))));

        sweeper.sweepExpiredOrders(T0);

        verify(eventPublisher).publishEvent(new OrderExpiredEvent(0, "sid-expired"));
        verifyNoMoreInteractions(eventPublisher);
    }

    // The deadlock guard: the order lock first, then the events by ascending id, as reservation and checkout
    // take them, released in reverse. The cart lists the higher event id first, so only a sort puts 1 first.
    @Test
    void GivenCartOverTwoEvents_WhenSweepExpiredOrders_ThenLocksTheOrderThenEventsInIdOrderAndUnlocksInReverse() {
        ActiveOrder cart = new ActiveOrder(5, NOW);
        cart.addStandingReservation(2, 20, 1, 30.0, NOW.minusMinutes(30));
        cart.addStandingReservation(1, 10, 1, 30.0, NOW.minusMinutes(30));
        when(orderRepo.findExpired(NOW)).thenReturn(List.of(cart));
        when(eventRepo.findById(1)).thenReturn(createEventWithZones(1, List.of(standingZoneHolding(10, cart, 1))));
        when(eventRepo.findById(2)).thenReturn(createEventWithZones(2, List.of(standingZoneHolding(20, cart, 1))));

        sweeper.sweepExpiredOrders(T0);

        InOrder locks = inOrder(orderRepo, eventRepo);
        locks.verify(orderRepo).lockForUpdate("user:5");
        locks.verify(eventRepo).lockForUpdate(1);
        locks.verify(eventRepo).lockForUpdate(2);
        locks.verify(eventRepo).unlock(2);
        locks.verify(eventRepo).unlock(1);
        locks.verify(orderRepo).unlock("user:5");
    }

    // The scan may list a cart whose hold has not run out at the sweeper's clock (a stale scan, or a
    // backend whose clock differs). The re-check under the lock must judge it by the same injected
    // clock, so a line added 5 minutes before T0 (well inside the hold) is kept.
    @Test
    void GivenCartAddedFiveMinutesBeforeTheClock_WhenSweepRechecksUnderTheLock_ThenCartIsKept() {
        ActiveOrder cart = ActiveOrder.forMember(5, "sid-fresh", NOW);
        cart.addStandingReservation(2, 20, 1, 30.0, NOW.minusMinutes(5));
        when(orderRepo.findExpired(NOW)).thenReturn(List.of(cart));

        sweeper.sweepExpiredOrders(T0);

        verify(orderRepo, never()).delete(any());
        verify(eventRepo, never()).save(any());
    }

    @Test
    void GivenCartWithExpiredItemsAcrossMultipleEvents_WhenSweepExpiredOrders_ThenReleasesPerZoneAndSavesEachEventOnce() {
        ActiveOrder cart = ActiveOrder.forGuest("sid-multi", NOW);
        cart.addStandingReservation(1, 10, 2, 50.0, NOW.minusMinutes(30));   // 2 in event=1 zone=10
        cart.addStandingReservation(1, 20, 1, 75.0, NOW.minusMinutes(30));   // 1 in event=1 zone=20
        cart.addStandingReservation(2, 30, 1, 100.0, NOW.minusMinutes(30));  // 1 in event=2 zone=30
        when(orderRepo.findExpired(NOW)).thenReturn(List.of(cart));

        StandingZone zone10 = standingZoneHolding(10, cart, 2);
        StandingZone zone20 = standingZoneHolding(20, cart, 1);
        StandingZone zone30 = standingZoneHolding(30, cart, 1);
        Event event1 = createEventWithZones(1, List.of(zone10, zone20));
        Event event2 = createEventWithZones(2, List.of(zone30));
        when(eventRepo.findById(1)).thenReturn(event1);
        when(eventRepo.findById(2)).thenReturn(event2);

        sweeper.sweepExpiredOrders(T0);

        assertReleased(zone10);
        assertReleased(zone20);
        assertReleased(zone30);
        verify(eventRepo, times(1)).save(event1);  // saved once after both zones updated
        verify(eventRepo, times(1)).save(event2);
        verify(orderRepo).delete(cart);
    }

    @Test
    void GivenCartWithEventMissingFromRepo_WhenSweepExpiredOrders_ThenSkipsReleaseGracefullyAndDeletesCart() {
        ActiveOrder cart = ActiveOrder.forGuest("sid-1", NOW);
        cart.addStandingReservation(99, 10, 1, 25.0, NOW.minusMinutes(30));
        when(orderRepo.findExpired(NOW)).thenReturn(List.of(cart));
        when(eventRepo.findById(99)).thenThrow(new EventNotFoundException(99));  // event vanished

        sweeper.sweepExpiredOrders(T0);

        // Cart is still deleted even though the inventory release was a no-op.
        verify(orderRepo).delete(cart);
        verify(eventRepo, never()).save(any());
    }

    // ---------------------------------------------------------------------
    // Combined sweep — both passes
    // ---------------------------------------------------------------------

    @Test
    void GivenExpiredSessionAndExpiredCart_WhenSweep_ThenHandlesSessionsAndOrders() {
        Session guest = new Session("sid-g", null, T0.minusSeconds(3600), T0.minusSeconds(60));
        when(sessionRepo.findExpiredBefore(T0)).thenReturn(List.of(guest));
        when(orderRepo.getBySessionId("sid-g")).thenReturn(Optional.empty());

        ActiveOrder expiredCart = ActiveOrder.forMember(5, "sid-m", NOW);
        expiredCart.addStandingReservation(1, 10, 1, 30.0, NOW.minusMinutes(30));
        when(orderRepo.findExpired(NOW)).thenReturn(List.of(expiredCart));

        StandingZone zone = standingZoneHolding(10, expiredCart, 1);
        when(eventRepo.findById(1)).thenReturn(createEventWithZones(1, List.of(zone)));

        sweeper.sweep();  // entry point — drives both passes

        verify(sessionRepo).delete("sid-g");
        verify(orderRepo).delete(expiredCart);
        assertReleased(zone);
    }

    // findExpired lists only carts holding an expired line, but a guest's cart can be empty when the guest's
    // session ends: the guest removed every line, or never added one.
    @Test
    void GivenExpiredGuestSessionWithAnEmptyCart_WhenSweepExpiredSessions_ThenTheCartIsDeletedAndNoEventIsTouched() {
        Session guest = new Session("sid-empty", null, T0.minusSeconds(7200), T0.minusSeconds(60));
        when(sessionRepo.findExpiredBefore(T0)).thenReturn(List.of(guest));
        ActiveOrder emptyCart = ActiveOrder.forGuest("sid-empty", NOW);
        when(orderRepo.getBySessionId("sid-empty")).thenReturn(Optional.of(emptyCart));

        sweeper.sweepExpiredSessions(T0);

        verify(orderRepo).delete(emptyCart);
        verify(sessionRepo).delete("sid-empty");
        verify(eventRepo, never()).findById(anyInt());
        verify(eventRepo, never()).save(any());
    }



    






    @Test
    void GivenExpiredSeatedOrder_WhenSweepExpiredOrders_ThenReleasesExactReservedSeats() {
        ActiveOrder expiredCart = ActiveOrder.forMember(5, "sid-1", NOW);

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
                NOW.minusMinutes(30));

        when(orderRepo.findExpired(NOW)).thenReturn(List.of(expiredCart));
        when(eventRepo.findById(2)).thenReturn(event);

        int cleaned = sweeper.sweepExpiredOrders(T0);

        assertEquals(1, cleaned);
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A1"));
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A2"));
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A3"));

        verify(orderRepo).delete(expiredCart);
        verify(eventRepo).save(event);
    }
    


    @Test
    void GivenExpiredMixedOrder_WhenSweepExpiredOrders_ThenReleasesStandingQuantityAndExactSeatedSeats() {
        ActiveOrder expiredCart = ActiveOrder.forGuest("sid-mixed", NOW);

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

        expiredCart.addStandingReservation(1, 10, 2, 50.0, NOW.minusMinutes(30));
        expiredCart.addSeatedReservation(1, 20, List.of("A1"), 120.0, NOW.minusMinutes(30));

        when(orderRepo.findExpired(NOW)).thenReturn(List.of(expiredCart));
        when(eventRepo.findById(1)).thenReturn(event);

        sweeper.sweepExpiredOrders(T0);

        assertEquals(5, standingZone.getAvailableAmount());
        assertEquals(0, standingZone.getReservedAmount());

        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A1"));
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A2"));

        verify(orderRepo).delete(expiredCart);
        verify(eventRepo).save(event);
    }
    


    @Test
    void GivenExpiredGuestSessionWithSeatedCart_WhenSweepExpiredSessions_ThenReleasesExactSeatsAndDeletesSessionAndCart() {
        Session guest = new Session("guest-seated-sid", null, T0.minusSeconds(7200), T0.minusSeconds(60));
        when(sessionRepo.findExpiredBefore(T0)).thenReturn(List.of(guest));

        ActiveOrder cart = ActiveOrder.forGuest("guest-seated-sid", NOW);

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

        cart.addSeatedReservation(1, 20, List.of("A1"), 120.0, NOW);

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
        ActiveOrder cart = ActiveOrder.forMember(5, "sid-checkout", NOW);
        cart.addStandingReservation(1, 10, 2, 50.0, NOW.minusMinutes(30));
        cart.markCheckoutInProgress();

        when(orderRepo.findExpired(NOW)).thenReturn(List.of(cart));

        int cleaned = sweeper.sweepExpiredOrders(T0);

        assertEquals(0, cleaned, "an order skipped because its checkout is in progress is not counted");
        verify(orderRepo).lockForUpdate("user:5");
        verify(orderRepo).unlock("user:5");
        verify(orderRepo, never()).delete(any());
        verify(eventRepo, never()).lockForUpdate(anyInt());
        verify(eventRepo, never()).save(any());
    }

    // The class Javadoc's policy covers the session path too: the checkout owns the cart's holds until it
    // completes or reverts the cart, so a guest session that ends meanwhile leaves the cart alone.
    @Test
    void GivenAGuestCartInCheckout_WhenItsSessionExpires_ThenTheCartIsNeitherReleasedNorDeleted() {
        Session guest = new Session("sid-paying", null, T0.minusSeconds(7200), T0.minusSeconds(60));
        when(sessionRepo.findExpiredBefore(T0)).thenReturn(List.of(guest));
        ActiveOrder cart = ActiveOrder.forGuest("sid-paying", NOW);
        cart.addStandingReservation(1, 10, 1, 50.0, NOW);
        cart.markCheckoutInProgress();
        when(orderRepo.getBySessionId("sid-paying")).thenReturn(Optional.of(cart));

        sweeper.sweepExpiredSessions(T0);

        verify(orderRepo, never()).delete(any());
        verify(eventRepo, never()).lockForUpdate(anyInt());
        verify(eventRepo, never()).save(any());
    }










    // R1-sweeper re-check: findExpired() returned this cart while it held an expired line, but the buyer
    // removes that line while the sweeper waits for the order lock, leaving only a fresh one. The sweeper
    // must re-validate expiry under the lock and NOT destroy the salvaged cart. The removal happens inside
    // lockForUpdate, so a check made before the lock would still see the expired line.
    @Test
    void GivenCartNoLongerExpiredWhenLocked_WhenSweepExpiredOrders_ThenIsSkippedNotDeleted() {
        ActiveOrder healthyAgain = ActiveOrder.forMember(5, "sid-healthy", NOW);
        healthyAgain.addStandingReservation(1, 10, 1, 30.0, NOW.minusMinutes(30)); // expired at the scan
        healthyAgain.addStandingReservation(1, 20, 1, 30.0, NOW); // fresh, not expired
        when(orderRepo.findExpired(NOW)).thenReturn(List.of(healthyAgain));
        doAnswer(lockTaken -> {
            healthyAgain.removeStandingSpots(1, 10, 1);
            return null;
        }).when(orderRepo).lockForUpdate("user:5");

        int cleaned = sweeper.sweepExpiredOrders(T0);

        assertEquals(0, cleaned, "an order skipped because it is no longer expired is not counted");
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
    void GivenZoneReleaseFailure_WhenSweepExpiredOrders_ThenDoesNotAbortRemainingReleasesOrDelete() {
        ActiveOrder cart = ActiveOrder.forGuest("sid-resilient", NOW);
        cart.addStandingReservation(1, 10, 1, 50.0, NOW.minusMinutes(30)); // event 1 -> throws
        cart.addStandingReservation(2, 20, 1, 60.0, NOW.minusMinutes(30)); // event 2 -> ok
        when(orderRepo.findExpired(NOW)).thenReturn(List.of(cart));

        // Zone 10 no longer holds the cart's place, as after abandonActiveOrder released it first, so the
        // real release refuses it.
        Event event1 = createEventWithZones(1, List.of(new StandingZone(10, "General Admission", STANDING_CAPACITY, 50.0)));
        StandingZone zone20 = standingZoneHolding(20, cart, 1);
        Event event2 = createEventWithZones(2, List.of(zone20));
        when(eventRepo.findById(1)).thenReturn(event1);
        when(eventRepo.findById(2)).thenReturn(event2);

        int cleaned = sweeper.sweepExpiredOrders(T0);

        assertEquals(1, cleaned);
        // event2's release still ran and was persisted despite event1 throwing...
        assertReleased(zone20);
        verify(eventRepo).save(event2);
        verify(eventRepo, never()).save(event1); // nothing released on event1
        // ...and the order was still deleted.
        verify(orderRepo).delete(cart);
    }

    // Only a zone's release is guarded; a failing save fails the tick, which rolls back as one transaction.
    @Test
    void GivenEventSaveFails_WhenSweepExpiredOrders_ThenItPropagatesAndEveryLockIsReleased() {
        ActiveOrder cart = new ActiveOrder(5, NOW);
        cart.addStandingReservation(1, 10, 1, 30.0, NOW.minusMinutes(30));
        cart.addStandingReservation(2, 20, 1, 30.0, NOW.minusMinutes(30));
        when(orderRepo.findExpired(NOW)).thenReturn(List.of(cart));
        Event event1 = createEventWithZones(1, List.of(standingZoneHolding(10, cart, 1)));
        when(eventRepo.findById(1)).thenReturn(event1);
        when(eventRepo.findById(2)).thenReturn(createEventWithZones(2, List.of(standingZoneHolding(20, cart, 1))));
        IllegalStateException saveFailure = new IllegalStateException("the event row could not be written");
        doThrow(saveFailure).when(eventRepo).save(event1);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> sweeper.sweepExpiredOrders(T0));

        assertSame(saveFailure, thrown, "the save's failure must reach the tick unchanged");
        verify(eventRepo).unlock(1);
        verify(eventRepo).unlock(2);
        verify(orderRepo).unlock("user:5");
        verify(orderRepo, never()).delete(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // test helper functions:

    // A zone of STANDING_CAPACITY places, `held` of them reserved under the order's key, as a reservation
    // leaves it, so the sweeper's release runs against real inventory. Named by id: a venue's names are unique.
    private static StandingZone standingZoneHolding(int zoneId, ActiveOrder order, int held) {
        StandingZone zone = new StandingZone(zoneId, "Standing " + zoneId, STANDING_CAPACITY, 30.0);
        zone.reserve(InventorySelection.standing(held, order.getOrderKey()));
        return zone;
    }

    private static void assertReleased(StandingZone zone) {
        assertEquals(0, zone.getReservedAmount(), "zone " + zone.getId() + " must hold nothing for the swept order");
        assertEquals(STANDING_CAPACITY, zone.getAvailableAmount(), "every place of zone " + zone.getId()
                + " must be back on sale");
    }

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
                List.of(new ShowDate(NOW.plusDays(30), NOW.plusDays(30).plusHours(2))),
                ZoneId.of("Asia/Jerusalem"),
                acceptingPurchasePolicy(),
                noDiscountPolicy()
        );
    }

    // ---- Defect 8: only the orders released count as cleaned (#187) ----
    // sweepExpiredOrders returns the orders it deleted; an order it skips under the lock stays.

    @Test
    void GivenOneExpiredOrderInCheckoutAndOneReleased_WhenSweepExpiredOrders_ThenItReportsOne() {
        ActiveOrder inCheckout = ActiveOrder.forMember(5, "sid-checkout", NOW);
        inCheckout.addStandingReservation(1, 10, 1, 50.0, NOW.minusMinutes(30));
        inCheckout.markCheckoutInProgress();
        ActiveOrder released = expiredOrderHoldingOneStandingTicket(6, "sid-released", 2, 20);
        when(orderRepo.findExpired(NOW)).thenReturn(List.of(inCheckout, released));

        int cleaned = sweeper.sweepExpiredOrders(T0);

        assertEquals(1, cleaned, "the order in checkout was skipped; only the released one counts");
        verify(orderRepo).delete(released);
        verify(orderRepo, never()).delete(inCheckout);
    }

    @Test
    void GivenOneOrderNoLongerExpiredUnderTheLockAndOneReleased_WhenSweepExpiredOrders_ThenItReportsOne() {
        ActiveOrder renewed = ActiveOrder.forMember(5, "sid-renewed", NOW);
        renewed.addStandingReservation(1, 10, 1, 30.0, NOW);
        ActiveOrder released = expiredOrderHoldingOneStandingTicket(6, "sid-released", 2, 20);
        when(orderRepo.findExpired(NOW)).thenReturn(List.of(renewed, released));

        int cleaned = sweeper.sweepExpiredOrders(T0);

        assertEquals(1, cleaned, "the renewed order was skipped; only the released one counts");
        verify(orderRepo).delete(released);
        verify(orderRepo, never()).delete(renewed);
    }

    private ActiveOrder expiredOrderHoldingOneStandingTicket(int userId, String sessionId, int eventId, int zoneId) {
        ActiveOrder order = ActiveOrder.forMember(userId, sessionId, NOW);
        StandingZone zone = new StandingZone(zoneId, "General Admission", 5, 30.0);
        zone.reserve(InventorySelection.standing(1, order.getOrderKey()));
        order.addStandingReservation(eventId, zoneId, 1, 30.0, NOW.minusMinutes(30));
        when(eventRepo.findById(eventId)).thenReturn(createEventWithZones(eventId, List.of(zone)));
        return order;
    }
}