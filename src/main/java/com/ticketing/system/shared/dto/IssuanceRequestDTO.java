package com.ticketing.system.shared.dto;

import java.util.List;

/**
 * Input to {@code TicketIssuer.issue} (UC-34): the tickets to issue for one purchase. Exactly one of
 * {@code buyerUserId} and {@code buyerEmail} is set; {@code StubTicketIssuer} refuses a request with
 * both or neither.
 *
 * @param buyerUserId the member who buys; {@code null} for a guest (D5 reversed: guests can buy)
 * @param buyerEmail  the guest's email, kept with the order; no email is sent
 *                    (NaimElijah/Ticketeer#102). {@code null} for a member
 * @param items       the tickets to issue; the issuer answers with one barcode per item, in the same
 *                    order
 */
public record IssuanceRequestDTO(
    Integer buyerUserId,
    String buyerEmail,
    List<TicketIssuanceItemDTO> items
) {
    /**
     * One ticket to issue.
     *
     * @param seatNumber the seat's label; {@code null} for a ticket in a standing zone
     */
    public record TicketIssuanceItemDTO(
        int eventId,
        String eventName,
        int zoneId,
        String seatNumber
    ) {}
}
