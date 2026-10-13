package com.ticketing.system.unit.infrastructure.persistence.ActiveOrderPersistence;

import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.sales.adapter.out.persistence.MemoryActiveOrderRepository;

class MemoryActiveOrderRepositoryContractTest extends IActiveOrderRepositoryContractTest {

    @Override
    protected ActiveOrderRepository newRepository() {
        return new MemoryActiveOrderRepository();
    }
}
