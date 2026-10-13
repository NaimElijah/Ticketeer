package com.ticketing.system.catalog.domain;

import com.ticketing.system.shared.InvariantChecked;

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
 * <p>The legacy counter-based methods ({@code reserve(int)} / {@code release(int)} /
 * {@code checkAvailability(int)} / {@code setCapacity(int)}) are defined here
 * with default throwing implementations so existing callers that hold an
 * {@code InventoryZone} reference continue to work for standing zones.
 * Seated-zone callers must downcast or use the typed
 * {@link SeatedZone#reserveSeats(java.util.List)} / {@link SeatedZone#releaseSeats(java.util.List)} methods.
 */
// V3: JOINED inheritance — InventoryZone is the parent table, SeatedZone/StandingZone get their own
// joined tables (keeps their NOT-NULL subclass constraints, which SINGLE_TABLE would forbid). The
// @Id is a DB-generated surrogate because the domain zone id is only unique within a VenueMap, not
// globally; the domain id stays a column (zone_number). @Version gives each zone its own optimistic
// lock (counter updates on a StandingZone bump it; structural seat changes on a SeatedZone bump it).
// A protected no-arg ctor lets Hibernate hydrate the subclasses.
@Entity
@Table(name = "inventory_zones")
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "zone_type")
public abstract class InventoryZone implements InvariantChecked {

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

    protected InventoryZone(int id, String name, double price) {
        // Invariants (name non-blank, price >= 0) are enforced by the concrete
        // subclass's checkInvariants(), invoked at the end of its constructor.
        // Calling the overridable checkInvariants() here would run before the
        // subclass's own fields are initialized.
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

    public double getprice() {
        return price;
    }

    public abstract ZoneType getZoneType();

    public abstract int getCapacity();

    public abstract int getAvailableAmount();

    public abstract int getReservedAmount();

    public abstract boolean checkAvailability(int quantity);

    public abstract boolean reserve(InventorySelection selection);

    public abstract boolean release(InventorySelection selection);

    public abstract boolean confirmSale(InventorySelection selection);

    /**
     * Return previously SOLD inventory to AVAILABLE stock (e.g. on a member refund).
     * Distinct from {@link #release(InventorySelection)}, which only frees RESERVED holds —
     * a refunded seat/place is SOLD, not held, so it needs its own transition.
     */
    public abstract boolean returnSoldToStock(InventorySelection selection);

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
     * with sibling zones are validated by {@link VenueMap#placeZoneOnGrid}.
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

    public int getGridRow() {
        return gridRow;
    }

    public int getGridCol() {
        return gridCol;
    }

    public int getGridRowSpan() {
        return gridRowSpan;
    }

    public int getGridColSpan() {
        return gridColSpan;
    }

}
