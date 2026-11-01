package com.ticketing.system.unit.infrastructure.persistence.NotificationPersistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.notifications.adapter.out.persistence.JpaNotificationRepository;
import com.ticketing.system.notifications.adapter.out.persistence.SpringDataNotificationRepository;
import com.ticketing.system.notifications.adapter.out.push.InMemoryNotificationService;
import com.ticketing.system.notifications.application.port.in.INotificationService;
import com.ticketing.system.notifications.application.port.out.NotificationRepository;
import com.ticketing.system.notifications.application.service.NotificationDispatchService;
import com.ticketing.system.notifications.application.service.NotificationService;
import com.ticketing.system.notifications.domain.Notification;
import com.ticketing.system.notifications.domain.NotificationStatus;
import com.ticketing.system.notifications.domain.NotificationType;

/**
 * Item C (#187): when a session ends, each shown-but-unread notification goes back to PENDING on its
 * own transaction, as delivery does. A store failure part-way leaves the rows already sent back
 * PENDING, so the next delivery still shows them; one transaction for the whole send-back would roll
 * them all back to SENT, and no later delivery would show any of them.
 *
 * <p>The service runs as the Spring bean, so its own transaction settings apply. {@link SecondSaveFails}
 * wraps the real {@link JpaNotificationRepository} and fails the second save, as the store would. Runs
 * on H2, outside any test transaction, so what the service committed is what the assertions read.
 */
@DataJpaTest
@Import({JpaNotificationRepository.class, NotificationService.class, NotificationDispatchService.class,
        InMemoryNotificationService.class, SendBackOnSessionEndJpaTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SendBackOnSessionEndJpaTest {

    private static final int RECIPIENT = 42;
    private static final String FIRST = "notif-1";
    private static final String SECOND = "notif-2";
    private static final Instant T0 = Instant.parse("2027-07-15T17:00:00Z");

    @Autowired private INotificationService notificationService;
    @Autowired private JpaNotificationRepository repository;
    @Autowired private SpringDataNotificationRepository data;

    @BeforeEach
    void storeTwoSentNotifications() {
        data.deleteAll();
        for (String id : List.of(FIRST, SECOND)) {
            repository.save(new Notification(id, RECIPIENT, NotificationType.DIRECT_MESSAGE,
                    NotificationStatus.SENT, "New message from a system admin: Venue change",
                    LocalDateTime.ofInstant(T0, ZoneOffset.UTC)));
        }
    }

    @AfterEach
    void cleanUp() {
        data.deleteAll();
    }

    @Test
    void GivenTheSecondOfTwoSavesFails_WhenRevertSentToPending_ThenTheFirstStaysPendingAndCommitted() {
        assertThrows(DataAccessResourceFailureException.class,
                () -> notificationService.revertSentToPending(RECIPIENT));

        assertEquals(NotificationStatus.PENDING, repository.findById(FIRST).getStatus(),
                "the row sent back before the failure should stay PENDING, so the next delivery shows it");
        assertEquals(NotificationStatus.SENT, repository.findById(SECOND).getStatus());
    }

    @TestConfiguration
    static class Wiring {

        @Bean
        Clock clock() {
            return Clock.fixed(T0, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        NotificationRepository secondSaveFails(JpaNotificationRepository real) {
            return new SecondSaveFails(real);
        }
    }

    /** The project's own port around the real adapter: every save after the first fails. */
    private static final class SecondSaveFails implements NotificationRepository {

        private final NotificationRepository delegate;
        private int saves;

        SecondSaveFails(NotificationRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public void save(Notification notification) {
            saves++;
            if (saves > 1) {
                throw new DataAccessResourceFailureException("store unavailable");
            }
            delegate.save(notification);
        }

        @Override
        public Notification findById(String notificationId) {
            return delegate.findById(notificationId);
        }

        @Override
        public List<Notification> findByRecipientAndStatus(int recipientUserId, NotificationStatus status) {
            return delegate.findByRecipientAndStatus(recipientUserId, status);
        }

        @Override
        public List<Notification> findByRecipient(int recipientUserId) {
            return delegate.findByRecipient(recipientUserId);
        }

        @Override
        public void lockForUpdate(String id) {
            delegate.lockForUpdate(id);
        }

        @Override
        public void unlock(String id) {
            delegate.unlock(id);
        }
    }
}
