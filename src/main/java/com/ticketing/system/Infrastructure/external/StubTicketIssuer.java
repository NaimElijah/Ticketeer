package com.ticketing.system.Infrastructure.external;

import com.ticketing.system.Core.Application.dto.BarcodeDTO;
import com.ticketing.system.Core.Application.dto.IssuanceRequestDTO;
import com.ticketing.system.Core.Application.dto.IssuanceResultDTO;
import com.ticketing.system.Core.Application.interfaces.ITicketIssuer;
import com.ticketing.system.shared.exception.TicketIssuanceFailedException;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

// In-process ticket issuer for TESTS ONLY (@Profile("test")). Every real run
// (default/prod + the dev profile) uses WsepTicketIssuer instead.
@Component
@Profile("test")
public class StubTicketIssuer implements ITicketIssuer {
    //* can change to be however wanted, this is a stub so we can do what we need with it */
    private static final String ISSUER_ID = "stub-ticket-issuer";

    private final AtomicInteger ticketIds = new AtomicInteger(1);

    @Override
    public String getId() {
        return ISSUER_ID;
    }

    // Toggleable for UC-1/UC-32 tests: lets a test simulate an unreachable provider so the
    // initialize / open-market failure paths can be exercised. Production default: reachable.
    private volatile boolean reachable = true;

    public void setReachable(boolean reachable) {
        this.reachable = reachable;
    }

    @Override
    public boolean verifyConnection() {
        return reachable;
    }

    @Override
    public IssuanceResultDTO issue(IssuanceRequestDTO request) {
        validateRequest(request);

        List<BarcodeDTO> barcodes = new ArrayList<>();

        for (int i = 0; i < request.items().size(); i++) {
            int ticketId = ticketIds.getAndIncrement();

            barcodes.add(new BarcodeDTO(
                    ticketId,
                    "QR-" + ticketId + "-" + UUID.randomUUID(),
                    "QR"));
        }

        return new IssuanceResultDTO(
                "issuance-" + UUID.randomUUID(),
                ISSUER_ID,
                LocalDateTime.now(),
                barcodes);
    }

    


    // helper method to validate the issuance request before processing. Throws TicketIssuanceFailedException if invalid.
    // Validates the issuance request for basic correctness. Throws TicketIssuanceFailedException if invalid.
    private void validateRequest(IssuanceRequestDTO request) {
        if (request == null) {
            throw new TicketIssuanceFailedException("issuance request must not be null");
        }

        boolean memberBuyer = request.buyerUserId() != null;
        boolean guestBuyer = request.buyerEmail() != null && !request.buyerEmail().isBlank();

        if (memberBuyer == guestBuyer) {
            throw new TicketIssuanceFailedException("issuance request must identify exactly one buyer type: member OR guest");
        }

        if (memberBuyer && request.buyerUserId() <= 0) {
            throw new TicketIssuanceFailedException("buyer user id must be positive");
        }

        if (request.items() == null || request.items().isEmpty()) {
            throw new TicketIssuanceFailedException("cannot issue zero tickets");
        }
    }
}
