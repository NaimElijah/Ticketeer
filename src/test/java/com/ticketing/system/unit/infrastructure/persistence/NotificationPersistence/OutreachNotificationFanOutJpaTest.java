package com.ticketing.system.unit.infrastructure.persistence.NotificationPersistence;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
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
import com.ticketing.system.shared.event.OutreachSentNotice;

/**
 * An outreach's alerts are stored one transaction each (ADR-019, #187): when one fails to store, the
 * alerts stored before it stay, and the fan-out stops. Runs the real services, through their
 * transactional proxies, on the JPA adapter and H2, outside any test transaction, as the
 * {@code notify-} thread runs them. {@link FailsForRecipient} wraps the real adapter and fails the
 * save of one recipient's alert.
 */
@DataJpaTest
@Import({JpaNotificationRepository.class, NotificationDispatchService.class, NotificationService.class,
        InMemoryNotificationService.class, OutreachNotificationFanOutJpaTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutreachNotificationFanOutJpaTest {

    private static final int FIRST = 7;
    private static final int FAILING = 8;
    private static final int THIRD = 9;
    private static final Instant NOW = Instant.parse("2027-07-15T17:00:00Z");

    @TestConfiguration
    static class Wiring {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        NotificationRepository failingForOneRecipient(JpaNotificationRepository jpa) {
            return new FailsForRecipient(jpa, FAILING);
        }
    }

    @Autowired private INotificationService notifications;
    @Autowired private JpaNotificationRepository repository;
    @Autowired private SpringDataNotificationRepository data;

    @BeforeEach
    @AfterEach
    void emptyTable() {
        data.deleteAll();
    }

    @Test
    void GivenTheSecondRecipientsAlertFailsToStore_WhenNotifyOutreach_ThenTheFirstIsKeptAndTheThirdIsNeverStored() {
        assertThrows(DataAccessResourceFailureException.class, () -> notifications.notifyOutreach(List.of(
                new OutreachSentNotice.Recipient(FIRST, "conv-7"),
                new OutreachSentNotice.Recipient(FAILING, "conv-8"),
                new OutreachSentNotice.Recipient(THIRD, "conv-9")),
                "a system admin", "Venue change", "Hall B."));

        assertAll(
                () -> assertEquals(1, repository.findByRecipient(FIRST).size(),
                        "the alert stored before the failure is committed and kept"),
                () -> assertTrue(repository.findByRecipient(FAILING).isEmpty(), "the failed alert is not stored"),
                () -> assertTrue(repository.findByRecipient(THIRD).isEmpty(),
                        "the fan-out stops at the failure, so the third alert is never stored"));
    }

    /** The project's own port around the real adapter, failing the save of one recipient's alert. */
    private static final class FailsForRecipient implements NotificationRepository {

        private final NotificationRepository delegate;
        private final int failingRecipient;

        FailsForRecipient(NotificationRepository delegate, int failingRecipient) {
            this.delegate = delegate;
            this.failingRecipient = failingRecipient;
        }

        @Override
        public void save(Notification notification) {
            if (notification.getRecipientUserId() == failingRecipient) {
                throw new DataAccessResourceFailureException("connection refused");
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
