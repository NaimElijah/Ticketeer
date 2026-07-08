package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ticketing.system.Core.Application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.Core.Application.dto.PurchaseHistoryDTO.TicketRecordDTO;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.ui.views.admin.SalesSummary;

/** Refund-aware headline figures for the Company Sales page (pure; no Vaadin). */
class SalesSummaryTest {

    @Test
    void revenueAndTickets_excludeRefundedOrders() {
        // 600 non-refunded + 300 refunded -> headline should reflect only the 600 order.
        PurchaseRecordDTO paid = order(600.0, false, ticket("Gala", 600.0));
        PurchaseRecordDTO refunded = order(300.0, true, ticket("Gala", 300.0));

        SalesSummary s = SalesSummary.of(List.of(paid, refunded));

        assertEquals(600.0, s.revenue(), "refunded order is excluded from revenue");
        assertEquals(1, s.ticketsSold(), "refunded order's ticket is not counted as sold");
        assertEquals(600.0, s.avgOrderValue(), "AOV is over non-refunded orders only");
        assertEquals("Gala", s.topEvent());
    }

    @Test
    void allRefunded_isZeroAndNoTopEvent() {
        SalesSummary s = SalesSummary.of(List.of(order(300.0, true, ticket("X", 300.0))));

        assertEquals(0.0, s.revenue());
        assertEquals(0, s.ticketsSold());
        assertEquals(0.0, s.avgOrderValue());
        assertEquals(SalesSummary.NO_TOP_EVENT, s.topEvent());
    }

    @Test
    void topEvent_isTheHighestGrossingNonRefundedEvent() {
        PurchaseRecordDTO a = order(100.0, false, ticket("Small", 100.0));
        PurchaseRecordDTO b = order(500.0, false, ticket("Big", 500.0));

        assertEquals("Big", SalesSummary.of(List.of(a, b)).topEvent());
    }

    // ---- helpers --------------------------------------------------------

    private static PurchaseRecordDTO order(double total, boolean refunded, TicketRecordDTO... tickets) {
        return new PurchaseRecordDTO(1, 1, null, LocalDateTime.now(), total, refunded,
                List.of(), List.of(tickets), "buyer");
    }

    private static TicketRecordDTO ticket(String eventName, double price) {
        return new TicketRecordDTO(1, 1, 1, 1, null, price, TicketStatus.PAID,
                eventName, "Zone", "Co", "CONCERT", null, "Venue", null);
    }
}
