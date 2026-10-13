package com.ticketing.system.messaging.domain;

// Lifecycle states for a Conversation.
//   OPEN       - awaiting any reply
//   RESPONDED  - counterparty has responded; back to initiator
//   RESOLVED   - terminal (used for COMPLAINT — admin marked resolved)
//   CLOSED     - terminal (no further messages allowed)
public enum ConversationStatus {
    OPEN,
    RESPONDED,
    RESOLVED,
    CLOSED
}
