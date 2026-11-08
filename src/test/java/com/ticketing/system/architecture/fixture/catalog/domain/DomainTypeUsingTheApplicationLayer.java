package com.ticketing.system.architecture.fixture.catalog.domain;

import com.ticketing.system.architecture.fixture.identity.application.port.out.IdentityDrivenPort;

/** Offender: a domain class reaching up into an application layer. */
public class DomainTypeUsingTheApplicationLayer {

    private IdentityDrivenPort port;
}
