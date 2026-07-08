package com.ticketing.system.unit.infrastructure.persistence.NotificationPersistence;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.notifications.application.port.out.NotificationRepository;
import com.ticketing.system.notifications.adapter.out.persistence.JpaNotificationRepository;
import com.ticketing.system.notifications.adapter.out.persistence.SpringDataNotificationRepository;

/**
 * Runs the {@link INotificationRepositoryContractTest} suite against the JPA adapter on an
 * embedded H2 schema. {@code @ActiveProfiles("jpa")} activates {@link JpaNotificationRepository};
 * {@code @DataJpaTest} provides H2 + a real {@code notifications} table (with the {@code data}
 * JSON column wired through {@code NotificationDataJsonConverter}). Each test starts from an
 * empty table ({@link #cleanTable()}) so the suite is order-independent. CI never touches a
 * real database.
 */
@DataJpaTest
@ActiveProfiles("jpa")
@Import(JpaNotificationRepository.class)
class JpaNotificationRepositoryContractTest extends INotificationRepositoryContractTest {

    @Autowired
    private JpaNotificationRepository repository;

    @Autowired
    private SpringDataNotificationRepository data;

    @BeforeEach
    void cleanTable() {
        data.deleteAll();
    }

    @Override
    protected NotificationRepository newRepository() {
        return repository;
    }
}
