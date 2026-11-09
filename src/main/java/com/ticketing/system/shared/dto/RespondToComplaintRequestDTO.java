package com.ticketing.system.shared.dto;

/**
 * Input to {@code MessagingService.respondToComplaint}: an admin's response to a complaint
 * conversation. The service resolves the complaint with its single reply. It takes the admin from the
 * token and does not read {@code adminId} or {@code newStatus}.
 */
public record RespondToComplaintRequestDTO(
    String conversationId,
    int adminId,
    String body,
    String newStatus
) {}
