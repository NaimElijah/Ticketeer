package com.ticketing.system.shared.dto;

/**
 * Input to {@code CompanyManagementService.RevokeAppointment}: an owner revokes a member's
 * appointment in a company. The founder's appointment cannot be revoked. A manager appointment can be
 * revoked only by the member who made it; an owner appointment only by the member who made it or by
 * its target ({@code CompanyAppointment.revoke}).
 */
public record AppointmentRevokeDTO(
        int companyId,
        int targetUserId) {
}