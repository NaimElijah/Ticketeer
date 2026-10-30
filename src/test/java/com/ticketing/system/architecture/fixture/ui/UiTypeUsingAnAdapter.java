package com.ticketing.system.architecture.fixture.ui;

import com.ticketing.system.architecture.fixture.identity.adapter.out.persistence.IdentityAdapterType;

/** Offender: the UI reaching past another context's ports into one of its adapters. */
public class UiTypeUsingAnAdapter {

    private IdentityAdapterType adapter;
}
