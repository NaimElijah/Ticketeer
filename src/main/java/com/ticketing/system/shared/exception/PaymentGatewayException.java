package com.ticketing.system.shared.exception;
import com.ticketing.system.sales.application.service.CheckoutService;

// Thrown when an external payment-gateway call fails (decline, timeout, unavailable).
// CheckoutService catches and either retries (timeout/unavailable) or aborts checkout (decline).
// UC-33; feeds UC-4 refund pipeline if charge succeeds but issuance fails.
public class PaymentGatewayException extends DomainException {

    public PaymentGatewayException(String reason) {
        super("Payment gateway failure: " + reason);
    }

    public PaymentGatewayException(String reason, Throwable cause) {
        super("Payment gateway failure: " + reason, cause);
    }
}
