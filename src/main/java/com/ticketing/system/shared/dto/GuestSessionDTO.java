package com.ticketing.system.shared.dto;

import java.time.Instant;

/**
 * Returned by {@code AuthenticationService.startGuestSession()}.
 *
 * <p>The {@code sessionId} is the credential the Guest carries on subsequent
 * requests until they either (a) end the session, (b) it expires, a fixed
 * lifetime after it started, or (c) they log in and the session is promoted to a Member
 * session in place.
 */
public record GuestSessionDTO(String sessionId, Instant createdAt) {

    /**
     * Leaves out the session id, which is the guest's credential, so a logged session or an
     * exception message built from one does not carry it (SLR.2).
     */
    @Override
    public String toString() {
        return "GuestSessionDTO[createdAt=" + createdAt + "]";
    }
}
