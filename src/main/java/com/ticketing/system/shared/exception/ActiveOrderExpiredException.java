package com.ticketing.system.shared.exception;

// Thrown when an operation (checkout, modify, view) is attempted on an ActiveOrder
// whose reservation timer has expired. UC-2 / UC-10 / II.2.8.1 / II.3.0.3.
public class ActiveOrderExpiredException extends DomainException {

    public ActiveOrderExpiredException() {
        super("Active order has expired; tickets have been released to inventory");
    }

    public ActiveOrderExpiredException(String message) {
        super(message);
    }
}
