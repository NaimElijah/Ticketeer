package com.ticketing.system.organization.domain;

/**
 * Whether a production company is open to the public.
 *
 * <p>Only {@link #ACTIVE} companies are listed by the public catalog. Suspending, closing and reopening a
 * company are deferred (II.4.13, II.4.14), and no code assigns {@link #INACTIVE} today.
 */
public enum CompanyStatus {
    /** Listed by the public catalog; the status a newly registered company starts in. */
    ACTIVE,
    /** Not listed by the public catalog. */
    INACTIVE
}
