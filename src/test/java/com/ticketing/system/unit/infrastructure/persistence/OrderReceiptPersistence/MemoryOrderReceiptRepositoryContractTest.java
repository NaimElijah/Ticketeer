package com.ticketing.system.unit.infrastructure.persistence.OrderReceiptPersistence;

import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.adapter.out.persistence.MemoryOrderReceiptRepository;

class MemoryOrderReceiptRepositoryContractTest extends IOrderReceiptRepositoryContractTest {

    @Override
    protected OrderReceiptRepository newRepository() {
        return new MemoryOrderReceiptRepository();
    }
}
