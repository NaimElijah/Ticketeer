package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
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

    // ---- Defect 10: Ticket refuses the transitions TicketStatus forbids (#187) ----
    // TicketStatus's state machine: RESERVED -> AVAILABLE, PAID -> ISSUED, PAID/ISSUED -> REFUNDED, and
    // any state -> VOIDED. A new Ticket is PAID, and no public method reaches AVAILABLE or RESERVED
    // legally, so the legal RESERVED -> AVAILABLE release cannot be shown here. Which of the two
    // exceptions a refusal throws is left to the fix; the state must not change either way.

    @Test
    void GivenRefundedTicket_WhenRelease_ThenRefusedAndStillRefunded() {
        Ticket ticket = refundedTicket();

        assertTransitionRefused(ticket, ticket::release, "REFUNDED -> AVAILABLE");
    }

    @Test
    void GivenPaidTicket_WhenRelease_ThenRefusedAndStillPaid() {
        Ticket ticket = paidTicket();

        assertTransitionRefused(ticket, ticket::release, "PAID -> AVAILABLE");
    }

    @Test
    void GivenRefundedTicket_WhenMarkIssued_ThenRefusedAndItsBarcodeKept() {
        Ticket ticket = refundedTicket();

        assertTransitionRefused(ticket, () -> ticket.markIssued("barcode-new"), "REFUNDED -> ISSUED");
        assertEquals(PAID_BARCODE, ticket.getBarcode(), "a refused issuance must not replace the barcode");
    }

    @Test
    void GivenVoidedTicket_WhenMarkIssued_ThenRefusedAndItsBarcodeKept() {
        Ticket ticket = voidedTicket();

        assertTransitionRefused(ticket, () -> ticket.markIssued("barcode-new"), "VOIDED -> ISSUED");
        assertEquals(PAID_BARCODE, ticket.getBarcode(), "a refused issuance must not replace the barcode");
    }

    @Test
    void GivenVoidedTicket_WhenMarkRefunded_ThenRefusedAndStillVoided() {
        Ticket ticket = voidedTicket();

        assertTransitionRefused(ticket, ticket::markRefunded, "VOIDED -> REFUNDED");
    }

    @Test
    void GivenUsedTicket_WhenMarkRefunded_ThenRefusedAndStillUsed() {
        Ticket ticket = usedTicket();

        assertTransitionRefused(ticket, ticket::markRefunded, "USED -> REFUNDED");
    }

    // markPaid and markUsed have no production caller, so they keep to TicketStatus's machine as well.
    @Test
    void GivenPaidTicket_WhenMarkPaid_ThenRefusedAndStillPaid() {
        Ticket ticket = paidTicket();

        assertTransitionRefused(ticket, ticket::markPaid, "PAID -> PAID");
    }

    @Test
    void GivenPaidTicket_WhenMarkUsed_ThenRefusedAndStillPaid() {
        Ticket ticket = paidTicket();

        assertTransitionRefused(ticket, ticket::markUsed, "PAID -> USED");
    }

    // ---- guards: the legal transitions still work ----

    @Test
    void GivenIssuedTicket_WhenMarkUsed_ThenUsed() {
        Ticket ticket = paidTicket();
        ticket.markIssued("barcode-issued");

        ticket.markUsed();

        assertEquals(TicketStatus.USED, ticket.getStatus());
    }

    @Test
    void GivenPaidTicket_WhenMarkIssued_ThenIssuedWithTheNewBarcode() {
        Ticket ticket = paidTicket();

        ticket.markIssued("barcode-issued");

        assertEquals(TicketStatus.ISSUED, ticket.getStatus());
        assertEquals("barcode-issued", ticket.getBarcode());
    }

    @Test
    void GivenIssuedTicket_WhenMarkRefunded_ThenRefunded() {
        Ticket ticket = paidTicket();
        ticket.markIssued("barcode-issued");

        ticket.markRefunded();

        assertEquals(TicketStatus.REFUNDED, ticket.getStatus());
    }

    @Test
    void GivenPaidTicket_WhenMarkVoided_ThenVoided() {
        Ticket ticket = paidTicket();

        ticket.markVoided();

        assertEquals(TicketStatus.VOIDED, ticket.getStatus());
    }

    @Test
    void GivenIssuedTicket_WhenMarkVoided_ThenVoided() {
        Ticket ticket = paidTicket();
        ticket.markIssued("barcode-issued");

        ticket.markVoided();

        assertEquals(TicketStatus.VOIDED, ticket.getStatus());
    }

    private static final String PAID_BARCODE = "barcode-paid";

    // A new Ticket starts PAID.
    private Ticket paidTicket() {
        return track(new Ticket(1, 10, 11, null, 50.0, 100, PAID_BARCODE));
    }

    private Ticket refundedTicket() {
        Ticket ticket = paidTicket();
        ticket.markRefunded();
        return ticket;
    }

    private Ticket voidedTicket() {
        Ticket ticket = paidTicket();
        ticket.markVoided();
        return ticket;
    }

    // Issued, then used, keeping the PAID barcode so a refused issuance can be told from an applied one.
    private Ticket usedTicket() {
        Ticket ticket = paidTicket();
        ticket.markIssued(PAID_BARCODE);
        ticket.markUsed();
        return ticket;
    }

    private static void assertTransitionRefused(Ticket ticket, Executable transition, String transitionName) {
        TicketStatus before = ticket.getStatus();

        RuntimeException refused = assertThrows(RuntimeException.class, transition,
                transitionName + " is not in TicketStatus's state machine, so it must be refused");

        assertTrue(refused instanceof IllegalStateException || refused instanceof InvalidStateTransitionException,
                transitionName + " must be refused as an illegal state transition, but threw " + refused);
        assertEquals(before, ticket.getStatus(), "a refused " + transitionName + " must leave the ticket " + before);
    }









}
