package com.ticketing.system.shared.dto;

/**
 * Input to {@code MessagingService.startConversation} (II.3.10: a member opens an INQUIRY with a
 * company). The service derives the initiator (the authenticated member) and the participant types,
 * so the caller supplies only the target company and the message.
 *
 * @param counterpartyId   the id of the company to write to
 * @param firstMessageBody the text of the first message
 */
public record StartConversationRequestDTO(
    int counterpartyId,
    String subject,
    String firstMessageBody
) {}
