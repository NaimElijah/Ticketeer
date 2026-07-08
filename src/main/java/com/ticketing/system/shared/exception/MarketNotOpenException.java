package com.ticketing.system.shared.exception;

// Thrown when a transaction is attempted while the market is not OPEN.
// UC-32 (admin must open the market before transactions are allowed).
public class MarketNotOpenException extends DomainException {

    public MarketNotOpenException() {
        super("Market is not currently open for transactions");
    }

    public MarketNotOpenException(String reason) {
        super("Market is not open: " + reason);
    }
}
