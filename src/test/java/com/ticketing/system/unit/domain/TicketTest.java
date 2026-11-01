package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.shared.exception.TicketNotAvailableException;
import com.ticketing.system.support.BaseDomainTest;
import com.ticketing.system.testutil.Tickets;

// Unit tests for the Ticket aggregate (the unified inventory + post-purchase record).
// TicketStatus documents the state machine; TicketStatusTest pins its whole transition table.
class TicketTest extends BaseDomainTest {

    private static final int HOLDER_ID = 42;
    private static final int OTHER_HOLDER_ID = 43;

    // AVAILABLE and RESERVED are held only by stored rows, so these tickets come from Tickets.storedAs.

    @Test
    void GivenAvailableTicket_WhenReserve_ThenReservedForThatHolder() {
        Ticket ticket = track(storedTicket(TicketStatus.AVAILABLE));

        ticket.reserve(HOLDER_ID);

        assertEquals(TicketStatus.RESERVED, ticket.getStatus());
        assertEquals(HOLDER_ID, ticket.getHolderUserId());
    }

    @Test
    void GivenReservedTicket_WhenAnotherReserveIsAttempted_ThenRefusedAsNotAvailableAndTheHolderKept() {
        Ticket ticket = track(storedTicket(TicketStatus.AVAILABLE));
        ticket.reserve(HOLDER_ID);

        assertThrows(TicketNotAvailableException.class, () -> ticket.reserve(OTHER_HOLDER_ID));

        assertEquals(TicketStatus.RESERVED, ticket.getStatus());
        assertEquals(HOLDER_ID, ticket.getHolderUserId(), "a refused reservation must not take the ticket over");
    }

    @Test
    void GivenReservedTicket_WhenMarkPaid_ThenPaid() {
        Ticket ticket = track(storedTicket(TicketStatus.RESERVED));

        ticket.markPaid();

        assertEquals(TicketStatus.PAID, ticket.getStatus());
    }

    @Test
    void GivenReservedTicket_WhenRelease_ThenAvailable() {
        Ticket ticket = track(storedTicket(TicketStatus.RESERVED));

        ticket.release();

        assertEquals(TicketStatus.AVAILABLE, ticket.getStatus());
    }

    private static Ticket storedTicket(TicketStatus status) {
        return Tickets.storedAs(status, 1, 10, 11, null, 50.0, 100, "barcode-stored");
    }

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
    // A refusal throws InvalidStateTransitionException and leaves the ticket as it was. A new Ticket is
    // PAID; the transitions out of AVAILABLE and RESERVED are shown above, on stored tickets.

    @Test
    void GivenRefundedTicket_WhenMarkRefunded_ThenRefusedAndStillRefunded() {
        Ticket ticket = refundedTicket();

        assertTransitionRefused(ticket, ticket::markRefunded, "REFUNDED -> REFUNDED");
    }

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

        assertInstanceOf(InvalidStateTransitionException.class, refused,
                transitionName + " must be refused as an illegal state transition");
        assertEquals(before, ticket.getStatus(), "a refused " + transitionName + " must leave the ticket " + before);
    }









}
