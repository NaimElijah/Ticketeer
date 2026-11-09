package com.ticketing.system.messaging.domain;

/** The kind of party that sends or receives in a {@link Conversation}. */
public enum ParticipantType {
    /** A specific user, by member id. */
    MEMBER,
    /** A specific production company, by company id. */
    COMPANY,
    /** A specific system admin, by admin id. */
    ADMIN,
    /** Any system admin; the complaint queue. */
    ADMIN_GROUP,
    /** Reserved for system-generated messages; nothing creates one yet. */
    SYSTEM
}
