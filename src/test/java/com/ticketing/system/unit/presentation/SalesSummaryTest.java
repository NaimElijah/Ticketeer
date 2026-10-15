package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.RefundStatus;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.TicketRecordDTO;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.ui.views.admin.SalesSummary;

/** Refund-aware headline figures for the sales and global-history pages (pure; no Vaadin). */
class SalesSummaryTest {

    @Test
    void GivenAPaidAndARefundedOrder_WhenSummarised_ThenOnlyThePaidOrderCounts() {
        PurchaseRecordDTO paid = order(ticket("Gala", 600.0, false));
        PurchaseRecordDTO refunded = order(ticket("Gala", 300.0, true));

        SalesSummary s = SalesSummary.of(List.of(paid, refunded));

        assertEquals(600.0, s.revenue(), "the refunded order is excluded from revenue");
        assertEquals(1, s.ticketsSold(), "the refunded order's ticket is not counted as sold");
        assertEquals(600.0, s.avgOrderValue(), "the average is over orders that still have a sale");
        assertEquals("Gala", s.topEvent());
        assertEquals(1, s.refundedOrders());
    }

    // #21: cancelling one event of a multi-event order refunds only that event's lines.
    @Test
    void GivenMultiEventOrderWithOneEventRefunded_WhenSummarised_ThenItsOtherLinesStillCount() {
        PurchaseRecordDTO multiEvent = order(ticket("Cancelled", 100.0, true), ticket("Kept", 40.0, false),
                ticket("Kept", 40.0, false));

        SalesSummary s = SalesSummary.of(List.of(multiEvent));

        assertEquals(80.0, s.revenue());
        assertEquals(2, s.ticketsSold());
        assertEquals(80.0, s.avgOrderValue());
        assertEquals("Kept", s.topEvent());
        assertEquals(1, s.refundedOrders(), "a partly refunded order counts as an order with a refund");
    }

    // #21: on a shared order a company's record holds only its own lines, which another company's
    // cancellation leaves unrefunded.
    @Test
    void GivenACompanysLinesOfASharedOrderWhoseOtherEventWasRefunded_WhenSummarised_ThenTheyAllCount() {
        PurchaseRecordDTO ownLinesOnly = order(ticket("Ours", 70.0, false), ticket("Ours", 30.0, false));

        SalesSummary s = SalesSummary.of(List.of(ownLinesOnly));

        assertEquals(100.0, s.revenue());
        assertEquals(2, s.ticketsSold());
        assertEquals(0, s.refundedOrders());
    }

    @Test
    void GivenOnlyRefundedOrders_WhenSummarised_ThenEverythingIsZeroAndThereIsNoTopEvent() {
        SalesSummary s = SalesSummary.of(List.of(order(ticket("X", 300.0, true))));

        assertEquals(0.0, s.revenue());
        assertEquals(0, s.ticketsSold());
        assertEquals(0.0, s.avgOrderValue());
        assertEquals(SalesSummary.NO_TOP_EVENT, s.topEvent());
    }

    @Test
    void GivenTwoEvents_WhenSummarised_ThenTheTopEventIsTheHighestGrossing() {
        PurchaseRecordDTO a = order(ticket("Small", 100.0, false));
        PurchaseRecordDTO b = order(ticket("Big", 500.0, false));

        assertEquals("Big", SalesSummary.of(List.of(a, b)).topEvent());
    }

    // ---- helpers --------------------------------------------------------

    private static PurchaseRecordDTO order(TicketRecordDTO... tickets) {
        List<TicketRecordDTO> lines = List.of(tickets);
        double total = lines.stream().mapToDouble(TicketRecordDTO::pricePaid).sum();
        return new PurchaseRecordDTO(1, 1, null, LocalDateTime.now(), total, RefundStatus.of(lines),
                List.of(), lines, "buyer");
    }

    private static TicketRecordDTO ticket(String eventName, double price, boolean refunded) {
        return new TicketRecordDTO(1, 1, 1, 1, null, price, refunded ? TicketStatus.REFUNDED : TicketStatus.PAID,
                eventName, "Zone", "Co", "CONCERT", null, "Venue", null, refunded);
    }
}
