package com.ticketing.system.shared.event;

/**
 * Integration event published by the organization context (CompanyManagementService) when a
 * member's manager appointment in a company is revoked. The notifications context listens for it
 * and raises a manager-revoked notification. Mirrors the former
 * {@code INotificationService.notifyManagerRevoked} call.
 */
public record ManagerRevokedNotice(
        int userId,          // the member whose appointment was revoked
        int companyId,       // the company the appointment belonged to
        String companyName   // the display name of that company
) {
}
