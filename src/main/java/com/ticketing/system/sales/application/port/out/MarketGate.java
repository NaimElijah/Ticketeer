package com.ticketing.system.sales.application.port.out;

/**
 * Outbound port through which sales asks whether the trading market is currently open.
 *
 * <p>UC-32 / I.2.1: no money moves and no tickets may be held while the market is closed. Sales
 * enforces that gate but must not know <em>who</em> owns the market lifecycle. This port inverts the
 * dependency: sales depends only on this interface, and governance (the top-level consumer) supplies
 * the implementation. Sales therefore no longer imports any governance type.
 */
public interface MarketGate {

    /**
     * Reports whether the trading market is open for buyer operations (reserve / checkout).
     *
     * @return {@code true} when the market is open, {@code false} when it is closed
     */
    boolean isOpen(); // true when the platform market is OPEN, false otherwise
}
