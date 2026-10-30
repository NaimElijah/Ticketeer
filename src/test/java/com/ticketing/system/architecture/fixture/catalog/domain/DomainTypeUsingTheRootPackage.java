package com.ticketing.system.architecture.fixture.catalog.domain;

import com.ticketing.system.architecture.fixture.RootPackageType;

/** Offender: a domain class depending on a class directly in the root package, which is no module. */
public class DomainTypeUsingTheRootPackage {

    private RootPackageType rootType;
}
