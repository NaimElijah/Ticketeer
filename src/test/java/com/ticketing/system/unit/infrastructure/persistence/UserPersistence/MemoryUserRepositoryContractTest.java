package com.ticketing.system.unit.infrastructure.persistence.UserPersistence;

import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.adapter.out.persistence.MemoryUserRepository;

class MemoryUserRepositoryContractTest extends IUserRepositoryContractTest {

    @Override
    protected UserRepository newRepository() {
        return new MemoryUserRepository();
    }
}
