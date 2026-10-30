package com.ticketing.system.architecture.fixture.ui;

import com.ticketing.system.architecture.fixture.identity.application.port.in.IdentityInboundPort;

/** Allowed case: the UI driving another context through its inbound port. */
public class UiTypeUsingAnInboundPort {

    private IdentityInboundPort inboundPort;
}
