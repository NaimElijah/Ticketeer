package com.ticketing.system.shared.exception;

/**
 * Reserved for an operation on an {@code ActiveOrder} whose reservation timer has expired. UC-2 /
 * UC-10 / II.2.8.1 / II.3.0.3.
 *
 * <p>No production code throws it today. An expired order surfaces as an
 * {@link InvalidStateTransitionException} from {@code ActiveOrder.validateCanCheckout}, which
 * checkout reports to the buyer as an expired order. As a {@link DomainException}, its message would
 * reach the user.
 */
public class ActiveOrderExpiredException extends DomainException {

    public ActiveOrderExpiredException() {
        super("Active order has expired; tickets have been released to inventory");
    }

    public ActiveOrderExpiredException(String message) {
        super(message);
    }
}
