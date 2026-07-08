package com.ticketing.system.shared.exception;
import com.ticketing.system.sales.application.port.out.PaymentGateway;

// Thrown when the payment gateway could NOT be reached at all (transport timeout / no response /
// connection error) — distinct from a real decline, which stays
// PaymentGatewayException("payment declined by gateway") for the WSEP "-1" reply. Extends
// PaymentGatewayException so the documented PaymentGateway.charge contract still holds and every
// existing catch(PaymentGatewayException) / instanceof check keeps working; the Presentation layer
// maps THIS subtype to a GENERIC error so no gateway/WSEP wording leaks to the buyer. The #409
// rollback is unchanged: when this is thrown the charge never returned, so no money moved, no
// inventory changed, and no receipt/ticket was created.
public class PaymentGatewayUnreachableException extends PaymentGatewayException {

    public PaymentGatewayUnreachableException(String reason) {
        super(reason);
    }

    public PaymentGatewayUnreachableException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
