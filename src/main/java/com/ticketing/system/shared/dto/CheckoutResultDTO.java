package com.ticketing.system.shared.dto;

import java.util.List;

// Output of CheckoutService checkout methods (UC-10).
// Returned only on success; failures throw domain exceptions.
public record CheckoutResultDTO(
    double totalCharged,
    int orderReceiptId,
    int paymentTransactionId,
    List<IssuedTicketDTO> issuedTickets
) {
    // One issued ticket: the issuer-assigned id plus the barcode VALUE the payment/
    // issuance provider actually returned (the real WSEP TIX code, or the stub's
    // code) — so the confirmation can show the true barcode instead of fabricating
    // one from the id.
    public record IssuedTicketDTO(int ticketId, String barcode) {}

    // Convenience for callers/tests that only need the ids.
    public List<Integer> issuedTicketIds() {
        return issuedTickets == null
                ? List.of()
                : issuedTickets.stream().map(IssuedTicketDTO::ticketId).toList();
    }
}
