package com.ticketing.system.catalog.application.dto;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.ticketing.system.catalog.domain.InventorySelection;

/**
 * Application-layer DTO for inventory selection input.
 *
 * This DTO is allowed to exist in the application layer, but domain classes
 * should not import it. Application services should convert it into the domain
 * value object InventorySelection before calling domain behavior.
 */
// This avoids passing random combinations of: quantity, ticketId, seatNumber and seatNumbers, all over the system.
public class InventorySelectionDTO {

    private final int quantity;                  // tickets quantity, For standing zones, this is the only relevant field.
    private final List<String> seatNumbers;      // only non-empty For seated zones, must contain no duplicates.

    public InventorySelectionDTO(int quantity, List<String> seatNumbers) {
        this.quantity = quantity;
        this.seatNumbers = seatNumbers == null ? List.of() : List.copyOf(seatNumbers);
    }

    public static InventorySelectionDTO standing(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        return new InventorySelectionDTO(quantity, List.of());
    }

    public static InventorySelectionDTO seated(List<String> seatNumbers) {
        if (seatNumbers == null || seatNumbers.isEmpty()) {
            throw new IllegalArgumentException("Seat numbers must be non-empty");
        }

        Set<String> unique = new HashSet<>(seatNumbers);
        if (unique.size() != seatNumbers.size()) {
            throw new IllegalArgumentException("Duplicate seat numbers are not allowed");
        }

        for (String seatNumber : seatNumbers) {
            if (seatNumber == null || seatNumber.isBlank()) {
                throw new IllegalArgumentException("Seat number must be non-blank");
            }
        }

        return new InventorySelectionDTO(seatNumbers.size(), new ArrayList<>(seatNumbers));
    }

    public InventorySelection toDomainSelection() {
        if (isStandingSelection()) {
            return InventorySelection.standing(quantity);
        }

        return InventorySelection.seated(seatNumbers);
    }

    public int getQuantity() {
        return quantity;
    }

    public List<String> getSeatNumbers() {
        return seatNumbers;
    }

    public boolean isStandingSelection() {
        return seatNumbers.isEmpty();
    }

    public boolean isSeatedSelection() {
        return !seatNumbers.isEmpty();
    }
}