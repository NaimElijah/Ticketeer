package com.ticketing.system.unit.infrastructure.persistence.UserPersistence;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.adapter.out.persistence.JpaUserRepository;
import com.ticketing.system.identity.adapter.out.persistence.SpringDataUserRepository;
import com.ticketing.system.identity.domain.User;

/**
 * Runs the {@link IUserRepositoryContractTest} suite against the JPA adapter on an embedded H2
 * schema. {@code @Import} adds {@link JpaUserRepository}; {@code @DataJpaTest}
 * provides H2 + real {@code users}, {@code company_appointments} and {@code appointment_permissions}
 * tables. Each test starts from an empty schema ({@link #cleanTable()}) so the suite is
 * order-independent; deleting users cascades to their owned appointments and permissions. CI never
 * touches a real database.
 */
@DataJpaTest
@Import(JpaUserRepository.class)
class JpaUserRepositoryContractTest extends IUserRepositoryContractTest {

    @Autowired
    private JpaUserRepository repository;

    @Autowired
    private SpringDataUserRepository data;

    @BeforeEach
    void cleanTable() {
        data.deleteAll();
    }

    @Override
    protected UserRepository newRepository() {
        return repository;
    }

    // A new adapter instance models a restarted process (#19, as JpaTicketRepositoryContractTest does for
    // tickets): the id sequence must resume above the rows a previous boot persisted, not start at 1.
    @Test
    void GivenUsersPersistedByAPreviousBoot_WhenAFreshAdapterMintsAnId_ThenItIsAboveTheExistingMax() {
        data.saveAllAndFlush(List.of(user(1), user(2), user(3)));

        int id = new JpaUserRepository(data).nextId();

        assertTrue(id > 3, "user id after a restart: " + id);
    }

    private static User user(int id) {
        return new User(id, "user" + id, "user" + id + "@example.com", "HASH", 20);
    }
}
