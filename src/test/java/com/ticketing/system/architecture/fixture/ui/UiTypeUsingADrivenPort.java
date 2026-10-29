package com.ticketing.system.architecture.fixture.ui;

import com.ticketing.system.architecture.fixture.identity.application.port.out.IdentityDrivenPort;

/** Offender: the UI calling another context's driven port instead of its application service. */
public class UiTypeUsingADrivenPort {

    private IdentityDrivenPort drivenPort;
}
