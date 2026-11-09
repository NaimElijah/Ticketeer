package com.ticketing.system.shared.event;

/**
 * Integration event published by the organization context (CompanyManagementService) when a
 * member receives a pending manager appointment they must accept or reject. The notifications
 * context listens for it and raises a pending-appointment notification. The member's role changes
 * only on acceptance, which publishes {@link RoleChangedNotice}.
 *
 * @param userId the member who received the pending manager appointment
 * @param companyId the company offering the appointment
 * @param companyName the display name of that company
 */
public record ManagerAppointmentPendingNotice(
        int userId,
        int companyId,
        String companyName
) {
}
