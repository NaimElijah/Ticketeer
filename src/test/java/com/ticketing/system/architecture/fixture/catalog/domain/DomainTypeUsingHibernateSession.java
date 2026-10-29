package com.ticketing.system.architecture.fixture.catalog.domain;

import org.hibernate.Session;

/** Offender: a domain class using a Hibernate type that is not a mapping annotation. */
public class DomainTypeUsingHibernateSession {

    private Session session;
}
