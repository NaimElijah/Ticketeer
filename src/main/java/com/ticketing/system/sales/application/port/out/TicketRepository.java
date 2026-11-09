package com.ticketing.system.sales.application.port.out;
import com.ticketing.system.sales.domain.Ticket;

import java.util.List;

import com.ticketing.system.shared.IRepository;

/** Aggregate-root entry point for the unified {@link Ticket} aggregate. */
public interface TicketRepository extends IRepository<Ticket, Integer> {

    /**
     * Returns the ticket with this id.
     *
     * @return the ticket, or {@code null} if there is none
     */
    Ticket findById(int ticktid);

    /**
     * Stores the ticket, inserting it or updating the stored ticket with the same id.
     *
     * @return {@code true}; both adapters return {@code true} after every save
     */
    boolean save(Ticket ticket);

    /**
     * Mints the id for a new ticket (#19). Ticket identity is assigned by the system, never by
     * the ticket issuer, and must never repeat an id already persisted — including ids written
     * before the current process started. Every creation path takes its ids from here.
     *
     * @return a positive id, strictly greater than every id previously minted or persisted
     */
    int nextId();

    /** Returns every ticket of the event, in any state (UC-8, UC-22); empty if there are none. */
    List<Ticket> findByEventId(int eventId);

    /** Returns the tickets that belong to one receipt (UC-10, UC-22, UC-16); empty if there are none. */
    List<Ticket> findByOrderReceiptId(int orderReceiptId);
}
  