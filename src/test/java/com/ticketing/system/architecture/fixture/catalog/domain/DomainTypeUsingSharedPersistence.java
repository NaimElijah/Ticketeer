package com.ticketing.system.architecture.fixture.catalog.domain;

import com.ticketing.system.shared.persistence.RepositoryLocks;

/** Offender: a domain class using the shared kernel's persistence infrastructure. */
public class DomainTypeUsingSharedPersistence {

    private RepositoryLocks<Integer> locks;
}
