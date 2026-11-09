package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;

/**
 * Output of {@code PaymentGateway.charge} (UC-33). It represents a successful charge only; a failure
 * is a {@code PaymentGatewayException} thrown by the gateway adapter.
 *
 * @param paymentTransactionId the gateway's id for the charge
 * @param chargedAt            when the gateway charged, in UTC wall time (ADR-008)
 */
public record PaymentResultDTO(
    int paymentTransactionId,
    String gatewayName,
    double chargedAmount,
    String currency,
    LocalDateTime chargedAt
) {}
