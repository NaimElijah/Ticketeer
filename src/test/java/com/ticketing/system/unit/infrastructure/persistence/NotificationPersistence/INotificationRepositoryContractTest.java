package com.ticketing.system.unit.infrastructure.persistence.NotificationPersistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.ticketing.system.notifications.application.port.out.NotificationRepository;
import com.ticketing.system.notifications.domain.Notification;
import com.ticketing.system.notifications.domain.NotificationStatus;
import com.ticketing.system.notifications.domain.NotificationType;
import com.ticketing.system.testutil.RepositoryLockProbe;

/**
 * Contract every {@link NotificationRepository} implementation must satisfy. The Memory and
 * JPA adapters each subclass this with their own {@link #newRepository()} factory; the tests
 * are reused. The {@code data} round-trip test pins the acceptance: a heterogeneous payload
 * survives serialization to the JSON column and back.
 */
abstract class INotificationRepositoryContractTest {

    protected abstract NotificationRepository newRepository();

    private NotificationRepository repo;

    private static final LocalDateTime T = LocalDateTime.of(2026, 1, 1, 12, 0, 0);

    @BeforeEach
    void setUp() {
        repo = newRepository();
    }

    private Notification note(String id, int recipient, NotificationStatus status) {
        return new Notification(id, recipient, NotificationType.PURCHASE_CONFIRMED, status, "hello", T);
    }

    private Set<String> ids(List<Notification> ns) {
        return ns.stream().map(Notification::getId).collect(Collectors.toSet());
    }

    // Both adapters store a message cut to its varchar(255) column, so neither refuses one the other keeps.
    @Test
    void GivenANotificationComposedWithAVeryLongName_WhenSaved_ThenItIsStoredCutToItsColumn() {
        repo.save(new Notification("long", 5, NotificationType.EVENT_CANCELLED, NotificationStatus.PENDING,
                "Event '" + "x".repeat(300) + "' has been cancelled.", T));

        assertEquals(Notification.MAX_MESSAGE_LENGTH, repo.findById("long").getMessage().length());
    }

    @Test
    void GivenASavedNotification_WhenFindById_ThenReturnsTheSavedNotification() {
        repo.save(note("n1", 5, NotificationStatus.PENDING));

        Notification found = repo.findById("n1");
        assertNotNull(found);
        assertEquals(5, found.getRecipientUserId());
        assertEquals(NotificationType.PURCHASE_CONFIRMED, found.getType());
        assertEquals(NotificationStatus.PENDING, found.getStatus());
    }

    @Test
    void GivenNoSuchNotification_WhenFindById_ThenReturnsNull() {
        assertNull(repo.findById("ghost"));
    }

    @Test
    void GivenANullId_WhenFindById_ThenReturnsNull() {
        assertNull(repo.findById(null));
    }

    @Test
    void GivenALoadedNotificationMarkedSent_WhenSave_ThenUpdatesItInPlace() {
        repo.save(note("n1", 5, NotificationStatus.PENDING));
        Notification loaded = repo.findById("n1");
        loaded.markSent();
        repo.save(loaded);

        assertEquals(NotificationStatus.SENT, repo.findById("n1").getStatus());
    }

    @Test
    void GivenNotificationsOfSeveralRecipientsAndStatuses_WhenFindByRecipientAndStatus_ThenFiltersByRecipientAndStatus() {
        repo.save(note("n1", 5, NotificationStatus.PENDING));
        repo.save(note("n2", 5, NotificationStatus.SENT));
        repo.save(note("n3", 7, NotificationStatus.PENDING));

        assertEquals(Set.of("n1"), ids(repo.findByRecipientAndStatus(5, NotificationStatus.PENDING)));
        assertEquals(Set.of("n2"), ids(repo.findByRecipientAndStatus(5, NotificationStatus.SENT)));
        assertTrue(repo.findByRecipientAndStatus(99, NotificationStatus.PENDING).isEmpty());
    }

    @Test
    void GivenNotificationsOfTwoRecipients_WhenFindByRecipient_ThenReturnsAllForThatRecipient() {
        repo.save(note("n1", 5, NotificationStatus.PENDING));
        repo.save(note("n2", 5, NotificationStatus.SENT));
        repo.save(note("n3", 7, NotificationStatus.PENDING));

        assertEquals(Set.of("n1", "n2"), ids(repo.findByRecipient(5)));
        assertTrue(repo.findByRecipient(99).isEmpty());
    }

    @Test
    void GivenANotificationWithHeterogeneousData_WhenSaved_ThenDataRoundTrips() {
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", "ord-123");          // String
        data.put("total", 100);                  // Integer
        data.put("price", 12.5);                 // Double
        data.put("vip", true);                   // Boolean
        data.put("items", List.of("A", "B"));    // List
        data.put("meta", Map.of("seat", "12A")); // nested Map

        repo.save(new Notification("n1", 5, NotificationType.PURCHASE_CONFIRMED,
                NotificationStatus.PENDING, "hello", T, data));

        assertEquals(data, repo.findById("n1").getData());
    }

    @Test
    void GivenANotificationWithEmptyData_WhenSaved_ThenDataRoundTripsAsEmptyMap() {
        repo.save(note("n1", 5, NotificationStatus.PENDING)); // 6-arg ctor → empty data
        assertTrue(repo.findById("n1").getData().isEmpty());
    }

    // The JPA table holds the type under a check constraint that a migration must widen for each new
    // constant (ADR-007); reading back through a query flushes the insert, so a missing value fails here.
    @ParameterizedTest
    @EnumSource(NotificationType.class)
    void GivenANotificationOfEachType_WhenSaved_ThenItIsFoundWithThatType(NotificationType type) {
        repo.save(new Notification("typed", 5, type, NotificationStatus.PENDING, "hello", T));

        List<Notification> found = repo.findByRecipient(5);
        assertEquals(1, found.size());
        assertEquals(type, found.get(0).getType());
    }

    // IRepository's lock contract: reentrant, and released by one unlock per lock. The second
    // lockForUpdate would hang a non-reentrant lock, hence the timeout on a separate thread. The Jpa
    // adapter makes both calls no-ops (@Version guards its writes), so it passes trivially.
    @Test
    @Timeout(value = RepositoryLockProbe.TEST_TIMEOUT_SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void GivenALockTakenTwiceAndReleasedTwice_WhenAnotherThreadLocksTheSameId_ThenItGetsTheLock() throws Exception {
        repo.lockForUpdate("n1");
        repo.lockForUpdate("n1");
        repo.unlock("n1");
        repo.unlock("n1");

        RepositoryLockProbe.assertAnotherThreadCanLock(repo, "n1");
    }
}
