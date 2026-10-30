package com.ticketing.system.sales.adapter.out.persistence;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.ticketing.system.sales.domain.Ticket;

/**
 * Spring Data JPA repository for {@link Ticket} — the auto-implemented SQL backing
 * {@link JpaTicketRepository}. The application layer never sees this type; it depends
 * only on the {@code TicketRepository} domain port.
 *
 * <p>The two single-key lookups are derived queries.
 */
public interface SpringDataTicketRepository extends JpaRepository<Ticket, Integer> {

    List<Ticket> findByEventId(int eventId);

    List<Ticket> findByOrderReceiptId(int orderReceiptId);

    /** Highest existing ticketId (0 when empty) — seeds the assigned-id sequence across restarts. */
    @Query("select coalesce(max(t.ticketId), 0) from Ticket t")
    int findMaxTicketId();
}
