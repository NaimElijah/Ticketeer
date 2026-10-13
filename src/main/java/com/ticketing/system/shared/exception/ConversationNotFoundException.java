package com.ticketing.system.shared.exception;

// Specific subclass of EntityNotFoundException for Conversation lookups (messaging subsystem).
public class ConversationNotFoundException extends EntityNotFoundException {

    public ConversationNotFoundException(Object conversationId) {
        super("Conversation", conversationId);
    }
}
