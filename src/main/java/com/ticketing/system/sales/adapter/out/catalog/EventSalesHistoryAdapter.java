package com.ticketing.system.sales.adapter.out.catalog;

import org.springframework.stereotype.Component;

import com.ticketing.system.catalog.application.port.out.EventSalesHistoryPort;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;

/**
 * Sales-side adapter for catalog's {@link EventSalesHistoryPort}. It answers catalog's
 * "does this event have sales history?" query by delegating to the sales {@link OrderReceiptRepository}.
 * Because the adapter lives in sales and depends on catalog's port, the source dependency points
 * {@code sales -> catalog} (acyclic); catalog compiles against its own port only, importing no sales type.
 * Backs the {@code EventManagementService.deleteEvent} orphan-prevention guard (UC-19).
 */
@Component
public class EventSalesHistoryAdapter implements EventSalesHistoryPort {

    // Sales aggregate-root port used to look up receipts by event id.
    private final OrderReceiptRepository orderReceiptRepository;

    /**
     * Wires the adapter to the sales order-receipt repository.
     *
     * @param orderReceiptRepository the sales port used to find receipts by event
     */
    public EventSalesHistoryAdapter(OrderReceiptRepository orderReceiptRepository) {
        this.orderReceiptRepository = orderReceiptRepository; // store for delegation
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasSalesHistory(int eventId) {
        // An event has sales history iff at least one receipt references it.
        return !orderReceiptRepository.findByEventId(eventId).isEmpty();
    }
}
