package com.ticketing.system.unit.infrastructure.persistence.AdminPersistence;

import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.identity.adapter.out.persistence.MemoryAdminRepository;

/** Runs the {@link IAdminRepositoryContractTest} suite against the in-memory adapter. */
class MemoryAdminRepositoryTest extends IAdminRepositoryContractTest {

    @Override
    protected AdminRepository newRepository() {
        return new MemoryAdminRepository();
    }
}
