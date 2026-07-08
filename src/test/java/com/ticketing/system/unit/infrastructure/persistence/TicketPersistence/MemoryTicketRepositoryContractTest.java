package com.ticketing.system.unit.infrastructure.persistence.TicketPersistence;

import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.adapter.out.persistence.MemoryTicketRepository;

class MemoryTicketRepositoryContractTest extends ITicketRepositoryContractTest {

    @Override
    protected TicketRepository newRepository() {
        return new MemoryTicketRepository();
    }
}
