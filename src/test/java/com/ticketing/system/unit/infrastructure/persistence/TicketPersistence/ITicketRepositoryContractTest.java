package com.ticketing.system.unit.infrastructure.persistence.TicketPersistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.sales.domain.TicketStatus;

/**
 * Contract every {@link TicketRepository} implementation must satisfy. The Memory and
 * JPA adapters each subclass this with their own {@link #newRepository()} factory; the
 * tests are reused. Keys are {@code int} end-to-end (the former String/int mismatch on
 * the port is fixed).
 *
 * <p>A freshly constructed {@link Ticket} is {@code PAID} (tickets are created at payment
 * time); {@link #available} flips one to {@code AVAILABLE} via {@code release()} so the
 * zone-availability queries have something to find.
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

    /** AVAILABLE ticket in the given event/zone. */
    private Ticket available(int id, int eventId, int zoneId) {
        Ticket t = ticket(id, eventId, zoneId, 1);
        t.release();
        return t;
    }

    private Set<Integer> ids(List<Ticket> tickets) {
        return tickets.stream().map(Ticket::getId).collect(Collectors.toSet());
    }

    // --- save / findById -------------------------------------------------------

    @Test
    void save_thenFindById_returnsTheSavedTicket() {
        assertTrue(repo.save(ticket(1, 10, 2, 1)));

        Ticket found = repo.findById(1);
        assertNotNull(found);
        assertEquals(10, found.getEventId());
        assertEquals(2, found.getZoneId());
    }

    @Test
    void findById_returnsNullWhenMissing() {
        assertNull(repo.findById(999));
    }

    @Test
    void save_updatesALoadedTicketInPlace() {
        repo.save(ticket(1, 10, 2, 1)); // PAID
        Ticket loaded = repo.findById(1);
        loaded.markRefunded();
        repo.save(loaded);

        assertEquals(TicketStatus.REFUNDED, repo.findById(1).getStatus());
    }

    // --- findByEventId ---------------------------------------------------------

    @Test
    void findByEventId_returnsOnlyThatEvent() {
        repo.save(ticket(1, 10, 2, 1));
        repo.save(ticket(2, 10, 3, 1));
        repo.save(ticket(3, 20, 2, 1));

        assertEquals(Set.of(1, 2), ids(repo.findByEventId(10)));
        assertEquals(Set.of(3), ids(repo.findByEventId(20)));
    }

    @Test
    void findByEventId_emptyWhenNoMatch() {
        repo.save(ticket(1, 10, 2, 1));
        assertTrue(repo.findByEventId(999).isEmpty());
    }

    // --- findAvailableInZone / countAvailableInZone ----------------------------

    @Test
    void findAvailableInZone_returnsOnlyAvailableInThatEventAndZone() {
        repo.save(available(1, 10, 2));
        repo.save(available(2, 10, 2));
        repo.save(ticket(3, 10, 2, 1));   // PAID — not available
        repo.save(available(4, 10, 3));   // wrong zone
        repo.save(available(5, 20, 2));   // wrong event

        assertEquals(Set.of(1, 2), ids(repo.findAvailableInZone(10, 2, 10)));
    }

    @Test
    void findAvailableInZone_respectsQuantityLimit() {
        repo.save(available(1, 10, 2));
        repo.save(available(2, 10, 2));
        repo.save(available(3, 10, 2));

        List<Ticket> picked = repo.findAvailableInZone(10, 2, 2);
        assertEquals(2, picked.size());
        assertTrue(picked.stream().allMatch(t -> t.isAvailable() && t.getEventId() == 10 && t.getZoneId() == 2));
    }

    @Test
    void findAvailableInZone_emptyWhenQuantityZero() {
        repo.save(available(1, 10, 2));
        assertTrue(repo.findAvailableInZone(10, 2, 0).isEmpty());
    }

    @Test
    void countAvailableInZone_countsOnlyAvailableInThatEventAndZone() {
        repo.save(available(1, 10, 2));
        repo.save(available(2, 10, 2));
        repo.save(ticket(3, 10, 2, 1));   // PAID
        repo.save(available(4, 10, 3));   // wrong zone
        repo.save(available(5, 20, 2));   // wrong event

        assertEquals(2, repo.countAvailableInZone(10, 2));
        assertEquals(0, repo.countAvailableInZone(99, 99));
    }

    // --- findByOrderReceiptId / findByHolderUserId -----------------------------

    @Test
    void findByOrderReceiptId_returnsOnlyThatReceipt() {
        repo.save(ticket(1, 10, 2, 100));
        repo.save(ticket(2, 10, 2, 100));
        repo.save(ticket(3, 10, 2, 200));

        assertEquals(Set.of(1, 2), ids(repo.findByOrderReceiptId(100)));
    }

    @Test
    void findByHolderUserId_returnsMatchingAndExcludesNullHolder() {
        Ticket held1 = ticket(1, 10, 2, 1);
        held1.setHolderUserId(5);
        Ticket held2 = ticket(2, 10, 2, 1);
        held2.setHolderUserId(5);
        repo.save(held1);
        repo.save(held2);
        repo.save(ticket(3, 10, 2, 1)); // holder null

        assertEquals(Set.of(1, 2), ids(repo.findByHolderUserId(5)));
        assertTrue(repo.findByHolderUserId(7).isEmpty());
    }

    // --- saveAll ---------------------------------------------------------------

    @Test
    void saveAll_persistsEveryTicket() {
        repo.saveAll(List.of(ticket(1, 10, 2, 1), ticket(2, 10, 2, 1), ticket(3, 20, 2, 1)));

        assertNotNull(repo.findById(1));
        assertNotNull(repo.findById(2));
        assertNotNull(repo.findById(3));
        assertEquals(Set.of(1, 2), ids(repo.findByEventId(10)));
    }
}
