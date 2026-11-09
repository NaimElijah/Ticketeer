package com.ticketing.system.shared.dto;

/**
 * Input to {@code CompanyManagementService.RevokeAppointment}: an owner revokes a member's
 * appointment in a company. The service refuses to revoke the company founder's.
 *
 * @param companyId    the company the appointment belongs to
 * @param targetUserId the member whose appointment is revoked
 */
public record AppointmentRevokeDTO(
        int companyId,
        int targetUserId) {
}