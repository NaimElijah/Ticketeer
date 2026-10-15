package com.ticketing.system.sales.application.service;

import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.application.port.out.PaymentGateway;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.sales.domain.TransactionRecord;
import com.ticketing.system.shared.dto.RefundResultDTO;
import com.ticketing.system.shared.event.EventCancelledNotice;
import com.ticketing.system.shared.exception.EntityNotFoundException;
import com.ticketing.system.shared.exception.RefundFailedException;

import lombok.extern.slf4j.Slf4j;

/**
 * Refunds the buyers of a cancelled event (UC-4 / UC-19, I.3): each receipt line exactly once, and
 * resumably after a partial failure (#20, ADR-001).
 *
 * <p>Each receipt goes through three steps:
 * <ol>
 *   <li><b>claim</b>: in a short transaction holding the receipt's lock, the event's unrefunded lines
 *       move to PENDING. A concurrent or repeated run then finds nothing to claim;</li>
 *   <li><b>refund</b>: the payment gateway is called outside any transaction (V3-TX-02);</li>
 *   <li><b>record</b>: in a second short transaction the lines become REFUNDED, the refund is added to
 *       the receipt, and that receipt's tickets for the event are flipped.</li>
 * </ol>
 * A gateway failure releases the claim and stops the run, so calling this again resumes with the
 * receipts that are still unrefunded. If a claim cannot be released, or a gateway refund cannot be
 * recorded, the lines stay PENDING and are never refunded twice; that receipt needs manual recovery.
 */
@Service
@Slf4j
public class EventCancellationRefundService {

    private final OrderReceiptRepository orderReceiptRepository;
    private final TicketRepository ticketRepository;
    private final PaymentGateway paymentGateway;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    /**
     * @param orderReceiptRepository receipts of the cancelled event
     * @param ticketRepository       tickets of the cancelled event
     * @param paymentGateway         external gateway that refunds the original charges
     * @param eventPublisher         publisher of the per-holder {@link EventCancelledNotice}
     * @param transactionManager     drives the short claim and record transactions
     */
    public EventCancellationRefundService(
            OrderReceiptRepository orderReceiptRepository,
            TicketRepository ticketRepository,
            PaymentGateway paymentGateway,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager) {
        this.orderReceiptRepository = orderReceiptRepository;
        this.ticketRepository = ticketRepository;
        this.paymentGateway = paymentGateway;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Refunds every line of {@code eventId} that is not refunded yet, flips the refunded receipts'
     * tickets, voids the event's unsold tickets, and notifies each member holder whose receipt was
     * refunded by this call. Safe to call again for the same event: refunded lines are skipped.
     *
     * @param eventId   the cancelled event
     * @param eventName its display name, for the holder notices
     * @throws RefundFailedException if a gateway refund fails or a receipt has no payment charge. The
     *                               receipts refunded before it stay refunded; calling this again
     *                               resumes with the rest.
     */
    public void refundCancelledEvent(int eventId, String eventName) {
        Set<Integer> notifiedHolders = new HashSet<>();
        for (OrderReceipt receipt : orderReceiptRepository.findByEventId(eventId)) {
            refundReceipt(receipt.getId(), eventId, eventName, notifiedHolders);
        }
        voidUnsoldTickets(eventId);
        log.info("Refund flow completed for cancelled event {}", eventId);
    }

    private void refundReceipt(int receiptId, int eventId, String eventName, Set<Integer> notifiedHolders) {
        Claim claim = inReceiptTransaction(receiptId, receipt -> claim(receipt, eventId));
        if (claim == null) {
            return; // already refunded, or claimed by a run that is still in flight
        }

        TransactionRecord refundRecord;
        try {
            refundRecord = claim.amount() > 0 ? refundAtGateway(receiptId, claim) : null;
        } catch (RuntimeException gatewayFailure) {
            releaseClaim(receiptId, eventId, gatewayFailure);
            throw gatewayFailure;
        }

        Integer holderUserId;
        try {
            holderUserId = inReceiptTransaction(receiptId, receipt -> {
                if (refundRecord != null) {
                    receipt.completeEventRefund(eventId, refundRecord);
                } else {
                    receipt.completeUnchargedEventRefund(eventId);
                }
                refundTicketsOf(receiptId, eventId);
                return receipt.isMemberReceipt() ? receipt.getHolderUserId() : null;
            });
        } catch (RuntimeException recordFailure) {
            log.error("Receipt {} was refunded at the gateway for cancelled event {} but the refund could not be"
                    + " recorded; its lines stay PENDING and need manual recovery (ADR-001)",
                    receiptId, eventId, recordFailure);
            throw recordFailure;
        }

        if (holderUserId != null && holderUserId > 0 && notifiedHolders.add(holderUserId)) {
            notifyHolder(holderUserId, eventId, eventName);
        }
    }

    /** Claims the event's unrefunded lines; {@code null} when there is nothing to claim. */
    private Claim claim(OrderReceipt receipt, int eventId) {
        OptionalDouble claimed = receipt.claimRefundForEvent(eventId);
        if (claimed.isEmpty()) {
            return null;
        }
        double amount = claimed.getAsDouble();
        if (amount > 0 && receipt.getPaymentTransactionId().isEmpty()) {
            // Undo before failing: the in-memory adapter holds this very instance, so a transaction
            // rollback alone would leave the lines PENDING.
            receipt.releaseRefundClaim(eventId);
            throw new RefundFailedException(receipt.getId(), "receipt does not contain a payment transaction");
        }
        return new Claim(amount, receipt.getPaymentTransactionId().orElse(0), receipt.getPaymentCurrency());
    }

    private TransactionRecord refundAtGateway(int receiptId, Claim claim) {
        RefundResultDTO result = paymentGateway.refund(claim.paymentTransactionId(), claim.amount());
        validateRefundResult(receiptId, claim.amount(), result);
        return TransactionRecord.refund(
                result.refundTransactionId(),
                paymentGateway.getId(),
                result.totalRefunded(),
                claim.currency(),
                result.refundedAt());
    }

    private void releaseClaim(int receiptId, int eventId, RuntimeException gatewayFailure) {
        try {
            inReceiptTransaction(receiptId, receipt -> {
                receipt.releaseRefundClaim(eventId);
                return null;
            });
        } catch (RuntimeException releaseFailure) {
            gatewayFailure.addSuppressed(releaseFailure);
            log.error("Refund of receipt {} for cancelled event {} failed and its claim could not be released;"
                    + " its lines stay PENDING and need manual recovery (ADR-001)",
                    receiptId, eventId, releaseFailure);
        }
    }

    /**
     * Runs {@code work} on a freshly read receipt inside a short transaction that holds the receipt's
     * lock (a real {@code SELECT … FOR UPDATE} under jpa), then saves it.
     */
    private <T> T inReceiptTransaction(int receiptId, Function<OrderReceipt, T> work) {
        return transactionTemplate.execute(status -> {
            orderReceiptRepository.lockForUpdate(receiptId);
            try {
                OrderReceipt receipt = orderReceiptRepository.findByOrderReceiptId(receiptId)
                        .orElseThrow(() -> new EntityNotFoundException("OrderReceipt", receiptId));
                T result = work.apply(receipt);
                orderReceiptRepository.save(receipt);
                return result;
            } finally {
                orderReceiptRepository.unlock(receiptId);
            }
        });
    }

    /** Sold tickets become REFUNDED; anything else on the receipt that is still live becomes VOIDED. */
    private void refundTicketsOf(int receiptId, int eventId) {
        for (Ticket ticket : ticketRepository.findByOrderReceiptId(receiptId)) {
            if (ticket.getEventId() != eventId || isClosed(ticket)) {
                continue;
            }
            if (ticket.getStatus() == TicketStatus.PAID || ticket.getStatus() == TicketStatus.ISSUED) {
                ticket.markRefunded();
            } else {
                ticket.markVoided();
            }
            ticketRepository.save(ticket);
        }
    }

    /**
     * Voids the event's tickets that were never sold. Sold tickets are handled per receipt, so a run
     * never flips a ticket whose refund another run still has in flight.
     */
    private void voidUnsoldTickets(int eventId) {
        List<Ticket> tickets = ticketRepository.findByEventId(eventId);
        for (Ticket ticket : tickets) {
            if (ticket.getStatus() == TicketStatus.AVAILABLE || ticket.getStatus() == TicketStatus.RESERVED) {
                ticket.markVoided();
                ticketRepository.save(ticket);
            }
        }
    }

    private static boolean isClosed(Ticket ticket) {
        return ticket.getStatus() == TicketStatus.REFUNDED || ticket.getStatus() == TicketStatus.VOIDED;
    }

    private void notifyHolder(int userId, int eventId, String eventName) {
        try {
            eventPublisher.publishEvent(new EventCancelledNotice(userId, eventId, eventName));
        } catch (RuntimeException e) {
            // The refund has committed; a lost notice must not report the refund as failed.
            log.warn("Failed to send cancellation notification to userId={} for eventId={}", userId, eventId, e);
        }
    }

    private void validateRefundResult(int receiptId, double expectedRefundAmount, RefundResultDTO refundResult) {
        if (refundResult == null) {
            throw new RefundFailedException(receiptId, "payment gateway returned null refund result");
        }
        if (refundResult.refundTransactionId() == null || refundResult.refundTransactionId().isBlank()) {
            throw new RefundFailedException(receiptId, "refund transaction id is missing");
        }
        if (Math.abs(refundResult.totalRefunded() - expectedRefundAmount) > 0.0001) {
            throw new RefundFailedException(receiptId, "refund amount mismatch");
        }
        if (refundResult.refundedAt() == null) {
            throw new RefundFailedException(receiptId, "refund timestamp is missing");
        }
    }

    /** What a claim holds for the gateway call that follows it. */
    private record Claim(double amount, int paymentTransactionId, String currency) {
    }
}
