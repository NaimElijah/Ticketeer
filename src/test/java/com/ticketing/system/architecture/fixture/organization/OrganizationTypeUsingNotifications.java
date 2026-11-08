package com.ticketing.system.architecture.fixture.organization;

import com.ticketing.system.architecture.fixture.notifications.NotificationsTypeUsingOrganization;

/** Offender: closes a cycle with {@link NotificationsTypeUsingOrganization}, which depends on organization. */
public class OrganizationTypeUsingNotifications {

    private NotificationsTypeUsingOrganization notifications;
}
