package com.ticketing.system.sales.adapter.out.wsep;

import com.ticketing.system.shared.dto.BarcodeDTO;
import com.ticketing.system.shared.dto.IssuanceRequestDTO;
import com.ticketing.system.shared.dto.IssuanceResultDTO;
import com.ticketing.system.sales.application.port.out.TicketIssuer;
import com.ticketing.system.shared.exception.TicketIssuanceFailedException;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * In-process {@link TicketIssuer} for tests only ({@code @Profile("test")}). Every real run, the
 * {@code dev} profile included, uses {@link WsepTicketIssuer} instead.
 *
 * <p>It issues one random {@code QR-} barcode per requested item. A request must name exactly one
 * buyer, a member id or a guest email, and at least one item.
 */
@Component
@Profile("test")
public class StubTicketIssuer implements TicketIssuer {
    private static final String ISSUER_ID = "stub-ticket-issuer";

    private final Clock clock;

    /**
     * Creates the stub.
     *
     * @param clock the source of the time stamped on issuances (ADR-008)
     */
    public StubTicketIssuer(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String getId() {
        return ISSUER_ID;
    }

    // Toggleable for UC-1/UC-32 tests: lets a test simulate an unreachable provider so the
    // initialize / open-market failure paths can be exercised. Default: reachable.
    private volatile boolean reachable = true;

    /**
     * Sets what {@link #verifyConnection()} reports from now on.
     *
     * @param reachable {@code false} simulates an unreachable provider
     */
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
            barcodes.add(new BarcodeDTO("QR-" + UUID.randomUUID(), "QR"));
        }

        return new IssuanceResultDTO(
                "issuance-" + UUID.randomUUID(),
                ISSUER_ID,
                LocalDateTime.now(clock),
                barcodes);
    }

    


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
