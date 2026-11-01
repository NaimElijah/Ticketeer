package com.ticketing.system.unit.infrastructure.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import com.ticketing.system.shared.config.WsepProperties;
import com.ticketing.system.shared.dto.CardDetailsDTO;
import com.ticketing.system.shared.dto.PaymentRequestDTO;
import com.ticketing.system.shared.dto.PaymentResultDTO;
import com.ticketing.system.shared.dto.RefundResultDTO;
import com.ticketing.system.shared.exception.PaymentGatewayException;
import com.ticketing.system.shared.exception.PaymentGatewayUnreachableException;
import com.ticketing.system.shared.exception.RefundFailedException;
import com.ticketing.system.shared.exception.RefundGatewayUnreachableException;
import com.ticketing.system.shared.exception.RefundOutcomeUnknownException;
import com.ticketing.system.shared.external.WsepCommunicationException;
import com.ticketing.system.shared.external.WsepHttpClient;
import com.ticketing.system.sales.adapter.out.wsep.WsepPaymentGateway;
import com.ticketing.system.testutil.LoopbackHttpServer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class WsepPaymentGatewayTest {

    private static final String FULL_PAN = "4111111111119999";
    private static final String CVV = "777";

    // A real client for the loopback-server tests: one attempt, no backoff, a timeout loopback never hits.
    private static final long REQUEST_TIMEOUT_SECONDS = 5;
    private static final int SINGLE_ATTEMPT = 1;
    private static final long NO_BACKOFF_MS = 0;

    private final WsepHttpClient http = mock(WsepHttpClient.class);
    private static final Instant T0 = Instant.parse("2027-07-15T17:00:00Z");
    private final WsepPaymentGateway gateway = new WsepPaymentGateway(http, Clock.fixed(T0, ZoneOffset.UTC));

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
        // Stamped from the injected clock, as UTC wall time (ADR-008).
        assertEquals(LocalDateTime.ofInstant(T0, ZoneOffset.UTC), result.chargedAt());

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

    // ADR-001: a refund that never reached WSEP moved no money, so its claim may be released ...
    @Test
    void GivenConnectionRefused_WhenRefund_ThenGatewayUnreachable() {
        when(http.post(any())).thenThrow(new WsepCommunicationException("refused", new ConnectException("refused")));

        assertThrows(RefundGatewayUnreachableException.class, () -> gateway.refund(54321, 100.0));
    }

    // ... but a timeout may come after WSEP reversed the charge, so the outcome is unknown.
    @Test
    void GivenRequestTimesOut_WhenRefund_ThenOutcomeUnknown() {
        when(http.post(any())).thenThrow(new WsepCommunicationException("timeout", new HttpTimeoutException("timed out")));

        assertThrows(RefundOutcomeUnknownException.class, () -> gateway.refund(54321, 100.0));
    }

    // ADR-001: a non-2xx answer came from the far side of the wire, possibly after WSEP reversed the
    // charge, so the refund's outcome is unknown. It is not a verdict: only a 2xx "-1" is a rejection.
    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {404, 500, 502, 503})
    void GivenWsepAnswersANon2xxStatus_WhenRefund_ThenOutcomeUnknownNotRejected(int status) {
        try (LoopbackHttpServer server = LoopbackHttpServer.start()) {
            server.respondWith(status, "Internal Server Error");
            WsepPaymentGateway realGateway = new WsepPaymentGateway(
                    new WsepHttpClient(new WsepProperties(
                            server.baseUrl(), REQUEST_TIMEOUT_SECONDS, SINGLE_ATTEMPT, NO_BACKOFF_MS)),
                    Clock.fixed(T0, ZoneOffset.UTC));

            assertThrows(RefundOutcomeUnknownException.class, () -> realGateway.refund(54321, 100.0),
                    "HTTP " + status + " may follow a reversed charge, so it must not read as a rejected refund");
        }
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

    // ---- Defect 7: a failed refund names the payment transaction, not an order (#187) ----
    // The gateway refunds by payment transaction id. The event-cancellation path shows the message to the
    // company's owners, so "order 54321" would send them looking for an order that does not exist.

    private static final int PAYMENT_TRANSACTION_ID = 54321;

    @Test
    void GivenWsepRejectsTheRefund_WhenRefund_ThenTheFailureNamesThePaymentTransactionNotAnOrder() {
        when(http.post(any())).thenReturn("-1");

        RefundFailedException failure = assertThrows(RefundFailedException.class,
                () -> gateway.refund(PAYMENT_TRANSACTION_ID, 100.0));

        assertNamesThePaymentTransaction(failure);
    }

    @Test
    void GivenConnectionRefused_WhenRefund_ThenTheFailureNamesThePaymentTransactionNotAnOrder() {
        when(http.post(any())).thenThrow(new WsepCommunicationException("refused", new ConnectException("refused")));

        RefundGatewayUnreachableException failure = assertThrows(RefundGatewayUnreachableException.class,
                () -> gateway.refund(PAYMENT_TRANSACTION_ID, 100.0));

        assertNamesThePaymentTransaction(failure);
    }

    // Through the real client and real HTTP, as the other non-2xx test does.
    @Test
    void GivenWsepAnswersHttp500_WhenRefund_ThenTheFailureNamesThePaymentTransactionNotAnOrder() {
        try (LoopbackHttpServer server = LoopbackHttpServer.start()) {
            server.respondWith(500, "Internal Server Error");
            WsepPaymentGateway realGateway = new WsepPaymentGateway(
                    new WsepHttpClient(new WsepProperties(
                            server.baseUrl(), REQUEST_TIMEOUT_SECONDS, SINGLE_ATTEMPT, NO_BACKOFF_MS)),
                    Clock.fixed(T0, ZoneOffset.UTC));

            RefundOutcomeUnknownException failure = assertThrows(RefundOutcomeUnknownException.class,
                    () -> realGateway.refund(PAYMENT_TRANSACTION_ID, 100.0));

            assertNamesThePaymentTransaction(failure);
        }
    }

    private static void assertNamesThePaymentTransaction(RefundFailedException failure) {
        String message = failure.getMessage();
        assertFalse(message.contains("order " + PAYMENT_TRANSACTION_ID),
                "a payment transaction id is no order id, but the message was: " + message);
        assertTrue(message.contains("payment transaction " + PAYMENT_TRANSACTION_ID),
                "the message must name the payment transaction it failed to refund, but was: " + message);
    }
}
