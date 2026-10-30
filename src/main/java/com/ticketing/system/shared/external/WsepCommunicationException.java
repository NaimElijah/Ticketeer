package com.ticketing.system.shared.external;

/**
 * Signals that a call to the WSEP endpoint produced no usable WSEP reply: it
 * could not complete at the transport level (connection refused, timeout, I/O
 * error), it was answered with a non-2xx status ({@link WsepHttpStatusException}),
 * or its body was larger than any WSEP reply. Distinct from a well-formed
 * {@code "-1"} business failure, which the adapters map to the relevant domain
 * exception. The adapters translate this into the right domain exception per
 * action (e.g. a payment-gateway failure or "unreachable" for a handshake).
 */
public class WsepCommunicationException extends RuntimeException {

    /**
     * @param message what went wrong, without request or response content
     * @param cause   the transport failure behind it
     */
    public WsepCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * For a reply that arrived but is unusable, so there is no transport failure to carry.
     *
     * @param message what went wrong, without request or response content
     */
    public WsepCommunicationException(String message) {
        super(message);
    }
}
