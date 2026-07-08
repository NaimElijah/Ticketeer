package com.ticketing.system.Core.Application.dto;
import com.ticketing.system.sales.application.port.out.PaymentGateway;

// Input to PaymentGateway.charge() (UC-33).
// 'idempotencyKey' is critical — if the gateway response is lost, retry must not double-charge.
//
// buyerUserId / buyerEmail are dual identity for Member vs Guest purchases:
//   - Member: buyerUserId set, buyerEmail optional.
//   - Guest:  buyerUserId null, buyerEmail required (D5 reversed — Guests can check out).
public record PaymentRequestDTO(
    String idempotencyKey,
    double amount,
    String currency,
    CardDetailsDTO card,                  // raw card details — the gateway (WSEP `pay`) needs them
    Integer buyerUserId,
    String buyerEmail
) {}
