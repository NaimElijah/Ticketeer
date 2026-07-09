package com.ticketing.system.catalog.application.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.catalog.application.port.in.InventoryCommandPort;
import com.ticketing.system.catalog.application.port.in.InventoryLineDTO;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.InventorySelection;
import com.ticketing.system.catalog.domain.InventoryZone;
import com.ticketing.system.catalog.domain.Seat;
import com.ticketing.system.catalog.domain.SeatStatus;
import com.ticketing.system.catalog.domain.SeatedZone;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.shared.domain.policy.PurchaseContext;
import com.ticketing.system.shared.domain.policy.PurchaseStage;
import com.ticketing.system.shared.exception.ConcurrentReservationException;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.EventNotOnSaleException;
import com.ticketing.system.shared.exception.InsufficientInventoryException;

/**
 * Catalog application service that owns all Event/InventoryZone inventory mutation on behalf of the
 * sales context, implementing {@link InventoryCommandPort}. The reserve/release/confirm/return logic
 * (event lock, load, policy check, domain mutation, save) was relocated here verbatim from sales'
 * {@code ReservationService}/{@code CheckoutService}/{@code RefundService} so that no sales
 * application service touches a {@code catalog.domain} type. The exact lock acquisition order and
 * transactional save points are preserved — see {@link InventoryCommandPort} for the locking contract.
 */
@Service
@Slf4j
public class InventoryService implements InventoryCommandPort {

    // Catalog aggregate-root port for the Event aggregate (load/save + the buyer-operation lock).
    private final EventRepository eventRepository;
    // Organization port used to resolve a company's purchase policy for effective-policy validation.
    private final ProductionCompanyRepository companyRepository;

    /**
     * Wires the catalog inventory service to the event store and the organization company port.
     *
     * @param eventRepository   the Event aggregate-root port
     * @param companyRepository the organization company port (for company purchase policies)
     */
    public InventoryService(EventRepository eventRepository, ProductionCompanyRepository companyRepository) {
        this.eventRepository = eventRepository;       // store the event port
        this.companyRepository = companyRepository;   // store the company port
    }

    /** {@inheritDoc} SELF-LOCKING single-event reserve; validates the RESERVE-stage effective policy first. */
    @Override
    public double reserve(int eventId, int zoneId, InventorySelectionDTO selection, String orderKey,
            int buyerId, Integer buyerAge, int totalQuantityForPolicy) {
        // Acquire the shared event buyer-lock INSIDE the port; the caller already holds the active-order
        // lock, so the global order-before-event acquisition order is preserved.
        eventRepository.lockForBuyerOperation(eventId);
        try {
            Event event = getEventOrThrow(eventId);                 // load + existence check
            InventoryZone zone = getZoneOrThrow(event, zoneId);     // venue map + zone existence check
            // Effective purchase policy (company AND event) at the RESERVE stage — max + (member) age
            // are enforced; minimum and unknown (guest) age are deferred to checkout.
            PurchaseContext context = new PurchaseContext(
                    buyerId, buyerAge, eventId, event.getCompanyId(), totalQuantityForPolicy, PurchaseStage.RESERVE);
            ProductionCompany company = companyRepository.getCompanyById(event.getCompanyId()); // owning company
            event.validateEffectivePolicy(company == null ? null : company.getPurchasePolicy(), context); // throws on rejection
            double pricePerTicket = zone.getprice();                // unit price for the caller's cart line
            event.reserveInventory(zoneId, toDomainSelection(selection, orderKey)); // mutate inventory (ownership-stamped)
            eventRepository.save(event);                            // persist under the held lock
            return pricePerTicket;                                  // hand the price back to sales
        } finally {
            eventRepository.unlockBuyerOperation(eventId);          // always release the buyer-lock
        }
    }

    /** {@inheritDoc} SELF-LOCKING single-event release; returns the unit price for rollback rebuilds. */
    @Override
    public double release(int eventId, int zoneId, InventorySelectionDTO selection, String orderKey) {
        eventRepository.lockForBuyerOperation(eventId);             // event lock inside the port
        try {
            Event event = getEventOrThrow(eventId);                 // load + existence check
            InventoryZone zone = getZoneOrThrow(event, zoneId);     // zone existence check + price source
            double pricePerTicket = zone.getprice();                // captured for the caller's rollback re-add
            event.releaseInventory(zoneId, toDomainSelection(selection, orderKey)); // release the held selection
            eventRepository.save(event);                            // persist under the held lock
            return pricePerTicket;                                  // unit price back to sales
        } finally {
            eventRepository.unlockBuyerOperation(eventId);          // release the buyer-lock
        }
    }

    /** {@inheritDoc} SELF-LOCKING re-reserve WITHOUT policy — compensates a remove that already released. */
    @Override
    public void restore(int eventId, int zoneId, InventorySelectionDTO selection, String orderKey) {
        eventRepository.lockForBuyerOperation(eventId);             // event lock inside the port
        try {
            Event event = getEventOrThrow(eventId);                 // load + existence check
            event.reserveInventory(zoneId, toDomainSelection(selection, orderKey)); // re-reserve (no policy re-check)
            eventRepository.save(event);                            // persist under the held lock
        } finally {
            eventRepository.unlockBuyerOperation(eventId);          // release the buyer-lock
        }
    }

    /** {@inheritDoc} SELF-LOCKING per event, fully best-effort return of SOLD inventory to stock. */
    @Override
    public void returnSoldToStock(List<InventoryLineDTO> lines) {
        // Group the flat refund lines by event then zone so each event is locked and saved once.
        Map<Integer, Map<Integer, List<InventoryLineDTO>>> byEventThenZone = groupByEventAndZone(lines);

        for (Map.Entry<Integer, Map<Integer, List<InventoryLineDTO>>> eventEntry : byEventThenZone.entrySet()) {
            int eventId = eventEntry.getKey();
            // Hold the buyer lock around load -> mutate -> save for this event. The whole per-event block is
            // best-effort: any failure is logged and swallowed, because the gateway refund and the
            // receipt/ticket flips have already committed — a stock-return hiccup must never fail the refund.
            try {
                eventRepository.lockForBuyerOperation(eventId);     // self-lock this event
                try {
                    Event event = eventRepository.findById(eventId); // load (throws under memory if absent)
                    boolean anyReturned = false;                    // track whether anything actually returned
                    for (Map.Entry<Integer, List<InventoryLineDTO>> zoneEntry : eventEntry.getValue().entrySet()) {
                        int zoneId = zoneEntry.getKey();
                        try {
                            // SOLD tickets are returned WITHOUT an order key (ownership isn't checked here).
                            event.returnSoldToStock(zoneId, toStockSelection(zoneEntry.getValue()));
                            anyReturned = true;                     // at least one zone returned
                        } catch (RuntimeException e) {
                            log.warn("Refund: failed to return zone {} of event {} to stock", zoneId, eventId, e);
                        }
                    }
                    if (anyReturned) {
                        eventRepository.save(event);                // persist only if something changed
                    }
                } finally {
                    eventRepository.unlockBuyerOperation(eventId);  // release this event's lock
                }
            } catch (RuntimeException e) {
                log.warn("Refund: failed to return event {} inventory to stock", eventId, e);
            }
        }
    }

    /** {@inheritDoc} Pure read — final per-ticket price via the event's discount policy. */
    @Override
    public double priceTicket(int eventId, int eventQuantity, double priceAtReservation, LocalDateTime now) {
        Event event = eventRepository.findById(eventId);            // load the event
        if (event == null) {
            throw new EventNotFoundException("Event not found: " + eventId); // preserve the original failure
        }
        return event.calculatePriceforoneticket(eventQuantity, priceAtReservation, now); // discount-policy price
    }

    /** {@inheritDoc} Pure read — the event's display name. */
    @Override
    public String eventName(int eventId) {
        Event event = eventRepository.findById(eventId);            // load the event
        if (event == null) {
            throw new EventNotFoundException("Event not found: " + eventId); // preserve the original failure
        }
        return event.getName();                                    // the event's name
    }

    /** {@inheritDoc} Pure read — validates the effective (company AND event) policy at CHECKOUT stage. */
    @Override
    public void validatePurchasePolicy(int eventId, int buyerId, Integer buyerAge, int quantity) {
        Event event = eventRepository.findById(eventId);           // load the event
        if (event == null) {
            throw new EventNotFoundException(eventId);             // matches CheckoutService's original throw
        }
        // CHECKOUT-stage context (every policy rule enforced): the 5-arg constructor defaults the stage.
        PurchaseContext context = new PurchaseContext(buyerId, buyerAge, event.getId(), event.getCompanyId(), quantity);
        ProductionCompany company = companyRepository.getCompanyById(event.getCompanyId()); // owning company
        event.validateEffectivePolicy(company == null ? null : company.getPurchasePolicy(), context); // throws on rejection
    }

    /** {@inheritDoc} Pure read — each event must exist and be ON_SALE or SOLD_OUT. */
    @Override
    public void validateEventsOnSale(List<Integer> eventIds) {
        for (Integer eventId : eventIds) {
            Event event = eventRepository.findById(eventId);       // load each event
            if (event == null) {
                throw new EventNotFoundException("Event not found: " + eventId);
            }
            // SOLD_OUT is allowed: an event that sold out while these tickets were reserved must still let
            // the holders complete their purchase (mirrors Event.validateCanConfirmSale).
            if (event.getStatus() != EventStatus.ON_SALE && event.getStatus() != EventStatus.SOLD_OUT) {
                throw new EventNotOnSaleException(eventId, "" + event.getStatus());
            }
        }
    }

    /** {@inheritDoc} CALLER-LOCKED read — fail-fast ownership/status check before a confirm. */
    @Override
    public void validateCanConfirmSale(List<InventoryLineDTO> lines, String orderKey) {
        Map<Integer, Map<Integer, List<InventoryLineDTO>>> grouped = groupByEventAndZone(lines);

        for (Map.Entry<Integer, Map<Integer, List<InventoryLineDTO>>> eventEntry : grouped.entrySet()) {
            Event event = eventRepository.findById(eventEntry.getKey()); // load the event
            if (event == null) {
                throw new EventNotFoundException("Event not found: " + eventEntry.getKey());
            }

            for (Map.Entry<Integer, List<InventoryLineDTO>> zoneEntry : eventEntry.getValue().entrySet()) {
                int zoneId = zoneEntry.getKey();
                List<InventoryLineDTO> zoneItems = zoneEntry.getValue();

                InventoryZone zone = event.getVenueMap().getZone(zoneId); // resolve the zone

                List<String> seatNumbers = extractSeatNumbers(zoneItems); // seated seats in this zone

                if (seatNumbers.isEmpty()) {
                    if (zone.isSeated()) {
                        throw new InsufficientInventoryException("Seated cart item is missing seat numbers");
                    }
                    if (zone.getReservedAmount() < zoneItems.size()) {
                        throw new InsufficientInventoryException(
                                "Not enough reserved standing tickets to confirm sale");
                    }
                } else {
                    if (zone.isStanding()) {
                        throw new InsufficientInventoryException("Standing cart item cannot contain seat numbers");
                    }
                    if (!(zone instanceof SeatedZone seatedZone)) {
                        throw new InsufficientInventoryException("Zone is not a seated zone");
                    }
                    for (String seatNumber : seatNumbers) {
                        if (seatedZone.getSeatStatus(seatNumber) != SeatStatus.RESERVED) {
                            throw new ConcurrentReservationException(
                                    "Seat " + seatNumber + " is no longer RESERVED — reservation may have expired");
                        }
                        // Ownership check: ensure this checkout's order still holds the seat. Skip when the
                        // seat was reserved without an explicit order key (anonymous sentinel).
                        Seat seat = seatedZone.getSeatByLabel(seatNumber);
                        String seatOwner = seat.getReservedByOrderKey();
                        if (orderKey != null && !orderKey.equals(seatOwner)) {
                            throw new ConcurrentReservationException(
                                    "Seat " + seatNumber + " is held by a different order — cannot confirm sale");
                        }
                    }
                }
            }
        }
    }

    /** {@inheritDoc} CALLER-LOCKED mutate — confirm RESERVED → SOLD, compensating a partial failure. */
    @Override
    public void confirmSale(List<InventoryLineDTO> lines, String orderKey) {
        Map<Integer, Map<Integer, List<InventoryLineDTO>>> grouped = groupByEventAndZone(lines);

        // Confirming across multiple events/zones is not a single atomic step. Track each confirmed unit so
        // a mid-loop failure can be compensated (SOLD -> AVAILABLE): we must never leave a partial SOLD end
        // state, so the caller's rollback + refund path stays correct.
        List<ConfirmedUnit> confirmed = new ArrayList<>();
        try {
            for (Map.Entry<Integer, Map<Integer, List<InventoryLineDTO>>> eventEntry : grouped.entrySet()) {
                Event event = eventRepository.findById(eventEntry.getKey()); // load the event

                for (Map.Entry<Integer, List<InventoryLineDTO>> zoneEntry : eventEntry.getValue().entrySet()) {
                    int zoneId = zoneEntry.getKey();
                    List<InventoryLineDTO> zoneItems = zoneEntry.getValue();

                    List<String> seatNumbers = extractSeatNumbers(zoneItems); // seated seats (empty = standing)
                    InventorySelection selection = seatNumbers.isEmpty()
                            ? InventorySelection.standing(zoneItems.size(), orderKey)
                            : InventorySelection.seated(seatNumbers, orderKey);

                    event.confirmInventorySale(zoneId, selection);          // RESERVED -> SOLD (ownership-checked)
                    confirmed.add(new ConfirmedUnit(event, zoneId, selection)); // remember for compensation
                }

                eventRepository.save(event);                                // persist this event once
            }
        } catch (RuntimeException confirmFailure) {
            compensateConfirmedSales(confirmed);                            // reverse anything already SOLD
            throw confirmFailure;                                          // re-raise the original failure
        }
    }

    /** {@inheritDoc} CALLER-LOCKED mutate — release still-held RESERVED lines during a checkout rollback. */
    @Override
    public void releaseHeld(List<InventoryLineDTO> lines, String orderKey) {
        Map<Integer, Map<Integer, List<InventoryLineDTO>>> grouped = groupByEventAndZone(lines);

        for (Map.Entry<Integer, Map<Integer, List<InventoryLineDTO>>> eventEntry : grouped.entrySet()) {
            int eventId = eventEntry.getKey();
            Event event = eventRepository.findById(eventId);               // load the event

            if (event == null) {
                continue;                                                  // event gone — nothing to release
            }

            // Release each (event, zone) independently: a zone that can no longer be released (already
            // confirmed/expired) must not abort the rollback for the remaining zones. Log and continue,
            // then save the event if anything in it was actually released.
            boolean anyReleased = false;
            for (Map.Entry<Integer, List<InventoryLineDTO>> zoneEntry : eventEntry.getValue().entrySet()) {
                int zoneId = zoneEntry.getKey();
                List<InventoryLineDTO> zoneItems = zoneEntry.getValue();

                List<String> seatNumbers = extractSeatNumbers(zoneItems);  // seated seats (empty = standing)
                try {
                    if (seatNumbers.isEmpty()) {
                        event.releaseInventory(zoneId, InventorySelection.standing(zoneItems.size(), orderKey));
                    } else {
                        event.releaseInventory(zoneId, InventorySelection.seated(seatNumbers, orderKey));
                    }
                    anyReleased = true;                                    // this zone released
                } catch (RuntimeException zoneReleaseFailure) {
                    log.warn(
                            "Could not release inventory during checkout rollback. eventId={}, zoneId={}, orderKey={}",
                            eventId, zoneId, orderKey, zoneReleaseFailure);
                }
            }

            if (anyReleased) {
                eventRepository.save(event);                               // persist only if something changed
            }
        }
    }

    // ---------------------------------------------------------------------
    // Internal helpers (moved verbatim from the former sales services)
    // ---------------------------------------------------------------------

    /** Loads an event by id, throwing {@link IllegalArgumentException} if it does not exist (reserve/release path). */
    private Event getEventOrThrow(int eventId) {
        Event event = eventRepository.findById(eventId);           // load
        if (event == null) {
            log.warn("Request rejected: event not found. eventId={}", eventId);
            throw new IllegalArgumentException("Event not found: " + eventId);
        }
        return event;
    }

    /** Resolves a zone from an event's venue map, normalizing both the throw and the null contract of getZone. */
    private InventoryZone getZoneOrThrow(Event event, int zoneId) {
        if (event.getVenueMap() == null) {
            throw new IllegalStateException("Venue map is not configured for event: " + event.getId());
        }
        InventoryZone zone;
        try {
            zone = event.getVenueMap().getZone(zoneId);            // may throw IllegalArgumentException
        } catch (IllegalArgumentException e) {
            log.warn("Request rejected: zone not found. eventId={}, zoneId={}", event.getId(), zoneId);
            throw new IllegalArgumentException("Zone not found: " + zoneId);
        }
        if (zone == null) {
            log.warn("Request rejected: zone not found. eventId={}, zoneId={}", event.getId(), zoneId);
            throw new IllegalArgumentException("Zone not found: " + zoneId);
        }
        return zone;
    }

    /** Translates a sales-safe selection DTO + owning order key into the domain value object at the boundary. */
    private InventorySelection toDomainSelection(InventorySelectionDTO selection, String orderKey) {
        if (selection.isStandingSelection()) {
            return InventorySelection.standing(selection.getQuantity(), orderKey);
        }
        return InventorySelection.seated(selection.getSeatNumbers(), orderKey);
    }

    /** Builds an ownership-less selection (SOLD-return path) from a zone's refund lines. */
    private InventorySelection toStockSelection(List<InventoryLineDTO> zoneLines) {
        List<String> seatNumbers = extractSeatNumbers(zoneLines);  // seated seats in this zone
        return seatNumbers.isEmpty()
                ? InventorySelection.standing(zoneLines.size())    // N standing units, no order key
                : InventorySelection.seated(seatNumbers);          // seated seats, no order key
    }

    /** Groups flat inventory lines by event id and then by zone id (mirrors the former checkout grouping). */
    private Map<Integer, Map<Integer, List<InventoryLineDTO>>> groupByEventAndZone(List<InventoryLineDTO> lines) {
        return lines.stream()
                .collect(Collectors.groupingBy(
                        InventoryLineDTO::eventId,
                        Collectors.groupingBy(InventoryLineDTO::zoneId)));
    }

    /** Extracts the non-null seat labels of a zone's lines (empty means the lines are standing units). */
    private List<String> extractSeatNumbers(List<InventoryLineDTO> zoneLines) {
        return zoneLines.stream()
                .map(InventoryLineDTO::seatNumber)
                .filter(seatNumber -> seatNumber != null)
                .toList();
    }

    /**
     * Best-effort reversal (SOLD -> AVAILABLE) of units already confirmed when a multi-event confirm fails
     * partway, so no inventory is stranded SOLD. Runs under the event locks the caller already holds.
     * Failures are logged, not propagated — we are already on the failure path.
     */
    private void compensateConfirmedSales(List<ConfirmedUnit> confirmed) {
        for (int i = confirmed.size() - 1; i >= 0; i--) {
            ConfirmedUnit unit = confirmed.get(i);
            try {
                unit.event().returnSoldToStock(unit.zoneId(), unit.selection()); // SOLD -> AVAILABLE
                eventRepository.save(unit.event());                              // persist the reversal
            } catch (RuntimeException compensationFailure) {
                log.error("Failed to compensate a confirmed sale during checkout rollback. zoneId={}",
                        unit.zoneId(), compensationFailure);
            }
        }
    }

    /**
     * A single (event, zone, selection) that was confirmed SOLD during {@link #confirmSale} — kept so a
     * partial-confirm failure can be compensated back to AVAILABLE.
     */
    private record ConfirmedUnit(Event event, int zoneId, InventorySelection selection) {
    }
}
