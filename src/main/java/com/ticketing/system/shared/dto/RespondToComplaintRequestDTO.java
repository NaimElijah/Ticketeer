package com.ticketing.system.shared.dto;

// Input to MessagingService.respondToComplaint() — admin response to a complaint Conversation.
// The service resolves the complaint with its single reply. It takes the admin from the token and
// does not read 'adminId' or 'newStatus'.
public record RespondToComplaintRequestDTO(
    String conversationId,
    int adminId,
    String body,
    String newStatus                 // ConversationStatus value as string (RESPONDED / RESOLVED / CLOSED)
) {}
