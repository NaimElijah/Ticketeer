package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * What {@code PaymentGateway.refund} returns for a refund the gateway accepted (UC-4 / I.3.3); a
 * refusal is an exception instead. {@code RefundService} and {@code EventCancellationRefundService}
 * check it with {@code RefundResultValidator}: a refund transaction id, a timestamp, and
 * {@code totalRefunded} equal to the amount asked for.
 *
 * @param orderReceiptId     the payment transaction id the refund reverses, as text. Both gateway
 *                           adapters fill it so; it is not an order receipt id
 * @param refundedTicketIds  empty in both gateway adapters
 * @param failedTicketIds    empty in both gateway adapters; no code reads it
 */
public record RefundResultDTO(
    String refundTransactionId,
    String orderReceiptId,
    double totalRefunded,
    LocalDateTime refundedAt,
    List<String> refundedTicketIds,
    List<String> failedTicketIds
) {}
