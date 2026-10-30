package com.ticketing.system.architecture.fixture.ui;

import com.ticketing.system.architecture.fixture.identity.application.service.AuthenticationService;

/** Allowed case: the UI driving another context through its application service. */
public class UiTypeUsingAnApplicationService {

    private AuthenticationService applicationService;
}
