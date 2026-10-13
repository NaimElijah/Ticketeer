package com.ticketing.system.unit.infrastructure.persistence.TicketPersistence;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.adapter.out.persistence.JpaTicketRepository;
import com.ticketing.system.sales.adapter.out.persistence.SpringDataTicketRepository;

/**
 * Runs the {@link ITicketRepositoryContractTest} suite against the JPA adapter on an
 * embedded H2 schema. {@code @ActiveProfiles("jpa")} activates {@link JpaTicketRepository};
 * {@code @DataJpaTest} provides H2 + a real {@code tickets} table. Each test starts from an
 * empty table ({@link #cleanTable()}) so the suite is order-independent. CI never touches a
 * real database.
 */
@DataJpaTest
@ActiveProfiles("jpa")
@Import(JpaTicketRepository.class)
class JpaTicketRepositoryContractTest extends ITicketRepositoryContractTest {

    @Autowired
    private JpaTicketRepository repository;

    @Autowired
    private SpringDataTicketRepository data;

    @BeforeEach
    void cleanTable() {
        data.deleteAll();
    }

    @Override
    protected TicketRepository newRepository() {
        return repository;
    }
}
