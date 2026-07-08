package com.ticketing.system.sales.adapter.out.wsep;
import com.ticketing.system.shared.external.SensitiveDataMasker; // transitional: shared-technical (stays in external until the Step 10 sweep)
import com.ticketing.system.shared.external.WsepCommunicationException; // transitional: shared-technical (stays in external until the Step 10 sweep)
import com.ticketing.system.shared.external.WsepHttpClient; // transitional: shared-technical WSEP client (stays in external until the Step 10 sweep)

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.ticketing.system.Core.Application.dto.CardDetailsDTO;
import com.ticketing.system.Core.Application.dto.PaymentRequestDTO;
import com.ticketing.system.Core.Application.dto.PaymentResultDTO;
import com.ticketing.system.Core.Application.dto.RefundResultDTO;
import com.ticketing.system.sales.application.port.out.PaymentGateway;
import com.ticketing.system.shared.exception.PaymentGatewayException;
import com.ticketing.system.shared.exception.PaymentGatewayUnreachableException;
import com.ticketing.system.shared.exception.RefundFailedException;

import lombok.extern.slf4j.Slf4j;

/**
 * Real payment gateway backed by the WSEP external system. Active in every real
 * run ({@code @Profile("!test")}); the test suite uses {@link StubPaymentGateway}
 * instead. Each method is one WSEP action exactly per the documented contract:
 * {@code handshake} → {@link #verifyConnection()}, {@code pay} → {@link #charge},
 * {@code refund} → {@link #refund}. {@code "-1"} is the universal failure reply
 * and is translated into the matching domain exception.
 *
 * <p>DEBUG logs are masked: the card number shows only its last 4 and the CVV /
 * expiry / holder are never logged (they are still sent on the wire — WSEP
 * requires them — just never recorded).
 */
@Component
@Profile("!test")
@Slf4j
public class WsepPaymentGateway implements PaymentGateway {

    private static final String GATEWAY_ID = "wsep-payment-gateway";
    private static final String FAILURE = "-1";

    private final WsepHttpClient http;

    public WsepPaymentGateway(WsepHttpClient http) {
        this.http = http;
    }

    @Override
    public String getId() {
        return GATEWAY_ID;
    }

    @Override
    public boolean verifyConnection() {
        try {
            // Retried (idempotent handshake): a cold-starting WSEP endpoint may miss the first attempt
            // but answer a later one, so a transient blip doesn't leave the market closed at boot (#455).
            boolean ok = "OK".equalsIgnoreCase(http.postWithRetry(WsepHttpClient.action("handshake")));
            log.debug("wsep handshake: reachable={}", ok);
            return ok;
        } catch (RuntimeException e) {
            log.debug("wsep handshake failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public PaymentResultDTO charge(PaymentRequestDTO request) {
        CardDetailsDTO card = request.card();
        if (card == null || card.cardNumber() == null || card.cardNumber().isBlank()) {
            throw new PaymentGatewayException("card details are required");
        }

        long start = System.nanoTime();
        log.debug("wsep pay -> amount={} {} card={}",
                request.amount(), request.currency(), SensitiveDataMasker.mask(card.cardNumber()));

        WsepHttpClient.Form form = WsepHttpClient.action("pay")
                .add("amount", request.amount())
                .add("currency", request.currency())
                .add("card_number", card.cardNumber())
                .add("month", card.expiryMonth())
                .add("year", card.expiryYear())
                .add("holder", card.holderName())
                .add("cvv", card.cvv())
                .add("id", request.buyerUserId() != null ? request.buyerUserId() : 0);

        String body;
        try {
            body = http.post(form);
        } catch (WsepCommunicationException e) {
            // No answer from WSEP (timeout / connection error). This is NOT a decline — the charge
            // never reached a verdict — so it gets the unreachable subtype, which the Presentation
            // layer surfaces as a generic error (no gateway wording leaked).
            throw new PaymentGatewayUnreachableException("payment gateway unreachable: " + e.getMessage(), e);
        }

        if (FAILURE.equals(body)) {
            // The ONLY real decline: WSEP explicitly replied "-1".
            log.debug("wsep pay declined ({} ms)", msSince(start));
            throw new PaymentGatewayException("payment declined by gateway");
        }

        int transactionId = parsePositiveInt(body, "pay");
        log.debug("wsep pay ok: txnId={} ({} ms)", transactionId, msSince(start));
        return new PaymentResultDTO(
                transactionId,
                GATEWAY_ID,
                request.amount(),
                request.currency(),
                LocalDateTime.now());
    }

    @Override
    public RefundResultDTO refund(int paymentTransactionId, double amount) {
        if (paymentTransactionId <= 0) {
            throw new RefundFailedException(paymentTransactionId, "paymentTransactionId must be positive");
        }

        long start = System.nanoTime();
        log.debug("wsep refund -> txnId={}", paymentTransactionId);

        String body;
        try {
            body = http.post(WsepHttpClient.action("refund").add("transaction_id", paymentTransactionId));
        } catch (WsepCommunicationException e) {
            throw new RefundFailedException(paymentTransactionId, "refund gateway unreachable: " + e.getMessage());
        }

        // WSEP refund reverses the whole charge by id and returns 1/-1 — there is no partial-refund amount.
        if (!"1".equals(body)) {
            log.debug("wsep refund failed: body={} ({} ms)", body, msSince(start));
            throw new RefundFailedException(paymentTransactionId, "refund rejected by gateway");
        }

        log.debug("wsep refund ok: txnId={} ({} ms)", paymentTransactionId, msSince(start));
        return new RefundResultDTO(
                "wsep-refund-" + paymentTransactionId,
                String.valueOf(paymentTransactionId),
                amount,
                LocalDateTime.now(),
                List.of(),
                List.of());
    }

    // Only "-1" is a real decline (handled by the caller). Any other non-positive or unparseable
    // body is a malformed/unexpected response, not a verdict — treat it as unreachable/generic so
    // it never masquerades as "Payment declined".
    private int parsePositiveInt(String body, String action) {
        try {
            int value = Integer.parseInt(body == null ? "" : body.trim());
            if (value <= 0) {
                throw new PaymentGatewayUnreachableException("wsep " + action + " returned non-positive value: " + value);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new PaymentGatewayUnreachableException("wsep " + action + " returned an unparseable response");
        }
    }

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
