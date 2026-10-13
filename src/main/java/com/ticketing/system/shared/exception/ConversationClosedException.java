package com.ticketing.system.shared.exception;

// Thrown when a message is sent to a Conversation whose status is RESOLVED or CLOSED.
public class ConversationClosedException extends DomainException {

    public ConversationClosedException(Object conversationId) {
        super("Conversation " + conversationId + " is closed; no further messages allowed");
    }
}
