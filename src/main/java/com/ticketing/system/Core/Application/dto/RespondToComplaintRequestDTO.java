package com.ticketing.system.Core.Application.dto;
import com.ticketing.system.messaging.application.service.MessagingService;

// Input to MessagingService.respondToComplaint() — admin response to a complaint Conversation.
// 'newStatus' transitions the conversation: typically RESPONDED (further dialogue expected)
// or RESOLVED (terminal). Sent as string.
public record RespondToComplaintRequestDTO(
    String conversationId,
    int adminId,
    String body,
    String newStatus                 // ConversationStatus value as string (RESPONDED / RESOLVED / CLOSED)
) {}
