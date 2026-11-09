package com.ticketing.system.shared.dto;

// Input to MessagingService.sendMessage() — append a message to an existing Conversation.
// The service works out the sender from the token and the conversation, and does not read
// 'senderId' or 'senderType'.
public record SendMessageRequestDTO(
    String conversationId,
    int senderId,
    String senderType,               // ParticipantType value as string
    String body
) {}
