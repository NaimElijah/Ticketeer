package com.ticketing.system.sales.adapter.out.persistence;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.domain.Ticket;

/**
 * JPA-backed {@link TicketRepository} — active only in the {@code jpa} run/dev profile.
 * Adapts the domain port onto Spring Data ({@link SpringDataTicketRepository}); the
 * application layer depends only on {@code TicketRepository}, never on Spring Data.
 *
 * <p>{@code lockForUpdate}/{@code unlock} are no-ops: concurrent writes are guarded by
 * {@code Ticket}'s {@code @Version} optimistic lock within the surrounding transaction
 * (per the {@code IRepository} contract — JPA replaces the in-memory write-lock with
 * version checks).
 *
 * <p>{@link #save} delegates to {@code data.save}: a fresh ticket (issuer-assigned id,
 * version {@code null}) is inserted, a loaded-then-mutated ticket (reserve / markPaid /
 * markIssued / ...) is updated under its {@code @Version} check — the only two flows
 * tickets ever see (unique issuer ids mean a fresh instance never reuses a live id).
 * {@link #saveAll} delegates to {@code data.saveAll} for UC-20 bulk pre-generation. The
 * writes are {@code @Transactional} so the adapter is self-sufficient before the service
 * layer gains transactions (#359); reads inherit Spring Data's read-only tx.
 */
@Repository
@Profile("jpa")
public class JpaTicketRepository implements TicketRepository {

    private final SpringDataTicketRepository data;

    public JpaTicketRepository(SpringDataTicketRepository data) {
        this.data = data;
    }

    @Override
    public void lockForUpdate(Integer id) { /* no-op — @Version optimistic locking */ }

    @Override
    public void unlock(Integer id) { /* no-op */ }

    @Override
    @Transactional
    public boolean save(Ticket ticket) {
        data.save(ticket);
        return true;
    }

    @Override
    public Ticket findById(int ticketId) {
        return data.findById(ticketId).orElse(null);
    }

    @Override
    public List<Ticket> findByEventId(int eventId) {
        return data.findByEventId(eventId);
    }

    @Override
    public List<Ticket> findAvailableInZone(int eventId, int zoneId, int quantity) {
        if (quantity <= 0) {
            return List.of();
        }
        return data.findAvailableInZone(eventId, zoneId, Limit.of(quantity));
    }

    @Override
    public int countAvailableInZone(int eventId, int zoneId) {
        return (int) data.countAvailableInZone(eventId, zoneId);
    }

    @Override
    public List<Ticket> findByOrderReceiptId(int orderReceiptId) {
        return data.findByOrderReceiptId(orderReceiptId);
    }

    @Override
    public List<Ticket> findByHolderUserId(int holderUserId) {
        return data.findByHolderUserId(holderUserId);
    }

    @Override
    @Transactional
    public void saveAll(List<Ticket> tickets) {
        if (tickets == null) {
            return;
        }
        data.saveAll(tickets);
    }
}
