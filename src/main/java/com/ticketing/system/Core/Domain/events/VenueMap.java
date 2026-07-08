package com.ticketing.system.Core.Domain.events;

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

// V3: an owned @Entity of Event (mapped via Event's @OneToOne). Assigned int @Id (minted by
// IEventRepository.nextVenueMapId()). location is an @Embedded value (nullable). The polymorphic
// inventoryZones are owned children (@OneToMany cascade-all + orphan-removal) loaded eagerly. A
// protected no-arg ctor lets Hibernate hydrate.
@Entity
@Table(name = "venue_maps")
public class VenueMap implements InvariantChecked {

    /** Default venue canvas grid when none is specified. */
    public static final int DEFAULT_GRID_ROWS = 3;
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

    public VenueMap(int id, Location location, List<InventoryZone> inventoryZones) {
        this(id, location, inventoryZones, DEFAULT_GRID_ROWS, DEFAULT_GRID_COLS);
    }

    public VenueMap(int id, Location location, List<InventoryZone> inventoryZones, int gridRows, int gridCols) {
        // Null-guarded here because the list is copied before checkInvariants() runs;
        // a null input would otherwise NPE in the copy rather than fail cleanly.
        if (inventoryZones == null) {
            throw new IllegalArgumentException("Inventory zones cannot be null");
        }
        if (gridRows < 1 || gridCols < 1) {
            throw new IllegalArgumentException("Venue grid must be at least 1x1");
        }

        this.id = id;
        this.location = location;
        this.inventoryZones = new ArrayList<>(inventoryZones);
        this.gridRows = gridRows;
        this.gridCols = gridCols;
        // initialize nextZoneId to one greater than the max existing zone ID, or 1 if there are no zones.
        // This is for convenience when adding new zones to the venue map.
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

      public List<InventoryZone> getInventoryZones() {
        return List.copyOf(inventoryZones);
    }

   

    public Location getLocation() {
        return location;
    }

    public void setLocation(Location location) {
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
     * Places a zone on the venue grid, validating it stays within the
     * gridRows × gridCols bounds and does not overlap an already-placed zone.
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

    public InventoryZone getZone(int zoneId) {
        for (InventoryZone zone : inventoryZones) {
            if (zone.getId() == zoneId) {
                return zone;
            }
        }
        throw new IllegalArgumentException("Zone not found");
    }
    
    public boolean checkAvailability(int zoneId, int quantity) {
        InventoryZone zone = getZone(zoneId);
        return zone.checkAvailability(quantity);
    }

    // True if any zone still has at least one AVAILABLE place/seat across the venue.
    public boolean hasAvailableInventory() {
        return inventoryZones.stream().anyMatch(zone -> zone.getAvailableAmount() > 0);
    }





    public void addPlacesToStandingZone(int zoneId, int amountToAdd) {
        StandingZone zone = getStandingZoneOrThrow(zoneId);
        zone.addPlaces(amountToAdd);
    }

    public void removePlacesFromStandingZone(int zoneId, int amountToRemove) {
        StandingZone zone = getStandingZoneOrThrow(zoneId);
        zone.removePlaces(amountToRemove);
    }



    public void addSeatsToSeatedZone(int zoneId, List<Seat> seatsToAdd) {
        SeatedZone zone = getSeatedZoneOrThrow(zoneId);
        zone.addSeats(seatsToAdd);
    }

    public void removeSeatsFromSeatedZone(int zoneId, List<String> seatLabelsToRemove) {
        SeatedZone zone = getSeatedZoneOrThrow(zoneId);
        zone.removeSeats(seatLabelsToRemove);
    }





    public int getNextZoneId() {
        return nextZoneId;
    }


    public void addZone(InventoryZone zone) {
        // zone id already given in calling method with the nextZoneId, but we validate here just in case to ensure no duplicate 
        // zone ids or names are added to the venue map, which could cause issues when trying to retrieve zones by id or name later on.
        validateNewZone(zone);

        inventoryZones.add(zone);

        if (zone.getId() >= nextZoneId) {
            nextZoneId = zone.getId() + 1;
        }
        checkInvariants();
    }


    public InventoryZone removeZone(int zoneId) {
        InventoryZone zone = getZone(zoneId);

        if (zoneHasReservedOrSoldInventory(zone)) {
            throw new IllegalStateException("Cannot remove zone " + zoneId + " because it has reserved or sold inventory");
        }

        inventoryZones.remove(zone);
        checkInvariants();
        return zone;
    }


    private void validateNewZone(InventoryZone zone) {
        if (zone == null) {
            throw new IllegalArgumentException("Zone cannot be null");
        }

        if (zone.getId() <= 0) {
            throw new IllegalArgumentException("Zone id must be positive");
        }

        for (InventoryZone existingZone : inventoryZones) {
            if (existingZone.getId() == zone.getId()) {
                throw new IllegalArgumentException("Zone id already exists: " + zone.getId());
            }

            if (existingZone.getName().equalsIgnoreCase(zone.getName())) {
                throw new IllegalArgumentException("Zone name already exists: " + zone.getName());
            }
        }

        zone.checkInvariants();
    }


    private boolean zoneHasReservedOrSoldInventory(InventoryZone zone) {
        return zone.getReservedAmount() > 0 || zone.getSoldAmount() > 0;
    }









    
    // supports both standing and seated zones via the InventorySelection abstraction, simply don't include seat numbers for standing zones.
    public void reserveInventory(int zoneId, InventorySelection selection) {
        InventoryZone zone = getZone(zoneId);
        zone.reserve(selection);
    }


    // supports both standing and seated zones via the InventorySelection abstraction, simply don't include seat numbers for standing zones.
    public void releaseInventory(int zoneId, InventorySelection selection) {
        InventoryZone zone = getZone(zoneId);
        zone.release(selection);
    }

    // Return previously SOLD inventory to AVAILABLE (member refund) — standing + seated.
    public void returnSoldToStock(int zoneId, InventorySelection selection) {
        InventoryZone zone = getZone(zoneId);
        zone.returnSoldToStock(selection);
    }

    // supports both standing and seated zones via the InventorySelection abstraction, simply don't include seat numbers for standing zones.
    public void confirmSale(int zoneId, InventorySelection selection) {
        InventoryZone zone = getZone(zoneId);
        zone.confirmSale(selection);
    }












    // getter
    private StandingZone getStandingZoneOrThrow(int zoneId) {
        InventoryZone zone = getZone(zoneId);
        if (zone instanceof StandingZone standingZone) {
            return standingZone;
        } else {
            throw new IllegalStateException("Zone is not a standing zone");
        }
    }
    // getter
    private SeatedZone getSeatedZoneOrThrow(int zoneId) {
        InventoryZone zone = getZone(zoneId);
        if (zone instanceof SeatedZone seatedZone) {
            return seatedZone;
        } else {
            throw new IllegalArgumentException("Zone is not a seated zone");
        }
    }









}
