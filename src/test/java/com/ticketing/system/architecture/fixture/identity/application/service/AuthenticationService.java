package com.ticketing.system.architecture.fixture.identity.application.service;

import com.ticketing.system.architecture.fixture.notifications.NotificationsTypeUsingOrganization;
import com.ticketing.system.architecture.fixture.notifications.application.service.NotificationDispatchService;

/**
 * Mirrors the production class name: its dependency on the dispatch service is the allowed
 * exception, its dependency on any other notifications type is not.
 */
public class AuthenticationService {

    private NotificationDispatchService dispatchService;

    private NotificationsTypeUsingOrganization otherNotificationsType;
}
