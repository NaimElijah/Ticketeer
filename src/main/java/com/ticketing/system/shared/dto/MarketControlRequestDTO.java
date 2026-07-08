package com.ticketing.system.shared.dto;

// Input to SystemAdminService.openMarket() / closeMarket() (UC-32).
// 'reason' is recorded for the audit log (no Close UC explicitly defined).
// 'token' carries the requesting admin's session credential — the service
// authorizes the toggle via requireSystemAdmin(token).
public record MarketControlRequestDTO(
    String action,                       // "OPEN" / "CLOSE"
    String reason,
    String token
) {}
