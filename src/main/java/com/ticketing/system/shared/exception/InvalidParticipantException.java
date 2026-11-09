package com.ticketing.system.shared.exception;

/**
 * Thrown when someone who is not a participant of a conversation tries to act on it: to reply
 * ({@code Conversation.addMessage}), to mark it read ({@code Conversation.markReadBy}) or to act on it
 * through {@code MessagingService}. The message reaches the user.
 */
public class InvalidParticipantException extends DomainException {

    /**
     * Creates the exception.
     *
     * @param participantId the user who is not a participant
     * @param conversationId the conversation they tried to act on
     */
    public InvalidParticipantException(Object participantId, Object conversationId) {
        super("Participant " + participantId + " is not part of conversation " + conversationId);
    }

    /**
     * Creates the exception with a specific text.
     *
     * @param message the text the user reads
     */
    public InvalidParticipantException(String message) {
        super(message);
    }
}
