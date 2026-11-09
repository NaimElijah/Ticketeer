package com.ticketing.system.catalog.domain;

import java.util.List;

import java.util.ArrayList;

import com.ticketing.system.shared.InvariantChecked;

import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * A venue's layout and inventory (UC-20): its {@link Location}, a canvas grid, and the
 * {@link InventoryZone}s placed on it. It belongs to one {@link Event}, which reaches it through
 * {@link Event#getVenueMap()} and checks who may edit it and when.
 *
 * <p>Zone ids are unique within a map, and so are zone names, ignoring case. New zones take ids from
 * {@link #getNextZoneId()}, so an id is not reused after its zone is removed.
 *
 * <p>Persistence: an owned {@code @Entity} of {@code Event}, with an assigned {@code int} id (minted by
 * {@code EventRepository.nextVenueMapId()}). The location is an {@code @Embedded} value that may be
 * {@code null} in a row stored before a venue was required to have one. The polymorphic zones are owned
 * children (cascade-all and orphan removal), loaded eagerly. A protected no-arg constructor lets
 * Hibernate hydrate.
 */
@Entity
@Table(name = "venue_maps")
public class VenueMap implements InvariantChecked {

    /** Rows of the venue canvas grid when none is specified. */
    public static final int DEFAULT_GRID_ROWS = 3;
    /** Columns of the venue canvas grid when none is specified. */
    public static final int DEFAULT_GRID_COLS = 3;

    @Id
    private int id;
    @Embedded
    private Location location;
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "venue_map_id")
    @Fetch(FetchMode.SUBSELECT)
    private List<InventoryZone> inventoryZones;
    @Column(name = "next_zone_id")
    private int nextZoneId;
    // Venue canvas grid: zones snap to cells of this gridRows × gridCols layout.
    @Column(name = "grid_rows")
    private int gridRows;
    @Column(name = "grid_cols")
    private int gridCols;

    /** For JPA only — do not call from application code. */
    protected VenueMap() {
        this.inventoryZones = new ArrayList<>();
    }

    /**
     * Creates a map on the default {@value #DEFAULT_GRID_ROWS}x{@value #DEFAULT_GRID_COLS} grid; see
     * {@link #VenueMap(int, Location, List, int, int)}, whose exceptions it throws too.
     */
    public VenueMap(int id, Location location, List<InventoryZone> inventoryZones) {
        this(id, location, inventoryZones, DEFAULT_GRID_ROWS, DEFAULT_GRID_COLS);
    }

    /**
     * Creates a map with the given zones. The next zone id starts one above the highest zone id given,
     * or at 1 for no zones. A whole map arrives here when an event is reconfigured, so the zones meet the
     * same rules as {@link #addZone} applies to one.
     *
     * @param id             the map's id
     * @param location       the venue's location
     * @param inventoryZones the zones, copied; may be empty
     * @param gridRows       the canvas grid's rows, at least 1
     * @param gridCols       the canvas grid's columns, at least 1
     * @throws IllegalArgumentException if {@code location} or {@code inventoryZones} is null, the grid is
     *                                  smaller than 1x1, a zone is null or has an id below 1, or two zones
     *                                  share an id or a name ignoring case
     * @throws IllegalStateException    if a zone breaks its own invariants
     */
    public VenueMap(int id, Location location, List<InventoryZone> inventoryZones, int gridRows, int gridCols) {
        // Null-guarded here because the list is copied before checkInvariants() runs;
        // a null input would otherwise NPE in the copy rather than fail cleanly.
        if (inventoryZones == null) {
            throw new IllegalArgumentException("Inventory zones cannot be null");
        }
        requireLocation(location);
        if (gridRows < 1 || gridCols < 1) {
            throw new IllegalArgumentException("Venue grid must be at least 1x1");
        }
        // A whole map arrives here on reconfiguration, so it must refuse what addZone refuses one at a time.
        List<InventoryZone> accepted = new ArrayList<>();
        for (InventoryZone zone : inventoryZones) {
            validateZoneAmong(accepted, zone);
            accepted.add(zone);
        }

        this.id = id;
        this.location = location;
        this.inventoryZones = new ArrayList<>(inventoryZones);
        this.gridRows = gridRows;
        this.gridCols = gridCols;
        this.nextZoneId = this.inventoryZones.stream()
                .mapToInt(InventoryZone::getId)
                .max()
                .orElse(0) + 1;
        checkInvariants();
    }

    @Override
    public void checkInvariants() {
        if (inventoryZones == null) {
            throw new IllegalStateException("VenueMap invariant violated: inventoryZones must not be null");
        }
        if (nextZoneId < 1) {
            throw new IllegalStateException("VenueMap invariant violated: nextZoneId must be >= 1 (was " + nextZoneId + ")");
        }
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
        checkInvariants();
    }

    /** @return an unmodifiable snapshot of the zones; later changes to the map do not show in it */
    public List<InventoryZone> getInventoryZones() {
        return List.copyOf(inventoryZones);
    }

   

    /** @return the venue's location; {@code null} only for a row stored before a venue needed one */
    public Location getLocation() {
        return location;
    }

    /**
     * Replaces the venue's location.
     *
     * @param location the new location
     * @throws IllegalArgumentException if {@code location} is {@code null}
     */
    public void setLocation(Location location) {
        requireLocation(location);
        this.location = location;
        checkInvariants();
    }

    public int getGridRows() {
        return gridRows;
    }

    public int getGridCols() {
        return gridCols;
    }

    /**
     * Places a zone on the venue grid, checking that it stays within the gridRows × gridCols bounds and
     * does not overlap an already-placed zone.
     *
     * @param zoneId  the zone to place
     * @param row     the first row, 1-based
     * @param col     the first column, 1-based
     * @param rowSpan the number of rows covered, at least 1
     * @param colSpan the number of columns covered, at least 1
     * @throws IllegalArgumentException if the map has no such zone, a coordinate or span is below 1, or
     *                                  the placement runs past the grid
     * @throws IllegalStateException    if the placement overlaps another placed zone
     */
    public void placeZoneOnGrid(int zoneId, int row, int col, int rowSpan, int colSpan) {
        InventoryZone zone = getZone(zoneId);
        if (row < 1 || col < 1 || rowSpan < 1 || colSpan < 1) {
            throw new IllegalArgumentException("Grid placement must be 1-based with spans >= 1");
        }
        if (row + rowSpan - 1 > gridRows || col + colSpan - 1 > gridCols) {
            throw new IllegalArgumentException(
                "Zone placement exceeds the " + gridRows + "x" + gridCols + " venue grid");
        }
        for (InventoryZone other : inventoryZones) {
            if (other.getId() == zoneId || !other.hasGridPlacement()) {
                continue;
            }
            if (rectsOverlap(row, col, rowSpan, colSpan,
                    other.getGridRow(), other.getGridCol(),
                    other.getGridRowSpan(), other.getGridColSpan())) {
                throw new IllegalStateException(
                    "Zone placement overlaps zone '" + other.getName() + "'");
            }
        }
        zone.placeOnGrid(row, col, rowSpan, colSpan);
        checkInvariants();
    }

    private static boolean rectsOverlap(int r1, int c1, int rs1, int cs1,
                                        int r2, int c2, int rs2, int cs2) {
        boolean rowsDisjoint = r1 + rs1 <= r2 || r2 + rs2 <= r1;
        boolean colsDisjoint = c1 + cs1 <= c2 || c2 + cs2 <= c1;
        return !(rowsDisjoint || colsDisjoint);
    }

    /**
     * Returns the zone with the given id.
     *
     * @param zoneId the zone's id
     * @return the zone; never {@code null}
     * @throws IllegalArgumentException if the map has no such zone
     */
    public InventoryZone getZone(int zoneId) {
        for (InventoryZone zone : inventoryZones) {
            if (zone.getId() == zoneId) {
                return zone;
            }
        }
        throw new IllegalArgumentException("Zone not found");
    }
    
    /**
     * Checks that a zone has at least {@code quantity} places available.
     *
     * @param zoneId   the zone's id
     * @param quantity the number of places wanted
     * @return {@code true}; too few places is an exception
     * @throws IllegalArgumentException if the map has no such zone, or {@code quantity} is not positive
     *                                  for a standing zone
     * @throws IllegalStateException    if the zone has fewer than {@code quantity} places available
     */
    public boolean checkAvailability(int zoneId, int quantity) {
        InventoryZone zone = getZone(zoneId);
        return zone.checkAvailability(quantity);
    }

    /** @return {@code true} if any zone still has at least one AVAILABLE place or seat */
    public boolean hasAvailableInventory() {
        return inventoryZones.stream().anyMatch(zone -> zone.getAvailableAmount() > 0);
    }





    /**
     * Adds places to a standing zone.
     *
     * @param zoneId      the standing zone
     * @param amountToAdd the number of places to add; positive
     * @throws IllegalArgumentException if the map has no such zone, or {@code amountToAdd} is not positive
     * @throws IllegalStateException    if the zone is not a standing zone
     */
    public void addPlacesToStandingZone(int zoneId, int amountToAdd) {
        StandingZone zone = getStandingZoneOrThrow(zoneId);
        zone.addPlaces(amountToAdd);
    }

    /**
     * Removes places from a standing zone. Reserved and sold places stay.
     *
     * @param zoneId         the standing zone
     * @param amountToRemove the number of places to remove; positive and at most the zone's available places
     * @throws IllegalArgumentException if the map has no such zone, or {@code amountToRemove} is not
     *                                  positive or exceeds the available places
     * @throws IllegalStateException    if the zone is not a standing zone
     */
    public void removePlacesFromStandingZone(int zoneId, int amountToRemove) {
        StandingZone zone = getStandingZoneOrThrow(zoneId);
        zone.removePlaces(amountToRemove);
    }



    /**
     * Adds seats to a seated zone.
     *
     * @param zoneId     the seated zone
     * @param seatsToAdd the new seats; see {@link SeatedZone#addSeats}
     * @throws IllegalArgumentException if the map has no such zone, the zone is not a seated zone, or the
     *                                  seats are refused by {@link SeatedZone#addSeats}
     */
    public void addSeatsToSeatedZone(int zoneId, List<Seat> seatsToAdd) {
        SeatedZone zone = getSeatedZoneOrThrow(zoneId);
        zone.addSeats(seatsToAdd);
    }

    /**
     * Removes seats from a seated zone; all go or none do.
     *
     * @param zoneId             the seated zone
     * @param seatLabelsToRemove the labels to remove; see {@link SeatedZone#removeSeats}
     * @throws IllegalArgumentException if the map has no such zone, the zone is not a seated zone, or the
     *                                  labels are refused by {@link SeatedZone#removeSeats}
     * @throws IllegalStateException    if a named seat is not AVAILABLE
     */
    public void removeSeatsFromSeatedZone(int zoneId, List<String> seatLabelsToRemove) {
        SeatedZone zone = getSeatedZoneOrThrow(zoneId);
        zone.removeSeats(seatLabelsToRemove);
    }





    /** @return the id the next zone added should take: one above the highest zone id the map has held */
    public int getNextZoneId() {
        return nextZoneId;
    }


    /**
     * Adds a zone to the map. Its id is normally {@link #getNextZoneId()}; the map moves the next id past
     * the zone's id when it is not below it.
     *
     * @param zone the zone to add
     * @throws IllegalArgumentException if {@code zone} is {@code null}, its id is not positive, or another
     *                                  zone has its id or its name ignoring case
     * @throws IllegalStateException    if the zone breaks its own invariants
     */
    public void addZone(InventoryZone zone) {
        // The id normally comes from getNextZoneId(); checking again here keeps a duplicate id or name out
        // of the map, since zones are looked up by id and shown by name.
        validateZoneAmong(inventoryZones, zone);

        inventoryZones.add(zone);

        if (zone.getId() >= nextZoneId) {
            nextZoneId = zone.getId() + 1;
        }
        checkInvariants();
    }


    /**
     * Removes a zone. The map keeps counting ids upward, so the removed zone's id is not reused.
     *
     * @param zoneId the zone to remove
     * @return the removed zone
     * @throws IllegalArgumentException if the map has no such zone
     * @throws IllegalStateException    if the zone has reserved or sold places
     */
    public InventoryZone removeZone(int zoneId) {
        InventoryZone zone = getZone(zoneId);

        if (zoneHasReservedOrSoldInventory(zone)) {
            throw new IllegalStateException("Cannot remove zone " + zoneId + " because it has reserved or sold inventory");
        }

        inventoryZones.remove(zone);
        checkInvariants();
        return zone;
    }


    // What a zone must be to join a map, whether one at a time (addZone) or with the whole map (the
    // constructor, on reconfiguration).
    private static void validateZoneAmong(List<InventoryZone> existingZones, InventoryZone zone) {
        if (zone == null) {
            throw new IllegalArgumentException("Zone cannot be null");
        }

        if (zone.getId() <= 0) {
            throw new IllegalArgumentException("Zone id must be positive");
        }

        requireUniqueAmong(existingZones, zone);

        zone.checkInvariants();
    }

    // Checked where a location comes in, not in checkInvariants, because the schema still allows a map stored
    // without one (V1). Event.checkInvariants does not run the map's own check yet; once it does,
    // SystemIntegrityVerifier runs it on every stored event when the market opens, and an old row must not
    // keep the market closed.
    private static void requireLocation(Location location) {
        if (location == null) {
            throw new IllegalArgumentException("A venue needs a location");
        }
    }

    // A zone is looked up by id, and shown by name, so neither may repeat within a map; names compare
    // ignoring case.
    private static void requireUniqueAmong(List<InventoryZone> existingZones, InventoryZone zone) {
        for (InventoryZone existingZone : existingZones) {
            if (existingZone.getId() == zone.getId()) {
                throw new IllegalArgumentException("Zone id already exists: " + zone.getId());
            }

            if (existingZone.getName().equalsIgnoreCase(zone.getName())) {
                throw new IllegalArgumentException("Zone name already exists: " + zone.getName());
            }
        }
    }


    private boolean zoneHasReservedOrSoldInventory(InventoryZone zone) {
        return zone.getReservedAmount() > 0 || zone.getSoldAmount() > 0;
    }









    
    /**
     * Reserves inventory in a zone, standing or seated: the selection carries seat labels for a seated
     * zone and none for a standing one.
     *
     * @param zoneId    the zone's id
     * @param selection what to reserve, and the order key that will hold it
     * @throws IllegalArgumentException if the map has no such zone, or the zone refuses the selection;
     *                                  see {@link InventoryZone#reserve}
     * @throws IllegalStateException    if the zone has too few places or a requested seat is unavailable
     */
    public void reserveInventory(int zoneId, InventorySelection selection) {
        InventoryZone zone = getZone(zoneId);
        zone.reserve(selection);
    }


    /**
     * Releases held (RESERVED) inventory in a zone, standing or seated.
     *
     * @param zoneId    the zone's id
     * @param selection what to release; when it carries an order key, the zone checks that order holds it
     * @throws IllegalArgumentException if the map has no such zone, or the zone refuses the selection
     * @throws IllegalStateException    if the zone holds less than the selection for that order
     */
    public void releaseInventory(int zoneId, InventorySelection selection) {
        InventoryZone zone = getZone(zoneId);
        zone.release(selection);
    }

    /**
     * Returns SOLD inventory in a zone to AVAILABLE, for a refund; standing or seated.
     *
     * @param zoneId    the zone's id
     * @param selection what to return; any order key is ignored
     * @throws IllegalArgumentException if the map has no such zone, or the zone refuses the selection
     * @throws IllegalStateException    if the zone has fewer SOLD places, or a named seat is not SOLD
     */
    public void returnSoldToStock(int zoneId, InventorySelection selection) {
        InventoryZone zone = getZone(zoneId);
        zone.returnSoldToStock(selection);
    }

    /**
     * Turns held (RESERVED) inventory in a zone into SOLD, standing or seated.
     *
     * @param zoneId    the zone's id
     * @param selection what to confirm; when it carries an order key, the zone checks that order holds it
     * @throws IllegalArgumentException if the map has no such zone, or the zone refuses the selection
     * @throws IllegalStateException    if the zone holds less than the selection for that order
     */
    public void confirmSale(int zoneId, InventorySelection selection) {
        InventoryZone zone = getZone(zoneId);
        zone.confirmSale(selection);
    }












    private StandingZone getStandingZoneOrThrow(int zoneId) {
        InventoryZone zone = getZone(zoneId);
        if (zone instanceof StandingZone standingZone) {
            return standingZone;
        } else {
            throw new IllegalStateException("Zone is not a standing zone");
        }
    }
    private SeatedZone getSeatedZoneOrThrow(int zoneId) {
        InventoryZone zone = getZone(zoneId);
        if (zone instanceof SeatedZone seatedZone) {
            return seatedZone;
        } else {
            throw new IllegalArgumentException("Zone is not a seated zone");
        }
    }









}
