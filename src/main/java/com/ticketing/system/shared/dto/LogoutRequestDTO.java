package com.ticketing.system.shared.dto;

/**
 * Input to {@code AuthenticationService.logout()}. UC-14.
 *
 * <p>The token identifies the session to invalidate. UC-14 per II.3.1 is purely
 * a session-termination concern; cart state is governed by II.3.0.1 / II.3.0.3
 * and is not touched here.
 */
public record LogoutRequestDTO(
    String token
) {}
