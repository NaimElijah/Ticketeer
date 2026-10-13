package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;
import java.util.List;

// View of a single CompanyAppointment.
// Used by UC-25 (org tree leaf) and any "view my pending appointments" query.
public record AppointmentInfoDTO(
    String appointmentId,
    int companyId,
    String companyName,
    int targetUserId,
    String targetUsername,
    int appointerUserId,
    String role,                         // "Owner" / "Manager"
    String status,                       // AppointmentStatus value as string
    List<String> grantedPermissions,
    LocalDateTime createdAt
) {}
