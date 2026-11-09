package com.ticketing.system.shared.dto;

/**
 * Input to {@code PaymentGateway.charge} (UC-33).
 *
 * <p>{@code idempotencyKey} is the client's checkout key, required and non-blank. It protects against
 * a repeated checkout, not a lost gateway reply: {@code CheckoutService} replays the first result for
 * a key it has completed ({@code CheckoutIdempotencyCache}, in memory) instead of charging again, and
 * refuses a key another buyer used. {@code WsepPaymentGateway} does not send it to WSEP, so WSEP cannot
 * deduplicate; only {@code StubPaymentGateway} deduplicates charges by it.
 *
 * <p>Exactly one of {@code buyerUserId} and {@code buyerEmail} is set: a member has
 * {@code buyerUserId}, a guest has {@code buyerEmail} (D5 reversed: guests can check out).
 * {@code StubPaymentGateway} refuses a request with both or neither.
 *
 * @param card the raw card details; the gateway (WSEP {@code pay}) needs them
 */
public record PaymentRequestDTO(
    String idempotencyKey,
    double amount,
    String currency,
    CardDetailsDTO card,
    Integer buyerUserId,
    String buyerEmail
) {

    /**
     * Leaves out the buyer email, which is personal data, so a logged charge or an exception message
     * built from one does not carry it (SLR.2). The card renders through {@link CardDetailsDTO#toString()}.
     */
    @Override
    public String toString() {
        return "PaymentRequestDTO[idempotencyKey=" + idempotencyKey + ", amount=" + amount
                + ", currency=" + currency + ", card=" + card + ", buyerUserId=" + buyerUserId + "]";
    }
}
