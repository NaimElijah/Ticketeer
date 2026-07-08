package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One row of a member's own invitations page (V2-WIRE-MEMBER-INVITES). Unlike
 * {@link AppointmentInfoDTO} — which is the owner-facing view keyed on the
 * <em>invitee</em> — this is the member-facing shape keyed on the
 * <em>inviter</em> ({@code fromUsername}), and carries {@code status} so the same
 * payload renders both the pending list and the resolved history.
 *
 * @param appointmentId   stringified appointment id
 * @param companyId       the company that issued the invitation
 * @param companyName     resolved company display name
 * @param role            {@code Owner} / {@code Manager}
 * @param fromUsername    username of the member who sent the invitation
 * @param permissions     granted permission names (empty for an Owner offer)
 * @param status          {@code PENDING} / {@code ACTIVE} / {@code REJECTED} / {@code REVOKED}
 * @param sentAt          when the invitation was created
 */
public record InvitationDTO(String appointmentId,
                            int companyId,
                            String companyName,
                            String role,
                            String fromUsername,
                            List<String> permissions,
                            String status,
                            LocalDateTime sentAt) {
}
