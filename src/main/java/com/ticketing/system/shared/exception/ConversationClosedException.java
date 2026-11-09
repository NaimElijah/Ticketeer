package com.ticketing.system.shared.exception;

/**
 * Thrown when a message is added to a conversation whose status is {@code RESOLVED} or
 * {@code CLOSED} ({@code Conversation.addMessage}). The message reaches the user.
 */
public class ConversationClosedException extends DomainException {

    /**
     * Creates the exception.
     *
     * @param conversationId the closed conversation
     */
    public ConversationClosedException(Object conversationId) {
        super("Conversation " + conversationId + " is closed; no further messages allowed");
    }
}
