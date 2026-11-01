package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.ticketing.system.reporting.application.dto.SalesSummaryDTO;
import com.ticketing.system.reporting.application.service.SalesSummaryCalculator;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.RefundStatus;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.TicketRecordDTO;
import com.ticketing.system.sales.domain.TicketStatus;

/** Refund-aware headline figures for the sales and global-history pages (#21). */
class SalesSummaryCalculatorTest {

    private final SalesSummaryCalculator calculator = new SalesSummaryCalculator();

    @Test
    void GivenAPaidAndARefundedOrder_WhenSummarised_ThenOnlyThePaidOrderCounts() {
        PurchaseRecordDTO paid = order(ticket(1, "Gala", 600.0, false));
        PurchaseRecordDTO refunded = order(ticket(1, "Gala", 300.0, true));

        SalesSummaryDTO s = calculator.summarize(List.of(paid, refunded));

        assertEquals(600.0, s.revenue(), "the refunded order is excluded from revenue");
        assertEquals(1, s.ticketsSold(), "the refunded order's ticket is not counted as sold");
        assertEquals(600.0, s.avgOrderValue(), "the average is over orders that still have a sale");
        assertEquals(Optional.of("Gala"), s.topEvent());
        assertEquals(1, s.refundedOrders());
    }

    // #21: cancelling one event of a multi-event order refunds only that event's lines.
    @Test
    void GivenMultiEventOrderWithOneEventRefunded_WhenSummarised_ThenItsOtherLinesStillCount() {
        PurchaseRecordDTO multiEvent = order(ticket(1, "Cancelled", 100.0, true),
                ticket(2, "Kept", 40.0, false), ticket(2, "Kept", 40.0, false));

        SalesSummaryDTO s = calculator.summarize(List.of(multiEvent));

        assertEquals(80.0, s.revenue());
        assertEquals(2, s.ticketsSold());
        assertEquals(80.0, s.avgOrderValue());
        assertEquals(Optional.of("Kept"), s.topEvent());
        assertEquals(1, s.refundedOrders(), "a partly refunded order counts as an order with a refund");
    }

    // #21: on a shared order a company's record holds only its own lines, which another company's
    // cancellation leaves unrefunded.
    @Test
    void GivenACompanysLinesOfASharedOrderWhoseOtherEventWasRefunded_WhenSummarised_ThenTheyAllCount() {
        PurchaseRecordDTO ownLinesOnly = order(ticket(1, "Ours", 70.0, false), ticket(1, "Ours", 30.0, false));

        SalesSummaryDTO s = calculator.summarize(List.of(ownLinesOnly));

        assertEquals(100.0, s.revenue());
        assertEquals(2, s.ticketsSold());
        assertEquals(0, s.refundedOrders());
    }

    @Test
    void GivenOnlyRefundedOrders_WhenSummarised_ThenEverythingIsZeroAndThereIsNoTopEvent() {
        SalesSummaryDTO s = calculator.summarize(List.of(order(ticket(1, "X", 300.0, true))));

        assertEquals(0.0, s.revenue());
        assertEquals(0, s.ticketsSold());
        assertEquals(0.0, s.avgOrderValue());
        assertEquals(Optional.empty(), s.topEvent());
    }

    @Test
    void GivenTwoEvents_WhenSummarised_ThenTheTopEventIsTheHighestGrossing() {
        PurchaseRecordDTO a = orderOf(lineOf(1, "Small", 100.0));
        PurchaseRecordDTO b = orderOf(lineOf(2, "Big", 500.0));

        assertEquals(Optional.of("Big"), calculator.summarize(List.of(a, b)).topEvent());
    }

    // ---- helpers --------------------------------------------------------

    private static PurchaseRecordDTO order(TicketRecordDTO... tickets) {
        List<TicketRecordDTO> lines = List.of(tickets);
        double total = lines.stream().mapToDouble(TicketRecordDTO::pricePaid).sum();
        return new PurchaseRecordDTO(1, 1, null, PURCHASED_AT, total, RefundStatus.of(lines),
                List.of(), lines, "buyer");
    }

    // Lines of one event share an id and different events get different ones: the top event is grouped by id.
    private static TicketRecordDTO ticket(int eventId, String eventName, double price, boolean refunded) {
        return new TicketRecordDTO(1, 1, eventId, 1, null, price,
                refunded ? TicketStatus.REFUNDED : TicketStatus.PAID,
                eventName, "Zone", "Co", "CONCERT", null, "Venue", null, refunded);
    }

    // ---- Defect 7: the top event is grouped by name (#187) ----

    private static final LocalDateTime PURCHASED_AT = LocalDateTime.of(2027, 7, 15, 17, 0);

    // Event names are not unique: two events called "Gala" are two events, each with its own takings.
    @Test
    void GivenTwoEventsWithOneName_WhenSummarised_ThenTheirSalesAreNotSummedIntoOneTopEvent() {
        PurchaseRecordDTO firstGala = orderOf(lineOf(1, "Gala", 300.0));
        PurchaseRecordDTO secondGala = orderOf(lineOf(2, "Gala", 250.0));
        PurchaseRecordDTO concert = orderOf(lineOf(3, "Concert", 400.0));

        SalesSummaryDTO s = calculator.summarize(List.of(firstGala, secondGala, concert));

        assertEquals(Optional.of("Concert"), s.topEvent(),
                "the top event is the one whose own sales are highest (Concert 400 > Gala 300 > Gala 250)");
    }

    private static PurchaseRecordDTO orderOf(TicketRecordDTO line) {
        return new PurchaseRecordDTO(1, 1, null, PURCHASED_AT, line.pricePaid(), RefundStatus.NONE,
                List.of(), List.of(line), "buyer");
    }

    private static TicketRecordDTO lineOf(int eventId, String eventName, double price) {
        return new TicketRecordDTO(1, 1, eventId, 1, null, price, TicketStatus.PAID,
                eventName, "Zone", "Co", "CONCERT", null, "Venue", null, false);
    }
}
