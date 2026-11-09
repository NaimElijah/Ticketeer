package com.ticketing.system.shared.dto;

import java.util.List;

/**
 * Output of the {@code CheckoutService} checkout methods (UC-10). Returned only on success. A failure
 * reaches the caller as a plain {@code RuntimeException} whose cause is the reason, except a closed
 * market, which is thrown as {@code MarketNotOpenException}.
 *
 * @param totalCharged         the amount charged to the buyer
 * @param paymentTransactionId the payment gateway's id for the charge
 * @param issuedTickets        the tickets bought, one entry each
 */
public record CheckoutResultDTO(
    double totalCharged,
    int orderReceiptId,
    int paymentTransactionId,
    List<IssuedTicketDTO> issuedTickets
) {
    /**
     * One issued ticket: the stored ticket id (minted by {@code TicketRepository.nextId}) and the
     * barcode value the issuer actually returned (the real WSEP TIX code, or the stub's code), so the
     * confirmation shows the true barcode instead of one made up from the id.
     */
    public record IssuedTicketDTO(int ticketId, String barcode) {}

    /** Returns the ids of the issued tickets; empty when {@code issuedTickets} is {@code null}. */
    public List<Integer> issuedTicketIds() {
        return issuedTickets == null
                ? List.of()
                : issuedTickets.stream().map(IssuedTicketDTO::ticketId).toList();
    }
}
