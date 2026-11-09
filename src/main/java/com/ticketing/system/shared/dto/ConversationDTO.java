package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A conversation in the messaging subsystem, as the calling member, company or admin sees it.
 * Enum-valued components are strings, to keep the DTO independent of the domain enums.
 *
 * @param type                    {@code INQUIRY}, {@code COMPLAINT} or {@code DIRECT}
 * @param status                  {@code OPEN}, {@code RESPONDED}, {@code RESOLVED} or {@code CLOSED}
 * @param initiatorId             the id of the party that started it
 * @param initiatorType           {@code MEMBER} or {@code ADMIN}: only a member or an admin starts a
 *                                conversation
 * @param counterpartyId          the id of the other party
 * @param counterpartyType        {@code MEMBER}, {@code COMPANY} or {@code ADMIN_GROUP}
 * @param unreadCountForViewer    the messages from the other side that the viewer has not read
 * @param initiatorDisplayName    the initiator's member username, or "TicketHub Support" for an admin;
 *                                a placeholder when it no longer exists
 * @param counterpartyDisplayName the counterparty's name, resolved the same way; a company shows its name
 */
public record ConversationDTO(
    String conversationId,
    String type,
    String status,
    int initiatorId,
    String initiatorType,
    int counterpartyId,
    String counterpartyType,
    String subject,
    LocalDateTime createdAt,
    LocalDateTime lastMessageAt,
    int unreadCountForViewer,
    List<MessageDTO> messages,
    String initiatorDisplayName,
    String counterpartyDisplayName
) {}
