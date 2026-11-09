package com.ticketing.system.catalog.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.ticketing.system.shared.InvariantChecked;

/**
 * Domain value object representing a buyer's inventory selection. It replaces passing loose
 * combinations of quantity, ticket id, seat number and seat numbers around the system. It is immutable.
 *
 * <p>Two legal modes:
 * <ol>
 *   <li>Standing-zone selection: {@code quantity > 0} and {@code seatNumbers} is empty.</li>
 *   <li>Seated-zone selection: {@code seatNumbers} is non-empty and {@code quantity == seatNumbers.size()}.</li>
 * </ol>
 *
 * <p>This class belongs in the domain because Event, VenueMap, InventoryZone,
 * StandingZone and SeatedZone all need to reason about this
 * concept as part of business logic.
 */
public final class InventorySelection implements InvariantChecked {

    private final int quantity;                // tickets quantity, For standing zones, this is the only relevant field.
    private final List<String> seatNumbers;    // only non-empty For seated zones, must contain no duplicates.
    private final String orderKey;             // identifies which order holds this reservation; null means no owner.
    // When the buyer's hold runs out, as UTC wall time (ADR-008); sales owns the hold duration and passes
    // it in. Null when no hold is recorded (a release, or a test reserving directly on a zone).
    private final LocalDateTime holdUntil;

    private InventorySelection(int quantity, List<String> seatNumbers, String orderKey, LocalDateTime holdUntil) {
        this.quantity = quantity;
        this.seatNumbers = seatNumbers == null ? List.of() : List.copyOf(seatNumbers);
        this.orderKey = orderKey;
        this.holdUntil = holdUntil;
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

    /**
     * Creates a standing selection with no order key and no hold end.
     *
     * @param quantity the number of places; positive
     * @throws IllegalArgumentException if {@code quantity} is not positive
     */
    public static InventorySelection standing(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        return new InventorySelection(quantity, List.of(), null, null);
    }

    /**
     * Creates a standing selection held by {@code orderKey}, with no hold end.
     *
     * @param quantity the number of places; positive
     * @param orderKey the holding order's key; a {@code null} or blank key stands for the shared
     *                 anonymous hold in a standing zone
     * @throws IllegalArgumentException if {@code quantity} is not positive
     */
    public static InventorySelection standing(int quantity, String orderKey) {
        return standing(quantity, orderKey, null);
    }

    /**
     * A standing selection held by {@code orderKey} until {@code holdUntil}.
     *
     * @param quantity  the number of places; positive
     * @param orderKey  the holding order's key; a {@code null} or blank key stands for the shared
     *                  anonymous hold in a standing zone
     * @param holdUntil when the hold runs out (UTC wall time, ADR-008), or null if none is recorded
     */
    public static InventorySelection standing(int quantity, String orderKey, LocalDateTime holdUntil) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        return new InventorySelection(quantity, List.of(), orderKey, holdUntil);
    }

    /**
     * Creates a seated selection with no order key and no hold end. The quantity is the number of seats.
     *
     * @param seatNumbers the seat labels; at least one, non-blank and distinct; copied
     * @throws IllegalArgumentException if {@code seatNumbers} is {@code null} or empty, or has a null,
     *                                  blank or repeated label
     */
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

        return new InventorySelection(seatNumbers.size(), new ArrayList<>(seatNumbers), null, null);
    }

    /**
     * Creates a seated selection held by {@code orderKey}, with no hold end.
     *
     * @param seatNumbers the seat labels; at least one, non-blank and distinct; copied
     * @param orderKey    the holding order's key; a seated zone requires a non-blank key to reserve
     * @throws IllegalArgumentException if {@code seatNumbers} is {@code null} or empty, or has a null,
     *                                  blank or repeated label
     */
    public static InventorySelection seated(List<String> seatNumbers, String orderKey) {
        return seated(seatNumbers, orderKey, null);
    }

    /**
     * A seated selection held by {@code orderKey} until {@code holdUntil}; each reserved seat records it.
     *
     * @param seatNumbers the seat labels; at least one, non-blank and distinct; copied
     * @param orderKey    the holding order's key; a seated zone requires a non-blank key to reserve
     * @param holdUntil when the hold runs out (UTC wall time, ADR-008), or null if none is recorded
     */
    public static InventorySelection seated(List<String> seatNumbers, String orderKey, LocalDateTime holdUntil) {
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

        return new InventorySelection(seatNumbers.size(), new ArrayList<>(seatNumbers), orderKey, holdUntil);
    }

    /** @return the number of places or seats selected; for a seated selection, the number of labels */
    public int getQuantity() {
        return quantity;
    }

    /** @return the selected seat labels as an unmodifiable list; empty for a standing selection */
    public List<String> getSeatNumbers() {
        return seatNumbers;
    }

    /** The order key that owns this reservation hold, or {@code null} if ownership is not tracked. */
    public String getOrderKey() {
        return orderKey;
    }

    /** When the hold on these units runs out (UTC wall time), or {@code null} if none is recorded. */
    public LocalDateTime getHoldUntil() {
        return holdUntil;
    }

    public boolean isStandingSelection() {
        return seatNumbers.isEmpty();
    }

    public boolean isSeatedSelection() {
        return !seatNumbers.isEmpty();
    }

    /** Two selections are equal when their quantity and seat labels match; the order key and hold end are ignored. */
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