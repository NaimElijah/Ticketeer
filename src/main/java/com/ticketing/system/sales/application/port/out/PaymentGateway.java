package com.ticketing.system.sales.application.port.out;

import com.ticketing.system.shared.dto.PaymentRequestDTO;
import com.ticketing.system.shared.dto.PaymentResultDTO;
import com.ticketing.system.shared.dto.RefundResultDTO;

/**
 * Port for external payment processing. Implementations live in {@code sales.adapter.out.wsep}: the
 * WSEP gateway outside the {@code test} profile, a stub under it.
 *
 * <p>Only one implementation can be wired today: checkout and both refund paths inject a single
 * {@code PaymentGateway}, so a second bean breaks startup (requirements I.3, OQ-13). Only governance's
 * reachability probe takes a list of them.
 */
public interface PaymentGateway {

    /**
     * Returns a stable name for the gateway, such as {@code "wsep-payment-gateway"}. It is recorded as
     * the provider on the refund records that {@link #refund} results produce.
     */
    String getId();

    /**
     * Checks that the gateway answers the handshake (UC-1 / I.1.2 startup verification).
     *
     * @return {@code true} if it is; {@code false} otherwise, including when the check itself fails
     */
    boolean verifyConnection();

    /**
     * Charges a card (UC-33).
     *
     * @param request the amount, currency, card and buyer to charge
     * @return the gateway's record of the charge
     * @throws com.ticketing.system.shared.exception.PaymentGatewayException if the gateway declines the
     *     charge, the card details are missing, or the answer cannot be used; its subtype
     *     {@code PaymentGatewayUnreachableException} means no usable answer arrived
     */
    PaymentResultDTO charge(PaymentRequestDTO request);

    /**
     * Refunds an existing charge (UC-4 / I.3.3).
     *
     * @param paymentTransactionId the gateway's transaction id of the charge, positive
     * @param amount               the amount to refund; ADR-001 describes how the WSEP adapter applies it
     * @return the gateway's record of the refund
     * @throws com.ticketing.system.shared.exception.RefundFailedException if the gateway declines
     * @throws com.ticketing.system.shared.exception.RefundGatewayUnreachableException if the request
     *     never reached the gateway
     * @throws com.ticketing.system.shared.exception.RefundOutcomeUnknownException if the request may
     *     have been processed without a usable answer (ADR-001)
     */
    RefundResultDTO refund(int paymentTransactionId, double amount);
}
