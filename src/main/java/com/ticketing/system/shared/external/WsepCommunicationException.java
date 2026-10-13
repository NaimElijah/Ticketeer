package com.ticketing.system.shared.external;

/**
 * Signals that a call to the WSEP endpoint could not complete at the transport
 * level (connection refused, timeout, I/O error) — distinct from a well-formed
 * {@code "-1"} business failure, which the adapters map to the relevant domain
 * exception. The adapters translate this into the right domain exception per
 * action (e.g. a payment-gateway failure or "unreachable" for a handshake).
 */
public class WsepCommunicationException extends RuntimeException {
    public WsepCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
