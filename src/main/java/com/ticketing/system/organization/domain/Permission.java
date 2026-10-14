package com.ticketing.system.organization.domain;

// The permission set (UC-24 / II.4.7) is undecided: OQ-2 in docs/requirements/requirements.md.
public enum Permission {
    MANAGE_INVENTORY,
    CONFIGURE_VENUE,
    EDIT_POLICIES,
    VIEW_SALES,
    RESPOND_TO_INQUIRIES
}
