package com.ticketing.system.shared.dto;

/**
 * Raw card details captured at checkout and carried to the payment gateway.
 *
 * <p>The real WSEP {@code pay} action needs all of these on the wire (card_number, cvv, month, year,
 * holder); the in-process stub only looks at the card number.
 *
 * <p>{@link #toString()} is overridden so the card data cannot leak through an accidental log of
 * this record, or of a {@link PaymentRequestDTO} that holds it. It renders only the last 4 digits of
 * the card: never the cvv, never the holder's name (personal data), never the expiry (which, with a
 * leaked card number, is most of what a payment needs).
 */
public record CardDetailsDTO(
    String cardNumber,
    String cvv,
    int expiryMonth,
    int expiryYear,
    String holderName
) {
    @Override
    public String toString() {
        return "CardDetailsDTO[card=" + maskedCard() + "]";
    }

    private String maskedCard() {
        if (cardNumber == null) {
            return "****";
        }
        String digits = cardNumber.replaceAll("\\D", "");
        if (digits.length() < 4) {
            return "****";
        }
        return "**** **** **** " + digits.substring(digits.length() - 4);
    }
}
