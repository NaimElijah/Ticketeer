package com.ticketing.system.shared.exception;

// Thrown when a sender attempts to post to a Conversation they're not a participant of,
// or when a participant of an unexpected type is supplied.
public class InvalidParticipantException extends DomainException {

    public InvalidParticipantException(Object participantId, Object conversationId) {
        super("Participant " + participantId + " is not part of conversation " + conversationId);
    }

    public InvalidParticipantException(String message) {
        super(message);
    }
}
