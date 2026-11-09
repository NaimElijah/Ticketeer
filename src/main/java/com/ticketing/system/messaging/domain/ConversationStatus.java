package com.ticketing.system.messaging.domain;

/** Lifecycle states of a {@link Conversation}. */
public enum ConversationStatus {
    /** Awaiting a reply from the counterparty. */
    OPEN,
    /** The counterparty has responded; the next move is the initiator's. */
    RESPONDED,
    /** An admin has answered a complaint. The conversation takes no more messages, but can still be closed. */
    RESOLVED,
    /** Closed by a participant. The conversation takes no more messages. */
    CLOSED
}
