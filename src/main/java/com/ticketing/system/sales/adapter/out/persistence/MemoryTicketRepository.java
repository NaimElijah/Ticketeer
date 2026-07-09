package com.ticketing.system.sales.adapter.out.persistence;

import com.ticketing.system.shared.persistence.RepositoryLocks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.domain.Ticket;

/**
 * In-memory {@link TicketRepository}. Lets Spring wire
 * CatalogService / CheckoutService / EventManagementService /
 * MemberAccountService. {@code @Profile("!jpa")}: the {@code jpa} run/dev profile
 * swaps in {@link JpaTicketRepository} instead.
 *
 * <p>All keys are {@code int}, matching the {@code Ticket} fields end-to-end (the
 * former {@code String} eventId/zoneId on the port have been corrected to {@code int}).
 */
@Repository
@Profile("!jpa")
public class MemoryTicketRepository implements TicketRepository {

    private final Map<Integer, Ticket> ticketsById = new ConcurrentHashMap<>();
    private final RepositoryLocks<Integer> locks = new RepositoryLocks<>();

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
    public List<Ticket> findAvailableInZone(int eventId, int zoneId, int quantity) {
        List<Ticket> result = new ArrayList<>();
        for (Ticket t : ticketsById.values()) {
            if (t.getEventId() == eventId
                    && t.getZoneId() == zoneId
                    && t.isAvailable()
                    && quantity > result.size()) {
                result.add(t);
            }
        }
        return result;
    }

    @Override
    public int countAvailableInZone(int eventId, int zoneId) {
        int count = 0;
        for (Ticket t : ticketsById.values()) {
            if (t.getEventId() == eventId && t.getZoneId() == zoneId && t.isAvailable())
                count++;
        }
        return count;
    }
    




    @Override
    public List<Ticket> findByOrderReceiptId(int orderReceiptId) {
        List<Ticket> result = new ArrayList<>();
        for (Ticket t : ticketsById.values()) {
            if (t.getOrderReceiptId() == orderReceiptId) result.add(t);
        }
        return result;
    }

    @Override
    public List<Ticket> findByHolderUserId(int holderUserId) {
        Integer target = holderUserId;
        List<Ticket> result = new ArrayList<>();
        for (Ticket t : ticketsById.values()) {
            if (target.equals(t.getHolderUserId())) result.add(t);
        }
        return result;
    }

    @Override
    public void saveAll(List<Ticket> tickets) {
        if (tickets == null) return;
        for (Ticket t : tickets) save(t);
    }
}
