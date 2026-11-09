package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A conversation in the messaging subsystem, as the calling member, company or admin sees it.
 * Enum-valued components are strings, to keep the DTO independent of the domain enums.
 *
 * @param conversationId          the conversation's id
 * @param type                    {@code INQUIRY}, {@code COMPLAINT} or {@code DIRECT}
 * @param status                  {@code OPEN}, {@code RESPONDED}, {@code RESOLVED} or {@code CLOSED}
 * @param initiatorId             the id of the party that started it
 * @param initiatorType           {@code MEMBER}, {@code COMPANY}, {@code ADMIN}, {@code ADMIN_GROUP} or
 *                                {@code SYSTEM}
 * @param counterpartyId          the id of the other party
 * @param counterpartyType        the other party's type, from the same set
 * @param unreadCountForViewer    the messages from the other side that the viewer has not read
 * @param messages                the conversation's messages
 * @param initiatorDisplayName    the initiator's member username or company name, or "TicketHub Support"
 *                                for an admin; a placeholder with the id when it no longer exists
 * @param counterpartyDisplayName the counterparty's name, resolved the same way
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
