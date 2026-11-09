
package com.ticketing.system.catalog.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.ticketing.system.shared.InvariantChecked;
import com.ticketing.system.shared.domain.TextLimits;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.shared.exception.PolicyViolationException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;

import lombok.extern.slf4j.Slf4j;

import com.ticketing.system.shared.domain.policy.AndPurchasePolicy;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;
import com.ticketing.system.shared.domain.policy.PurchaseContext;
import com.ticketing.system.shared.domain.policy.PurchasePolicy;
import com.ticketing.system.shared.domain.policy.PurchasePolicyJsonConverter;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

/**
 * The event aggregate root: an event's details, show dates, {@link VenueMap}, lifecycle status
 * ({@link EventStatus}) and purchase and discount policies (UC-19, UC-20, UC-21).
 *
 * <p>The inventory edit methods of this class check that the owning company asks and that the event
 * is still DRAFT or SCHEDULED. Buyer operations ({@link #reserveInventory},
 * {@link #releaseInventory}, {@link #confirmInventorySale}, {@link #returnSoldToStock}) change seat and
 * zone state, and move the event between ON_SALE and SOLD_OUT when stock runs out or frees up.
 *
 * <p>Persistence: the id is an assigned {@code int} (minted by {@code EventRepository.nextId()}); the
 * company is held by id; category and status are stored by name. {@code @Version} drives the
 * event-level optimistic lock for structural and lifecycle edits, while {@link Seat} and
 * {@link InventoryZone} carry their own {@code @Version} so independent buyer reservations never
 * false-conflict. Artist names and show dates are element collections; the venue map is an owned
 * {@code @OneToOne} (null while the event is in DRAFT); the policies are stored as JSON. A protected
 * no-arg constructor lets Hibernate hydrate; the public constructors enforce the invariants.
 */
@Slf4j
@Entity
@Table(name = "events")
public class Event implements InvariantChecked {
    private static final String NAME_FIELD = "Event name";
    private static final String DESCRIPTION_FIELD = "Event description";
    private static final String ARTIST_FIELD = "Artist name";
    // Scheduling needs inventory (transitionToScheduled), so a scheduled event may not lose all of it.
    private static final String SCHEDULED_NEEDS_A_ZONE = "A scheduled event must keep at least one inventory zone";

    @Id
    private int id;
    @Column(nullable = false)
    private String name;
    @Column
    private String description;
    @Column
    private Double rating;
    @ElementCollection(fetch = FetchType.EAGER)
    @jakarta.persistence.CollectionTable(name = "event_artists", joinColumns = @JoinColumn(name = "event_id"))
    @Column(name = "artist_name")
    private List<String> artistsNames;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventCategory category;
    @Column(name = "company_id", nullable = false)
    private int comapnyid;
    // volatile: status is read by buyer operations (reserve/release/confirm) holding only the SHARED
    // event read lock, while markSoldOut()/revertToOnSale() write it. volatile gives those readers
    // visibility; statusLock (below) makes the auto-transition read-modify-write atomic.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private volatile EventStatus status;
    @OneToOne(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "venue_map_id")
    private VenueMap venueMap;
    @ElementCollection(fetch = FetchType.EAGER)
    @jakarta.persistence.CollectionTable(name = "event_show_dates", joinColumns = @JoinColumn(name = "event_id"))
    private List<ShowDate> showDates;
    // The IANA zone the show dates are wall-clock times in (ADR-008). It lives on the event rather
    // than the VenueMap because the map may be null in DRAFT while show dates are already mandatory.
    @Column(name = "time_zone", nullable = false, length = 64)
    private ZoneId timeZone;
    @Convert(converter = PurchasePolicyJsonConverter.class)
    @Column(name = "purchase_policy", columnDefinition = "text", nullable = false)
    private PurchasePolicy purchasePolicy;
    @Convert(converter = DiscountPolicyJsonConverter.class)
    @Column(name = "discount_policy", columnDefinition = "text")
    private DiscountPolicy discountPolicy;

    @Version
    private Long version;

    // Runtime monitor guarding the ON_SALE<->SOLD_OUT auto-transitions so concurrent buyer operations
    // (which hold only the shared event read lock) can't race the status read-modify-write. @Transient:
    // never persisted; re-created by the field initializer on JPA hydrate (same pattern as seat locks).
    @Transient
    private final Object statusLock = new Object();

    /** For JPA only — do not call from application code. */
    protected Event() { }

    /**
     * Creates an event with no description. No production code calls it; it delegates to
     * {@link #Event(int, String, String, Double, List, EventCategory, int, EventStatus, VenueMap, List, ZoneId, PurchasePolicy, DiscountPolicy)}
     * with a {@code null} description, and so throws what that constructor throws.
     */
    public Event(int id, String name, Double rating, List<String> artistsNames, EventCategory category, int comapnyid,
            EventStatus status, VenueMap venueMap, List<ShowDate> showDates, ZoneId timeZone,
            PurchasePolicy PurchasePolicy, DiscountPolicy discountPolicy) {
        this(id, name, null, rating, artistsNames, category, comapnyid, status, venueMap, showDates, timeZone,
                PurchasePolicy, discountPolicy);
    }

    /**
     * Creates an event in the given status. The status is stored as given; no lifecycle transition runs.
     *
     * @param id            the event's id; positive
     * @param name          the event's name; non-blank, at most {@link TextLimits#MAX_TEXT_LENGTH} characters
     * @param description   the event's description, or {@code null} for none; at most
     *                      {@link TextLimits#MAX_TEXT_LENGTH} characters
     * @param rating        the event's rating from 0 to 5, or {@code null} if unrated
     * @param artistsNames  the lineup; at least one name, each non-blank and at most
     *                      {@link TextLimits#MAX_TEXT_LENGTH} characters; copied
     * @param category      the event's category
     * @param comapnyid     the id of the owning company; positive
     * @param status        the event's lifecycle status
     * @param venueMap      the venue map, or {@code null} while the event is in DRAFT
     * @param showDates     at least one show, as wall-clock times at the venue read in {@code timeZone};
     *                      copied
     * @param timeZone      the venue's IANA zone (ADR-008)
     * @param PurchasePolicy the event's own purchase policy, or {@code null} for {@link NoPurchasePolicy}
     * @param discountPolicy the event's discount policy
     * @throws BusinessRuleViolationException if the name, the description or an artist's name is too long;
     *                                        its message is shown to the user
     * @throws IllegalArgumentException       if an artist's name is null or blank
     * @throws IllegalStateException          if any other invariant fails: a non-positive id or company id,
     *                                        a blank name, a rating outside 0 to 5, or a missing category,
     *                                        status, lineup, show date, time zone or discount policy
     */
    public Event(int id, String name, String description, Double rating, List<String> artistsNames,
            EventCategory category, int comapnyid, EventStatus status, VenueMap venueMap, List<ShowDate> showDates,
            ZoneId timeZone, PurchasePolicy PurchasePolicy, DiscountPolicy discountPolicy) {
        // Invariants are enforced by checkInvariants() at the end of construction
        // (single source of truth). Collection copies are null-safe so a null input
        // reaches checkInvariants() as a clean IllegalStateException instead of an NPE here.
        requireTextFits(name, description, artistsNames);
        requireNamedArtists(artistsNames);
        this.id = id;
        this.name = name;
        this.description = description;
        this.rating = rating;
        this.artistsNames = artistsNames == null ? null : new ArrayList<>(artistsNames);
        this.category = category;
        this.comapnyid = comapnyid;
        this.status = status;
        this.venueMap = venueMap;
        this.showDates = showDates == null ? null : new ArrayList<>(showDates);
        this.timeZone = timeZone;
        // purchasePolicy defaults to NoPurchasePolicy when not supplied.
        this.purchasePolicy = PurchasePolicy == null ? new NoPurchasePolicy() : PurchasePolicy;
        // Stored as given; checkInvariants refuses null. Discounts are not in the implementation plan, so
        // EventManagementService.addEvent passes a 0% policy.
        this.discountPolicy = discountPolicy;
        checkInvariants();
    }






    /**
     * Reserves inventory in one zone. The zone decides whether the selection is a standing quantity or
     * seated seats. When this takes the last available place or seat of an ON_SALE event, the event
     * becomes SOLD_OUT.
     *
     * @param zoneId    the zone within the venue map
     * @param selection the quantity or seats to reserve; carries the holding order's key
     * @return {@code true}; a refusal is an exception
     * @throws IllegalArgumentException if {@code selection} is {@code null}, the zone does not exist, or
     *                                  the selection is of the wrong kind or invalid for the zone
     * @throws IllegalStateException    if the event has no venue map or is not ON_SALE, or the zone has
     *                                  too few available places or a requested seat is not available
     */
    public boolean reserveInventory(int zoneId, InventorySelection selection) {
        validateCanReserve(selection);
        this.venueMap.reserveInventory(zoneId, selection);
        // When the last available place/seat is taken, the event sells out automatically. Guarded by
        // statusLock so concurrent buyer reservations can't race this read-modify-write of status.
        synchronized (statusLock) {
            if (status == EventStatus.ON_SALE && !venueMap.hasAvailableInventory()) {
                markSoldOut();
            }
        }
        return true;
    }

    /**
     * Releases held (RESERVED) inventory in one zone back to AVAILABLE, whatever the event's status.
     * A SOLD_OUT event becomes ON_SALE again once any place or seat is available.
     *
     * @param zoneId    the zone within the venue map
     * @param selection the quantity or seats to release; when it carries an order key, the zone checks
     *                  that order holds them
     * @return {@code true}; a refusal is an exception
     * @throws IllegalArgumentException if {@code selection} is {@code null}, the zone does not exist, or
     *                                  the selection is of the wrong kind or invalid for the zone
     * @throws IllegalStateException    if the event has no venue map, or the zone holds less than the
     *                                  selection for that order
     */
    public boolean releaseInventory(int zoneId, InventorySelection selection) {
        validateInventoryAction(selection);
        this.venueMap.releaseInventory(zoneId, selection);
        // Releasing inventory (manual removal, checkout rollback, or expiry sweep) re-opens
        // a sold-out event for sale again. Guarded by statusLock (see reserveInventory).
        synchronized (statusLock) {
            if (status == EventStatus.SOLD_OUT && venueMap.hasAvailableInventory()) {
                revertToOnSale();
            }
        }
        return true;
    }

    /**
     * Return previously SOLD inventory to AVAILABLE stock (member refund). Mirrors
     * {@link #releaseInventory} but acts on SOLD seats/places rather than RESERVED holds,
     * and likewise re-opens a sold-out event once a place frees up.
     *
     * @param zoneId    the zone within the venue map
     * @param selection the SOLD quantity or seats to return; any order key is ignored
     * @return {@code true}; a refusal is an exception
     * @throws IllegalArgumentException if {@code selection} is {@code null}, the zone does not exist, or
     *                                  the selection is of the wrong kind or invalid for the zone
     * @throws IllegalStateException    if the event has no venue map, or the zone has fewer SOLD places
     *                                  (or a requested seat is not SOLD) than the selection
     */
    public boolean returnSoldToStock(int zoneId, InventorySelection selection) {
        validateInventoryAction(selection);
        this.venueMap.returnSoldToStock(zoneId, selection);
        // Guarded by statusLock (see reserveInventory).
        synchronized (statusLock) {
            if (status == EventStatus.SOLD_OUT && venueMap.hasAvailableInventory()) {
                revertToOnSale();
            }
        }
        return true;
    }

    /**
     * Turns held (RESERVED) inventory in one zone into SOLD. Allowed while the event is ON_SALE or
     * SOLD_OUT, so holders of reservations made before it sold out can still complete their purchase.
     *
     * @param zoneId    the zone within the venue map
     * @param selection the quantity or seats to confirm; when it carries an order key, the zone checks
     *                  that order holds them
     * @throws IllegalArgumentException if {@code selection} is {@code null}, the zone does not exist, or
     *                                  the selection is of the wrong kind or invalid for the zone
     * @throws IllegalStateException    if the event has no venue map or is not ON_SALE or SOLD_OUT, or the
     *                                  zone holds less than the selection for that order
     */
    public void confirmInventorySale(int zoneId, InventorySelection selection) {
        validateCanConfirmSale(selection);
        this.venueMap.confirmSale(zoneId, selection);
    }













    private void validateCanReserve(InventorySelection selection) {
        validateInventoryAction(selection);
        if (status != EventStatus.ON_SALE) {
            throw new IllegalStateException("Cannot reserve tickets for an event that is not on sale");
        }
    }

    private void validateCanConfirmSale(InventorySelection selection) {
        validateInventoryAction(selection);

        // SOLD_OUT is allowed: an event that sold out while tickets were reserved must still let
        // the holders of those reservations complete their purchase (reserved -> sold).
        if (status != EventStatus.ON_SALE && status != EventStatus.SOLD_OUT) {
            throw new IllegalStateException("Cannot confirm ticket sale for an event that is not on sale");
        }
    }


    private void validateInventoryAction(InventorySelection selection) {
        if (selection == null) {
            throw new IllegalArgumentException("Inventory selection is required");
        }

        if (this.venueMap == null) {
            throw new IllegalStateException("Venue map must be initialized first");
        }
    }


    /**
     * Checks that a zone has at least {@code quantity} places available. The event needs a venue map.
     *
     * @param zoneId   the zone within the venue map
     * @param quantity the number of places wanted
     * @return {@code true}; too few places is an exception
     * @throws IllegalArgumentException if the zone does not exist, or {@code quantity} is not positive
     *                                  for a standing zone
     * @throws IllegalStateException    if the zone has fewer than {@code quantity} places available
     */
    public boolean checkAvailability(int zoneId, int quantity) {
        return this.venueMap.checkAvailability(zoneId, quantity);
    }

    public int getId() {
        return id;
    }

    /**
     * Prices a purchase of {@code quantity} tickets through the event's discount policy.
     *
     * @param quantity                    the number of tickets; positive
     * @param priceAtoneticketReservation the price of one ticket when it was reserved; not negative
     * @param now                         the pricing time; the flat percentage policy does not read it
     * @return the total for all {@code quantity} tickets after the discount
     * @throws IllegalArgumentException if {@code quantity} is not positive, or the price is {@code null}
     *                                  or negative
     */
    public double calculatePrice(int quantity, Double priceAtoneticketReservation, LocalDateTime now) {
        return discountPolicy.calculate(quantity, priceAtoneticketReservation, now);
    }
    //? These 2 functions are not in the implementation plan, so above for each event we just give discount 0, so not doing any discount.
    /**
     * Prices one ticket of a purchase of {@code quantity} tickets through the event's discount policy.
     * Takes the same arguments as {@link #calculatePrice}.
     *
     * @param quantity                    the number of tickets in the purchase; positive
     * @param priceAtoneticketReservation the price of one ticket when it was reserved; not negative
     * @param now                         the pricing time; the flat percentage policy does not read it
     * @return the per-ticket price after the discount
     * @throws IllegalArgumentException if {@code quantity} is not positive, or the price is {@code null}
     *                                  or negative
     */
    public double calculatePriceforoneticket(int quantity, Double priceAtoneticketReservation, LocalDateTime now) {
        return discountPolicy.calculatePriceforoneticket(quantity, priceAtoneticketReservation, now);
    }







    /**
     * UC-20: replaces the event's venue map. Only the owning company may, before sales open.
     *
     * @param venueMap          the new map
     * @param incomingCompanyId the company asking
     * @throws UnauthorizedActionException if another company asks
     * @throws IllegalArgumentException    if {@code venueMap} is null
     * @throws IllegalStateException       if the event is past SCHEDULED, if tickets are reserved or sold, or
     *                                     if the event is SCHEDULED and the new map has no zone
     */
    public void configureVenueMap(VenueMap venueMap, int incomingCompanyId) {
        if (comapnyid != incomingCompanyId) {
            throw new UnauthorizedActionException("configure the venue map of another company's event");
        }

        if (venueMap == null) {
            throw new IllegalArgumentException("Venue map cannot be null");
        }

        validateCanEditInventoryForCompany(incomingCompanyId);

        if (this.venueMap != null && hasReservedOrSoldInventory()) {
            throw new IllegalStateException("Cannot reconfigure venue map while tickets are reserved or sold");
        }

        if (status == EventStatus.SCHEDULED && venueMap.getInventoryZones().isEmpty()) {
            throw new IllegalStateException(SCHEDULED_NEEDS_A_ZONE);
        }

        this.venueMap = venueMap;

        // UC-20: binding a venue map with inventory advances a draft event to SCHEDULED.
        // Reconfiguring an already-scheduled (or later) event leaves its status untouched.
        if (status == EventStatus.DRAFT && !venueMap.getInventoryZones().isEmpty()) {
            transitionToScheduled();
        }
    }


    private boolean hasReservedOrSoldInventory() {
        if (venueMap == null) {
            return false;
        }
        return venueMap.getInventoryZones().stream()
                .anyMatch(zone -> zone.getReservedAmount() > 0 || zone.getSoldAmount() > 0);
    }








    /**
     * Adds a standing zone to the venue map, on the owning company's behalf. The zone gets the map's
     * next zone id.
     *
     * @param zoneName          the zone's name; non-blank, at most {@link TextLimits#MAX_TEXT_LENGTH}
     *                          characters, and different from every other zone's name ignoring case
     * @param capacity          the number of places; not negative
     * @param pricePerTicket    the price of one place, from 0 to {@link InventoryZone#MAX_PRICE}
     * @param incomingCompanyId the company asking
     * @return the new zone's id
     * @throws UnauthorizedActionException    if another company asks
     * @throws BusinessRuleViolationException if the event has no venue map, or the name is too long (its
     *                                        message is shown to the user)
     * @throws IllegalArgumentException       if another zone already has the name
     * @throws IllegalStateException          if the event is past SCHEDULED, or the name, capacity or price
     *                                        breaks the zone's invariants
     */
    public int addStandingZone(String zoneName, int capacity, double pricePerTicket, int incomingCompanyId) {
        validateCanEditExistingVenueInventory(incomingCompanyId);
        int newZoneId = venueMap.getNextZoneId();

        StandingZone standingZone = new StandingZone(
                newZoneId,
                zoneName,
                capacity,
                pricePerTicket);
        venueMap.addZone(standingZone);
        return newZoneId;
    }

    /**
     * Adds a seated zone to the venue map, on the owning company's behalf. The zone gets the map's
     * next zone id.
     *
     * @param zoneName          the zone's name; non-blank, at most {@link TextLimits#MAX_TEXT_LENGTH}
     *                          characters, and different from every other zone's name ignoring case
     * @param pricePerTicket    the price of one seat, from 0 to {@link InventoryZone#MAX_PRICE}
     * @param seats             the zone's seats; at least one, with distinct labels
     * @param incomingCompanyId the company asking
     * @return the new zone's id
     * @throws UnauthorizedActionException    if another company asks
     * @throws BusinessRuleViolationException if the event has no venue map, or the name is too long (its
     *                                        message is shown to the user)
     * @throws IllegalArgumentException       if {@code seats} is null or empty, two seats share a label, or
     *                                        another zone already has the name
     * @throws IllegalStateException          if the event is past SCHEDULED, or the name or price breaks the
     *                                        zone's invariants
     */
    public int addSeatedZone(String zoneName, double pricePerTicket, List<Seat> seats, int incomingCompanyId) {
        validateCanEditExistingVenueInventory(incomingCompanyId);

        if (seats == null || seats.isEmpty()) {
            throw new IllegalArgumentException("Seated zone must contain at least one seat");
        }
        int newZoneId = venueMap.getNextZoneId();

        SeatedZone seatedZone = new SeatedZone(
                newZoneId,
                zoneName,
                pricePerTicket,
                seats);
        venueMap.addZone(seatedZone);
        return newZoneId;
    }

    /**
     * Removes one inventory zone from the venue map, on the owning company's behalf.
     *
     * @param zoneId            the zone to remove
     * @param incomingCompanyId the company asking
     * @throws UnauthorizedActionException    if another company asks
     * @throws BusinessRuleViolationException if the event has no venue map
     * @throws IllegalArgumentException       if the map has no such zone
     * @throws IllegalStateException          if the event is past SCHEDULED, is SCHEDULED and this is its
     *                                        only zone, or the zone has reserved or sold places
     */
    public void removeInventoryZone(int zoneId, int incomingCompanyId) {
        validateCanEditExistingVenueInventory(incomingCompanyId);
        if (status == EventStatus.SCHEDULED && isOnlyZone(zoneId)) {
            throw new IllegalStateException(SCHEDULED_NEEDS_A_ZONE);
        }
        venueMap.removeZone(zoneId);
    }

    // An id that is not on the map is not its only zone, so removeZone still reports it as not found.
    private boolean isOnlyZone(int zoneId) {
        List<InventoryZone> zones = venueMap.getInventoryZones();
        return zones.size() == 1 && zones.get(0).getId() == zoneId;
    }

    










    /**
     * Adds places to a standing zone, on the owning company's behalf.
     *
     * @param zoneId            the standing zone
     * @param amountToAdd       the number of places to add; positive
     * @param incomingCompanyId the company asking
     * @throws UnauthorizedActionException    if another company asks
     * @throws BusinessRuleViolationException if the event has no venue map
     * @throws IllegalArgumentException       if the map has no such zone, or {@code amountToAdd} is not
     *                                        positive
     * @throws IllegalStateException          if the event is past SCHEDULED, or the zone is not a standing
     *                                        zone
     */
    public void addPlacesToStandingZone(int zoneId, int amountToAdd, int incomingCompanyId) {
        validateCanEditExistingVenueInventory(incomingCompanyId);
        this.getVenueMapOrThrow().addPlacesToStandingZone(zoneId, amountToAdd);
    }

    /**
     * Removes places from a standing zone, on the owning company's behalf. Only available places can go:
     * reserved and sold ones stay.
     *
     * @param zoneId            the standing zone
     * @param amountToRemove    the number of places to remove; positive and at most the zone's available
     *                          places
     * @param incomingCompanyId the company asking
     * @throws UnauthorizedActionException    if another company asks
     * @throws BusinessRuleViolationException if the event has no venue map
     * @throws IllegalArgumentException       if the map has no such zone, {@code amountToRemove} is not
     *                                        positive, or it exceeds the zone's available places
     * @throws IllegalStateException          if the event is past SCHEDULED, or the zone is not a standing
     *                                        zone
     */
    public void removePlacesFromStandingZone(int zoneId, int amountToRemove, int incomingCompanyId) {
        validateCanEditExistingVenueInventory(incomingCompanyId);
        this.getVenueMapOrThrow().removePlacesFromStandingZone(zoneId, amountToRemove);
    }



    /**
     * Adds seats to a seated zone, on the owning company's behalf.
     *
     * @param zoneId            the seated zone
     * @param seatsToAdd        the new seats; at least one, each AVAILABLE, with labels distinct from each
     *                          other and from the zone's existing seats
     * @param incomingCompanyId the company asking
     * @throws UnauthorizedActionException    if another company asks
     * @throws BusinessRuleViolationException if the event has no venue map
     * @throws IllegalArgumentException       if the map has no such zone, the zone is not a seated zone, or
     *                                        {@code seatsToAdd} is empty, holds a null or non-AVAILABLE
     *                                        seat, or repeats a label the zone already has
     * @throws IllegalStateException          if the event is past SCHEDULED, or a seat breaks its own
     *                                        invariants
     */
    public void addSeatsToSeatedZone(int zoneId, List<Seat> seatsToAdd, int incomingCompanyId) {
        validateCanEditExistingVenueInventory(incomingCompanyId);
        this.getVenueMapOrThrow().addSeatsToSeatedZone(zoneId, seatsToAdd);
    }

    /**
     * Removes seats from a seated zone, on the owning company's behalf. All the seats go or none do.
     *
     * @param zoneId             the seated zone
     * @param seatLabelsToRemove the labels to remove; at least one, non-blank and distinct
     * @param incomingCompanyId  the company asking
     * @throws UnauthorizedActionException    if another company asks
     * @throws BusinessRuleViolationException if the event has no venue map
     * @throws IllegalArgumentException       if the map has no such zone, the zone is not a seated zone, or
     *                                        {@code seatLabelsToRemove} is empty, has a blank or repeated
     *                                        label, or names a seat the zone does not have
     * @throws IllegalStateException          if the event is past SCHEDULED, or a named seat is not AVAILABLE
     */
    public void removeSeatsFromSeatedZone(int zoneId, List<String> seatLabelsToRemove, int incomingCompanyId) {
        validateCanEditExistingVenueInventory(incomingCompanyId);
        this.getVenueMapOrThrow().removeSeatsFromSeatedZone(zoneId, seatLabelsToRemove);
    }




    




    private void validateCanEditExistingVenueInventory(int incomingCompanyId) {
        validateCanEditInventoryForCompany(incomingCompanyId);
        if (this.venueMap == null) {
            throw new BusinessRuleViolationException("Venue map must be initialized first");
        }
    }

    private void validateCanEditInventoryForCompany(int incomingCompanyId) {
        if (comapnyid != incomingCompanyId) {
            throw new UnauthorizedActionException("edit event inventory, only company that owns the event can edit inventory");
        }
        if (status != EventStatus.DRAFT && status != EventStatus.SCHEDULED) {
            throw new IllegalStateException("Inventory can only be edited while event is DRAFT or SCHEDULED");
        }
    }











    /**
     * UC-20: moves the event from DRAFT to SCHEDULED once a venue map with at least one zone is bound.
     * Creates no tickets: a ticket is created at checkout. {@link #configureVenueMap} calls it when it
     * binds such a map to a DRAFT event. Does nothing when the event is already SCHEDULED.
     *
     * @throws InvalidStateTransitionException if the event has no venue map or the map has no zone, or the
     *                                         event is in any status but DRAFT or SCHEDULED
     */
    public void transitionToScheduled() {
        if (status == EventStatus.SCHEDULED) {
            return;
        }

        if (venueMap == null || venueMap.getInventoryZones().isEmpty()) {
            throw new InvalidStateTransitionException(
                    "Event cannot be scheduled without a venue map and at least one inventory zone");
        }

        if (status != EventStatus.DRAFT) {
            throw new InvalidStateTransitionException("Event", status.name(), EventStatus.SCHEDULED.name());
        }

        this.status = EventStatus.SCHEDULED;
    }


    /**
     * UC-19 / UC-32: moves the event from SCHEDULED to ON_SALE when a company member publishes it
     * ({@code EventManagementService.publishEvent} or {@code changeEventStatus}). Opening the market does
     * not call it. Does nothing when the event is already ON_SALE.
     *
     * @throws InvalidStateTransitionException if the event has no venue map or the map has no zone, has no
     *                                         show date, or is in any status but SCHEDULED or ON_SALE
     * @throws IllegalStateException           if the event breaks one of its invariants
     */
    public void transitionToOnSale() {
        if (status == EventStatus.ON_SALE) {
            return;
        }

        if (venueMap == null || venueMap.getInventoryZones().isEmpty()) {
            throw new InvalidStateTransitionException("Cannot publish event without venue map and inventory");
        }

        if (showDates.isEmpty()) {
            throw new InvalidStateTransitionException("All show dates must be present before going on sale");
        }

        checkInvariants(); // enforce all invariants before allowing sales to start

        if (status != EventStatus.SCHEDULED) {
            throw new InvalidStateTransitionException("Event", status.name(), EventStatus.ON_SALE.name());
        }

        this.status = EventStatus.ON_SALE;
    }




    /**
     * UC-19: cancels the event softly, keeping it for history. It only changes the status:
     * {@code EventManagementService.cancelEventAndRefund} publishes the {@code EventCancelledEvent} that
     * starts the refunds (UC-4). Does nothing when the event is already CANCELED.
     *
     * @param reason a reason for the cancellation; this method ignores it
     * @throws IllegalStateException if the event is COMPLETED
     */
    public void transitionToCanceled(String reason) {
        if (status == EventStatus.CANCELED) {
            return;
        }

        if (status == EventStatus.COMPLETED) {
            throw new IllegalStateException("Cannot cancel a completed event");
        }

        this.status = EventStatus.CANCELED;
    }
    



    /**
     * Moves an ON_SALE or SOLD_OUT event to COMPLETED once its last show has passed. Does not check the
     * show dates itself; see {@link #hasFinishedAsOf}. Does nothing when the event is already COMPLETED.
     *
     * @throws IllegalStateException if the event is in any status but ON_SALE, SOLD_OUT or COMPLETED
     */
    public void transitionToCompleted() {
        if (status == EventStatus.COMPLETED) {
            return;
        }

        if (status != EventStatus.ON_SALE && status != EventStatus.SOLD_OUT) {
            throw new IllegalStateException("Only on-sale or sold-out events can be marked as completed");
        }

        this.status = EventStatus.COMPLETED;
    }

    


    /**
     * Moves an ON_SALE event to SOLD_OUT. {@link #reserveInventory} calls it when no AVAILABLE seat or
     * standing place remains; it does not check that itself. Does nothing when the event is already
     * SOLD_OUT. Safe against concurrent buyer operations.
     *
     * @throws InvalidStateTransitionException if the event is in any status but ON_SALE or SOLD_OUT
     */
    public void markSoldOut() {
        // statusLock makes this read-modify-write atomic against concurrent buyer ops; reentrant when
        // called from reserveInventory's guarded block on the same thread.
        synchronized (statusLock) {
            if (status == EventStatus.SOLD_OUT) {
                return;
            }

            if (status != EventStatus.ON_SALE) {
                throw new InvalidStateTransitionException("Event", status.name(), EventStatus.SOLD_OUT.name());
            }

            this.status = EventStatus.SOLD_OUT;
        }
    }


    /**
     * Moves a SOLD_OUT event back to ON_SALE. {@link #releaseInventory} and {@link #returnSoldToStock}
     * call it when a place or seat becomes available again; it does not check that itself. Does nothing
     * when the event is already ON_SALE. Safe against concurrent buyer operations.
     *
     * @throws InvalidStateTransitionException if the event is in any status but SOLD_OUT or ON_SALE
     */
    public void revertToOnSale() {
        // statusLock makes this read-modify-write atomic against concurrent buyer ops; reentrant when
        // called from releaseInventory/returnSoldToStock's guarded block on the same thread.
        synchronized (statusLock) {
            if (status == EventStatus.ON_SALE) {
                return;
            }

            if (status != EventStatus.SOLD_OUT) {
                throw new InvalidStateTransitionException("Event", status.name(), EventStatus.ON_SALE.name());
            }

            this.status = EventStatus.ON_SALE;
        }
    }

    







    /**
     * UC-19: updates the event's details, only while it is DRAFT or SCHEDULED. A {@code null} argument
     * leaves that field alone, as does a blank {@code newName}. A new time zone re-reads the existing
     * show dates in that zone (ADR-008). A changed schedule or zone must still start after {@code now}
     * at the venue. That is checked before any field changes, so a rejected edit leaves the aggregate
     * untouched (the in-memory repository hands out live objects).
     *
     * <p>The caller must hold the event lock ({@code EventRepository.lockForUpdate}): the fields are
     * not synchronized, so concurrent reads rely on the service's lock discipline.
     *
     * @param newName     the new name, or {@code null}; at most {@link TextLimits#MAX_TEXT_LENGTH} characters
     * @param newDescription the new description, or {@code null}; at most {@link TextLimits#MAX_TEXT_LENGTH}
     *                    characters
     * @param newCategory the new category, or {@code null}
     * @param newLocation the new venue location, or {@code null}; applied only when the event has a venue
     *                    map, and ignored otherwise
     * @param newTimeZone the new IANA zone the show dates are read in, or {@code null}
     * @param newShowDates the new schedule, or {@code null}; when given, at least one show
     * @param now         the instant a changed schedule or zone must start after
     * @throws IllegalStateException          if the event is past SCHEDULED, or the edited event breaks an
     *                                        invariant
     * @throws IllegalArgumentException       if {@code newShowDates} is empty, or a show of the changed
     *                                        schedule or zone starts at or before {@code now}
     * @throws BusinessRuleViolationException if the name or description is too long (its message is shown
     *                                        to the user)
     */
    public void editDetails(String newName, String newDescription, EventCategory newCategory,
            Location newLocation, ZoneId newTimeZone, List<ShowDate> newShowDates, Instant now) {
        if (status != EventStatus.DRAFT && status != EventStatus.SCHEDULED) {
            throw new IllegalStateException("Event details can only be edited while in DRAFT or SCHEDULED state");
        }
        if (newShowDates != null && newShowDates.isEmpty()) {
            throw new IllegalArgumentException("showDates cannot be empty; an event must have at least one show date");
        }
        if (newShowDates != null || newTimeZone != null) {
            requireStartsAfter(newShowDates != null ? newShowDates : showDates,
                    newTimeZone != null ? newTimeZone : timeZone, now);
        }
        requireTextFits(newName, newDescription, null);
        if (newName != null && !newName.isBlank()) {
            this.name = newName;
        }
        if (newDescription != null) {
            this.description = newDescription;
        }
        if (newCategory != null) {
            this.category = newCategory;
        }
        if (newLocation != null && this.venueMap != null) {
            this.venueMap.setLocation(newLocation);
        }
        if (newTimeZone != null) {
            this.timeZone = newTimeZone;
        }
        if (newShowDates != null) {
            // null means "leave the schedule alone"; an explicitly empty list was rejected above
            // rather than silently ignored, since an event must always have >=1 show date.
            // A mutable copy, as the constructor makes: saving a managed event merges it onto itself, and
            // Hibernate then replaces an element collection's contents in place, which an immutable list refuses.
            this.showDates = new ArrayList<>(newShowDates);
        }
        checkInvariants();
    }

    public String getName() {
        return name;
    }

    /** @return the event's description, or {@code null} if it has none */
    public String getDescription() {
        return description;
    }

    /** @return the event's rating from 0 to 5, or {@code null} if it is unrated */
    public Double getRating() {
        return rating;
    }

    public int getCompanyId() {
        return comapnyid;
    }

    public EventStatus getStatus() {
        return status;
    }

    public EventCategory getCategory() {
        return category;
    }

    /** @return an unmodifiable copy of the lineup; a change to the event's lineup does not show in it */
    public List<String> getArtistsNames() {
        return List.copyOf(artistsNames);
    }

    /** @return an unmodifiable copy of the schedule; the times are wall-clock times in {@link #getTimeZone()} */
    public List<ShowDate> getShowDates() {
        return List.copyOf(showDates);
    }

    /** @return the IANA zone the show dates are wall-clock times in (ADR-008) */
    public ZoneId getTimeZone() {
        return timeZone;
    }

    /**
     * Tells whether the event's last show has ended as of {@code now}; the completion sweeper uses it
     * to complete ON_SALE and SOLD_OUT events. A multi-show event finishes only after its latest show.
     * The show times are read in the event's zone, so the answer does not depend on the server's zone.
     *
     * @param now the instant to test against
     * @return {@code true} if {@code now} is after the end of the latest show; {@code false} when that
     *         show has not ended, or the event has no show dates
     */
    public boolean hasFinishedAsOf(Instant now) {
        if (showDates == null || showDates.isEmpty()) {
            return false;
        }
        Instant lastEnd = showDates.stream()
                .map(show -> show.endsAt(timeZone))
                .max(Comparator.naturalOrder())
                .orElse(null);
        return lastEnd != null && now.isAfter(lastEnd);
    }

    /**
     * The precondition for scheduling this event (UC-19): every show starts after {@code now} at the
     * venue. Checked when an event is created; {@link #editDetails} checks a changed schedule itself.
     *
     * @throws IllegalArgumentException if a show starts at or before {@code now}
     */
    public void requireShowsStartAfter(Instant now) {
        requireStartsAfter(showDates, timeZone, now);
    }

    private static void requireStartsAfter(List<ShowDate> shows, ZoneId zone, Instant now) {
        for (ShowDate show : shows) {
            show.requireStartsAfter(now, zone);
        }
    }

    /** @return the event's venue map, or {@code null} while the event is in DRAFT and none is bound */
    public VenueMap getVenueMap() {
        return venueMap;
    }

    // private getter
    private VenueMap getVenueMapOrThrow() {
        if (this.venueMap == null) {
            throw new IllegalStateException("Venue map must be initialized first");
        }
        return this.venueMap;
    }

    public PurchasePolicy getPurchasePolicy() {
        return purchasePolicy;
    }

    public DiscountPolicy getDiscountPolicy() {
        return discountPolicy;
    }





    /**
     * UC-21: replaces the event's own purchase policy. The owning company's policy is not touched; it is
     * combined with this one each time a purchase is checked ({@link #validateEffectivePolicy}).
     *
     * @param purchasePolicy the new policy
     * @throws IllegalArgumentException if {@code purchasePolicy} is {@code null}
     */
    public void setPurchasePolicy(PurchasePolicy purchasePolicy) {
        if (purchasePolicy == null) {
            throw new IllegalArgumentException("Purchase policy cannot be null");
        }

        this.purchasePolicy = purchasePolicy;
    }

    /**
     * Checks a purchase against this event's own purchase policy only, without the company's policy;
     * {@link #validateEffectivePolicy} checks both.
     *
     * @param context the purchase to check
     * @throws IllegalArgumentException if {@code context} is {@code null}
     * @throws IllegalStateException    if the policy is not satisfied; its message is shown to the user
     */
    public void validatePurchasePolicy(PurchaseContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Purchase context cannot be null");
        }

        if (purchasePolicy == null) {
            purchasePolicy = new NoPurchasePolicy();
        }

        if (!purchasePolicy.isSatisfiedBy(context)) {
            throw new IllegalStateException(purchasePolicy.getFailureMessage(context));
        }
    }

    /**
     * Checks a purchase against the effective purchase policy: the supplied company policy AND this
     * event's policy, composed into one {@link AndPurchasePolicy}. Both must hold.
     *
     * @param companyPolicy the owning company's policy, or {@code null} for none
     * @param context       the purchase to check
     * @throws IllegalArgumentException if {@code context} is {@code null}
     * @throws PolicyViolationException if either policy is not satisfied; its message is shown to the user
     */
    public void validateEffectivePolicy(PurchasePolicy companyPolicy, PurchaseContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Purchase context cannot be null");
        }
        if (purchasePolicy == null) {
            purchasePolicy = new NoPurchasePolicy();
        }
        PurchasePolicy company = companyPolicy == null ? new NoPurchasePolicy() : companyPolicy;
        PurchasePolicy effective = new AndPurchasePolicy(company, purchasePolicy);
        if (!effective.isSatisfiedBy(context)) {
            // Typed domain exception (not IllegalStateException): the Presentation layer maps
            // PolicyViolationException -> PayOutcome.PolicyRejected so the buyer sees the clear,
            // specific reason ("Cannot purchase: you can buy at most 4 tickets") rather than the
            // generic failure toast.
            throw new PolicyViolationException(effective.getFailureMessage(context));
        }
    }











    // Checked before any field is assigned, so a refused value never reaches the aggregate (#186).
    private static void requireTextFits(String name, String description, List<String> artistsNames) {
        TextLimits.requireFits(NAME_FIELD, name);
        TextLimits.requireFits(DESCRIPTION_FIELD, description);
        if (artistsNames != null) {
            artistsNames.forEach(artist -> TextLimits.requireFits(ARTIST_FIELD, artist));
        }
    }

    // A null artist broke every listing that reads the names (getArtistsNames copies them with
    // List.copyOf), and a blank one names nobody. Checked before any field is assigned.
    private static void requireNamedArtists(List<String> artistsNames) {
        if (artistsNames == null) {
            return; // a missing list is checkInvariants' to refuse
        }
        for (String artist : artistsNames) {
            if (artist == null || artist.isBlank()) {
                throw new IllegalArgumentException("Every artist needs a name");
            }
        }
    }

    @Override
    public void checkInvariants() {
        if (id <= 0) {
            throw new IllegalStateException("Event invariant violated: id must be positive (was " + id + ")");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("Event invariant violated: name must be non-blank");
        }
        if (rating != null && (!Double.isFinite(rating) || rating < 0 || rating > 5)) {
            throw new IllegalStateException(
                    "Event invariant violated: rating must be a finite value between 0 and 5 (was " + rating + ")");
        }
        if (comapnyid <= 0) {
            throw new IllegalStateException(
                    "Event invariant violated: companyId must be positive (was " + comapnyid + ")");
        }
        if (status == null) {
            throw new IllegalStateException("Event invariant violated: status must not be null");
        }
        if (category == null) {
            throw new IllegalStateException("Event invariant violated: category must not be null");
        }
        if (artistsNames == null || artistsNames.isEmpty()) {
            throw new IllegalStateException("Event invariant violated: artistsNames list must be non-empty");
        }
        if (showDates == null || showDates.isEmpty()) {
            throw new IllegalStateException("Event invariant violated: showDates list must be non-empty");
        }
        if (timeZone == null) {
            throw new IllegalStateException("Event invariant violated: timeZone must not be null");
        }
        if (purchasePolicy == null) {
            throw new IllegalStateException("Event invariant violated: purchasePolicy must not be null");
        }
        if (discountPolicy == null) {
            throw new IllegalStateException("Event invariant violated: discountPolicy must not be null");
        }
        // venueMap may be null in DRAFT state (UC-19) before UC-20 binds it — don't enforce non-null.
        // If present, the VenueMap's own invariants apply (cascade-check when implemented).
    }









    

}