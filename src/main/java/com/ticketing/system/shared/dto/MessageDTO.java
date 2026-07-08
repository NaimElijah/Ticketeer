package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;

// One Message rendered for the UI. Replaces the old InboxMessageDTO.
// 'senderType' is sent as a string to keep DTO independent of the domain enum.
public record MessageDTO(
    String messageId,
    int senderId,
    String senderType,
    String body,
    LocalDateTime sentAt,
    boolean read
) {}
