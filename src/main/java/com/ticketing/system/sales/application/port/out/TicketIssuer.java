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

    /** Returns a stable name for the issuer, such as {@code "wsep-ticket-issuer"}. */
    String getId();

    /**
     * Checks that the issuer is reachable and accepts this platform's credentials (UC-1 / I.1.3 startup
     * verification).
     *
     * @return {@code true} if it is; {@code false} otherwise, including when the check itself fails
     */
    boolean verifyConnection();

    /**
     * Submits a batch of tickets for issuance (UC-34) and returns one barcode per requested item, in
     * request order. Ticket ids are not the issuer's concern: {@code TicketRepository.nextId} mints them.
     *
     * @param request the buyer and the items to issue; it must name at least one item
     * @return the issuance, with one barcode for each item
     * @throws com.ticketing.system.shared.exception.TicketIssuanceFailedException if the issuer rejects
     *     the request or gives an answer that cannot be used; its subtype
     *     {@code TicketIssuerUnreachableException} means no usable answer arrived. A failure here
     *     sends the checkout down its rollback path (UC-4)
     */
    IssuanceResultDTO issue(IssuanceRequestDTO request);
}
