package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One company appointment as an owner sees it. Returned by
 * {@code CompanyManagementService.listManagers} (the active managers) and
 * {@code listPendingInvitations} (the offers awaiting an answer). A member's own invitations use
 * {@link InvitationDTO}.
 */
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
