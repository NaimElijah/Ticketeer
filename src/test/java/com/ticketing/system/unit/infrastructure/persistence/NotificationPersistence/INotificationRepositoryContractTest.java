package com.ticketing.system.unit.infrastructure.persistence.NotificationPersistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

import com.ticketing.system.notifications.application.port.out.NotificationRepository;
import com.ticketing.system.notifications.domain.Notification;
import com.ticketing.system.notifications.domain.NotificationStatus;
import com.ticketing.system.notifications.domain.NotificationType;

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

    @Test
    void save_thenFindById_returnsTheSavedNotification() {
        repo.save(note("n1", 5, NotificationStatus.PENDING));

        Notification found = repo.findById("n1");
        assertNotNull(found);
        assertEquals(5, found.getRecipientUserId());
        assertEquals(NotificationType.PURCHASE_CONFIRMED, found.getType());
        assertEquals(NotificationStatus.PENDING, found.getStatus());
    }

    @Test
    void findById_returnsNullWhenMissing() {
        assertNull(repo.findById("ghost"));
    }

    @Test
    void findById_returnsNullForNullId() {
        assertNull(repo.findById(null));
    }

    @Test
    void save_updatesALoadedNotificationInPlace() {
        repo.save(note("n1", 5, NotificationStatus.PENDING));
        Notification loaded = repo.findById("n1");
        loaded.markSent();
        repo.save(loaded);

        assertEquals(NotificationStatus.SENT, repo.findById("n1").getStatus());
    }

    @Test
    void findByRecipientAndStatus_filtersByRecipientAndStatus() {
        repo.save(note("n1", 5, NotificationStatus.PENDING));
        repo.save(note("n2", 5, NotificationStatus.SENT));
        repo.save(note("n3", 7, NotificationStatus.PENDING));

        assertEquals(Set.of("n1"), ids(repo.findByRecipientAndStatus(5, NotificationStatus.PENDING)));
        assertEquals(Set.of("n2"), ids(repo.findByRecipientAndStatus(5, NotificationStatus.SENT)));
        assertTrue(repo.findByRecipientAndStatus(99, NotificationStatus.PENDING).isEmpty());
    }

    @Test
    void findByRecipient_returnsAllForThatRecipient() {
        repo.save(note("n1", 5, NotificationStatus.PENDING));
        repo.save(note("n2", 5, NotificationStatus.SENT));
        repo.save(note("n3", 7, NotificationStatus.PENDING));

        assertEquals(Set.of("n1", "n2"), ids(repo.findByRecipient(5)));
        assertTrue(repo.findByRecipient(99).isEmpty());
    }

    @Test
    void heterogeneousData_roundTrips() {
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
    void emptyData_roundTripsAsEmptyMap() {
        repo.save(note("n1", 5, NotificationStatus.PENDING)); // 6-arg ctor → empty data
        assertTrue(repo.findById("n1").getData().isEmpty());
    }

    @Test
    void nextId_returnsDistinctValues() {
        assertNotEquals(repo.nextId(), repo.nextId());
    }
}
