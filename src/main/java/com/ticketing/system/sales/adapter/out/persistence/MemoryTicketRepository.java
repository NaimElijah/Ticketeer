package com.ticketing.system.sales.adapter.out.persistence;

import com.ticketing.system.shared.persistence.RepositoryLocks;
import com.ticketing.system.shared.persistence.PersistenceProfiles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.domain.Ticket;

/**
 * In-memory {@link TicketRepository}. Lets Spring wire
 * CatalogService / CheckoutService / EventManagementService /
 * MemberAccountService. Used only by the in-memory test suite ({@code test} with
 * {@code memory}); every other run gets {@link JpaTicketRepository}.
 *
 * <p>All keys are {@code int}, matching the {@code Ticket} fields end-to-end (the
 * former {@code String} eventId/zoneId on the port have been corrected to {@code int}).
 */
@Repository
@Profile(PersistenceProfiles.IN_MEMORY_ADAPTERS)
public class MemoryTicketRepository implements TicketRepository {

    private final Map<Integer, Ticket> ticketsById = new ConcurrentHashMap<>();
    private final RepositoryLocks<Integer> locks = new RepositoryLocks<>();
    private final AtomicInteger idSequence = new AtomicInteger(1);

    @Override
    public void lockForUpdate(Integer id) { locks.lock(id); }

    @Override
    public void unlock(Integer id) { locks.unlock(id); }

    @Override
    public boolean save(Ticket ticket) {
        ticketsById.put(ticket.getId(), ticket);
        return true;
    }

    @Override
    public int nextId() {
        return idSequence.getAndIncrement();
    }

    @Override
    public Ticket findById(int ticketId) {
        return ticketsById.get(ticketId);
    }

    @Override
    public List<Ticket> findByEventId(int eventId) {
        List<Ticket> result = new ArrayList<>();
        for (Ticket t : ticketsById.values()) {
            if (t.getEventId() == eventId)
                result.add(t);
        }
        return result;
    }

@Override
    public List<Ticket> findByOrderReceiptId(int orderReceiptId) {
        List<Ticket> result = new ArrayList<>();
        for (Ticket t : ticketsById.values()) {
            if (t.getOrderReceiptId() == orderReceiptId) result.add(t);
        }
        return result;
    }
}
