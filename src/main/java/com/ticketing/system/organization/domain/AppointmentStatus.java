package com.ticketing.system.organization.domain;

/**
 * Lifecycle states of a {@link CompanyAppointment} (UC-23 / UC-24 design walkthrough).
 *
 * <p>An appointment starts {@link #PENDING}, or {@link #ACTIVE} for a company's founding appointment. A
 * pending one becomes {@link #ACTIVE} or {@link #REJECTED}; an active one can become {@link #REVOKED}.
 * {@code REJECTED} and {@code REVOKED} are terminal.
 */
public enum AppointmentStatus {
    /** Created by an appointing owner (UC-23 Owner, UC-24 Manager) and awaiting the target's response. */
    PENDING,
    /** The target accepted, so the role is in force. A founding appointment is created in this state. */
    ACTIVE,
    /** The target rejected the appointment. */
    REJECTED,
    /**
     * An active appointment that was ended. A Manager appointment is revoked by its inviter, an Owner
     * appointment by its inviter or its target (II.4.9). A Manager appointment is also revoked when its holder
     * accepts an Owner offer from the same inviter.
     */
    REVOKED
}
