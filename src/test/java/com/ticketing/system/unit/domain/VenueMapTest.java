package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.domain.InventoryZone;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.StandingZone;
import com.ticketing.system.catalog.domain.VenueMap;
import com.ticketing.system.support.BaseDomainTest;

public class VenueMapTest extends BaseDomainTest {

    private final Location LOCATION = new Location("Belgium", "Brussels");

    private VenueMap mapWithZones(InventoryZone... zones) {
        return track(new VenueMap(1, LOCATION, List.of(zones), 3, 3));
    }

    @Test
    void GivenThreeArgCtor_WhenConstructed_ThenGridDefaultsTo3x3() {
        VenueMap map = track(new VenueMap(1, LOCATION, List.of(new StandingZone(1, "A", 10, 50))));

        assertEquals(VenueMap.DEFAULT_GRID_ROWS, map.getGridRows());
        assertEquals(VenueMap.DEFAULT_GRID_COLS, map.getGridCols());
        assertEquals(3, map.getGridRows());
        assertEquals(3, map.getGridCols());
    }

    @Test
    void GivenValidPlacement_WhenPlaceZoneOnGrid_ThenZoneReflectsIt() {
        StandingZone zone = new StandingZone(1, "A", 10, 50);
        VenueMap map = mapWithZones(zone);

        // zoneId=1, row=1, col=1, rowSpan=2, colSpan=2
        map.placeZoneOnGrid(1, 1, 1, 2, 2);

        InventoryZone placed = map.getZone(1);
        assertTrue(placed.hasGridPlacement());
        assertEquals(1, placed.getGridRow());
        assertEquals(1, placed.getGridCol());
        assertEquals(2, placed.getGridRowSpan());
        assertEquals(2, placed.getGridColSpan());
    }

    @Test
    void GivenPlacementExceedingGrid_WhenPlaceZoneOnGrid_ThenThrowsIllegalArgument() {
        StandingZone zone = new StandingZone(1, "A", 10, 50);
        VenueMap map = mapWithZones(zone);

        // zoneId=1, row=3, col=1, rowSpan=2 -> row 3 + rowSpan 2 - 1 = 4 > gridRows(3)
        assertThrows(IllegalArgumentException.class, () -> map.placeZoneOnGrid(1, 3, 1, 2, 2));
        assertFalse(map.getZone(1).hasGridPlacement());
    }

    @Test
    void GivenSubOneCoordinates_WhenPlaceZoneOnGrid_ThenThrowsIllegalArgument() {
        StandingZone zone = new StandingZone(1, "A", 10, 50);
        VenueMap map = mapWithZones(zone);

        // zoneId=1, row=0 (< 1) -> rejected
        assertThrows(IllegalArgumentException.class, () -> map.placeZoneOnGrid(1, 0, 1, 1, 1));
    }

    @Test
    void GivenOverlappingPlacement_WhenPlaceSecondZone_ThenThrowsIllegalState() {
        StandingZone zoneA = new StandingZone(1, "A", 10, 50);
        StandingZone zoneB = new StandingZone(2, "B", 10, 50);
        VenueMap map = mapWithZones(zoneA, zoneB);

        map.placeZoneOnGrid(1, 1, 1, 2, 2);
        // B's rectangle (rows 1-2, cols 1-2) overlaps A's (rows 1-2, cols 1-2)
        assertThrows(IllegalStateException.class, () -> map.placeZoneOnGrid(2, 2, 2, 2, 2));
    }

    @Test
    void GivenNonOverlappingAdjacentPlacements_WhenPlaceBothZones_ThenBothSucceed() {
        StandingZone zoneA = new StandingZone(1, "A", 10, 50);
        StandingZone zoneB = new StandingZone(2, "B", 10, 50);
        VenueMap map = mapWithZones(zoneA, zoneB);

        map.placeZoneOnGrid(1, 1, 1, 1, 1);   // top-left cell
        map.placeZoneOnGrid(2, 1, 2, 1, 1);   // immediately to the right

        assertTrue(map.getZone(1).hasGridPlacement());
        assertTrue(map.getZone(2).hasGridPlacement());
        assertEquals(1, map.getZone(2).getGridRow());
        assertEquals(2, map.getZone(2).getGridCol());
    }
}
