package com.ticketing.system.shared.event;

/**
 * Integration event published by the organization context (CompanyManagementService) when a
 * member's role in a company changes (appointment accepted, manager appointed, or permissions
 * updated). The notifications context listens for it and raises a role-changed notification.
 * Mirrors the former {@code INotificationService.notifyRoleChanged} call.
 */
public record RoleChangedNotice(
        int userId,          // the member whose role changed
        int companyId,       // the company the role applies to
        String companyName,  // the display name of that company
        String newRole       // the new role label to surface to the member
) {
}
