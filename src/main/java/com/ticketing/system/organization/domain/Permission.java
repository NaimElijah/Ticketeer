package com.ticketing.system.organization.domain;

/**
 * A granular capability that a Manager appointment can grant in one company (UC-24 / II.4.7). An Owner holds
 * all of them implicitly.
 *
 * <p>The permission set is undecided: OQ-2 in {@code docs/requirements/requirements.md}.
 */
public enum Permission {
    /** Managing events and their inventory in catalog. */
    MANAGE_INVENTORY,
    /** Configuring venue maps in catalog. */
    CONFIGURE_VENUE,
    /** Editing purchase policies: the company's, and its events'. */
    EDIT_POLICIES,
    /** Viewing the company's sales figures in reporting. */
    VIEW_SALES,
    /**
     * Answering customer inquiries addressed to the company in messaging; also shows the open-inquiry count
     * on the company dashboard.
     */
    RESPOND_TO_INQUIRIES
}
