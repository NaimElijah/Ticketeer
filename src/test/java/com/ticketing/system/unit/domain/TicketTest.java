package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.support.BaseDomainTest;

// Unit tests for the Ticket aggregate (the unified inventory + post-purchase record).
// State machine: AVAILABLE -> RESERVED -> PAID -> ISSUED -> USED | REFUNDED | VOIDED
class TicketTest extends BaseDomainTest {

    @Test
    @Disabled("V1: implement Ticket lifecycle invariants")
    void givenAvailableTicket_whenReserve_thenStatusReserved() {}

    @Test
    @Disabled("V1: implement SLR.1.2 race condition prevention via optimistic lock")
    void givenReservedTicket_whenAnotherReserveAttempted_thenRejected() {}

    @Test
    @Disabled("V1: implement RESERVED -> PAID transition on successful checkout (UC-10)")
    void givenReservedTicket_whenPaid_thenStatusPaid() {}

    @Test
    @Disabled("V1: implement RESERVED -> AVAILABLE on expiry (UC-2)")
    void givenReservedTicket_whenExpired_thenStatusAvailable() {}

    // ---------------------------------------------------------------------
    // D7 — nullable holderUserId (auth rework #181)
    // ---------------------------------------------------------------------

    @Test
    void freshlyConstructedTicket_hasNullHolderUserId() {
        Ticket t = track(new Ticket(1, 10, 11, null, 25.0, 100, "barcode-X"));
        assertNull(t.getHolderUserId());
    }

    @Test
    void setHolderUserId_assignsMemberOwnership() {
        Ticket t = track(new Ticket(1, 10, 11, null, 25.0, 100, "barcode-X"));
        t.setHolderUserId(42);
        assertEquals(42, t.getHolderUserId());
    }

    @Test
    void setHolderUserId_nullClearsOwnership() {
        // E.g., refund-to-pool flow could clear the holder. Domain allows it.
        Ticket t = track(new Ticket(1, 10, 11, null, 25.0, 100, "barcode-X"));
        t.setHolderUserId(42);
        t.setHolderUserId(null);
        assertNull(t.getHolderUserId());
    }

    @Test
    void testMarkRefundedChangesStatusToRefunded() {
        Ticket ticket = track(new Ticket(10, 1, 11, null, 50.0, 100, "BARCODE123"));
        ticket.markRefunded();
        assertEquals(TicketStatus.REFUNDED, ticket.getStatus());
    }




    


    @Test
    void GivenStandingTicket_WhenConstructed_ThenSeatNumberIsNull() {
        Ticket ticket = track(new Ticket(1, 10, 11, null, 50.0, 100, "barcode-X"));

        assertNull(ticket.getSeatNumber());
        assertEquals(1, ticket.getEventId());
        assertEquals(10, ticket.getZoneId());
    }

    @Test
    void GivenSeatedTicket_WhenConstructed_ThenSeatNumberIsPreserved() {
        Ticket ticket = track(new Ticket(1, 10, 11, "A12", 75.0, 101, "barcode-Y"));

        assertEquals("A12", ticket.getSeatNumber());
        assertEquals(1, ticket.getEventId());
        assertEquals(10, ticket.getZoneId());
        assertEquals(101, ticket.getId());
    }

    @Test
    void GivenBlankSeatNumber_WhenConstructed_ThenThrowsException() {
        assertThrows(IllegalStateException.class,
                () -> new Ticket(1, 10, 11, "   ", 75.0, 101, "barcode-Y"));
    }









}
