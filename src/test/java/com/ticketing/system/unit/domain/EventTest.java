package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.Core.Domain.events.Event;
import com.ticketing.system.Core.Domain.events.EventStatus;
import com.ticketing.system.Core.Domain.events.InventorySelection;
import com.ticketing.system.Core.Domain.events.InventoryZone;
import com.ticketing.system.Core.Domain.events.StandingZone;
import com.ticketing.system.Core.Domain.events.Location;
import com.ticketing.system.Core.Domain.events.VenueMap;
import com.ticketing.system.Core.Domain.policies.purchase.NoPurchasePolicy;
import com.ticketing.system.Core.Domain.events.EventCategory;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.support.BaseDomainTest;

import java.time.LocalDateTime;
import com.ticketing.system.Core.Domain.events.DiscountPolicy;
import com.ticketing.system.Core.Domain.policies.purchase.PurchasePolicy;
import com.ticketing.system.Core.Domain.events.Seat;
import com.ticketing.system.Core.Domain.events.SeatStatus;
import com.ticketing.system.Core.Domain.events.SeatedZone;
import com.ticketing.system.Core.Domain.events.ShowDate;

// Unit tests for the Event aggregate (Event + VenueMap + InventoryZone + ShowDate + policies).
class EventTest extends BaseDomainTest {

    private final int COMPANY_ID = 100;
    private final int EVENT_ID = 10;
    private final int ZONE_ID = 5;
    private final Location LOCATION = new Location("Belgium", "Brussels");

    private final List<String> ARTISTS = List.of("Artist 1", "Artist 2");

    private InventoryZone zone;
    private Event event;

    @BeforeEach
    public void setUp() {
        zone = track(new StandingZone(ZONE_ID, "VIP", 10, 100));

        VenueMap venueMap = new VenueMap(1, LOCATION, List.of(zone));

        event = track(new Event(
                EVENT_ID,
                "Concert",
                4.5,
                ARTISTS,
                EventCategory.CONCERT,
                COMPANY_ID,
                EventStatus.SCHEDULED,
                venueMap,
                List.of(new ShowDate(LocalDateTime.now().plusDays(30), LocalDateTime.now().plusDays(30).plusHours(2))),
                null,
                new DiscountPolicy(0)));

    }

    // Construction-time invariant enforcement (issue #303): a freshly-constructed
    // Event
    // must satisfy its invariants, so invalid args fail at construction with
    // IllegalStateException.
    @Test
    void GivenRatingOutOfRange_WhenConstructed_ThenThrowsIllegalState() {
        VenueMap venueMap = new VenueMap(1, LOCATION, List.of(new StandingZone(ZONE_ID, "VIP", 10, 100)));
        assertThrows(IllegalStateException.class, () -> new Event(
                EVENT_ID, "Concert", 9.0, ARTISTS, EventCategory.CONCERT, COMPANY_ID,
                EventStatus.SCHEDULED, venueMap,
                List.of(new ShowDate(LocalDateTime.now().plusDays(30), LocalDateTime.now().plusDays(30).plusHours(2))),
                null, new DiscountPolicy(0)));
    }

    @Test
    void GivenEmptyArtists_WhenConstructed_ThenThrowsIllegalState() {
        VenueMap venueMap = new VenueMap(1, LOCATION, List.of(new StandingZone(ZONE_ID, "VIP", 10, 100)));
        assertThrows(IllegalStateException.class, () -> new Event(
                EVENT_ID, "Concert", 4.5, List.of(), EventCategory.CONCERT, COMPANY_ID,
                EventStatus.SCHEDULED, venueMap,
                List.of(new ShowDate(LocalDateTime.now().plusDays(30), LocalDateTime.now().plusDays(30).plusHours(2))),
                null, new DiscountPolicy(0)));
    }

    @Test
    void GivenEmptyShowDates_WhenConstructed_ThenThrowsIllegalState() {
        VenueMap venueMap = new VenueMap(1, LOCATION, List.of(new StandingZone(ZONE_ID, "VIP", 10, 100)));
        assertThrows(IllegalStateException.class, () -> new Event(
                EVENT_ID, "Concert", 4.5, ARTISTS, EventCategory.CONCERT, COMPANY_ID,
                EventStatus.SCHEDULED, venueMap, List.of(),
                null, new DiscountPolicy(0)));
    }

    // Member refund returns SOLD inventory to AVAILABLE and re-opens a sold-out event.
    @Test
    void GivenSoldOutEvent_WhenReturnSoldToStock_ThenPlaceFreedAndRevertsToOnSale() {
        StandingZone z = new StandingZone(ZONE_ID, "GA", 2, 50);
        VenueMap vm = new VenueMap(1, LOCATION, List.of(z));
        Event e = track(new Event(EVENT_ID, "Concert", 4.5, ARTISTS, EventCategory.CONCERT, COMPANY_ID,
                EventStatus.ON_SALE, vm,
                List.of(new ShowDate(LocalDateTime.now().plusDays(30), LocalDateTime.now().plusDays(30).plusHours(2))),
                null, new DiscountPolicy(0)));

        // Sell the event out.
        e.reserveInventory(ZONE_ID, InventorySelection.standing(2, "ord"));
        e.confirmInventorySale(ZONE_ID, InventorySelection.standing(2, "ord"));
        assertEquals(EventStatus.SOLD_OUT, e.getStatus());

        // Refund one place — it returns to AVAILABLE and the event re-opens.
        e.returnSoldToStock(ZONE_ID, InventorySelection.standing(1));

        assertEquals(EventStatus.ON_SALE, e.getStatus());
        assertEquals(1, z.getSoldAmount());
        assertEquals(1, z.getAvailableAmount());
    }

    // UC-19 — editDetails applies all five updatable fields while DRAFT or
    // SCHEDULED.
    @Test
    void GivenScheduledEvent_WhenEditDetailsAllFields_ThenAllApplied() {
        ShowDate newShow = new ShowDate(LocalDateTime.now().plusDays(40),
                LocalDateTime.now().plusDays(40).plusHours(2));

        event.editDetails("New Name", "New Description", EventCategory.THEATER,
                new Location("France", "Paris"), List.of(newShow));

        assertEquals("New Name", event.getName());
        assertEquals("New Description", event.getDescription());
        assertEquals(EventCategory.THEATER, event.getCategory());
        assertEquals(new Location("France", "Paris"), event.getVenueMap().getLocation());
        assertEquals(1, event.getShowDates().size());
        assertEquals(newShow.getStartTime(), event.getShowDates().get(0).getStartTime());
    }

    // Null arguments mean "leave this field alone".
    @Test
    void GivenNullArguments_WhenEditDetails_ThenNothingChanges() {
        event.editDetails(null, null, null, null, null);

        assertEquals("Concert", event.getName());
        assertEquals(EventCategory.CONCERT, event.getCategory());
        assertEquals(LOCATION, event.getVenueMap().getLocation());
        assertEquals(1, event.getShowDates().size());
    }

    // An explicitly empty showDates list is rejected (vs null, which leaves the
    // schedule alone).
    @Test
    void GivenEmptyShowDates_WhenEditDetails_ThenThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> event.editDetails(
                null, null, null, null, List.of()));
    }

    @Test
    void GivenOnSaleEvent_WhenEditDetails_ThenThrowsIllegalState() {
        event.transitionToOnSale();

        assertThrows(IllegalStateException.class, () -> event.editDetails(
                "New Name", null, null, null, null));
    }

    @Test
    void GivenStandingZone_WhenReserveInventoryStandingSelection_ThenQuantityReserved() {
        StandingZone standingZone = track(new StandingZone(1, "General Admission", 10, 50.0));
        Event event = createEventWithZones(List.of(standingZone));

        event.transitionToOnSale();
        event.reserveInventory(1, InventorySelection.standing(3));

        assertEquals(7, standingZone.getAvailableAmount());
        assertEquals(3, standingZone.getReservedAmount());
    }

    @Test
    void GivenSeatedZone_WhenReserveInventorySeatSelection_ThenExactSeatsReserved() {
        SeatedZone seatedZone = track(new SeatedZone(
                2,
                "Orchestra",
                120.0,
                List.of(
                        new Seat("A1", 0, 0),
                        new Seat("A2", 1, 0),
                        new Seat("A3", 2, 0))));
        Event event = createEventWithZones(List.of(seatedZone));

        event.transitionToOnSale();
        event.reserveInventory(2, InventorySelection.seated(List.of("A1", "A2"), "test-order"));

        assertEquals(SeatStatus.RESERVED, seatedZone.getSeatStatus("A1"));
        assertEquals(SeatStatus.RESERVED, seatedZone.getSeatStatus("A2"));
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A3"));
    }

    @Test
    void GivenSeatedZone_WhenReleaseInventorySeatSelection_ThenExactSeatsReleased() {
        SeatedZone seatedZone = track(new SeatedZone(
                2,
                "Orchestra",
                120.0,
                List.of(
                        new Seat("A1", 0, 0),
                        new Seat("A2", 1, 0))));
        Event event = createEventWithZones(List.of(seatedZone));

        event.transitionToOnSale();
        event.reserveInventory(2, InventorySelection.seated(List.of("A1", "A2"), "test-order"));
        event.releaseInventory(2, InventorySelection.seated(List.of("A1"), "test-order"));

        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A1"));
        assertEquals(SeatStatus.RESERVED, seatedZone.getSeatStatus("A2"));
    }

    @Test
    void GivenSeatedZone_WhenConfirmSale_ThenReservedSeatsBecomeSold() {
        SeatedZone seatedZone = track(new SeatedZone(
                2,
                "Orchestra",
                120.0,
                List.of(
                        new Seat("A1", 0, 0),
                        new Seat("A2", 1, 0))));
        Event event = createEventWithZones(List.of(seatedZone));

        event.transitionToOnSale();
        event.reserveInventory(2, InventorySelection.seated(List.of("A1"), "test-order"));
        event.confirmInventorySale(2, InventorySelection.seated(List.of("A1"), "test-order"));

        assertEquals(SeatStatus.SOLD, seatedZone.getSeatStatus("A1"));
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A2"));
    }

    @Test
    void GivenPurchasePolicyRejectsQuantity_WhenReserveInventory_ThenThrowsException() {
        StandingZone standingZone = track(new StandingZone(1, "General Admission", 10, 50.0));

        PurchasePolicy rejectingPolicy = new NoPurchasePolicy();

        Event event = track(new Event(
                EVENT_ID,
                "Concert",
                4.5,
                ARTISTS,
                EventCategory.CONCERT,
                COMPANY_ID,
                EventStatus.SCHEDULED,
                new VenueMap(1, LOCATION, List.of(standingZone)),
                List.of(new ShowDate(LocalDateTime.now().plusDays(15), LocalDateTime.now().plusDays(15).plusHours(2))),
                rejectingPolicy,
                noDiscountPolicy()));

        assertThrows(IllegalStateException.class, () -> event.reserveInventory(1, InventorySelection.standing(1)));

        assertEquals(10, standingZone.getAvailableAmount());
        assertEquals(0, standingZone.getReservedAmount());
    }

    @Test
    void GivenScheduledEvent_WhenAddStandingZone_ThenZoneIsAdded() {
        StandingZone existingZone = track(new StandingZone(1, "General", 100, 50));
        Event event = createEventWithZones(List.of(existingZone));

        int newZoneId = event.addStandingZone("VIP Standing", 30, 120.0, COMPANY_ID);

        InventoryZone addedZone = event.getVenueMap().getZone(newZoneId);

        assertEquals("VIP Standing", addedZone.getName());
        assertEquals(30, addedZone.getCapacity());
        assertEquals(30, addedZone.getAvailableAmount());
    }

    @Test
    void GivenScheduledEvent_WhenAddSeatedZone_ThenZoneIsAdded() {
        StandingZone existingZone = track(new StandingZone(1, "General", 100, 50));
        Event event = createEventWithZones(List.of(existingZone));

        int newZoneId = event.addSeatedZone(
                "Balcony",
                150.0,
                List.of(
                        new Seat("B1", 0, 0),
                        new Seat("B2", 1, 0)),
                COMPANY_ID);

        InventoryZone addedZone = event.getVenueMap().getZone(newZoneId);

        assertEquals("Balcony", addedZone.getName());
        assertEquals(2, addedZone.getCapacity());
        assertEquals(2, addedZone.getAvailableAmount());
    }

    @Test
    void GivenScheduledEvent_WhenRemoveEmptyZone_ThenZoneIsRemoved() {
        StandingZone zoneToRemove = track(new StandingZone(1, "General", 100, 50));
        StandingZone remainingZone = track(new StandingZone(2, "VIP", 20, 150));

        Event event = createEventWithZones(List.of(zoneToRemove, remainingZone));

        event.removeInventoryZone(1, COMPANY_ID);

        assertThrows(IllegalArgumentException.class, () -> event.getVenueMap().getZone(1));
        assertEquals("VIP", event.getVenueMap().getZone(2).getName());
    }

    @Test
    void GivenReservedInventoryInZone_WhenRemoveZone_ThenThrowsException() {
        StandingZone zone = track(new StandingZone(1, "General", 100, 50));
        Event event = createEventWithZones(List.of(zone));

        event.transitionToOnSale();
        event.reserveInventory(1, InventorySelection.standing(5));

        assertThrows(IllegalStateException.class, () -> event.removeInventoryZone(1, COMPANY_ID));

        assertEquals(100, zone.getCapacity());
        assertEquals(5, zone.getReservedAmount());
    }

    @Test
    void GivenOnSaleEvent_WhenAddStandingZone_ThenThrowsException() {
        StandingZone existingZone = track(new StandingZone(1, "General", 100, 50));

        Event event = track(new Event(
                EVENT_ID,
                "Concert",
                4.5,
                ARTISTS,
                EventCategory.CONCERT,
                COMPANY_ID,
                EventStatus.ON_SALE,
                new VenueMap(1, LOCATION, List.of(existingZone)),
                List.of(new ShowDate(
                        LocalDateTime.now().plusDays(30),
                        LocalDateTime.now().plusDays(30).plusHours(2))),
                acceptingPurchasePolicy(),
                noDiscountPolicy()));

        assertThrows(IllegalStateException.class, () -> event.addStandingZone("Late Zone", 50, 90.0, COMPANY_ID));
    }

    @Test
    void GivenOnSaleEvent_WhenRemoveZone_ThenThrowsException() {
        StandingZone existingZone = track(new StandingZone(1, "General", 100, 50));

        Event event = track(new Event(
                EVENT_ID,
                "Concert",
                4.5,
                ARTISTS,
                EventCategory.CONCERT,
                COMPANY_ID,
                EventStatus.ON_SALE,
                new VenueMap(1, LOCATION, List.of(existingZone)),
                List.of(new ShowDate(
                        LocalDateTime.now().plusDays(30),
                        LocalDateTime.now().plusDays(30).plusHours(2))),
                acceptingPurchasePolicy(),
                noDiscountPolicy()));

        assertThrows(IllegalStateException.class, () -> event.removeInventoryZone(1, COMPANY_ID));
    }

    @Test
    void GivenWrongCompany_WhenAddZone_ThenThrowsException() {
        StandingZone existingZone = track(new StandingZone(1, "General", 100, 50));
        Event event = createEventWithZones(List.of(existingZone));

        assertThrows(RuntimeException.class, () -> event.addStandingZone("Wrong Company Zone", 50, 90.0, 999));
    }

    @Test
    void GivenDuplicateZoneName_WhenAddZone_ThenThrowsException() {
        StandingZone existingZone = track(new StandingZone(1, "General", 100, 50));
        Event event = createEventWithZones(List.of(existingZone));

        assertThrows(IllegalArgumentException.class, () -> event.addStandingZone("General", 50, 90.0, COMPANY_ID));
    }

    @Test
    void GivenScheduledEvent_WhenAddSeatsToSeatedZone_ThenSeatsAdded() {
        SeatedZone seatedZone = track(new SeatedZone(
                ZONE_ID,
                "Orchestra",
                120.0,
                List.of(new Seat("A1", 0, 0))));

        Event event = createEventWithZones(List.of(seatedZone));

        event.addSeatsToSeatedZone(
                ZONE_ID,
                List.of(new Seat("A2", 1, 0), new Seat("A3", 2, 0)),
                COMPANY_ID);

        assertEquals(3, seatedZone.getCapacity());
        assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A2"));
    }

    @Test
    void GivenOnSaleEvent_WhenAddSeatsToSeatedZone_ThenThrowsException() {
        SeatedZone seatedZone = track(new SeatedZone(
                ZONE_ID,
                "Orchestra",
                120.0,
                List.of(new Seat("A1", 0, 0))));

        Event event = track(new Event(
                EVENT_ID,
                "Concert",
                4.5,
                ARTISTS,
                EventCategory.CONCERT,
                COMPANY_ID,
                EventStatus.ON_SALE,
                new VenueMap(1, LOCATION, List.of(seatedZone)),
                List.of(new ShowDate(
                        LocalDateTime.now().plusDays(30),
                        LocalDateTime.now().plusDays(30).plusHours(2))),
                acceptingPurchasePolicy(),
                noDiscountPolicy()));

        assertThrows(IllegalStateException.class, () -> event.addSeatsToSeatedZone(
                ZONE_ID,
                List.of(new Seat("A2", 1, 0)),
                COMPANY_ID));
    }

    @Test
    void GivenScheduledEvent_WhenAddAndRemovePlacesFromStandingZone_ThenCapacityUpdated() {
        StandingZone standingZone = track(new StandingZone(ZONE_ID, "General", 100, 50));
        Event event = createEventWithZones(List.of(standingZone));

        event.addPlacesToStandingZone(ZONE_ID, 20, COMPANY_ID);
        event.removePlacesFromStandingZone(ZONE_ID, 10, COMPANY_ID);

        assertEquals(110, standingZone.getCapacity());
        assertEquals(110, standingZone.getAvailableAmount());
    }

    @Test
    void GivenOnSaleEvent_WhenRemovePlacesFromStandingZone_ThenThrowsException() {
        StandingZone standingZone = track(new StandingZone(ZONE_ID, "General", 100, 50));

        Event event = track(new Event(
                EVENT_ID,
                "Concert",
                4.5,
                ARTISTS,
                EventCategory.CONCERT,
                COMPANY_ID,
                EventStatus.ON_SALE,
                new VenueMap(1, LOCATION, List.of(standingZone)),
                List.of(new ShowDate(
                        LocalDateTime.now().plusDays(30),
                        LocalDateTime.now().plusDays(30).plusHours(2))),
                acceptingPurchasePolicy(),
                noDiscountPolicy()));

        assertThrows(IllegalStateException.class,
                () -> event.removePlacesFromStandingZone(ZONE_ID, 10, COMPANY_ID));
    }

    @Test
    void GivenEventCanceledAfterReservation_WhenConfirmInventorySale_ThenThrowException() {
        StandingZone standingZone = track(new StandingZone(1, "General Admission", 10, 50.0));
        Event event = createEventWithZones(List.of(standingZone));

        event.transitionToOnSale();
        event.reserveInventory(1, InventorySelection.standing(1, "order-1"));

        event.transitionToCanceled("Cancelled during checkout");

        assertThrows(IllegalStateException.class,
                () -> event.confirmInventorySale(1, InventorySelection.standing(1, "order-1")));

        assertEquals(1, standingZone.getReservedAmount());
        assertEquals(0, standingZone.getSoldAmount());
    }

    // -- hasFinishedAsOf (auto-completion predicate) ---------------------

    // Drives the completion sweeper: true once `now` is strictly past the last show's end.
    @Test
    void GivenSingleShow_WhenHasFinishedAsOf_ThenStrictlyAfterEndTime() {
        LocalDateTime end = LocalDateTime.now().plusDays(10);
        Event e = eventWithShowDates(List.of(new ShowDate(end.minusHours(2), end)));

        assertFalse(e.hasFinishedAsOf(end.minusSeconds(1)), "before the show ends");
        assertFalse(e.hasFinishedAsOf(end), "exactly at the end is not yet past (strict isAfter)");
        assertTrue(e.hasFinishedAsOf(end.plusSeconds(1)), "after the show ends");
    }

    // A multi-leg event is finished only after its LATEST leg ends — uses max(endTime),
    // not the first/last element of the list. The earlier-ending leg is placed last to
    // expose any "use the last element" bug.
    @Test
    void GivenMultiShow_WhenHasFinishedAsOf_ThenUsesLatestEnd() {
        LocalDateTime lastEnd = LocalDateTime.now().plusDays(20);
        LocalDateTime earlierEnd = LocalDateTime.now().plusDays(10);
        Event e = eventWithShowDates(List.of(
                new ShowDate(lastEnd.minusHours(2), lastEnd),
                new ShowDate(earlierEnd.minusHours(2), earlierEnd)));

        // Past the earlier leg but before the latest leg → not finished.
        assertFalse(e.hasFinishedAsOf(earlierEnd.plusDays(1)));
        // Past the latest leg → finished.
        assertTrue(e.hasFinishedAsOf(lastEnd.plusSeconds(1)));
    }

    // -- ON_SALE / SOLD_OUT -> COMPLETED transition ----------------------

    @Test
    void GivenOnSaleEvent_WhenTransitionToCompleted_ThenCompleted() {
        Event event = createEventWithZones(List.of(track(new StandingZone(1, "General", 5, 50.0))));
        event.transitionToOnSale();

        event.transitionToCompleted();

        assertEquals(EventStatus.COMPLETED, event.getStatus());
    }

    @Test
    void GivenSoldOutEvent_WhenTransitionToCompleted_ThenCompleted() {
        StandingZone zone = track(new StandingZone(1, "General", 2, 50.0));
        Event event = createEventWithZones(List.of(zone));
        event.transitionToOnSale();
        event.reserveInventory(1, InventorySelection.standing(2)); // exhaust inventory -> SOLD_OUT
        assertEquals(EventStatus.SOLD_OUT, event.getStatus());

        event.transitionToCompleted();

        assertEquals(EventStatus.COMPLETED, event.getStatus());
    }

    @Test
    void GivenCompletedEvent_WhenTransitionToCompletedAgain_ThenIdempotent() {
        Event event = createEventWithZones(List.of(track(new StandingZone(1, "General", 5, 50.0))));
        event.transitionToOnSale();
        event.transitionToCompleted();

        event.transitionToCompleted(); // no throw

        assertEquals(EventStatus.COMPLETED, event.getStatus());
    }

    @Test
    void GivenScheduledEvent_WhenTransitionToCompleted_ThenThrows() {
        Event event = createEventWithZones(List.of(track(new StandingZone(1, "General", 5, 50.0))));

        assertThrows(IllegalStateException.class, event::transitionToCompleted);
    }

    @Test
    void GivenDraftEvent_WhenTransitionToCompleted_ThenThrows() {
        Event event = createDraftEvent();

        assertThrows(IllegalStateException.class, event::transitionToCompleted);
    }

    @Test
    void GivenCanceledEvent_WhenTransitionToCompleted_ThenThrows() {
        Event event = createEventWithZones(List.of(track(new StandingZone(1, "General", 5, 50.0))));
        event.transitionToOnSale();
        event.transitionToCanceled("done");

        assertThrows(IllegalStateException.class, event::transitionToCompleted);
    }

    // test helper functions:

    private Event eventWithShowDates(List<ShowDate> showDates) {
        return track(new Event(
                EVENT_ID,
                "Concert",
                4.5,
                ARTISTS,
                EventCategory.CONCERT,
                COMPANY_ID,
                EventStatus.ON_SALE,
                new VenueMap(1, LOCATION, List.of(track(new StandingZone(ZONE_ID, "VIP", 10, 100)))),
                showDates,
                acceptingPurchasePolicy(),
                noDiscountPolicy()));
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

    // -- DRAFT -> SCHEDULED transition (issue #305) -----------------------

    @Test
    void GivenDraftEventWithInventory_WhenConfigureVenueMap_ThenStatusScheduled() {
        Event event = createDraftEvent();
        StandingZone zone = track(new StandingZone(1, "General", 50, 80.0));

        event.configureVenueMap(new VenueMap(1, LOCATION, List.of(zone)), COMPANY_ID);

        assertEquals(EventStatus.SCHEDULED, event.getStatus());
    }

    @Test
    void GivenDraftEventNoInventory_WhenConfigureVenueMap_ThenStaysDraft() {
        Event event = createDraftEvent();

        event.configureVenueMap(new VenueMap(1, LOCATION, List.of()), COMPANY_ID);

        assertEquals(EventStatus.DRAFT, event.getStatus());
    }

    @Test
    void GivenScheduledEvent_WhenTransitionToScheduledAgain_ThenIdempotent() {
        StandingZone zone = track(new StandingZone(1, "General", 50, 80.0));
        Event event = createEventWithZones(List.of(zone));

        event.transitionToScheduled();

        assertEquals(EventStatus.SCHEDULED, event.getStatus());
    }

    @Test
    void GivenDraftEvent_WhenTransitionToOnSale_ThenThrowsInvalidStateTransition() {
        Event event = createDraftEvent();
        // addStandingZone gives inventory without triggering the auto-transition, so
        // the
        // event still has DRAFT status when transitionToOnSale is called directly.
        event.addStandingZone("General", 50, 80.0, COMPANY_ID);

        assertThrows(InvalidStateTransitionException.class, event::transitionToOnSale);
    }

    @Test
    void GivenDraftEventWithInventory_WhenScheduledThenOnSale_ThenOnSale() {
        Event event = createDraftEvent();
        StandingZone zone = track(new StandingZone(1, "General", 50, 80.0));

        event.configureVenueMap(new VenueMap(1, LOCATION, List.of(zone)), COMPANY_ID); // -> SCHEDULED
        event.transitionToOnSale(); // -> ON_SALE

        assertEquals(EventStatus.ON_SALE, event.getStatus());
    }

    // -- ON_SALE <-> SOLD_OUT transition --------------------------------

    @Test
    void GivenOnSaleEvent_WhenAllInventoryReserved_ThenSoldOut() {
        StandingZone zone = track(new StandingZone(1, "General", 5, 50.0));
        Event event = createEventWithZones(List.of(zone));
        event.transitionToOnSale();

        event.reserveInventory(1, InventorySelection.standing(5));

        assertEquals(EventStatus.SOLD_OUT, event.getStatus());
    }

    @Test
    void GivenOnSaleEvent_WhenPartiallyReserved_ThenStaysOnSale() {
        StandingZone zone = track(new StandingZone(1, "General", 5, 50.0));
        Event event = createEventWithZones(List.of(zone));
        event.transitionToOnSale();

        event.reserveInventory(1, InventorySelection.standing(3));

        assertEquals(EventStatus.ON_SALE, event.getStatus());
    }

    @Test
    void GivenSoldOutEvent_WhenReservationReleased_ThenBackOnSale() {
        StandingZone zone = track(new StandingZone(1, "General", 5, 50.0));
        Event event = createEventWithZones(List.of(zone));
        event.transitionToOnSale();
        event.reserveInventory(1, InventorySelection.standing(5, "order-1"));
        assertEquals(EventStatus.SOLD_OUT, event.getStatus());

        event.releaseInventory(1, InventorySelection.standing(1, "order-1"));

        assertEquals(EventStatus.ON_SALE, event.getStatus());
    }

    @Test
    void GivenMultiZone_WhenOneZoneFullButAnotherHasAvailability_ThenStaysOnSale() {
        StandingZone full = track(new StandingZone(1, "General", 5, 50.0));
        StandingZone other = track(new StandingZone(2, "VIP", 5, 120.0));
        Event event = createEventWithZones(List.of(full, other));
        event.transitionToOnSale();

        event.reserveInventory(1, InventorySelection.standing(5));

        assertEquals(EventStatus.ON_SALE, event.getStatus());
    }

    @Test
    void GivenSoldOutEvent_WhenSaleConfirmed_ThenStaysSoldOut() {
        StandingZone zone = track(new StandingZone(1, "General", 5, 50.0));
        Event event = createEventWithZones(List.of(zone));
        event.transitionToOnSale();
        event.reserveInventory(1, InventorySelection.standing(5, "order-1"));
        assertEquals(EventStatus.SOLD_OUT, event.getStatus());

        event.confirmInventorySale(1, InventorySelection.standing(5, "order-1"));

        assertEquals(EventStatus.SOLD_OUT, event.getStatus());
    }

    @Test
    void GivenScheduledEvent_WhenRevertToOnSaleCalledDirectly_ThenThrows() {
        StandingZone zone = track(new StandingZone(1, "General", 5, 50.0));
        Event event = createEventWithZones(List.of(zone));

        assertThrows(InvalidStateTransitionException.class, event::revertToOnSale);
    }

    private Event createDraftEvent() {
        return track(new Event(
                EVENT_ID,
                "Concert",
                4.5,
                ARTISTS,
                EventCategory.CONCERT,
                COMPANY_ID,
                EventStatus.DRAFT,
                new VenueMap(1, LOCATION, List.of()),
                List.of(new ShowDate(LocalDateTime.now().plusDays(30), LocalDateTime.now().plusDays(30).plusHours(2))),
                acceptingPurchasePolicy(),
                noDiscountPolicy()));
    }

    private Event createEventWithZones(List<InventoryZone> zones) {
        return track(new Event(
                EVENT_ID,
                "Concert",
                4.5,
                ARTISTS,
                EventCategory.CONCERT,
                COMPANY_ID,
                EventStatus.SCHEDULED,
                new VenueMap(1, LOCATION, zones),
                List.of(new ShowDate(LocalDateTime.now().plusDays(30), LocalDateTime.now().plusDays(30).plusHours(2))),
                acceptingPurchasePolicy(),
                noDiscountPolicy()));
    }
}
