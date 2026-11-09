package com.ticketing.system.shared.exception;

/**
 * Thrown when someone who is not a participant of a conversation tries to act on it: to reply
 * ({@code Conversation.addMessage}), to mark it read ({@code Conversation.markReadBy}) or to act on it
 * through {@code MessagingService}. The message reaches the user.
 */
public class InvalidParticipantException extends DomainException {

    public InvalidParticipantException(Object participantId, Object conversationId) {
        super("Participant " + participantId + " is not part of conversation " + conversationId);
    }

    public InvalidParticipantException(String message) {
        super(message);
    }
}
