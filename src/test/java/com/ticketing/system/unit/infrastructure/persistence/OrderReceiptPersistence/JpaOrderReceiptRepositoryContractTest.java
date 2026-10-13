package com.ticketing.system.unit.infrastructure.persistence.OrderReceiptPersistence;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.adapter.out.persistence.JpaOrderReceiptRepository;
import com.ticketing.system.sales.adapter.out.persistence.SpringDataOrderReceiptRepository;

/**
 * Runs the {@link IOrderReceiptRepositoryContractTest} suite against the JPA adapter on an embedded
 * H2 schema. {@code @ActiveProfiles("jpa")} activates {@link JpaOrderReceiptRepository};
 * {@code @DataJpaTest} provides H2 + real {@code receipts}, {@code receipt_lines} and
 * {@code receipt_transactions} tables. Each test starts from an empty schema ({@link #cleanTable()})
 * so the suite is order-independent. CI never touches a real database.
 */
@DataJpaTest
@ActiveProfiles("jpa")
@Import(JpaOrderReceiptRepository.class)
class JpaOrderReceiptRepositoryContractTest extends IOrderReceiptRepositoryContractTest {

    @Autowired
    private JpaOrderReceiptRepository repository;

    @Autowired
    private SpringDataOrderReceiptRepository data;

    @BeforeEach
    void cleanTable() {
        data.deleteAll();
    }

    @Override
    protected OrderReceiptRepository newRepository() {
        return repository;
    }
}
