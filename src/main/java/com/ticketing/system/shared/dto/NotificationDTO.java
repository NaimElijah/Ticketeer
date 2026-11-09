package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;

/**
 * A notification, returned by {@code NotificationDispatchService.deliverPending} (UC-37); the UI
 * keeps the results in {@code NotificationSession}. {@code type} and {@code status} are strings, to
 * keep the DTO independent of the domain enums.
 */
public record NotificationDTO(
    String notificationId,
    String type,
    String status,
    String message,
    LocalDateTime createdAt
) {}
