package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;

// Output of PaymentGateway.charge() (UC-33).
// Failure = a PaymentGatewayException is thrown by the gateway adapter; this DTO
// only represents successful charges.
public record PaymentResultDTO(
    int paymentTransactionId,
    String gatewayName,
    double chargedAmount,
    String currency,
    LocalDateTime chargedAt
) {}
