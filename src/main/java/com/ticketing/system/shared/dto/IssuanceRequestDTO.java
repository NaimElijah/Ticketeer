package com.ticketing.system.shared.dto;

import java.util.List;

// Input to TicketIssuer.issue() (UC-34).
//
// buyerUserId is null for Guest issuances (D5 reversed — Guests get tickets
// via email). buyerEmail is required for Guest, optional for Member.
public record IssuanceRequestDTO(
    Integer buyerUserId,
    String buyerEmail,
    List<TicketIssuanceItemDTO> items
) {
    public record TicketIssuanceItemDTO(
        int eventId,
        String eventName,
        int zoneId,
        String seatNumber                // nullable for standing-zone tickets
    ) {}
}
