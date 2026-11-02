package com.ticketing.system.unit.infrastructure.external;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.ticketing.system.sales.adapter.out.wsep.StubPaymentGateway;
import com.ticketing.system.shared.exception.RefundFailedException;

/**
 * The test profile's payment gateway refunds by payment transaction id, as WSEP does, so its refusals
 * name the payment transaction, as {@code WsepPaymentGatewayTest} pins for the real one (#187).
 */
class StubPaymentGatewayTest {

    private static final int UNKNOWN_PAYMENT_TRANSACTION_ID = 54321;

    private final StubPaymentGateway gateway =
            new StubPaymentGateway(Clock.fixed(Instant.parse("2027-07-15T17:00:00Z"), ZoneOffset.UTC));

    @Test
    void GivenNoSuchCharge_WhenRefund_ThenTheFailureNamesThePaymentTransactionNotAnOrder() {
        RefundFailedException failure = assertThrows(RefundFailedException.class,
                () -> gateway.refund(UNKNOWN_PAYMENT_TRANSACTION_ID, 100.0));

        String message = failure.getMessage();
        assertFalse(message.contains("order " + UNKNOWN_PAYMENT_TRANSACTION_ID),
                "a payment transaction id is no order id, but the message was: " + message);
        assertTrue(message.contains("payment transaction " + UNKNOWN_PAYMENT_TRANSACTION_ID),
                "the message must name the payment transaction it failed to refund, but was: " + message);
    }
}
