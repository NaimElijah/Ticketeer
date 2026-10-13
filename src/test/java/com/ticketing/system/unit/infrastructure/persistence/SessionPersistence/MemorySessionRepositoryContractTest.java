package com.ticketing.system.unit.infrastructure.persistence.SessionPersistence;

import java.time.Clock;

import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.adapter.out.persistence.MemorySessionRepository;

class MemorySessionRepositoryContractTest extends ISessionRepositoryContractTest {

    @Override
    protected SessionRepository newRepository() {
        return new MemorySessionRepository(Clock.systemUTC());
    }
}
