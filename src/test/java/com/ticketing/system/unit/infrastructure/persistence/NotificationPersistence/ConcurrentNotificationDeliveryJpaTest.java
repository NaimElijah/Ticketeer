package com.ticketing.system.unit.infrastructure.persistence.NotificationPersistence;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.notifications.adapter.out.persistence.JpaNotificationRepository;
import com.ticketing.system.notifications.adapter.out.persistence.SpringDataNotificationRepository;
import com.ticketing.system.notifications.adapter.out.push.InMemoryNotificationService;
import com.ticketing.system.notifications.application.port.out.NotificationRepository;
import com.ticketing.system.notifications.application.service.NotificationDispatchService;
import com.ticketing.system.notifications.domain.Notification;
import com.ticketing.system.notifications.domain.NotificationStatus;
import com.ticketing.system.notifications.domain.NotificationType;
import com.ticketing.system.shared.dto.NotificationDTO;

/**
 * Defect 2 (#187): a concurrent {@code deliverPending} must not fail the other caller. A poll on one
 * device and a login on another can read the same PENDING row; whichever saves it second holds a stale
 * {@code @Version}, and its save fails. That row was delivered by the other caller, so this caller skips
 * it and goes on with the rest.
 *
 * <p>The interleaving is driven, not raced: {@link OtherCallerDeliversFirst} wraps the real
 * {@link JpaNotificationRepository} and, just before this caller saves the raced row, commits the other
 * caller's delivery of it from a fresh read. Runs on H2, outside any test transaction, as
 * {@code deliverPending} runs ({@code NOT_SUPPORTED}), so each save commits on its own.
 */
@DataJpaTest
@Import(JpaNotificationRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConcurrentNotificationDeliveryJpaTest {

    private static final int RECIPIENT = 42;
    private static final String RACED = "notif-raced";
    private static final String FIRST_OTHER = "notif-1";
    private static final String SECOND_OTHER = "notif-3";
    private static final LocalDateTime CREATED = LocalDateTime.of(2027, 7, 15, 17, 0);

    @Autowired private JpaNotificationRepository repository;
    @Autowired private SpringDataNotificationRepository data;

    @BeforeEach
    void storeThreePendingNotifications() {
        data.deleteAll();
        for (String id : List.of(FIRST_OTHER, RACED, SECOND_OTHER)) {
            repository.save(new Notification(id, RECIPIENT, NotificationType.DIRECT_MESSAGE,
                    NotificationStatus.PENDING, "New message from a system admin: Venue change", CREATED));
        }
    }

    @AfterEach
    void cleanUp() {
        data.deleteAll();
    }

    @Test
    void GivenAnotherCallerDeliversARowBetweenThisCallersReadAndSave_WhenDeliverPending_ThenItDoesNotFailAndTheOtherRowsAreDelivered() {
        OtherCallerDeliversFirst racing = new OtherCallerDeliversFirst(repository, RACED);
        NotificationDispatchService thisCaller = new NotificationDispatchService(racing,
                new InMemoryNotificationService());

        List<NotificationDTO> delivered = assertDoesNotThrow(() -> thisCaller.deliverPending(RECIPIENT),
                "a row another caller delivered first should be skipped, not fail this caller's delivery");

        assertAll(
                () -> assertTrue(racing.otherCallerDelivered(),
                        "arrangement: the other caller should have delivered the raced row first"),
                () -> assertEquals(Set.of(FIRST_OTHER, SECOND_OTHER), ids(delivered),
                        "this caller should return the rows it delivered, and not the one the other caller did"),
                () -> assertEquals(NotificationStatus.SENT, repository.findById(FIRST_OTHER).getStatus()),
                () -> assertEquals(NotificationStatus.SENT, repository.findById(SECOND_OTHER).getStatus()),
                () -> assertEquals(NotificationStatus.SENT, repository.findById(RACED).getStatus()));
    }

    private static Set<String> ids(List<NotificationDTO> notifications) {
        return notifications.stream().map(NotificationDTO::notificationId).collect(Collectors.toSet());
    }

    /**
     * The project's own port around the real adapter: the first save of {@code racedId} is preceded by
     * the other caller's whole delivery of that row (a fresh read, marked SENT, saved and committed), so
     * the save that follows carries the version this caller read, which is now stale.
     */
    private static final class OtherCallerDeliversFirst implements NotificationRepository {

        private final NotificationRepository delegate;
        private final String racedId;
        private boolean otherCallerDelivered;

        OtherCallerDeliversFirst(NotificationRepository delegate, String racedId) {
            this.delegate = delegate;
            this.racedId = racedId;
        }

        boolean otherCallerDelivered() {
            return otherCallerDelivered;
        }

        @Override
        public void save(Notification notification) {
            if (!otherCallerDelivered && racedId.equals(notification.getId())) {
                Notification otherCallersCopy = delegate.findById(racedId);
                otherCallersCopy.markSent();
                delegate.save(otherCallersCopy);
                otherCallerDelivered = true;
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
