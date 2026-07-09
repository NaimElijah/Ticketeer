package com.ticketing.system.shared.dto;

import java.time.Instant;

/**
 * Returned by {@code AuthenticationService.startGuestSession()}.
 *
 * <p>The {@code sessionId} is the credential the Guest carries on subsequent
 * requests until they either (a) end the session, (b) it expires by idle
 * timeout, or (c) they log in and the session is promoted to a Member
 * session in place.
 */
public record GuestSessionDTO(String sessionId, Instant createdAt) {}
