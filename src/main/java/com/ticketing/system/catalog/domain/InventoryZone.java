package com.ticketing.system.catalog.domain;

import com.ticketing.system.shared.InvariantChecked;
import com.ticketing.system.shared.domain.TextLimits;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Abstract base for all inventory-zone types inside a {@link VenueMap}.
 *
 * <p>Two concrete shapes:
 * <ul>
 *   <li>{@link StandingZone} — counter-based. Capacity is a single number;
 *       reservations and releases are integer arithmetic.</li>
 *   <li>{@link SeatedZone} — catalog of named, addressable seats with
 *       coordinates. Reservations target specific seat labels.</li>
 * </ul>
 *
 * <p>Common state (id, name, price) lives on this class. Pre-purchase
 * inventory state (capacity/reservations) lives on the subclass and is
 * mediated through {@link #getCapacity()} / {@link #getAvailableAmount()} /
 * {@link #getReservedAmount()}.
 *
 * <p>Every inventory transition ({@link #reserve}, {@link #release}, {@link #confirmSale},
 * {@link #returnSoldToStock}) takes an {@link InventorySelection}, so a caller holding an
 * {@code InventoryZone} reference needs no downcast. A subclass throws
 * {@code IllegalArgumentException} for a selection of the other kind: seat labels for a standing zone,
 * or a bare quantity for a seated zone.
 *
 * <p>Persistence: JOINED inheritance. {@code InventoryZone} is the parent table, and {@code SeatedZone}
 * and {@code StandingZone} get their own joined tables, which keeps their NOT NULL subclass constraints
 * (SINGLE_TABLE would forbid them). The {@code @Id} is a database-generated surrogate because the
 * domain zone id is only unique within a {@link VenueMap}, not globally; the domain id stays a column
 * ({@code zone_number}). {@code @Version} gives each zone its own optimistic lock: counter updates on a
 * standing zone bump it, and so do structural seat changes on a seated zone. A protected no-arg
 * constructor lets Hibernate hydrate the subclasses.
 */
@Entity
@Table(name = "inventory_zones")
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "zone_type")
public abstract class InventoryZone implements InvariantChecked {

    /**
     * The highest ticket price a zone may carry (OQ-28). The zone invariants are the only check on a
     * price: the editor's field limits are advisory, and a client can send any number.
     */
    public static final double MAX_PRICE = 1_000_000.0;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long zonePk;

    @Column(name = "zone_number", nullable = false)
    protected int id;
    @Column(nullable = false)
    protected String name;
    @Column(nullable = false)
    protected double price;

    // Grid placement on the VenueMap canvas: 1-based start cell + cell spans.
    // gridRowSpan == 0 means "not explicitly placed" → the preview falls back to
    // auto-layout. Bounds against the grid size and non-overlap with other zones
    // are enforced by VenueMap.placeZoneOnGrid (which holds the whole map).
    @Column(name = "grid_row")
    private int gridRow;
    @Column(name = "grid_col")
    private int gridCol;
    @Column(name = "grid_row_span")
    private int gridRowSpan;
    @Column(name = "grid_col_span")
    private int gridColSpan;

    @Version
    private Long version;

    /** For JPA only — do not call from application code. */
    protected InventoryZone() { }

    /**
     * Sets the zone's id, name and price. A subclass constructor then calls {@link #checkInvariants()}.
     *
     * @param id    the zone's id within its map
     * @param name  the zone's name; at most {@link TextLimits#MAX_TEXT_LENGTH} characters
     * @param price the price of one place or seat
     * @throws com.ticketing.system.shared.exception.BusinessRuleViolationException if the name is too
     *     long; its message is shown to the user
     */
    protected InventoryZone(int id, String name, double price) {
        // Invariants (name non-blank, price in bounds) are enforced by the concrete
        // subclass's checkInvariants(), invoked at the end of its constructor.
        // Calling the overridable checkInvariants() here would run before the
        // subclass's own fields are initialized.
        TextLimits.requireFits("Zone name", name);
        this.id = id;
        this.name = name;
        this.price = price;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** @return the price of one place or seat in this zone, from 0 to {@link #MAX_PRICE} */
    public double getprice() {
        return price;
    }

    /**
     * @param price a candidate zone price
     * @return whether it is a finite number in {@code [0, MAX_PRICE]}. Finiteness is tested on its
     *         own, though the range already excludes NaN and the infinities, because the former check
     *         ({@code price < 0}) let NaN through
     */
    protected static boolean isPriceInBounds(double price) {
        return Double.isFinite(price) && price >= 0 && price <= MAX_PRICE;
    }

    /** @return whether this zone counts places or addresses seats */
    public abstract ZoneType getZoneType();

    /** @return the zone's total places (standing) or seats (seated), whatever their state */
    public abstract int getCapacity();

    /** @return the places or seats that can still be reserved */
    public abstract int getAvailableAmount();

    /** @return the places or seats held by an order and not yet sold */
    public abstract int getReservedAmount();

    /**
     * Checks that at least {@code quantity} places or seats are available.
     *
     * @param quantity the number wanted
     * @return {@code true}; too few is an exception
     * @throws IllegalStateException    if fewer than {@code quantity} are available
     * @throws IllegalArgumentException if the zone is standing and {@code quantity} is not positive
     */
    public abstract boolean checkAvailability(int quantity);

    /**
     * Reserves places or seats for an order. For a seated zone the selection must name seats and carry a
     * non-blank order key, and all its seats are reserved or none are.
     *
     * @param selection what to reserve; its kind must match the zone's
     * @return {@code true}; a refusal is an exception
     * @throws IllegalArgumentException if the selection is of the other kind, or a seated selection has no
     *                                  order key or names a seat the zone does not have
     * @throws IllegalStateException    if a standing zone has too few places, or a requested seat is not
     *                                  AVAILABLE
     */
    public abstract boolean reserve(InventorySelection selection);

    /**
     * Frees held (RESERVED) places or seats. When the selection carries an order key, only that order's
     * hold can be freed; a standing selection without one is released from the shared anonymous hold.
     *
     * @param selection what to release; its kind must match the zone's
     * @return {@code true}; a refusal is an exception
     * @throws IllegalArgumentException if the selection is of the other kind, or names a seat the zone
     *                                  does not have
     * @throws IllegalStateException    if the order holds fewer places than the selection, or a seat is
     *                                  not RESERVED or is held by another order
     */
    public abstract boolean release(InventorySelection selection);

    /**
     * Turns held (RESERVED) places or seats into SOLD, on the same terms as {@link #release}.
     *
     * @param selection what to sell; its kind must match the zone's
     * @return {@code true}; a refusal is an exception
     * @throws IllegalArgumentException if the selection is of the other kind, or names a seat the zone
     *                                  does not have
     * @throws IllegalStateException    if the order holds fewer places than the selection, or a seat is
     *                                  not RESERVED or is held by another order
     */
    public abstract boolean confirmSale(InventorySelection selection);

    /**
     * Return previously SOLD inventory to AVAILABLE stock (e.g. on a member refund).
     * Distinct from {@link #release(InventorySelection)}, which only frees RESERVED holds —
     * a refunded seat/place is SOLD, not held, so it needs its own transition. A sold place carries no
     * hold, so the selection's order key is ignored.
     *
     * @param selection what to return; its kind must match the zone's
     * @return {@code true}; a refusal is an exception
     * @throws IllegalArgumentException if the selection is of the other kind, or names a seat the zone
     *                                  does not have
     * @throws IllegalStateException    if a standing zone has fewer SOLD places than the selection, or a
     *                                  seat is not SOLD
     */
    public abstract boolean returnSoldToStock(InventorySelection selection);

    /** @return the places or seats whose sale was confirmed and not since returned to stock */
    public abstract int getSoldAmount();

    public boolean isStanding() {
        return getZoneType() == ZoneType.STANDING;
    }

    public boolean isSeated() {
        return getZoneType() == ZoneType.SEATED;
    }

    /**
     * Places this zone on the venue grid. Coordinates are 1-based and spans are
     * cell counts (&gt;= 1). Bounds against the actual grid size and non-overlap
     * with sibling zones are validated by {@link VenueMap#placeZoneOnGrid}, not here.
     *
     * @param row     the first row, 1-based
     * @param col     the first column, 1-based
     * @param rowSpan the rows covered, at least 1
     * @param colSpan the columns covered, at least 1
     * @throws IllegalArgumentException if a coordinate or span is below 1
     */
    public void placeOnGrid(int row, int col, int rowSpan, int colSpan) {
        if (row < 1 || col < 1) {
            throw new IllegalArgumentException("Grid row/col are 1-based and must be >= 1");
        }
        if (rowSpan < 1 || colSpan < 1) {
            throw new IllegalArgumentException("Grid spans must be >= 1");
        }
        this.gridRow = row;
        this.gridCol = col;
        this.gridRowSpan = rowSpan;
        this.gridColSpan = colSpan;
    }

    /** True once the zone has an explicit grid placement (vs. the auto-layout fallback). */
    public boolean hasGridPlacement() {
        return gridRowSpan > 0 && gridColSpan > 0;
    }

    /** @return the first grid row, 1-based, or 0 while the zone has no placement */
    public int getGridRow() {
        return gridRow;
    }

    /** @return the first grid column, 1-based, or 0 while the zone has no placement */
    public int getGridCol() {
        return gridCol;
    }

    /** @return the number of grid rows covered, or 0 while the zone has no placement */
    public int getGridRowSpan() {
        return gridRowSpan;
    }

    /** @return the number of grid columns covered, or 0 while the zone has no placement */
    public int getGridColSpan() {
        return gridColSpan;
    }

}
