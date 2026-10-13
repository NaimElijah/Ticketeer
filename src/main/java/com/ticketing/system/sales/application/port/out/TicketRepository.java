package com.ticketing.system.sales.application.port.out;
import com.ticketing.system.sales.domain.Ticket;

import java.util.List;

import com.ticketing.system.shared.IRepository;

// Aggregate-root entry point for the unified Ticket aggregate.
public interface TicketRepository extends IRepository<Ticket, Integer> {

    Ticket findById(int ticktid);

    boolean save(Ticket ticket);

    /**
     * Mints the id for a new ticket (#19). Ticket identity is assigned by the system, never by
     * the ticket issuer, and must never repeat an id already persisted — including ids written
     * before the current process started. Every creation path (checkout today, UC-20
     * pre-generation via {@link #saveAll} once it exists) takes its ids from here.
     *
     * @return a positive id, strictly greater than every id previously minted or persisted
     */
    int nextId();

    // UC-8 / UC-22 — render venue map / list company sales.
    // Keys are int: Ticket stores eventId/zoneId as int, so the port uses int end-to-end.
    List<Ticket> findByEventId(int eventId);

    // UC-9 (quantity-mode reservation) — atomically pick N AVAILABLE tickets in a zone.
    List<Ticket> findAvailableInZone(int eventId, int zoneId, int quantity);

    // UC-8 — count for standing-zone availability display.
    int countAvailableInZone(int eventId, int zoneId);

    // UC-10 / UC-22 / UC-16 — tickets that belong to one OrderReceipt.
    List<Ticket> findByOrderReceiptId(int orderReceiptId);

    // UC-16 / UC-22 / UC-31 — purchase history per buyer.
    List<Ticket> findByHolderUserId(int holderUserId);

    // UC-20 — bulk pre-generation when an Event's VenueMap is bound.
    void saveAll(List<Ticket> tickets);
}
  