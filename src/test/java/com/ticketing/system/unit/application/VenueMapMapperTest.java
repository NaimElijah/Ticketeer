package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ticketing.system.Core.Application.dto.GridPlacementDTO;
import com.ticketing.system.Core.Application.dto.InventoryZoneDTO;
import com.ticketing.system.Core.Application.dto.VenueMapDTO;
import com.ticketing.system.Core.Application.dtoMappers.VenueMapMapper;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.StandingZone;
import com.ticketing.system.catalog.domain.VenueMap;

class VenueMapMapperTest {

    private final VenueMapMapper mapper = new VenueMapMapper();
    private final Location LOCATION = new Location("Belgium", "Brussels");

    @Test
    void GivenPlacedAndUnplacedZones_WhenMapToDTO_ThenGridAndPlacementsAreCopied() {
        StandingZone placed = new StandingZone(1, "Placed", 10, 50);
        StandingZone unplaced = new StandingZone(2, "Unplaced", 10, 50);

        VenueMap venueMap = new VenueMap(7, LOCATION, List.of(placed, unplaced), 3, 3);
        venueMap.placeZoneOnGrid(1, 1, 2, 1, 2);

        VenueMapDTO dto = mapper.venueMapToVenueMapDTO(venueMap);

        assertEquals(3, dto.gridRows());
        assertEquals(3, dto.gridCols());
        assertEquals(2, dto.inventoryZones().size());

        InventoryZoneDTO placedDto = findZone(dto.inventoryZones(), "Placed");
        GridPlacementDTO placement = placedDto.getPlacement();
        assertNotNull(placement);
        assertEquals(1, placement.row());
        assertEquals(2, placement.col());
        assertEquals(1, placement.rowSpan());
        assertEquals(2, placement.colSpan());

        InventoryZoneDTO unplacedDto = findZone(dto.inventoryZones(), "Unplaced");
        assertNull(unplacedDto.getPlacement());
    }

    private InventoryZoneDTO findZone(List<InventoryZoneDTO> zones, String name) {
        return zones.stream()
                .filter(z -> z.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("zone not found: " + name));
    }
}
