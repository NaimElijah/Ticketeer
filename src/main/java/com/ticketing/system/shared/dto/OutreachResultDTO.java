package com.ticketing.system.shared.dto;

/**
 * Result of {@code MessagingService.sendOutreach}.
 *
 * @param recipientCount      how many recipients received a DIRECT conversation
 * @param firstConversationId the id of the first conversation created, a representative handle for the
 *                            fan-out
 */
public record OutreachResultDTO(int recipientCount, String firstConversationId) {}
