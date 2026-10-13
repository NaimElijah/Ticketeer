package com.ticketing.system.shared.dto;

// Input to MessagingService.sendMessage() — append a message to an existing Conversation.
public record SendMessageRequestDTO(
    String conversationId,
    int senderId,
    String senderType,               // ParticipantType value as string
    String body
) {}
