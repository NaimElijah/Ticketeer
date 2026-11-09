package com.ticketing.system.shared.event;

/**
 * Integration event published by the organization context (CompanyManagementService) when a
 * member receives a pending owner appointment they must accept or reject. The notifications
 * context listens for it and raises a pending-appointment notification. The listener hands its components to
 * {@code INotificationService.notifyOwnerAppointmentPending}.
 *
 * @param userId the member who received the pending owner appointment
 * @param companyId the company offering the appointment
 * @param companyName the display name of that company
 */
public record OwnerAppointmentPendingNotice(
        int userId,
        int companyId,
        String companyName
) {
}
