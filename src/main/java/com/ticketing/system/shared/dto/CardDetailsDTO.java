package com.ticketing.system.shared.dto;

// Raw card details captured at checkout and carried to the payment gateway.
// The real WSEP `pay` action needs all of these on the wire (card_number, cvv,
// month, year, holder); the in-process stub only looks at the card number.
//
// toString() is overridden so the PAN and CVV can never leak through an
// accidental log/{}-interpolation of this record (or of a PaymentRequestDTO
// that holds it) — only the last 4 of the card are ever rendered, never the cvv.
public record CardDetailsDTO(
    String cardNumber,
    String cvv,
    int expiryMonth,
    int expiryYear,
    String holderName
) {
    @Override
    public String toString() {
        return "CardDetailsDTO[holder=" + holderName
                + ", card=" + maskedCard()
                + ", exp=" + expiryMonth + "/" + expiryYear + "]";
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
