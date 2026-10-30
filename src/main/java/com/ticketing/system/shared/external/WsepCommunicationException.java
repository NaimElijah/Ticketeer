package com.ticketing.system.shared.external;

/**
 * Signals that a call to the WSEP endpoint produced no usable WSEP reply: it
 * could not complete at the transport level (connection refused, timeout, I/O
 * error), it was answered with a non-2xx status, or its body was larger than any
 * WSEP reply. Distinct from a well-formed {@code "-1"} business failure, which the
 * adapters map to the relevant domain exception.
 *
 * <p>The request may still have been processed: a non-2xx status or a read timeout
 * can come after WSEP acted on it. Each adapter maps this per action. {@code refund}
 * reports an outcome-unknown failure unless the request was never sent. {@code pay}
 * and the handshake report the service as unreachable, and {@code issue_ticket} as a
 * failed issuance, so a {@code pay} whose answer was lost reads as an unreachable
 * gateway although the card may have been charged.
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
