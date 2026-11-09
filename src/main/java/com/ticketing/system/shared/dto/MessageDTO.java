package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;

/**
 * One message of a conversation, rendered for the UI. {@code senderType} is a string, to keep the DTO
 * independent of the domain enum.
 *
 * @param senderId   the sender's id, within the space of its {@code senderType}
 * @param senderType {@code MEMBER}, {@code COMPANY}, {@code ADMIN}, {@code ADMIN_GROUP} or
 *                   {@code SYSTEM}
 * @param read       whether the other side has read it
 */
public record MessageDTO(
    String messageId,
    int senderId,
    String senderType,
    String body,
    LocalDateTime sentAt,
    boolean read
) {}
