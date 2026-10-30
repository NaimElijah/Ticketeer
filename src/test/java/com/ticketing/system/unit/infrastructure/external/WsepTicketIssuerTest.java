package com.ticketing.system.unit.infrastructure.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ticketing.system.shared.config.WsepProperties;
import com.ticketing.system.shared.dto.IssuanceRequestDTO;
import com.ticketing.system.shared.dto.IssuanceResultDTO;
import com.ticketing.system.shared.exception.TicketIssuanceFailedException;
import com.ticketing.system.shared.external.WsepCommunicationException;
import com.ticketing.system.shared.external.WsepHttpClient;
import com.ticketing.system.sales.adapter.out.wsep.WsepTicketIssuer;
import com.ticketing.system.testutil.LoopbackHttpServer;

class WsepTicketIssuerTest {

    // tickets.barcode_value is varchar(255) (db/migration/V1__baseline_schema.sql): a longer code
    // cannot be stored, so it must fail the issuance before the charge is committed against it.
    private static final int BARCODE_COLUMN_LENGTH = 255;
    // A real client for the loopback-server test: one attempt, no backoff, a timeout loopback never hits.
    private static final long REQUEST_TIMEOUT_SECONDS = 5;
    private static final int SINGLE_ATTEMPT = 1;
    private static final long NO_BACKOFF_MS = 0;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2027-07-15T17:00:00Z"), ZoneOffset.UTC);

    private final WsepHttpClient http = mock(WsepHttpClient.class);
    private final WsepTicketIssuer issuer = new WsepTicketIssuer(http, FIXED_CLOCK);

    private IssuanceRequestDTO twoTicketRequest() {
        return new IssuanceRequestDTO(42, null, List.of(
                new IssuanceRequestDTO.TicketIssuanceItemDTO(10, "Concert", 5, null),
                new IssuanceRequestDTO.TicketIssuanceItemDTO(10, "Concert", 5, null)));
    }

    @Test
    void verifyConnection_trueOnOk_falseOtherwise() {
        // verifyConnection now goes through the retrying postWithRetry (idempotent handshake).
        when(http.postWithRetry(any())).thenReturn("OK");
        assertTrue(issuer.verifyConnection());

        when(http.postWithRetry(any())).thenReturn("down");
        assertFalse(issuer.verifyConnection());

        when(http.postWithRetry(any())).thenThrow(new WsepCommunicationException("x", new RuntimeException()));
        assertFalse(issuer.verifyConnection());
    }

    @Test
    void issue_oneCallPerTicket_collectsCodesIntoBarcodes() {
        when(http.post(any())).thenReturn("TIX-aaaa-1111", "TIX-bbbb-2222");

        IssuanceResultDTO result = issuer.issue(twoTicketRequest());

        assertEquals("wsep-ticket-issuer", result.issuerName());
        assertNotNull(result.issuanceTransactionId());
        assertEquals(2, result.barcodes().size());
        assertEquals("TIX-aaaa-1111", result.barcodes().get(0).barcodeValue());
        assertEquals("TIX-bbbb-2222", result.barcodes().get(1).barcodeValue());
        assertEquals("QR", result.barcodes().get(0).format());
        assertEquals("QR", result.barcodes().get(1).format());

        ArgumentCaptor<WsepHttpClient.Form> captor = ArgumentCaptor.forClass(WsepHttpClient.Form.class);
        verify(http, times(2)).post(captor.capture());
        Map<String, String> first = captor.getAllValues().get(0).fields();
        assertEquals("issue_ticket", first.get("action_type"));
        assertEquals("42", first.get("customer_id"));
        assertEquals("10", first.get("event_id"));
        assertEquals("5", first.get("zone"));
        assertEquals("1", first.get("quantity"));
    }

    @Test
    void issue_minusOneFailsTheWholeIssuance() {
        when(http.post(any())).thenReturn("-1");
        assertThrows(TicketIssuanceFailedException.class, () -> issuer.issue(twoTicketRequest()));
    }

    @Test
    void issue_emptyItemsThrows() {
        IssuanceRequestDTO empty = new IssuanceRequestDTO(42, null, List.of());
        assertThrows(TicketIssuanceFailedException.class, () -> issuer.issue(empty));
    }

    @Test
    void issue_transportFailureBecomesIssuanceFailure() {
        when(http.post(any())).thenThrow(new WsepCommunicationException("refused", new RuntimeException()));
        assertThrows(TicketIssuanceFailedException.class, () -> issuer.issue(twoTicketRequest()));
    }

    @Test
    void GivenCodeAsLongAsTheBarcodeColumn_WhenIssue_ThenItIsTheBarcode() {
        String longest = "T".repeat(BARCODE_COLUMN_LENGTH);
        when(http.post(any())).thenReturn(longest, "TIX-bbbb-2222");

        IssuanceResultDTO result = issuer.issue(twoTicketRequest());

        assertEquals(longest, result.barcodes().get(0).barcodeValue(),
                "a code that fits tickets.barcode_value exactly must be accepted");
    }

    @Test
    void GivenCodeLongerThanTheBarcodeColumn_WhenIssue_ThenIssuanceFails() {
        when(http.post(any())).thenReturn("T".repeat(BARCODE_COLUMN_LENGTH + 1), "TIX-bbbb-2222");

        assertThrows(TicketIssuanceFailedException.class, () -> issuer.issue(twoTicketRequest()),
                "a code longer than tickets.barcode_value (varchar " + BARCODE_COLUMN_LENGTH
                        + ") cannot be stored and must fail the issuance");
    }

    @Test
    void GivenTheSameCodeTwiceInOneIssuance_WhenIssue_ThenIssuanceFails() {
        when(http.post(any())).thenReturn("TIX-same-0001", "TIX-same-0001");

        assertThrows(TicketIssuanceFailedException.class, () -> issuer.issue(twoTicketRequest()),
                "two tickets of one purchase must never share a barcode");
    }

    // Through the real client and real HTTP: an error page is not a ticket code.
    @Test
    void GivenWsepAnswersHttp500WithAnErrorPage_WhenIssue_ThenIssuanceFailsInsteadOfReturningThePageAsABarcode() {
        try (LoopbackHttpServer server = LoopbackHttpServer.start()) {
            server.respondWith(500, "Internal Server Error");
            WsepTicketIssuer realIssuer = new WsepTicketIssuer(
                    new WsepHttpClient(new WsepProperties(
                            server.baseUrl(), REQUEST_TIMEOUT_SECONDS, SINGLE_ATTEMPT, NO_BACKOFF_MS)), FIXED_CLOCK);

            assertThrows(TicketIssuanceFailedException.class, () -> realIssuer.issue(twoTicketRequest()),
                    "an HTTP 500 body must never become a ticket barcode");
        }
    }
}
