package com.ticketing.system.messaging.domain;

/** What a {@link Conversation} is for. A COMPLAINT is one-shot; the others are two-way chats. */
public enum ConversationType {
    /** A member contacts a company; a two-way chat (II.3.10, II.4.4). */
    INQUIRY,
    /**
     * A member files a one-shot complaint with the admin queue (II.3.3, II.6.3.1): the member's single
     * message and one admin reply, which resolves it.
     */
    COMPLAINT,
    /** An admin contacts a member proactively; a two-way chat (II.6.3.2). */
    DIRECT
}
