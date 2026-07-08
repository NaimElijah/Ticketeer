package com.ticketing.system.sales.application.port.out;

import com.ticketing.system.Core.Application.dto.PaymentRequestDTO;
import com.ticketing.system.Core.Application.dto.PaymentResultDTO;
import com.ticketing.system.Core.Application.dto.RefundResultDTO;

// Port for external payment processing. Multi-provider support (I.3.2) is realized
// in Infrastructure by injecting List<PaymentGateway>; this interface stays single.
// Implementations live in Infrastructure/external/.
public interface PaymentGateway {

    // Identifies the gateway implementation (e.g. "stripe", "paypal").
    // Used for logging, routing, and reporting.
    String getId();

    // UC-1 / I.1.2 startup verification — true if the gateway is reachable + authenticated.
    boolean verifyConnection();

    // UC-33 — process a charge. Throws PaymentGatewayException on failure / decline.
    PaymentResultDTO charge(PaymentRequestDTO request);

    // UC-4 / I.3.3 — refund an existing charge. Throws RefundFailedException on failure.
    RefundResultDTO refund(int paymentTransactionId, double amount);
}
