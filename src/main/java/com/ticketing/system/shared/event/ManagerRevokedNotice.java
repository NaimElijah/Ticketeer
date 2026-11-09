package com.ticketing.system.shared.event;

/**
 * Integration event published by the organization context (CompanyManagementService) when a
 * member's manager or owner appointment in a company is revoked. The notifications context listens for it
 * and raises a manager-revoked notification. The listener hands its components to
 * {@code INotificationService.notifyManagerRevoked}.
 *
 * @param userId the member whose appointment was revoked
 * @param companyId the company the appointment belonged to
 * @param companyName the display name of that company
 */
public record ManagerRevokedNotice(
        int userId,
        int companyId,
        String companyName
) {
}
