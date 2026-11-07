package com.ticketing.system.unit.infrastructure.persistence.TicketPersistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.testutil.RepositoryLockProbe;

/**
 * Contract every {@link TicketRepository} implementation must satisfy. The Memory and
 * JPA adapters each subclass this with their own {@link #newRepository()} factory; the
 * tests are reused. Keys are {@code int} end-to-end (the former String/int mismatch on
 * the port is fixed).
 *
 * <p>A freshly constructed {@link Ticket} is {@code PAID} (tickets are created at payment
 * time).
 */
abstract class ITicketRepositoryContractTest {

    protected abstract TicketRepository newRepository();

    private TicketRepository repo;

    @BeforeEach
    void setUp() {
        repo = newRepository();
    }

    // --- helpers ---------------------------------------------------------------

    /** PAID ticket (the constructor default), no holder. */
    private Ticket ticket(int id, int eventId, int zoneId, int orderReceiptId) {
        return new Ticket(eventId, zoneId, orderReceiptId, null, 10.0, id, "BC-" + id);
    }

    private Set<Integer> ids(List<Ticket> tickets) {
        return tickets.stream().map(Ticket::getId).collect(Collectors.toSet());
    }

    // --- save / findById -------------------------------------------------------

    @Test
    void GivenSavedTicket_WhenFindById_ThenReturnsTheSavedTicket() {
        assertTrue(repo.save(ticket(1, 10, 2, 1)));

        Ticket found = repo.findById(1);
        assertNotNull(found);
        assertEquals(10, found.getEventId());
        assertEquals(2, found.getZoneId());
    }

    @Test
    void GivenNoSuchTicket_WhenFindById_ThenReturnsNull() {
        assertNull(repo.findById(999));
    }

    @Test
    void GivenLoadedTicketMarkedRefunded_WhenSave_ThenUpdatesItInPlace() {
        repo.save(ticket(1, 10, 2, 1)); // PAID
        Ticket loaded = repo.findById(1);
        loaded.markRefunded();
        repo.save(loaded);

        assertEquals(TicketStatus.REFUNDED, repo.findById(1).getStatus());
    }

    // The Jpa table holds the status under a check constraint that a migration must widen for each new
    // constant (ADR-007). A ticket starts PAID, from which AVAILABLE and RESERVED cannot be reached, so
    // those two are not stored here.
    @Test
    void GivenTicketsInEveryStatusReachableFromPaid_WhenSaved_ThenEachKeepsItsStatus() {
        Ticket paid = ticket(1, 10, 2, 1);
        Ticket issued = ticket(2, 10, 2, 1);
        issued.markIssued("BC-2");
        Ticket used = ticket(3, 10, 2, 1);
        used.markIssued("BC-3");
        used.markUsed();
        Ticket refunded = ticket(4, 10, 2, 1);
        refunded.markRefunded();
        Ticket voided = ticket(5, 10, 2, 1);
        voided.markVoided();
        List.of(paid, issued, used, refunded, voided).forEach(repo::save);

        Map<Integer, TicketStatus> stored = repo.findByEventId(10).stream()
                .collect(Collectors.toMap(Ticket::getId, Ticket::getStatus));

        assertEquals(Map.of(1, TicketStatus.PAID, 2, TicketStatus.ISSUED, 3, TicketStatus.USED,
                4, TicketStatus.REFUNDED, 5, TicketStatus.VOIDED), stored);
    }

    // --- findByEventId ---------------------------------------------------------

    @Test
    void GivenTicketsOfTwoEvents_WhenFindByEventId_ThenReturnsOnlyThatEventsTickets() {
        repo.save(ticket(1, 10, 2, 1));
        repo.save(ticket(2, 10, 3, 1));
        repo.save(ticket(3, 20, 2, 1));

        assertEquals(Set.of(1, 2), ids(repo.findByEventId(10)));
        assertEquals(Set.of(3), ids(repo.findByEventId(20)));
    }

    @Test
    void GivenNoTicketMatchingTheEvent_WhenFindByEventId_ThenIsEmpty() {
        repo.save(ticket(1, 10, 2, 1));
        assertTrue(repo.findByEventId(999).isEmpty());
    }

    // --- findByOrderReceiptId ----------------------------------------------------

    @Test
    void GivenTicketsOfTwoReceipts_WhenFindByOrderReceiptId_ThenReturnsOnlyThatReceiptsTickets() {
        repo.save(ticket(1, 10, 2, 100));
        repo.save(ticket(2, 10, 2, 100));
        repo.save(ticket(3, 10, 2, 200));

        assertEquals(Set.of(1, 2), ids(repo.findByOrderReceiptId(100)));
    }

    // --- nextId (#19) ----------------------------------------------------------

    @Test
    void GivenRepository_WhenNextIdCalledTwice_ThenIdsArePositiveDistinctAndIncreasing() {
        int first = repo.nextId();
        int second = repo.nextId();

        assertTrue(first > 0);
        assertTrue(second > first);
    }

    // IRepository's lock contract: reentrant, and released by one unlock per lock. The second
    // lockForUpdate would hang a non-reentrant lock, hence the timeout on a separate thread. The Jpa
    // adapter makes both calls no-ops (@Version guards its writes), so it passes trivially.
    @Test
    @Timeout(value = RepositoryLockProbe.TEST_TIMEOUT_SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void GivenALockTakenTwiceAndReleasedTwice_WhenAnotherThreadLocksTheSameId_ThenItGetsTheLock() throws Exception {
        repo.lockForUpdate(1);
        repo.lockForUpdate(1);
        repo.unlock(1);
        repo.unlock(1);

        RepositoryLockProbe.assertAnotherThreadCanLock(repo, 1);
    }
}
