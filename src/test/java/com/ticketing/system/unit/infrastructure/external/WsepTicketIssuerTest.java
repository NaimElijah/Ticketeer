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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ticketing.system.Core.Application.dto.IssuanceRequestDTO;
import com.ticketing.system.Core.Application.dto.IssuanceResultDTO;
import com.ticketing.system.shared.exception.TicketIssuanceFailedException;
import com.ticketing.system.Infrastructure.external.WsepCommunicationException;
import com.ticketing.system.Infrastructure.external.WsepHttpClient;
import com.ticketing.system.Infrastructure.external.WsepTicketIssuer;

class WsepTicketIssuerTest {

    private final WsepHttpClient http = mock(WsepHttpClient.class);
    private final WsepTicketIssuer issuer = new WsepTicketIssuer(http);

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
        assertTrue(result.barcodes().get(0).ticketId() > 0);
        assertTrue(result.barcodes().get(1).ticketId() > 0);

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
}
