package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;

// Returned by NotificationDispatchService.deliverPending() (UC-37) and any inbox view.
// 'type' and 'status' are sent as strings to keep the DTO independent of domain enums.
public record NotificationDTO(
    String notificationId,
    String type,
    String status,
    String message,
    LocalDateTime createdAt
) {}
