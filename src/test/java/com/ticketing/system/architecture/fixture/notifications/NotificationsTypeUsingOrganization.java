package com.ticketing.system.architecture.fixture.notifications;

import com.ticketing.system.architecture.fixture.organization.OrganizationFixtureType;

/** Offender: notifications may depend only on shared, yet this depends on organization. */
public class NotificationsTypeUsingOrganization {

    private OrganizationFixtureType organization;
}
