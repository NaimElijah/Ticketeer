package com.ticketing.system.sales.application.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
import com.ticketing.system.shared.exception.RefundGatewayUnreachableException;
import com.ticketing.system.shared.exception.RefundOutcomeUnknownException;

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
 * A gateway that declines, or that the request never reached, moved no money: the claim is released
 * so calling this again retries it. A decline is specific to one receipt, so the run carries on with
 * the others and reports every declined receipt at the end; an unreachable gateway stops the run.
 * When the gateway may have refunded without a usable answer, when a claim cannot be released, or when
 * a gateway refund cannot be recorded, the run stops and the lines stay PENDING; they are never
 * refunded twice, and that receipt needs manual recovery.
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
        // Each claim must commit before its gateway call even inside a caller's transaction; joining one
        // would hold the row lock across the call and keep the claim invisible to concurrent runs.
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Refunds every line of {@code eventId} that is not refunded yet, flips the refunded receipts'
     * tickets, voids the event's unsold tickets, and notifies each member holder whose receipt was
     * refunded by this call. Safe to call again for the same event: refunded lines are skipped.
     *
     * @param eventId   the cancelled event
     * @param eventName its display name, for the holder notices
     * @throws RefundOutcomeUnknownException if the gateway may have refunded a receipt without a usable
     *                                       answer; that receipt stays PENDING for manual recovery
     * @throws RefundGatewayUnreachableException if the gateway could not be reached; the run stops
     *                                            and calling this again resumes with the rest
     * @throws RefundFailedException              naming every receipt whose refund was declined, that
     *                                            has no payment charge, or whose refund is still pending
     *                                            under another run's claim, after the others were
     *                                            refunded; calling this again retries the declined ones
     */
    public void refundCancelledEvent(int eventId, String eventName) {
        Set<Integer> notifiedHolders = new HashSet<>();
        List<Integer> declinedReceiptIds = new ArrayList<>();
        // A fixed order makes a resumed run retry the receipts in the order the first run tried them.
        List<OrderReceipt> receipts = orderReceiptRepository.findByEventId(eventId).stream()
                .sorted(Comparator.comparingInt(OrderReceipt::getId))
                .toList();
        for (OrderReceipt receipt : receipts) {
            try {
                refundReceipt(receipt.getId(), eventId, eventName, notifiedHolders);
            } catch (RefundGatewayUnreachableException | RefundOutcomeUnknownException gatewayInTrouble) {
                throw gatewayInTrouble;
            } catch (RefundFailedException declined) {
                log.warn("Refund of receipt {} for cancelled event {} was declined; carrying on with the other"
                        + " receipts", receipt.getId(), eventId, declined);
                declinedReceiptIds.add(receipt.getId());
            }
        }
        voidUnsoldTickets(eventId);

        // Every claim this run made is resolved by now, so a PENDING line left on the event belongs to
        // another run still in flight, or to a claim a crash stranded. Either way the event is not fully
        // refunded, and the owner must not be told it is.
        List<Integer> pendingReceiptIds = orderReceiptRepository.findByEventId(eventId).stream()
                .filter(receipt -> receipt.hasPendingRefundFor(eventId))
                .map(OrderReceipt::getId)
                .sorted()
                .toList();
        if (!pendingReceiptIds.isEmpty()) {
            log.warn("Receipts {} of cancelled event {} still have a refund pending: another cancellation may be"
                    + " refunding them, or a stranded claim needs manual recovery (ADR-001)",
                    pendingReceiptIds, eventId);
        }
        if (!declinedReceiptIds.isEmpty() || !pendingReceiptIds.isEmpty()) {
            List<Integer> unrefunded = new ArrayList<>(declinedReceiptIds);
            unrefunded.addAll(pendingReceiptIds);
            throw new RefundFailedException(unrefunded, "not refunded for cancelled event " + eventId
                    + " (declined: " + declinedReceiptIds + ", still pending: " + pendingReceiptIds
                    + "); the other receipts were refunded, and cancelling again retries the declined ones");
        }
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
        } catch (RefundOutcomeUnknownException unknown) {
            log.error("Receipt {} may have been refunded at the gateway for cancelled event {}, but no usable"
                    + " answer came back; its lines stay PENDING and need manual recovery (ADR-001)",
                    receiptId, eventId, unknown);
            throw unknown;
        } catch (RefundFailedException noMoneyMoved) {
            releaseClaim(receiptId, eventId, noMoneyMoved);
            throw noMoneyMoved;
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

    /**
     * Refunds the claim at the gateway. A {@link RefundFailedException} other than
     * {@link RefundOutcomeUnknownException} means no money moved; anything that goes wrong once the
     * request may have been sent is reported as {@link RefundOutcomeUnknownException}.
     */
    private TransactionRecord refundAtGateway(int receiptId, Claim claim) {
        RefundResultDTO result;
        try {
            result = paymentGateway.refund(claim.paymentTransactionId(), claim.amount());
        } catch (RefundFailedException gatewayAnswer) {
            throw gatewayAnswer;
        } catch (RuntimeException unexpected) {
            throw new RefundOutcomeUnknownException(receiptId, "unexpected error from the payment gateway", unexpected);
        }
        // The gateway answered, so the money may have moved: a rejected answer must not free the claim.
        try {
            RefundResultValidator.validate(receiptId, claim.amount(), result);
            return TransactionRecord.refund(
                    result.refundTransactionId(),
                    paymentGateway.getId(),
                    result.totalRefunded(),
                    claim.currency(),
                    result.refundedAt());
        } catch (RuntimeException rejected) {
            throw new RefundOutcomeUnknownException(receiptId, "the gateway's refund answer was rejected", rejected);
        }
    }

    private void releaseClaim(int receiptId, int eventId, RefundFailedException gatewayFailure) {
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

    /** What a claim holds for the gateway call that follows it. */
    private record Claim(double amount, int paymentTransactionId, String currency) {
    }
}
