package com.ticketing.system.unit.infrastructure.persistence.ProductionCompanyPersistence;

import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.adapter.out.persistence.MemoryProductionCompanyRepository;

class MemoryProductionCompanyRepositoryContractTest extends IProductionCompanyRepositoryContractTest {

    @Override
    protected ProductionCompanyRepository newRepository() {
        return new MemoryProductionCompanyRepository();
    }
}
