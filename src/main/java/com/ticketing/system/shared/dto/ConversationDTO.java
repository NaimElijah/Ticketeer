package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;
import java.util.List;

// View of a Conversation from the centralized messaging subsystem.
// Replaces the old company-only Conversation/Inbox DTOs.
// 'type' / 'status' / participant types as strings to keep DTO independent of domain enums.
public record ConversationDTO(
    String conversationId,
    String type,                     // ConversationType
    String status,                   // ConversationStatus
    int initiatorId,
    String initiatorType,            // ParticipantType
    int counterpartyId,
    String counterpartyType,
    String subject,
    LocalDateTime createdAt,
    LocalDateTime lastMessageAt,
    int unreadCountForViewer,
    List<MessageDTO> messages,
    String initiatorDisplayName,     // resolved: member username / company name / "TicketHub Support"
    String counterpartyDisplayName   // resolved: same scheme
) {}
