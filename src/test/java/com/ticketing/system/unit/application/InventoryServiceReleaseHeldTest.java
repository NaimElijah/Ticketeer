package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import ch.qos.logback.classic.Level;

import com.ticketing.system.catalog.application.port.in.InventoryCommandPort;
import com.ticketing.system.catalog.application.port.in.InventoryLineDTO;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.application.service.InventoryService;
import com.ticketing.system.catalog.domain.DiscountPolicy;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.InventorySelection;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.catalog.domain.StandingZone;
import com.ticketing.system.catalog.domain.VenueMap;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.testutil.TestTransactions;

/**
 * A checkout rollback releases every hold it can, even when one of the order's events is gone (#187).
 * Both event adapters throw {@link EventNotFoundException} for a missing event rather than return
 * {@code null}, so a release that only skipped {@code null} aborted on the deleted event and left the
 * buyer's holds in every later event reserved until they expired.
 */
class InventoryServiceReleaseHeldTest {

    private static final int DELETED_EVENT_ID = 4101;
    private static final int LIVE_EVENT_ID = 4102;
    private static final int ZONE_ID = 1;
    private static final int HELD_PLACES = 2;
    private static final String ORDER_KEY = "rolled-back-order";
    private static final LocalDateTime SHOW_START = LocalDateTime.of(2099, 6, 1, 18, 0);
    private static final LocalDateTime HOLD_UNTIL = LocalDateTime.of(2099, 1, 1, 0, 0);

    private final EventRepository events = mock(EventRepository.class);
    private final InventoryCommandPort inventory =
            new InventoryService(events, mock(ProductionCompanyRepository.class), TestTransactions.noOpManager());

    private Event liveEvent;
    private LogCapture inventoryLog;

    @BeforeEach
    void holdPlacesInTwoEventsOfWhichTheFirstIsDeleted() {
        liveEvent = onSaleEventHolding(LIVE_EVENT_ID, HELD_PLACES);
        when(events.findById(DELETED_EVENT_ID)).thenThrow(new EventNotFoundException(DELETED_EVENT_ID));
        when(events.findById(LIVE_EVENT_ID)).thenReturn(liveEvent);
        inventoryLog = LogCapture.of(InventoryService.class);
    }

    @AfterEach
    void detachLog() {
        inventoryLog.close();
    }

    @Test
    void GivenHoldsInADeletedEventAndALiveOne_WhenTheHoldsAreReleased_ThenTheLiveEventsHoldsAreReleasedAndSaved() {
        assertDoesNotThrow(() -> inventory.releaseHeld(heldLines(), ORDER_KEY),
                "a deleted event must not abort the rollback");

        assertEquals(0, liveEvent.getVenueMap().getZone(ZONE_ID).getReservedAmount(),
                "event " + LIVE_EVENT_ID + "'s holds should be released");
        verify(events).save(liveEvent);
    }

    @Test
    void GivenHoldsInADeletedEventAndALiveOne_WhenTheHoldsAreReleased_ThenAWarningNamesTheDeletedEvent() {
        inventory.releaseHeld(heldLines(), ORDER_KEY);

        List<String> warnings = inventoryLog.messagesAt(Level.WARN);
        assertTrue(warnings.stream().anyMatch(message -> message.contains(String.valueOf(DELETED_EVENT_ID))),
                "the skipped event " + DELETED_EVENT_ID + " should be named at WARN; WARN lines: " + warnings);
    }

    // A store failure on one event must not stop the release of the others. It must still reach the caller,
    // so the checkout keeps the cart's lines instead of clearing lines whose holds remain.
    @Test
    void GivenTheSaveOfOneEventFails_WhenTheHoldsAreReleased_ThenTheOtherIsStillReleasedAndTheFailurePropagates() {
        Event failing = onSaleEventHolding(DELETED_EVENT_ID, 1);
        doReturn(failing).when(events).findById(DELETED_EVENT_ID);
        DataAccessResourceFailureException outage = new DataAccessResourceFailureException("connection refused");
        doThrow(outage).when(events).save(failing);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> inventory.releaseHeld(heldLines(), ORDER_KEY));

        assertSame(outage, thrown);
        assertEquals(0, liveEvent.getVenueMap().getZone(ZONE_ID).getReservedAmount(),
                "event " + LIVE_EVENT_ID + "'s holds should be released despite the other event's failure");
        verify(events).save(liveEvent);
    }

    private static List<InventoryLineDTO> heldLines() {
        return List.of(
                new InventoryLineDTO(DELETED_EVENT_ID, ZONE_ID, null),
                new InventoryLineDTO(LIVE_EVENT_ID, ZONE_ID, null),
                new InventoryLineDTO(LIVE_EVENT_ID, ZONE_ID, null));
    }

    private static Event onSaleEventHolding(int eventId, int heldPlaces) {
        Event event = new Event(eventId, "Live Show", 4.0, List.of("Artist A"), EventCategory.CONCERT, 10,
                EventStatus.ON_SALE,
                new VenueMap(eventId, new Location("Belgium", "Brussels"),
                        List.of(new StandingZone(ZONE_ID, "Floor", 100, 50))),
                List.of(new ShowDate(SHOW_START, SHOW_START.plusHours(3))), ZoneId.of("Europe/Brussels"),
                new NoPurchasePolicy(), new DiscountPolicy(0));
        event.reserveInventory(ZONE_ID, InventorySelection.standing(heldPlaces, ORDER_KEY, HOLD_UNTIL));
        return event;
    }
}
