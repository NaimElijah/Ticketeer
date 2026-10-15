package com.ticketing.system.reporting.application.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.ticketing.system.reporting.application.dto.SalesSummaryDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.RefundStatus;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.TicketRecordDTO;

/**
 * Computes the headline sales figures that the company sales page (UC-22) and the admin global
 * history (UC-31) show over the order records they list, so the views only render (#21).
 *
 * <p>Only lines that are not refunded count as sold: when one event of a multi-event or shared order
 * is cancelled, the order's other lines still count. The caller passes records it has already
 * authorized and filtered (for example by date range), so this does no access checks of its own.
 */
@Service
public class SalesSummaryCalculator {

    /**
     * @param records the order records to summarise; may be empty
     * @return the figures over the lines of {@code records} that are not refunded
     */
    public SalesSummaryDTO summarize(List<PurchaseRecordDTO> records) {
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

        Optional<String> topEvent = sold.stream()
                .filter(t -> t.eventName() != null)
                .collect(Collectors.groupingBy(
                        TicketRecordDTO::eventName,
                        Collectors.summingDouble(TicketRecordDTO::pricePaid)))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);

        return new SalesSummaryDTO(revenue, sold.size(), avgOrderValue, topEvent, refundedOrders);
    }
}
