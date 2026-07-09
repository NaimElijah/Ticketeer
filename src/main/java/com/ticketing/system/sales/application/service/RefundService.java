package com.ticketing.system.sales.application.service;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.identity.application.service.AuthenticationService;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ticketing.system.shared.dto.RefundResultDTO;
import com.ticketing.system.sales.application.port.out.PaymentGateway;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.sales.domain.TicketStatus;
// Catalog inbound port: catalog owns the SOLD -> AVAILABLE inventory return, so RefundService no
// longer imports any catalog.domain type — it hands the refunded lines to the port.
import com.ticketing.system.catalog.application.port.in.InventoryCommandPort;
import com.ticketing.system.catalog.application.port.in.InventoryLineDTO;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.EntityNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.RefundFailedException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.TransactionRecord;

import lombok.extern.slf4j.Slf4j;

/**
 * Member-initiated refund of one owned order (I.3.3 / #64, #284). The owner/admin side of refunds
 * lives elsewhere ({@link EventManagementService#cancelEventAndRefund}); this is the buyer's
 * "Request refund" path from {@code ReceiptView} / {@code MyAccountView}.
 *
 * <p>Mirrors the cancellation refund mechanics, scoped to a single member-owned receipt: charge
 * the gateway refund first, then flip the receipt + its tickets to refunded. Refunds are
 * <strong>immediate</strong> (no request lifecycle exists in the domain) and whole-order (the
 * receipt's refunded flag is all-or-nothing). It deliberately dispatches <strong>no</strong>
 * notification — real-time notifications (I.5) are a Grade ג' V2 exemption, which also keeps this
 * flow clear of the unimplemented {@code NotificationDispatchService.dispatchFromEvent} (#304).
 *
 * <p>Throws typed domain exceptions; the presenter translates them into UI outcomes.
 */
@Service
@Slf4j
public class RefundService {

    private final AuthenticationService authenticationService;
    private final OrderReceiptRepository orderReceiptRepository;
    private final TicketRepository ticketRepository;
    private final PaymentGateway paymentGateway;
    // Catalog inbound port that owns returning refunded (SOLD) inventory to AVAILABLE stock.
    private final InventoryCommandPort inventoryPort;
    // Programmatic transaction for the refund critical section: it must hold a real row lock on the
    // receipt (SELECT … FOR UPDATE) across the eligibility check + gateway refund + receipt flip so a
    // double-click can't refund twice (#410). The gateway-first call runs inside it by design.
    private final TransactionTemplate transactionTemplate;

    public RefundService(
            AuthenticationService authenticationService,
            OrderReceiptRepository orderReceiptRepository,
            TicketRepository ticketRepository,
            PaymentGateway paymentGateway,
            InventoryCommandPort inventoryPort,
            PlatformTransactionManager transactionManager) {
        this.authenticationService = authenticationService;
        this.orderReceiptRepository = orderReceiptRepository;
        this.ticketRepository = ticketRepository;
        this.paymentGateway = paymentGateway;
        this.inventoryPort = inventoryPort;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Refunds the whole order identified by {@code orderId} on behalf of the authenticated member.
     *
     * @throws InvalidTokenException          token missing/invalid
     * @throws EntityNotFoundException        no such receipt
     * @throws UnauthorizedActionException    the receipt isn't the caller's (403)
     * @throws BusinessRuleViolationException the order isn't refund-eligible (already refunded)
     * @throws RefundFailedException          the gateway refund failed / no original charge
     */
    public RefundResultDTO requestRefund(String token, int orderId, String reason) {
        // Don't log the raw reason — it's user free-text and may carry sensitive data; length only.
        log.info("Member refund requested for order {} (reason length: {})",
                orderId, reason == null ? 0 : reason.length());
        if (!authenticationService.validateToken(token)) {
            throw new InvalidTokenException();
        }
        int userId = authenticationService.extractUserId(token);

        // Serialize the eligibility check + gateway refund + receipt flip in ONE transaction holding a
        // row lock on the receipt. orderReceiptRepository.lockForUpdate takes a real SELECT … FOR UPDATE
        // under jpa (and a real lock under the in-memory repo), held until this transaction commits.
        // Without it, two concurrent requests (double-click / retry) could both pass wasRefunded() and
        // both call paymentGateway.refund() — refunding the buyer twice (#410). The loser blocks on the
        // lock, then sees isRefunded=true and bails before any second gateway call. The slow WSEP refund
        // runs inside this section by design (gateway-first: never mark refunded while the gateway
        // disagrees); refunds are low-frequency, so holding the row lock across it is acceptable.
        RefundResultDTO refundResult = transactionTemplate.execute(status -> {
            orderReceiptRepository.lockForUpdate(orderId);
            try {
                OrderReceipt receipt = orderReceiptRepository.findByOrderReceiptId(orderId)
                        .orElseThrow(() -> new EntityNotFoundException("OrderReceipt", orderId));

                Integer holderUserId = receipt.getHolderUserId();
                if (holderUserId == null || holderUserId != userId) {
                    throw new UnauthorizedActionException("refund order " + orderId, userId);
                }

                if (receipt.wasRefunded()) {
                    throw new BusinessRuleViolationException("Order " + orderId + " has already been refunded");
                }

                double refundAmount = receipt.getTotalAmount();
                int paymentTransactionId = receipt.getPaymentTransactionId()
                        .orElseThrow(() -> new RefundFailedException(orderId, "receipt does not contain a payment transaction"));

                RefundResultDTO result = paymentGateway.refund(paymentTransactionId, refundAmount);
                validateRefundResult(orderId, refundAmount, result);

                receipt.markRefunded(TransactionRecord.refund(
                        result.refundTransactionId(),
                        paymentGateway.getId(),
                        result.totalRefunded(),
                        receipt.getPaymentCurrency(),
                        result.refundedAt()));
                orderReceiptRepository.save(receipt);
                return result;
            } finally {
                orderReceiptRepository.unlock(orderId);
            }
        });

        // The money refund + receipt flip have committed under the lock; the remaining ticket flips and
        // inventory return run outside it (best-effort, idempotent-by-state). A second concurrent request
        // already bailed on wasRefunded() above, so only this caller reaches here.
        // PAID/ISSUED tickets become REFUNDED; anything else (e.g. reserved-but-unissued) is voided.
        List<Ticket> tickets = ticketRepository.findByOrderReceiptId(orderId);
        List<Ticket> refundedTickets = new ArrayList<>();
        for (Ticket ticket : tickets) {
            if (ticket.getStatus() == TicketStatus.PAID || ticket.getStatus() == TicketStatus.ISSUED) {
                ticket.markRefunded();
                refundedTickets.add(ticket);
            } else {
                ticket.markVoided();
            }
            ticketRepository.save(ticket);
        }

        // The refunded seats/places were SOLD — return them to AVAILABLE stock so they're bookable
        // again. Done after the money refund + ticket flips; a stock-return failure is logged, not
        // propagated (we never undo a completed refund over an inventory hiccup).
        returnRefundedInventoryToStock(refundedTickets);

        log.info("Member refund completed for order {} — {} refunded (ref {})",
                orderId, refundResult.totalRefunded(), refundResult.refundTransactionId());
        return refundResult;
    }

    /**
     * Returns each refunded ticket's seat/place to AVAILABLE stock through the catalog inventory port.
     * The port groups the flat lines by event then zone, holds each event's buyer-lock across
     * load -> mutate -> save, and is fully best-effort: a stock-return hiccup is logged and swallowed
     * (the gateway refund and receipt/ticket flips have already committed, so it must never fail the
     * refund). Behaviour is preserved verbatim — only the ownership of the mutation moved into catalog.
     */
    private void returnRefundedInventoryToStock(List<Ticket> refundedTickets) {
        // One flat line per refunded ticket: a seated ticket carries its seat label; a standing ticket
        // has a null seat label (one standing unit). The port turns these back into domain selections.
        List<InventoryLineDTO> lines = new ArrayList<>();
        for (Ticket t : refundedTickets) {
            String seatNumber = t.isSeatedTicket() ? t.getSeatNumber() : null;
            lines.add(new InventoryLineDTO(t.getEventId(), t.getZoneId(), seatNumber));
        }
        // Best-effort at the sales boundary too: the gateway refund and the receipt/ticket flips have
        // already committed, so an inventory-return failure must never make the refund report failure.
        // (The port is itself best-effort per event; this catch also guards a misbehaving implementation.)
        try {
            inventoryPort.returnSoldToStock(lines);
        } catch (RuntimeException e) {
            log.warn("Refund: returning inventory to stock failed; the refund has already committed", e);
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
}
