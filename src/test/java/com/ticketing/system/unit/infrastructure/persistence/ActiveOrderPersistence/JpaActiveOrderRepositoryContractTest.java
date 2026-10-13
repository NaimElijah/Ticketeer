package com.ticketing.system.unit.infrastructure.persistence.ActiveOrderPersistence;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.sales.adapter.out.persistence.JpaActiveOrderRepository;
import com.ticketing.system.sales.adapter.out.persistence.SpringDataActiveOrderRepository;

/**
 * Runs the {@link IActiveOrderRepositoryContractTest} suite against the JPA adapter on an embedded H2
 * schema. {@code @ActiveProfiles("jpa")} activates {@link JpaActiveOrderRepository};
 * {@code @DataJpaTest} provides H2 + real {@code active_orders} and {@code active_order_items} tables.
 * Each test starts from an empty schema ({@link #cleanTable()}) so the suite is order-independent;
 * deleting an order cascades to its item collection. CI never touches a real database.
 */
@DataJpaTest
@ActiveProfiles("jpa")
@Import(JpaActiveOrderRepository.class)
class JpaActiveOrderRepositoryContractTest extends IActiveOrderRepositoryContractTest {

    @Autowired
    private JpaActiveOrderRepository repository;

    @Autowired
    private SpringDataActiveOrderRepository data;

    @BeforeEach
    void cleanTable() {
        data.deleteAll();
    }

    @Override
    protected ActiveOrderRepository newRepository() {
        return repository;
    }
}
