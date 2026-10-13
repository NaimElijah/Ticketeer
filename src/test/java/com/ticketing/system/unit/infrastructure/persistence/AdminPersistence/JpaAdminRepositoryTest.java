package com.ticketing.system.unit.infrastructure.persistence.AdminPersistence;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.identity.adapter.out.persistence.JpaAdminRepository;
import com.ticketing.system.identity.adapter.out.persistence.SpringDataAdminRepository;

/**
 * Runs the {@link IAdminRepositoryContractTest} suite against the JPA adapter on an
 * embedded H2 schema. {@code @ActiveProfiles("jpa")} activates {@link JpaAdminRepository};
 * {@code @DataJpaTest} provides H2 + a real {@code admins} table.
 *
 * <p>Each contract test starts from an empty table ({@link #cleanTable()}) so the suite is
 * order-independent — the Memory subclass gets a fresh repo per test, and this gives the
 * JPA adapter the same clean slate. CI never touches a real database.
 */
@DataJpaTest
@ActiveProfiles("jpa")
@Import(JpaAdminRepository.class)
class JpaAdminRepositoryTest extends IAdminRepositoryContractTest {

    @Autowired
    private JpaAdminRepository repository;

    @Autowired
    private SpringDataAdminRepository data;

    @BeforeEach
    void cleanTable() {
        data.deleteAll();
    }

    @Override
    protected AdminRepository newRepository() {
        return repository;
    }
}
