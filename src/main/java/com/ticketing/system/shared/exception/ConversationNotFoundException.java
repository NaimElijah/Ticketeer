package com.ticketing.system.shared.exception;

/**
 * The {@link EntityNotFoundException} for a conversation lookup in the messaging context, thrown by
 * {@code MessagingService} when an id matches no conversation.
 */
public class ConversationNotFoundException extends EntityNotFoundException {

    /**
     * Creates the exception.
     *
     * @param conversationId the id that was looked up
     */
    public ConversationNotFoundException(Object conversationId) {
        super("Conversation", conversationId);
    }
}
