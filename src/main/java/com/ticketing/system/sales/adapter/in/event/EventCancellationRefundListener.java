package com.ticketing.system.sales.adapter.in.event;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.ticketing.system.catalog.domain.event.EventCancelledEvent;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.application.port.out.PaymentGateway;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.sales.domain.TransactionRecord;
import com.ticketing.system.shared.dto.RefundResultDTO;
import com.ticketing.system.shared.event.EventCancelledNotice;
import com.ticketing.system.shared.exception.RefundFailedException;

/**
 * Sales inbound adapter that runs the refund + ticket-void + holder-notification flow when the
 * catalog context cancels an event (UC-4 / UC-19). It listens for catalog's {@link EventCancelledEvent}
 * so that catalog owns none of this sales concern and imports no sales type. The logic was relocated
 * verbatim from the former {@code EventManagementService.cancelEventAndRefund}.
 *
 * <p>It is a PLAIN synchronous {@link EventListener}: it fires inline within the publishing thread and
 * transaction, so a {@link RefundFailedException} propagates back into the catalog cancel call and
 * aborts it exactly as the old inline loop did. Gateway-first ordering is preserved — a receipt is
 * never marked refunded while the gateway disagrees.
 */
@Component
@Slf4j
public class EventCancellationRefundListener {

    // Sales aggregate-root port for receipts of the cancelled event.
    private final OrderReceiptRepository orderReceiptRepository;
    // Sales aggregate-root port for the event's tickets.
    private final TicketRepository ticketRepository;
    // External payment gateway used to issue refunds.
    private final PaymentGateway paymentGateway;
    // Publisher for per-holder EventCancelledNotice integration events (notifications context listens).
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Wires the listener to the sales refund ports and the event publisher for holder notices.
     *
     * @param orderReceiptRepository sales receipts port
     * @param ticketRepository       sales tickets port
     * @param paymentGateway         external refund gateway
     * @param eventPublisher         publisher for per-holder notifications
     */
    public EventCancellationRefundListener(
            OrderReceiptRepository orderReceiptRepository,
            TicketRepository ticketRepository,
            PaymentGateway paymentGateway,
            ApplicationEventPublisher eventPublisher) {
        this.orderReceiptRepository = orderReceiptRepository; // store receipts port
        this.ticketRepository = ticketRepository;             // store tickets port
        this.paymentGateway = paymentGateway;                 // store refund gateway
        this.eventPublisher = eventPublisher;                 // store notification publisher
    }

    /**
     * Refunds every receipt for the cancelled event, flips its tickets to refunded/voided, and notifies
     * member ticket holders. Runs synchronously so a refund failure rolls back the cancellation.
     *
     * @param event the catalog cancellation event carrying the event id and name
     */
    @EventListener
    public void on(EventCancelledEvent event) {
        int eventId = event.eventId();                                          // the cancelled event's id
        List<OrderReceipt> orderReceipts = orderReceiptRepository.findByEventId(eventId); // all receipts touching it

        // For each receipt, refund the lines belonging to this event via the gateway, validate the
        // result, then flip the receipt to refunded. Gateway-first: never mark refunded on a disagreement.
        for (OrderReceipt receipt : orderReceipts) {
            double totalRefundForReceipt = receipt.getReceiptLines().stream()  // sum the event's lines on this receipt
                    .filter(line -> line.getEventId() == eventId)              // only lines for the cancelled event
                    .mapToDouble(ReceiptLine::getPriceAtReservation)           // price paid per line
                    .sum();

            if (totalRefundForReceipt <= 0) {                                  // nothing to refund on this receipt
                continue;
            }
            // Extract the original charge id so the gateway knows which transaction to refund; missing it
            // is unrecoverable, so we fail loudly rather than refund without a reference to the charge.
            int paymentTransactionId = receipt.getPaymentTransactionId()
                    .orElseThrow(() -> new RefundFailedException(
                            receipt.getId(),
                            "receipt does not contain a payment transaction"));

            // Call the external gateway BEFORE mutating any domain state (money-first).
            RefundResultDTO refundResult = paymentGateway.refund(paymentTransactionId, totalRefundForReceipt);
            // Reject an ill-formed / mismatched refund result before trusting it.
            validateRefundResult(receipt.getId(), totalRefundForReceipt, refundResult);

            // Refund succeeded — record the refund transaction and flag the receipt refunded.
            receipt.markRefunded(TransactionRecord.refund(
                    refundResult.refundTransactionId(),
                    paymentGateway.getId(),
                    refundResult.totalRefunded(),
                    receipt.getPaymentCurrency(),
                    refundResult.refundedAt()));

            orderReceiptRepository.save(receipt);                              // persist the refunded receipt
        }

        // Flip every ticket for the event: PAID/ISSUED -> REFUNDED, anything else -> VOIDED. Done after
        // refunds so a mid-refund failure never leaves tickets marked for an unrefunded receipt.
        List<Ticket> tickets = ticketRepository.findByEventId(eventId);        // all tickets of the event
        for (Ticket ticket : tickets) {
            if (ticket.getStatus() == TicketStatus.PAID || ticket.getStatus() == TicketStatus.ISSUED) {
                ticket.markRefunded();                                         // sold tickets become refunded
            } else {
                ticket.markVoided();                                          // everything else is voided
            }
            ticketRepository.save(ticket);                                     // persist the ticket status
        }

        // Notify each distinct member ticket holder; a per-user failure is logged, not fatal.
        notifyTicketHoldersOfCancellation(eventId, event.eventName(), orderReceipts);

        log.info("Refund flow completed for cancelled event {}", eventId);
    }

    /**
     * Publishes one {@link EventCancelledNotice} per distinct member ticket holder of the cancelled event.
     * A failure to publish for one holder is logged and swallowed so it can't abort the rest.
     *
     * @param eventId   the cancelled event's id
     * @param eventName the cancelled event's display name
     * @param receipts  the receipts touching the event (source of the holder ids)
     */
    private void notifyTicketHoldersOfCancellation(int eventId, String eventName, List<OrderReceipt> receipts) {
        // Collect unique member user ids to avoid duplicate notifications across a holder's receipts.
        Set<Integer> memberUserIds = receipts.stream()
                .filter(OrderReceipt::isMemberReceipt)                          // guests aren't in-app notified
                .map(OrderReceipt::getHolderUserId)                            // the holder's user id
                .filter(id -> id != null && id > 0)                            // guard against unset ids
                .collect(Collectors.toSet());

        for (Integer userId : memberUserIds) {
            try {
                // Publish a cross-context integration event; the notifications listener delivers it in-line.
                eventPublisher.publishEvent(new EventCancelledNotice(userId, eventId, eventName));
            } catch (Exception e) {
                log.warn("Failed to send cancellation notification to userId={} for eventId={}", userId, eventId, e);
            }
        }
    }

    /**
     * Validates a gateway refund result, throwing {@link RefundFailedException} on any anomaly (null,
     * missing transaction id, amount mismatch, or missing timestamp). Centralizes refund validation.
     *
     * @param receiptId            the receipt being refunded (for error context)
     * @param expectedRefundAmount the amount we asked the gateway to refund
     * @param refundResult         the gateway's response to validate
     */
    private void validateRefundResult(int receiptId, double expectedRefundAmount, RefundResultDTO refundResult) {
        if (refundResult == null) {                                            // gateway returned nothing
            throw new RefundFailedException(receiptId, "payment gateway returned null refund result");
        }
        if (refundResult.refundTransactionId() == null || refundResult.refundTransactionId().isBlank()) {
            throw new RefundFailedException(receiptId, "refund transaction id is missing"); // no proof of refund
        }
        if (Math.abs(refundResult.totalRefunded() - expectedRefundAmount) > 0.0001) {
            throw new RefundFailedException(receiptId, "refund amount mismatch");           // refunded != requested
        }
        if (refundResult.refundedAt() == null) {                               // no settlement timestamp
            throw new RefundFailedException(receiptId, "refund timestamp is missing");
        }
    }
}
