package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;

/**
 * A notification, returned by {@code NotificationDispatchService.deliverPending} (UC-37) and by any
 * inbox view. {@code type} and {@code status} are strings, to keep the DTO independent of the domain
 * enums.
 *
 * @param type   the notification type's name
 * @param status the notification status's name
 */
public record NotificationDTO(
    String notificationId,
    String type,
    String status,
    String message,
    LocalDateTime createdAt
) {}
