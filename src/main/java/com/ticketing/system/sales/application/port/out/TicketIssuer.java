package com.ticketing.system.sales.application.port.out;

import com.ticketing.system.Core.Application.dto.IssuanceRequestDTO;
import com.ticketing.system.Core.Application.dto.IssuanceResultDTO;

// Port for external ticket-issuance / barcode-generation services.
// Multi-provider support (I.4.2) realized via Spring DI of List<TicketIssuer>.
public interface TicketIssuer {

    String getId();

    // UC-1 / I.1.3 startup verification.
    boolean verifyConnection();

    // UC-34 — submit a batch of tickets for issuance, receive barcodes.
    // Throws TicketIssuanceFailedException on failure (triggers UC-4 refund pipeline).
    IssuanceResultDTO issue(IssuanceRequestDTO request);
}
