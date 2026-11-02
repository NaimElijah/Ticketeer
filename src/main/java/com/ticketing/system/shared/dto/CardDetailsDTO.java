package com.ticketing.system.shared.dto;

// Raw card details captured at checkout and carried to the payment gateway.
// The real WSEP `pay` action needs all of these on the wire (card_number, cvv,
// month, year, holder); the in-process stub only looks at the card number.
//
// toString() is overridden so the card data can never leak through an
// accidental log/{}-interpolation of this record (or of a PaymentRequestDTO
// that holds it) — only the last 4 of the card are ever rendered: never the cvv,
// never the holder's name (personal data), never the expiry (which, with a
// leaked card number, is most of what a payment needs).
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
