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
 * <p>Used by the manager roster and pending-invitation queries (#264).
 */
public class AppointmentInfoMapper {

    /**
     * Maps an appointment to its read-side view.
     *
     * @param targetUsername the appointed user's username, looked up by the caller
     * @param companyName    the company's name, looked up by the caller
     * @return the view, with the appointment id as decimal text and the role, status and permissions as enum names
     */
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
