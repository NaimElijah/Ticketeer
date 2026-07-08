package com.ticketing.system.Core.Domain.Tickets;

import java.util.List;

import com.ticketing.system.shared.IRepository;

// Aggregate-root entry point for the unified Ticket aggregate.
public interface ITicketRepository extends IRepository<Ticket, Integer> {

    Ticket findById(int ticktid);

    boolean save(Ticket ticket);

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
  