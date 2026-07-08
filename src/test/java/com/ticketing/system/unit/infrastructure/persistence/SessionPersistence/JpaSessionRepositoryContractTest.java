package com.ticketing.system.unit.infrastructure.persistence.SessionPersistence;

import java.time.Clock;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.adapter.out.persistence.JpaSessionRepository;
import com.ticketing.system.identity.adapter.out.persistence.SpringDataSessionRepository;

/**
 * Runs the {@link ISessionRepositoryContractTest} suite against the JPA adapter on an
 * embedded H2 schema. {@code @ActiveProfiles("jpa")} activates {@link JpaSessionRepository};
 * {@code @DataJpaTest} provides H2 + a real {@code sessions} table.
 *
 * <p>The repository is Spring-built (via {@code @Import}) so its {@code @PersistenceContext}
 * EntityManager — needed by {@code save}'s {@code merge} — is injected; the {@code @DataJpaTest}
 * slice doesn't load {@code SecurityConfig}, so {@link TestClockConfig} supplies the {@code Clock}
 * (system-UTC, matching the Memory contract test). Each test starts from an empty table
 * ({@link #cleanTable()}) so the suite is order-independent. CI never touches a real database.
 */
@DataJpaTest
@ActiveProfiles("jpa")
@Import({JpaSessionRepository.class, JpaSessionRepositoryContractTest.TestClockConfig.class})
class JpaSessionRepositoryContractTest extends ISessionRepositoryContractTest {

    @Autowired
    private JpaSessionRepository repository;

    @Autowired
    private SpringDataSessionRepository data;

    @BeforeEach
    void cleanTable() {
        data.deleteAll();
    }

    @Override
    protected SessionRepository newRepository() {
        return repository;
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
