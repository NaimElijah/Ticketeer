package com.ticketing.system.Core.Application.dto;
import com.ticketing.system.messaging.application.service.MessagingService;

// Result of MessagingService.sendOutreach(): how many recipients received a DIRECT conversation,
// plus the id of the first one created (a representative handle for the fan-out).
public record OutreachResultDTO(int recipientCount, String firstConversationId) {}
