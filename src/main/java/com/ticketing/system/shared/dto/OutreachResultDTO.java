package com.ticketing.system.shared.dto;

// Result of MessagingService.sendOutreach(): how many recipients received a DIRECT conversation,
// plus the id of the first one created (a representative handle for the fan-out).
public record OutreachResultDTO(int recipientCount, String firstConversationId) {}
