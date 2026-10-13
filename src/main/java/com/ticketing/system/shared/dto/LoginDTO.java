package com.ticketing.system.shared.dto;

import java.util.List;

/*
LoginDTO is the response from LoginService.login() (UC-1). It includes:
- authToken: the token the client will use for authenticated requests.
- activeOrder: details of the user's active order, if any (UC-1 requirement).
- notifications: a list of the user's notifications, which may include any pending notifications that were delivered
 */
public record LoginDTO(
        AuthTokenDTO authToken,
        ActiveOrderDTO activeOrder,
        List<NotificationDTO> notifications) {
}
