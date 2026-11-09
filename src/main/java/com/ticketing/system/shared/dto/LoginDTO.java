package com.ticketing.system.shared.dto;

import java.util.List;

/**
 * The response of {@code AuthenticationService.login} (UC-12).
 *
 * @param authToken     the token the client uses for authenticated requests
 * @param activeOrder   the member's cart carried over by the login (UC-12); {@code null} when the member
 *                      has none
 * @param notifications the member's pending notifications that the login delivered; empty when there
 *                      are none, or when delivering them failed (they then stay pending)
 */
public record LoginDTO(
        AuthTokenDTO authToken,
        ActiveOrderDTO activeOrder,
        List<NotificationDTO> notifications) {
}
