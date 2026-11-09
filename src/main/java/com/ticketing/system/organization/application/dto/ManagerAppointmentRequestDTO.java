package com.ticketing.system.organization.application.dto;

import java.util.List;

import com.ticketing.system.organization.domain.Permission;

/**
 * Input to {@code CompanyManagementService.appointManager} (UC-24 step 1, appoint).
 *
 * @param companyId    the company to appoint into; a positive id
 * @param targetUserId the member to appoint; a positive id
 * @param permissions  the permissions to grant, a non-empty list per II.4.7.2
 */
public record ManagerAppointmentRequestDTO(
        int companyId,
        int targetUserId,
        List<Permission> permissions) {
}
