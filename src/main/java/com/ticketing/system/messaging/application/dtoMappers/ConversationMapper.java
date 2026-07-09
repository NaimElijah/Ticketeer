package com.ticketing.system.messaging.application.dtoMappers;

import java.util.List;

import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.MessageDTO;
import com.ticketing.system.messaging.domain.Conversation;
import com.ticketing.system.messaging.domain.Message;

/**
 * Maps the {@link Conversation} aggregate to its DTO view (the "never return domain
 * objects" rule). Stateless; instantiated inline by {@code MessagingService}, mirroring
 * {@code AppointmentInfoMapper}. Domain enums are emitted as {@code name()} strings so the
 * DTOs stay independent of the domain enums, and the unread badge is computed for the
 * specific viewer requesting the conversation.
 */
public class ConversationMapper {

    public ConversationDTO toDTO(Conversation conversation, int viewerId,
            String initiatorDisplayName, String counterpartyDisplayName) {
        List<MessageDTO> messages = conversation.getMessages().stream()
                .map(this::toMessageDTO)
                .toList();
        return new ConversationDTO(
                conversation.getConversationId(),
                conversation.getType().name(),
                conversation.getStatus().name(),
                conversation.getInitiatorId(),
                conversation.getInitiatorType().name(),
                conversation.getCounterpartyId(),
                conversation.getCounterpartyType().name(),
                conversation.getSubject(),
                conversation.getCreatedAt(),
                conversation.getLastMessageAt(),
                conversation.unreadCountFor(viewerId),
                messages,
                initiatorDisplayName,
                counterpartyDisplayName);
    }

    public MessageDTO toMessageDTO(Message message) {
        return new MessageDTO(
                message.getMessageId(),
                message.getSenderId(),
                message.getSenderType().name(),
                message.getBody(),
                message.getSentAt(),
                message.isRead());
    }
}
