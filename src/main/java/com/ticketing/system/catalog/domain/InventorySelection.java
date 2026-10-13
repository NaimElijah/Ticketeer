package com.ticketing.system.catalog.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.ticketing.system.shared.InvariantChecked;

/**
 * Domain value object representing a buyer's inventory selection.
 *
 * Two legal modes:
 * 1. Standing-zone selection: quantity > 0 and seatNumbers is empty.
 * 2. Seated-zone selection: seatNumbers is non-empty and quantity == seatNumbers.size().
 *
 * This class belongs in the domain because Event, VenueMap, InventoryZone,
 * StandingZone, SeatedZone, and ActiveOrder all need to reason about this
 * concept as part of business logic.
 */
// This avoids passing random combinations of: quantity, ticketId, seatNumber and seatNumbers, all over the system.
public final class InventorySelection implements InvariantChecked {

    private final int quantity;                // tickets quantity, For standing zones, this is the only relevant field.
    private final List<String> seatNumbers;    // only non-empty For seated zones, must contain no duplicates.
    private final String orderKey;             // identifies which ActiveOrder holds this reservation; null means "no ownership check".

    private InventorySelection(int quantity, List<String> seatNumbers, String orderKey) {
        this.quantity = quantity;
        this.seatNumbers = seatNumbers == null ? List.of() : List.copyOf(seatNumbers);
        this.orderKey = orderKey;
        checkInvariants();
    }

    @Override
    public void checkInvariants() {
        if (seatNumbers == null) {
            throw new IllegalStateException("InventorySelection invariant violated: seatNumbers must not be null");
        }
        if (seatNumbers.isEmpty()) {
            // standing selection
            if (quantity <= 0) {
                throw new IllegalStateException(
                        "InventorySelection invariant violated: standing selection quantity must be positive (was " + quantity + ")");
            }
        } else {
            // seated selection
            if (quantity != seatNumbers.size()) {
                throw new IllegalStateException("InventorySelection invariant violated: seated selection quantity ("
                        + quantity + ") must equal seatNumbers size (" + seatNumbers.size() + ")");
            }
            Set<String> unique = new HashSet<>(seatNumbers);
            if (unique.size() != seatNumbers.size()) {
                throw new IllegalStateException("InventorySelection invariant violated: duplicate seat numbers are not allowed");
            }
            for (String seatNumber : seatNumbers) {
                if (seatNumber == null || seatNumber.isBlank()) {
                    throw new IllegalStateException("InventorySelection invariant violated: seat number must be non-blank");
                }
            }
        }
    }

    public static InventorySelection standing(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        return new InventorySelection(quantity, List.of(), null);
    }

    public static InventorySelection standing(int quantity, String orderKey) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        return new InventorySelection(quantity, List.of(), orderKey);
    }

    public static InventorySelection seated(List<String> seatNumbers) {
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

        return new InventorySelection(seatNumbers.size(), new ArrayList<>(seatNumbers), null);
    }

    public static InventorySelection seated(List<String> seatNumbers, String orderKey) {
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

        return new InventorySelection(seatNumbers.size(), new ArrayList<>(seatNumbers), orderKey);
    }

    public int getQuantity() {
        return quantity;
    }

    public List<String> getSeatNumbers() {
        return seatNumbers;
    }

    /** The order key that owns this reservation hold, or {@code null} if ownership is not tracked. */
    public String getOrderKey() {
        return orderKey;
    }

    public boolean isStandingSelection() {
        return seatNumbers.isEmpty();
    }

    public boolean isSeatedSelection() {
        return !seatNumbers.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InventorySelection)) return false;
        InventorySelection other = (InventorySelection) o;
        return quantity == other.quantity && seatNumbers.equals(other.seatNumbers);
    }

    @Override
    public int hashCode() {
        int result = quantity;
        result = 31 * result + seatNumbers.hashCode();
        return result;
    }
}