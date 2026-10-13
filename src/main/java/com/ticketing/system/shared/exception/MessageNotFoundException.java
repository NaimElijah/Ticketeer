package com.ticketing.system.shared.exception;

// Thrown when a referenced Message id doesn't exist within its Conversation.
public class MessageNotFoundException extends DomainException {

    public MessageNotFoundException(Object messageId) {
        super("Message not found: " + messageId);
    }
}
