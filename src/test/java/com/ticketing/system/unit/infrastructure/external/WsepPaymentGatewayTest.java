package com.ticketing.system.unit.infrastructure.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import com.ticketing.system.Core.Application.dto.CardDetailsDTO;
import com.ticketing.system.Core.Application.dto.PaymentRequestDTO;
import com.ticketing.system.Core.Application.dto.PaymentResultDTO;
import com.ticketing.system.Core.Application.dto.RefundResultDTO;
import com.ticketing.system.shared.exception.PaymentGatewayException;
import com.ticketing.system.shared.exception.PaymentGatewayUnreachableException;
import com.ticketing.system.shared.exception.RefundFailedException;
import com.ticketing.system.Infrastructure.external.WsepCommunicationException;
import com.ticketing.system.Infrastructure.external.WsepHttpClient;
import com.ticketing.system.sales.adapter.out.wsep.WsepPaymentGateway;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class WsepPaymentGatewayTest {

    private static final String FULL_PAN = "4111111111119999";
    private static final String CVV = "777";

    private final WsepHttpClient http = mock(WsepHttpClient.class);
    private final WsepPaymentGateway gateway = new WsepPaymentGateway(http);

    private PaymentRequestDTO request() {
        CardDetailsDTO card = new CardDetailsDTO(FULL_PAN, CVV, 11, 2031, "Jane Holder");
        return new PaymentRequestDTO("idem-1", 100.0, "USD", card, 42, null);
    }

    @Test
    void verifyConnection_trueOnOk_falseOtherwise_falseOnTransportError() {
        // verifyConnection now goes through the retrying postWithRetry (idempotent handshake).
        when(http.postWithRetry(any())).thenReturn("OK");
        assertTrue(gateway.verifyConnection());

        when(http.postWithRetry(any())).thenReturn("down");
        assertFalse(gateway.verifyConnection());

        when(http.postWithRetry(any())).thenThrow(new WsepCommunicationException("x", new RuntimeException()));
        assertFalse(gateway.verifyConnection());
    }

    @Test
    void charge_mapsEveryPayParamAndReturnsTransactionId() {
        when(http.post(any())).thenReturn("54321");

        PaymentResultDTO result = gateway.charge(request());

        assertEquals(54321, result.paymentTransactionId());
        assertEquals("wsep-payment-gateway", result.gatewayName());
        assertEquals(100.0, result.chargedAmount());
        assertEquals("USD", result.currency());

        ArgumentCaptor<WsepHttpClient.Form> captor = ArgumentCaptor.forClass(WsepHttpClient.Form.class);
        verify(http).post(captor.capture());
        Map<String, String> sent = captor.getValue().fields();
        assertEquals("pay", sent.get("action_type"));
        assertEquals("100.0", sent.get("amount"));
        assertEquals("USD", sent.get("currency"));
        assertEquals(FULL_PAN, sent.get("card_number"));
        assertEquals("11", sent.get("month"));
        assertEquals("2031", sent.get("year"));
        assertEquals("Jane Holder", sent.get("holder"));
        assertEquals(CVV, sent.get("cvv"));      // cvv IS sent to WSEP (required)...
        assertEquals("42", sent.get("id"));
    }

    @Test
    void charge_minusOneIsDecline_notUnreachable() {
        when(http.post(any())).thenReturn("-1");
        // A real WSEP "-1" decline must be the base PaymentGatewayException, NOT the unreachable
        // subtype — that's what keeps the buyer's "Payment declined" UX distinct from the generic one.
        PaymentGatewayException ex =
                assertThrows(PaymentGatewayException.class, () -> gateway.charge(request()));
        assertFalse(ex instanceof PaymentGatewayUnreachableException,
                "a real decline must NOT be the unreachable subtype");
    }

    @Test
    void charge_unparseableBodyIsUnreachable_notDecline() {
        when(http.post(any())).thenReturn("not-a-number");
        assertThrows(PaymentGatewayUnreachableException.class, () -> gateway.charge(request()));
    }

    @Test
    void charge_nonPositiveBodyIsUnreachable_notDecline() {
        when(http.post(any())).thenReturn("0");
        assertThrows(PaymentGatewayUnreachableException.class, () -> gateway.charge(request()));
    }

    @Test
    void charge_transportFailureIsUnreachable_notDecline() {
        when(http.post(any())).thenThrow(new WsepCommunicationException("refused", new RuntimeException()));
        assertThrows(PaymentGatewayUnreachableException.class, () -> gateway.charge(request()));
    }

    @Test
    void refund_oneIsSuccess_minusOneFails() {
        when(http.post(any())).thenReturn("1");
        RefundResultDTO ok = gateway.refund(54321, 100.0);
        assertEquals("54321", ok.orderReceiptId());

        when(http.post(any())).thenReturn("-1");
        assertThrows(RefundFailedException.class, () -> gateway.refund(54321, 100.0));
    }

    @Test
    void charge_neverLogsFullPanOrCvv() {
        Logger logger = (Logger) LoggerFactory.getLogger(WsepPaymentGateway.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level original = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            when(http.post(any())).thenReturn("54321");
            gateway.charge(request());

            boolean anyLogged = false;
            for (ILoggingEvent event : appender.list) {
                String msg = event.getFormattedMessage();
                assertFalse(msg.contains(FULL_PAN), "full PAN leaked: " + msg);
                assertFalse(msg.contains(CVV), "cvv leaked: " + msg);
                if (msg.contains("9999")) {
                    anyLogged = true; // last 4 is fine to show
                }
            }
            assertTrue(anyLogged, "expected a masked card debug line");
        } finally {
            logger.setLevel(original);
            logger.detachAppender(appender);
        }
    }
}
