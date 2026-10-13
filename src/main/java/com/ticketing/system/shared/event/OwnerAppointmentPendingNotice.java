package com.ticketing.system.shared.event;

/**
 * Integration event published by the organization context (CompanyManagementService) when a
 * member receives a pending owner appointment they must accept or reject. The notifications
 * context listens for it and raises a pending-appointment notification. Mirrors the former
 * {@code INotificationService.notifyOwnerAppointmentPending} call.
 */
public record OwnerAppointmentPendingNotice(
        int userId,          // the member who received the pending owner appointment
        int companyId,       // the company offering the appointment
        String companyName   // the display name of that company
) {
}
