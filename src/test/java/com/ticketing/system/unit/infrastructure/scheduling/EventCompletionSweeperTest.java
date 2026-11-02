package com.ticketing.system.unit.infrastructure.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.mockito.InOrder;
import org.mockito.Mockito;

import com.ticketing.system.catalog.domain.DiscountPolicy;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.domain.InventorySelection;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.catalog.domain.StandingZone;
import com.ticketing.system.catalog.domain.VenueMap;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;
import com.ticketing.system.shared.domain.policy.PurchasePolicy;
import com.ticketing.system.bootstrap.scheduling.EventCompletionSweeper;

/**
 * Drives {@link EventCompletionSweeper#completeFinishedEvents(Instant)} directly with a
 * controllable {@code now} (mirrors {@code SessionAndOrderSweeperTest}), and {@code sweep()} through
 * a fixed clock. Real {@link Event} aggregates are used so the actual {@code hasFinishedAsOf} /
 * {@code transitionToCompleted} domain logic is exercised end-to-end; only the repository is mocked.
 * Every event is at a venue in Jerusalem, so each {@code now} is the instant of a Jerusalem wall time.
 */
class EventCompletionSweeperTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final ZoneId JERUSALEM = ZoneId.of("Asia/Jerusalem");
    private static final Location LOCATION = new Location("Israel", "Tel Aviv");
    // A 20:00–23:00 show at a venue in Jerusalem. In summer (IDT, UTC+3) it ends at 20:00:00Z; in
    // winter (IST, UTC+2) at 21:00:00Z.
    private static final LocalDateTime SUMMER_SHOW_END = LocalDateTime.of(2027, 7, 15, 23, 0);
    private static final LocalDateTime WINTER_SHOW_END = LocalDateTime.of(2027, 12, 15, 23, 0);

    private static final LocalDateTime SHOW_END = LocalDateTime.of(2027, 3, 10, 22, 0);
    private static final Instant AFTER_SHOW = SHOW_END.plusDays(1).atZone(JERUSALEM).toInstant();
    private static final Instant BEFORE_SHOW = SHOW_END.minusDays(1).atZone(JERUSALEM).toInstant();

    private EventRepository eventRepo;
    private EventCompletionSweeper sweeper;

    @BeforeEach
    void setUp() {
        eventRepo = mock(EventRepository.class);
        Clock fixedClock = Clock.fixed(T0, ZoneOffset.UTC);
        sweeper = new EventCompletionSweeper(eventRepo, fixedClock);

        // Defaults: nothing live.
        when(eventRepo.findByStatus(EventStatus.ON_SALE)).thenReturn(List.of());
        when(eventRepo.findByStatus(EventStatus.SOLD_OUT)).thenReturn(List.of());
    }

    @Test
    void emptyScan_completesNothing() {
        int completed = sweeper.completeFinishedEvents(AFTER_SHOW);

        assertEquals(0, completed);
        verify(eventRepo, never()).lockForUpdate(Mockito.anyInt());
        verify(eventRepo, never()).save(Mockito.any());
    }

    @Test
    void pastOnSaleEvent_isCompletedAndSaved() {
        Event e = onSaleEvent(1, SHOW_END);
        when(eventRepo.findByStatus(EventStatus.ON_SALE)).thenReturn(List.of(e));
        when(eventRepo.findById(1)).thenReturn(e);

        int completed = sweeper.completeFinishedEvents(AFTER_SHOW);

        assertEquals(1, completed);
        assertEquals(EventStatus.COMPLETED, e.getStatus());
        // Mutate only under the lock; release it after saving.
        InOrder inOrder = Mockito.inOrder(eventRepo);
        inOrder.verify(eventRepo).lockForUpdate(1);
        inOrder.verify(eventRepo).save(e);
        inOrder.verify(eventRepo).unlock(1);
    }

    @Test
    void pastSoldOutEvent_isCompletedAndSaved() {
        Event e = soldOutEvent(2, SHOW_END);
        when(eventRepo.findByStatus(EventStatus.SOLD_OUT)).thenReturn(List.of(e));
        when(eventRepo.findById(2)).thenReturn(e);

        int completed = sweeper.completeFinishedEvents(AFTER_SHOW);

        assertEquals(1, completed);
        assertEquals(EventStatus.COMPLETED, e.getStatus());
        verify(eventRepo).save(e);
    }

    @Test
    void futureEvent_isUntouched() {
        Event e = onSaleEvent(3, SHOW_END);
        when(eventRepo.findByStatus(EventStatus.ON_SALE)).thenReturn(List.of(e));

        int completed = sweeper.completeFinishedEvents(BEFORE_SHOW);

        assertEquals(0, completed);
        assertEquals(EventStatus.ON_SALE, e.getStatus());
        verify(eventRepo, never()).lockForUpdate(3);
        verify(eventRepo, never()).save(Mockito.any());
    }

    // Only ON_SALE and SOLD_OUT are scanned — DRAFT/SCHEDULED/CANCELED/COMPLETED are never queried.
    @Test
    void onlyLiveStatusesAreScanned() {
        sweeper.completeFinishedEvents(AFTER_SHOW);

        verify(eventRepo).findByStatus(EventStatus.ON_SALE);
        verify(eventRepo).findByStatus(EventStatus.SOLD_OUT);
        verify(eventRepo, never()).findByStatus(EventStatus.DRAFT);
        verify(eventRepo, never()).findByStatus(EventStatus.SCHEDULED);
        verify(eventRepo, never()).findByStatus(EventStatus.CANCELED);
        verify(eventRepo, never()).findByStatus(EventStatus.COMPLETED);
    }

    // Raced: scanned as ON_SALE, but by the time it's locked the owner has canceled it. The
    // re-check under the lock skips it — no completion — and the lock is still released.
    @Test
    void statusChangedUnderLock_isSkipped() {
        Event scanned = onSaleEvent(4, SHOW_END);       // looked finished + ON_SALE during the scan
        Event canceledNow = canceledEvent(4, SHOW_END); // re-read under the lock returns CANCELED
        when(eventRepo.findByStatus(EventStatus.ON_SALE)).thenReturn(List.of(scanned));
        when(eventRepo.findById(4)).thenReturn(canceledNow);

        int completed = sweeper.completeFinishedEvents(AFTER_SHOW);

        assertEquals(0, completed);
        assertEquals(EventStatus.CANCELED, canceledNow.getStatus());
        verify(eventRepo).lockForUpdate(4);
        verify(eventRepo).unlock(4);
        verify(eventRepo, never()).save(Mockito.any());
    }

    // Resilience: an event vanishing between scan and lock is skipped, its lock is still
    // released, and the rest of the tick proceeds.
    @Test
    void vanishedEvent_isSkipped_lockReleased_sweepContinues() {
        Event gone = onSaleEvent(5, SHOW_END);
        Event ok = onSaleEvent(6, SHOW_END);
        when(eventRepo.findByStatus(EventStatus.ON_SALE)).thenReturn(List.of(gone, ok));
        when(eventRepo.findById(5)).thenThrow(new EventNotFoundException(5));
        when(eventRepo.findById(6)).thenReturn(ok);

        int completed = sweeper.completeFinishedEvents(AFTER_SHOW);

        assertEquals(1, completed);
        verify(eventRepo).lockForUpdate(5);
        verify(eventRepo).unlock(5);            // released despite the exception
        verify(eventRepo, never()).save(gone);
        assertEquals(EventStatus.COMPLETED, ok.getStatus());
        verify(eventRepo).save(ok);
    }

    // -- the venue's zone decides when a show has ended (#74, ADR-008) --------

    @Test
    void GivenJerusalemShowEndingAt23LocalInSummer_WhenSweptAt195959Z_ThenStillOnSale() {
        Event e = liveEventAt(7, SUMMER_SHOW_END);

        sweeperAt("2027-07-15T19:59:59Z").sweep();

        assertEquals(EventStatus.ON_SALE, e.getStatus());
    }

    @Test
    void GivenJerusalemShowEndingAt23LocalInSummer_WhenSweptAt200001Z_ThenCompleted() {
        Event e = liveEventAt(8, SUMMER_SHOW_END);

        sweeperAt("2027-07-15T20:00:01Z").sweep();

        assertEquals(EventStatus.COMPLETED, e.getStatus());
    }

    @Test
    void GivenJerusalemShowEndingAt23LocalInWinter_WhenSweptAt205959Z_ThenStillOnSale() {
        Event e = liveEventAt(9, WINTER_SHOW_END);

        sweeperAt("2027-12-15T20:59:59Z").sweep();

        assertEquals(EventStatus.ON_SALE, e.getStatus());
    }

    @Test
    void GivenJerusalemShowEndingAt23LocalInWinter_WhenSweptAt210001Z_ThenCompleted() {
        Event e = liveEventAt(10, WINTER_SHOW_END);

        sweeperAt("2027-12-15T21:00:01Z").sweep();

        assertEquals(EventStatus.COMPLETED, e.getStatus());
    }

    // -- helpers ---------------------------------------------------------

    private EventCompletionSweeper sweeperAt(String instant) {
        return new EventCompletionSweeper(eventRepo, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private Event liveEventAt(int id, LocalDateTime showEnd) {
        Event e = onSaleEvent(id, showEnd);
        when(eventRepo.findByStatus(EventStatus.ON_SALE)).thenReturn(List.of(e));
        when(eventRepo.findById(id)).thenReturn(e);
        return e;
    }

    private Event onSaleEvent(int id, LocalDateTime showEnd) {
        return buildEvent(id, EventStatus.ON_SALE, showEnd, 10);
    }

    private Event soldOutEvent(int id, LocalDateTime showEnd) {
        Event e = buildEvent(id, EventStatus.ON_SALE, showEnd, 2);
        e.reserveInventory(1, InventorySelection.standing(2)); // exhaust inventory -> SOLD_OUT
        assertEquals(EventStatus.SOLD_OUT, e.getStatus());
        return e;
    }

    private Event canceledEvent(int id, LocalDateTime showEnd) {
        Event e = buildEvent(id, EventStatus.ON_SALE, showEnd, 10);
        e.transitionToCanceled("raced cancel");
        return e;
    }

    private Event buildEvent(int id, EventStatus status, LocalDateTime showEnd, int capacity) {
        StandingZone zone = new StandingZone(1, "General Admission", capacity, 50.0);
        return new Event(
                id,
                "Concert",
                4.5,
                List.of("Artist"),
                EventCategory.CONCERT,
                100,
                status,
                new VenueMap(1, LOCATION, List.of(zone)),
                List.of(new ShowDate(showEnd.minusHours(3), showEnd)),
                JERUSALEM,
                acceptingPurchasePolicy(),
                noDiscountPolicy());
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
            public double calculatePriceforoneticket(int quantity, Double priceAtOneTicketReservation,
                    LocalDateTime now) {
                return priceAtOneTicketReservation;
            }
        };
    }
}
