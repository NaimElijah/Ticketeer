package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.InventorySelection;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.testutil.TestTransactions;

/**
 * A member refund's stock return that fails for any reason leaves the refunded places SOLD for good, as
 * one that lost a race on every attempt does, so it is logged at ERROR too, never at WARN (#187).
 */
class InventoryServiceStockReturnFailureTest {

    private static final int EVENT_ID = 4201;
    private static final int ZONE_ID = 1;

    private final EventRepository events = mock(EventRepository.class);
    private final InventoryCommandPort inventory =
            new InventoryService(events, mock(ProductionCompanyRepository.class), TestTransactions.noOpManager());

    private LogCapture inventoryLog;

    @BeforeEach
    void attachLog() {
        inventoryLog = LogCapture.of(InventoryService.class);
    }

    @AfterEach
    void detachLog() {
        inventoryLog.close();
    }

    @Test
    void GivenTheEventStoreFails_WhenARefundReturnsItsPlaces_ThenAnErrorNamesTheEventAndTheRefundGoesOn() {
        when(events.findById(EVENT_ID)).thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertDoesNotThrow(() -> inventory.returnSoldToStock(List.of(new InventoryLineDTO(EVENT_ID, ZONE_ID, null))),
                "the refund has already committed, so a failed stock return must not fail it");

        List<String> errors = inventoryLog.messagesAt(Level.ERROR);
        assertTrue(errors.stream().anyMatch(message -> message.contains(String.valueOf(EVENT_ID))),
                "a stock return that failed should be logged at ERROR naming event " + EVENT_ID
                        + "; ERROR lines: " + errors);
    }

    // A seated zone takes its places back all or nothing, and refuses when one of them is not SOLD. The
    // refund has committed, so the zone's places stay SOLD for good, as when the whole event fails.
    @Test
    void GivenAZoneThatRefusesItsPlacesBack_WhenARefundReturnsThem_ThenAnErrorNamesTheEventAndTheZone() {
        Event event = mock(Event.class);
        when(events.findById(EVENT_ID)).thenReturn(event);
        doThrow(new IllegalStateException("Seat A2 is not SOLD"))
                .when(event).returnSoldToStock(eq(ZONE_ID), any(InventorySelection.class));

        assertDoesNotThrow(() -> inventory.returnSoldToStock(List.of(new InventoryLineDTO(EVENT_ID, ZONE_ID, "A1"))));

        List<String> errors = inventoryLog.messagesAt(Level.ERROR);
        assertTrue(errors.stream().anyMatch(message -> message.contains(String.valueOf(EVENT_ID))
                        && message.contains("zoneId=" + ZONE_ID)),
                "a zone that refused its places should be logged at ERROR naming event " + EVENT_ID
                        + " and zone " + ZONE_ID + "; ERROR lines: " + errors);
    }
}
