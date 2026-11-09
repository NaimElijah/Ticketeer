package com.ticketing.system.organization.domain;

/**
 * The role an appointment gives its holder in a production company.
 *
 * <p>An {@link #Owner} holds every permission implicitly and its appointment lists none. A
 * {@link #Manager} holds only the permissions its appointment lists, and at least one.
 */
public enum CompanyRole {
    /** Holds only the permissions the appointment lists. */
    Manager,
    /** Holds every permission implicitly; the appointment carries no permission list. */
    Owner
}
