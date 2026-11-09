package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One company appointment as an owner sees it. Returned by
 * {@code CompanyManagementService.listManagers} (the active managers) and
 * {@code listPendingInvitations} (the offers awaiting an answer). A member's own invitations use
 * {@link InvitationDTO}.
 *
 * @param appointmentId      the appointment's id, as text
 * @param targetUserId       the member the appointment is offered to
 * @param appointerUserId    the owner who made the offer
 * @param role               {@code Owner} or {@code Manager}
 * @param status             {@code ACTIVE} in the managers list, {@code PENDING} in the pending invitations
 * @param grantedPermissions the names of the permissions granted; empty for an owner
 */
public record AppointmentInfoDTO(
    String appointmentId,
    int companyId,
    String companyName,
    int targetUserId,
    String targetUsername,
    int appointerUserId,
    String role,
    String status,
    List<String> grantedPermissions,
    LocalDateTime createdAt
) {}
