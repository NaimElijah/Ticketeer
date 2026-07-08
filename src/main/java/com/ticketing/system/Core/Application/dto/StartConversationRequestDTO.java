package com.ticketing.system.Core.Application.dto;
import com.ticketing.system.messaging.application.service.MessagingService;

// Input to MessagingService.startConversation() (II.3.10 — a Member opens an INQUIRY with a
// Company). The service derives the initiator (the authenticated member) and the participant
// types, so the caller only supplies the target company and the message.
public record StartConversationRequestDTO(
    int counterpartyId,              // target companyId
    String subject,
    String firstMessageBody
) {}
