package com.ticketing.system.shared.dto;

/**
 * Input to {@code MessagingService.sendMessage}: appends a message to an existing conversation. The
 * service works out the sender from the token and the conversation, and does not read {@code senderId}
 * or {@code senderType}.
 */
public record SendMessageRequestDTO(
    String conversationId,
    int senderId,
    String senderType,
    String body
) {}
