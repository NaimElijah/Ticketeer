package com.ticketing.system.architecture.fixture.catalog.domain;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Offender: a domain class using Jackson without being, or sitting inside, an AttributeConverter. */
public class DomainTypeUsingJackson {

    private ObjectMapper mapper;
}
