package com.ticketing.system.architecture.fixture.identity.application.service;

import com.ticketing.system.architecture.fixture.identity.adapter.out.persistence.IdentityAdapterType;

/** Offender: an application class using a concrete adapter instead of the port it implements. */
public class ApplicationTypeUsingAnAdapter {

    private IdentityAdapterType adapter;
}
