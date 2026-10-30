package com.ticketing.system.shared.dto;

// Input to PaymentGateway.charge() (UC-33).
// 'idempotencyKey' is the client's checkout key, required and non-blank. It protects against a repeated
// checkout, not a lost gateway reply: CheckoutService replays the first result for a key it has completed
// (CheckoutIdempotencyCache, in memory) instead of charging again, and refuses a key another buyer used.
// WsepPaymentGateway does not send it to WSEP, so WSEP cannot deduplicate; only StubPaymentGateway
// deduplicates charges by it.
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
