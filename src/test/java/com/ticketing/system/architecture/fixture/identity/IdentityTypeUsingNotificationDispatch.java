package com.ticketing.system.architecture.fixture.identity;

import com.ticketing.system.architecture.fixture.notifications.application.service.NotificationDispatchService;

/** Offender: the dispatch-service exception belongs to AuthenticationService alone. */
public class IdentityTypeUsingNotificationDispatch {

    private NotificationDispatchService dispatchService;
}
