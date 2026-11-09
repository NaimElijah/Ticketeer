package com.ticketing.system.sales.application.port.out;

import com.ticketing.system.shared.dto.IssuanceRequestDTO;
import com.ticketing.system.shared.dto.IssuanceResultDTO;

/**
 * Port for external ticket-issuance / barcode-generation services. Implementations live in
 * {@code sales.adapter.out.wsep}: the WSEP issuer outside the {@code test} profile, a stub under it.
 *
 * <p>Multi-provider support (I.4.2): this interface stays single, so a provider is one more bean.
 * Governance's reachability probe takes every {@code TicketIssuer} bean; checkout takes a single one.
 */
public interface TicketIssuer {

    String getId();

    // UC-1 / I.1.3 startup verification.
    boolean verifyConnection();

    // UC-34 — submit a batch of tickets for issuance, receive one barcode per requested item, in
    // request order. Ticket ids are not the issuer's concern (TicketRepository.nextId mints them).
    // Throws TicketIssuanceFailedException on failure, or TicketIssuerUnreachableException when it
    // gives no usable answer (both trigger UC-4 refund pipeline).
    IssuanceResultDTO issue(IssuanceRequestDTO request);
}
