package com.ticketing.system.organization.application.dto;

/**
 * A member's role in one production company, as the membership read model reports it.
 *
 * <p>One constant per legal combination of appointment role and foundership. {@code CompanyRole}
 * plus a {@code founder} flag would also admit a founding manager, which no company can have: the
 * founder always holds the founding owner appointment. Callers switch on this type, never on its
 * {@linkplain #label() label}, so rewording a label cannot change what a role may do.
 */
public enum MembershipRole {
    /** The member who registered the company; an owner who can never be revoked. */
    FOUNDER("Founder"),
    /** An owner appointed by another owner. */
    CO_OWNER("Co-owner"),
    /** A manager, holding only the permissions their appointing owner granted. */
    MANAGER("Manager");

    private final String label;

    MembershipRole(String label) {
        this.label = label;
    }

    /**
     * @return the label shown for this role, in the UI and in {@code MyCompanyDTO}; display text only
     */
    public String label() {
        return label;
    }
}
