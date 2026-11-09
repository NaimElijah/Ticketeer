package com.ticketing.system.shared.exception;

/**
 * Thrown when a payment gave no usable WSEP verdict: a transport failure (timeout, no response,
 * connection error, a non-2xx status), or a reply that is not a positive transaction id.
 * {@code WsepPaymentGateway.charge} throws it. A real decline stays a plain
 * {@link PaymentGatewayException} ("payment declined by gateway", the WSEP {@code "-1"} reply).
 *
 * <p>It extends {@link PaymentGatewayException} so every existing catch of that type still applies.
 * {@code CheckoutPresenter} tests for this subtype first and shows the buyer generic text, so no
 * gateway wording reaches them, and {@code CheckoutService} logs it at ERROR as an outage.
 */
public class PaymentGatewayUnreachableException extends PaymentGatewayException {

    /**
     * Creates the exception.
     *
     * @param reason what was wrong with the exchange
     */
    public PaymentGatewayUnreachableException(String reason) {
        super(reason);
    }

    /**
     * Creates the exception with the transport failure behind it.
     *
     * @param reason what was wrong with the exchange
     * @param cause the transport failure, or {@code null}
     */
    public PaymentGatewayUnreachableException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
