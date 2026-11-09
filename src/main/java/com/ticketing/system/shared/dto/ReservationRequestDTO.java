package com.ticketing.system.shared.dto;

import java.util.List;

/**
 * A reservation request as one value (UC-5 / UC-9). No service takes it today: {@code ReservationService}'s
 * {@code reserveForMember} and {@code reserveForGuest} take the ids and a catalog
 * {@code InventorySelectionDTO}.
 *
 * <p>Two selection modes (II.2.5): specific seats ({@code seatNumbers}), or a quantity in a zone
 * ({@code zoneId} and {@code quantity}).
 *
 * @param quantity    how many tickets of a standing zone
 * @param seatNumbers the labels of the seats of a seated zone
 */
public record ReservationRequestDTO(
        int eventId,
        int zoneId,
        Integer quantity,
        List<String> seatNumbers
) {}
