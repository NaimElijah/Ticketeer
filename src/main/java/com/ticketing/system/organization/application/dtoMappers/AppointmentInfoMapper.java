package com.ticketing.system.organization.application.dtoMappers;

import com.ticketing.system.shared.dto.AppointmentInfoDTO;
import com.ticketing.system.organization.domain.CompanyAppointment;
import com.ticketing.system.organization.domain.Permission;

/**
 * Maps a {@link CompanyAppointment} domain object to the read-side
 * {@link AppointmentInfoDTO}. The target username and company name are passed in
 * by the caller (looked up from their respective aggregates) so the mapper stays
 * a pure value transform with no repository dependencies.
 *
 * <p>Used by the manager roster + pending-invitation queries (#264) and reused by
 * the "my invitations" query (#275).
 */
public class AppointmentInfoMapper {

    public AppointmentInfoDTO toDTO(CompanyAppointment appt, String targetUsername, String companyName) {
        return new AppointmentInfoDTO(
                String.valueOf(appt.getAppointmentId()),
                appt.getCompanyId(),
                companyName,
                appt.getTargetId(),
                targetUsername,
                appt.getInviterId(),
                appt.getRole().name(),
                appt.getStatus().name(),
                appt.getPermissions().stream().map(Permission::name).toList(),
                appt.getCreatedAt());
    }
}
