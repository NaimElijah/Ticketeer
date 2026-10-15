package com.ticketing.system.sales.adapter.in.event;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.ticketing.system.catalog.domain.event.EventCancelledEvent;
import com.ticketing.system.sales.application.service.EventCancellationRefundService;
import com.ticketing.system.shared.exception.RefundFailedException;

/**
 * Sales inbound adapter that refunds a cancelled event's buyers when the catalog context cancels it
 * (UC-4 / UC-19). Listening for catalog's {@link EventCancelledEvent} keeps catalog free of any sales
 * type; the refund flow itself lives in {@link EventCancellationRefundService}.
 *
 * <p>It is a plain synchronous {@link EventListener}, so a {@link RefundFailedException} surfaces in
 * the catalog cancel call and the owner sees the failure. The event stays cancelled and the receipts
 * refunded so far stay refunded; catalog republishes the event when the owner cancels again, which
 * resumes the remaining refunds (#20).
 */
@Component
public class EventCancellationRefundListener {

    private final EventCancellationRefundService refundService;

    /**
     * @param refundService the sales application service that runs the refunds
     */
    public EventCancellationRefundListener(EventCancellationRefundService refundService) {
        this.refundService = refundService;
    }

    /**
     * Refunds every not-yet-refunded line of the cancelled event.
     *
     * @param event the catalog cancellation event carrying the event id and name
     * @throws RefundFailedException if a refund fails; calling again resumes from that receipt
     */
    @EventListener
    public void on(EventCancelledEvent event) {
        refundService.refundCancelledEvent(event.eventId(), event.eventName());
    }
}
