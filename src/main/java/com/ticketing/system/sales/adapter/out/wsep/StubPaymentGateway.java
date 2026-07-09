package com.ticketing.system.sales.adapter.out.wsep;

import com.ticketing.system.shared.dto.PaymentRequestDTO;
import com.ticketing.system.shared.dto.PaymentResultDTO;
import com.ticketing.system.shared.dto.RefundResultDTO;
import com.ticketing.system.sales.application.port.out.PaymentGateway;
import com.ticketing.system.shared.exception.IdempotencyConflictException;
import com.ticketing.system.shared.exception.PaymentGatewayException;
import com.ticketing.system.shared.exception.RefundFailedException;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// In-process payment gateway for TESTS ONLY (@Profile("test")). Every real run
// (default/prod + the dev profile) uses WsepPaymentGateway instead; the profile
// keeps the singular PaymentGateway injection in CheckoutService unambiguous —
// exactly one gateway bean exists per profile.
@Component
@Profile("test")
public class StubPaymentGateway implements PaymentGateway {
    //*Note: changed from not implemented to what's below here, can change however wanted though, it's a stub for our needs */
    private static final String GATEWAY_ID = "stub-payment-gateway";

    private final AtomicInteger transactionIds = new AtomicInteger(1);
    private final ConcurrentHashMap<String, PaymentRequestDTO> requestsByIdempotencyKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PaymentResultDTO> chargesByIdempotencyKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, PaymentResultDTO> chargesByTransactionId = new ConcurrentHashMap<>();

    @Override
    public String getId() {
        return GATEWAY_ID;
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
    public PaymentResultDTO charge(PaymentRequestDTO request) {
        validateChargeRequest(request);

        String key = request.idempotencyKey();
            
        PaymentRequestDTO existingRequest = requestsByIdempotencyKey.putIfAbsent(key, request);
         if (existingRequest != null && !samePaymentRequest(existingRequest, request)) {
             throw new IdempotencyConflictException(key);
        }

        PaymentRequestDTO canonicalRequest = existingRequest != null ? existingRequest : request;

        PaymentResultDTO result = chargesByIdempotencyKey.computeIfAbsent(key, k -> {
             int transactionId = transactionIds.getAndIncrement();
             PaymentResultDTO created = new PaymentResultDTO(
                     transactionId,
                     GATEWAY_ID,
                     canonicalRequest.amount(),
                     canonicalRequest.currency(),
                     LocalDateTime.now()
             );
             chargesByTransactionId.put(transactionId, created);
             return created;
         });

        return result;
    }

    @Override
    public RefundResultDTO refund(int paymentTransactionId, double amount) {
        if (paymentTransactionId <= 0) {
            throw new RefundFailedException(paymentTransactionId, "paymentTransactionId must be positive");
        }

        if (amount <= 0) {
            throw new RefundFailedException(paymentTransactionId, "refund amount must be positive");
        }

        PaymentResultDTO originalCharge = chargesByTransactionId.get(paymentTransactionId);
        if (originalCharge == null) {
            throw new RefundFailedException(paymentTransactionId, "original payment transaction not found");
        }

        if (amount > originalCharge.chargedAmount()) {
            throw new RefundFailedException(paymentTransactionId, "cannot refund more than original charge");
        }

        return new RefundResultDTO(
                "refund-" + transactionIds.getAndIncrement(),
                String.valueOf(paymentTransactionId),
                amount,
                LocalDateTime.now(),
                List.of(),
                List.of());
    }

    



    // #################################################################################
    //
    // Helper methods for validating requests and comparing them for idempotency checks.
    //
    // #################################################################################


    private void validateChargeRequest(PaymentRequestDTO request) {
        if (request == null) {
            throw new PaymentGatewayException("payment request must not be null");
        }

        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new PaymentGatewayException("idempotency key is required");
        }

        if (request.amount() <= 0) {
            throw new PaymentGatewayException("payment amount must be positive");
        }

        if (request.currency() == null || request.currency().isBlank()) {
            throw new PaymentGatewayException("currency is required");
        }

        if (request.card() == null || request.card().cardNumber() == null || request.card().cardNumber().isBlank()) {
            throw new PaymentGatewayException("card details are required");
        }

        boolean memberBuyer = request.buyerUserId() != null;
        boolean guestBuyer = request.buyerEmail() != null && !request.buyerEmail().isBlank();

        if (memberBuyer == guestBuyer) {
            throw new PaymentGatewayException("payment request must identify exactly one buyer type: member OR guest");
        }

        if (memberBuyer && request.buyerUserId() <= 0) {
            throw new PaymentGatewayException("buyer user id must be positive");
        }
    }



    private boolean samePaymentRequest(PaymentRequestDTO a, PaymentRequestDTO b) {
        return Double.compare(a.amount(), b.amount()) == 0
                && Objects.equals(a.currency(), b.currency())
                && Objects.equals(a.card(), b.card())
                && Objects.equals(a.buyerUserId(), b.buyerUserId())
                && Objects.equals(a.buyerEmail(), b.buyerEmail());
    }
}
