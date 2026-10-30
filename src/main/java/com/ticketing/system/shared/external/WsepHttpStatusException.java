package com.ticketing.system.shared.external;

/**
 * Signals that the WSEP endpoint answered with a non-2xx HTTP status. Something on the far side of
 * the wire replied (WSEP itself, a proxy or a load balancer), but not with a WSEP verdict, so the body
 * is never handed to a caller. Because the request may have been processed before the error was
 * returned, callers must treat it as an unknown outcome, never as WSEP's {@code "-1"} rejection.
 */
public class WsepHttpStatusException extends WsepCommunicationException {

    private final int status;

    /**
     * Creates the exception for one non-2xx answer.
     *
     * @param status the HTTP status code WSEP's endpoint returned
     */
    public WsepHttpStatusException(int status) {
        super("WSEP answered HTTP " + status);
        this.status = status;
    }

    /** The HTTP status code the endpoint returned. */
    public int status() {
        return status;
    }
}
