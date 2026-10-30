package com.ticketing.system.architecture.fixture.sales.domain;

import com.ticketing.system.architecture.fixture.catalog.domain.CatalogDomainType;

/**
 * Offender: sales may depend on catalog, but a sales domain class may not; it must reach catalog
 * through a port.
 */
public class SalesDomainTypeUsingCatalogDomain {

    private CatalogDomainType catalogType;
}
