package com.ticketing.system.ui.views.admin;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.RefundStatus;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.TicketRecordDTO;

/**
 * Refund-aware headline figures for a list of order records, as the sales and global-history grids
 * show them. Only lines that are not refunded count as sold (#21): when one event of a multi-event
 * or shared order is cancelled, the order's other lines still count. Refunded orders stay listed and
 * tagged in the grid. Pure (no Vaadin) so it is unit-testable.
 *
 * @param revenue        the prices of the lines not refunded
 * @param ticketsSold    how many lines are not refunded
 * @param avgOrderValue  revenue per order that still has a line not refunded
 * @param topEvent       the event with the highest revenue, or {@link #NO_TOP_EVENT}
 * @param refundedOrders how many orders have at least one refunded line
 */
public record SalesSummary(double revenue, int ticketsSold, double avgOrderValue, String topEvent,
        long refundedOrders) {

    public static final String NO_TOP_EVENT = "—";

    public static SalesSummary of(List<PurchaseRecordDTO> records) {
        List<TicketRecordDTO> sold = records.stream()
                .flatMap(r -> r.tickets().stream())
                .filter(t -> !t.refunded())
                .toList();

        double revenue = sold.stream().mapToDouble(TicketRecordDTO::pricePaid).sum();
        long ordersWithSales = records.stream()
                .filter(r -> r.tickets().stream().anyMatch(t -> !t.refunded()))
                .count();
        double avgOrderValue = ordersWithSales == 0 ? 0.0 : revenue / ordersWithSales;
        long refundedOrders = records.stream().filter(r -> r.refundStatus() != RefundStatus.NONE).count();

        String topEvent = sold.stream()
                .filter(t -> t.eventName() != null)
                .collect(Collectors.groupingBy(
                        TicketRecordDTO::eventName,
                        Collectors.summingDouble(TicketRecordDTO::pricePaid)))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(NO_TOP_EVENT);

        return new SalesSummary(revenue, sold.size(), avgOrderValue, topEvent, refundedOrders);
    }
}
