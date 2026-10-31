package com.ticketing.system.shared.event;

/**
 * Integration event published by the organization context (CompanyManagementService) when a
 * member receives a pending manager appointment they must accept or reject. The notifications
 * context listens for it and raises a pending-appointment notification. The member's role changes
 * only on acceptance, which publishes {@link RoleChangedNotice}.
 */
public record ManagerAppointmentPendingNotice(
        int userId,          // the member who received the pending manager appointment
        int companyId,       // the company offering the appointment
        String companyName   // the display name of that company
) {
}
