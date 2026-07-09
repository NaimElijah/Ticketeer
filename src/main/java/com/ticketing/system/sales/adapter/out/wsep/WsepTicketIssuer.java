package com.ticketing.system.sales.adapter.out.wsep;
import com.ticketing.system.shared.external.SensitiveDataMasker; // transitional: shared-technical (stays in external until the Step 10 sweep)
import com.ticketing.system.shared.external.WsepCommunicationException; // transitional: shared-technical (stays in external until the Step 10 sweep)
import com.ticketing.system.shared.external.WsepHttpClient; // transitional: shared-technical WSEP client (stays in external until the Step 10 sweep)

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.BarcodeDTO;
import com.ticketing.system.shared.dto.IssuanceRequestDTO;
import com.ticketing.system.shared.dto.IssuanceResultDTO;
import com.ticketing.system.sales.application.port.out.TicketIssuer;
import com.ticketing.system.shared.exception.TicketIssuanceFailedException;

import lombok.extern.slf4j.Slf4j;

/**
 * Real ticket issuer backed by the WSEP external system. Active in every real
 * run ({@code @Profile("!test")}); the test suite uses {@link StubTicketIssuer}
 * instead. {@code handshake} → {@link #verifyConnection()} and {@code issue_ticket}
 * → {@link #issue}.
 *
 * <p>WSEP {@code issue_ticket} returns ONE ticket code per call, while a
 * purchase needs one barcode per ticket, so this issues one ticket per item
 * (GA, {@code quantity=1}) and assembles the list. A per-ticket positive id is
 * synthesized locally (WSEP returns only the {@code TIX-…} code), matching the
 * stub's contract. Any {@code "-1"} reply fails the whole issuance, which drives
 * the checkout's refund/rollback path.
 */
@Component
@Profile("!test")
@Slf4j
public class WsepTicketIssuer implements TicketIssuer {

    private static final String ISSUER_ID = "wsep-ticket-issuer";
    private static final String FAILURE = "-1";

    private final WsepHttpClient http;
    private final AtomicInteger ticketIds = new AtomicInteger(1);

    public WsepTicketIssuer(WsepHttpClient http) {
        this.http = http;
    }

    @Override
    public String getId() {
        return ISSUER_ID;
    }

    @Override
    public boolean verifyConnection() {
        try {
            // Retried (idempotent handshake): a cold-starting WSEP endpoint may miss the first attempt
            // but answer a later one, so a transient blip doesn't leave the market closed at boot (#455).
            boolean ok = "OK".equalsIgnoreCase(http.postWithRetry(WsepHttpClient.action("handshake")));
            log.debug("wsep handshake (issuer): reachable={}", ok);
            return ok;
        } catch (RuntimeException e) {
            log.debug("wsep handshake (issuer) failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public IssuanceResultDTO issue(IssuanceRequestDTO request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new TicketIssuanceFailedException("cannot issue zero tickets");
        }

        long start = System.nanoTime();
        String customerId = request.buyerUserId() != null ? String.valueOf(request.buyerUserId()) : "guest";

        List<BarcodeDTO> barcodes = new ArrayList<>();
        for (IssuanceRequestDTO.TicketIssuanceItemDTO item : request.items()) {
            WsepHttpClient.Form form = WsepHttpClient.action("issue_ticket")
                    .add("customer_id", customerId)
                    .add("event_id", item.eventId())
                    .add("zone", item.zoneId())
                    .add("quantity", 1);

            String code;
            try {
                code = http.post(form);
            } catch (WsepCommunicationException e) {
                throw new TicketIssuanceFailedException("ticket issuer unreachable: " + e.getMessage());
            }

            if (code == null || code.isBlank() || FAILURE.equals(code)) {
                throw new TicketIssuanceFailedException("ticket issuance rejected by WSEP for event " + item.eventId());
            }

            barcodes.add(new BarcodeDTO(ticketIds.getAndIncrement(), code, "QR"));
        }

        log.debug("wsep issue_ticket ok: count={} firstCode={} ({} ms)",
                barcodes.size(),
                SensitiveDataMasker.truncId(barcodes.get(0).barcodeValue(), 8),
                msSince(start));

        return new IssuanceResultDTO(
                "wsep-issuance-" + UUID.randomUUID(),
                ISSUER_ID,
                LocalDateTime.now(),
                barcodes);
    }

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
