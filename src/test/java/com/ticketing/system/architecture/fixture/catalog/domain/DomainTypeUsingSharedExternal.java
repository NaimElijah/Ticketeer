package com.ticketing.system.architecture.fixture.catalog.domain;

import com.ticketing.system.shared.external.WsepHttpClient;

/** Offender: a domain class using the shared kernel's outbound HTTP client. */
public class DomainTypeUsingSharedExternal {

    private WsepHttpClient client;
}
