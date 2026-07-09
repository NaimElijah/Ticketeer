package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;
import java.util.List;

// Output of the auto-refund flow (UC-4 / I.3.3).
// Reports per-ticket outcome since not all may refund successfully if the gateway is partially up.
public record RefundResultDTO(
    String refundTransactionId,
    String orderReceiptId,
    double totalRefunded,
    LocalDateTime refundedAt,
    List<String> refundedTicketIds,
    List<String> failedTicketIds         // tickets we couldn't refund — caller escalates
) {}
