package com.ticketing.system.catalog.application.dto;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.ticketing.system.catalog.domain.InventorySelection;

/**
 * Application-layer DTO for inventory selection input. It avoids passing loose combinations of
 * quantity, ticket id, seat number and seat numbers around the system.
 *
 * <p>This DTO is allowed to exist in the application layer, but domain classes
 * should not import it. Application services should convert it into the domain
 * value object InventorySelection before calling domain behavior.
 */
public class InventorySelectionDTO {

    private final int quantity;
    private final List<String> seatNumbers;

    /**
     * Creates a selection from the given quantity and seat labels. The {@link #standing} and
     * {@link #seated} factories check their arguments up front; the domain {@code InventorySelection}
     * checks its own invariants when this is converted.
     *
     * @param quantity    the number of tickets; for a standing zone this is the only field that matters
     * @param seatNumbers the seat labels, which only a seated zone uses and which must not repeat;
     *                    {@code null} is stored as empty, and the list is copied
     */
    public InventorySelectionDTO(int quantity, List<String> seatNumbers) {
        this.quantity = quantity;
        this.seatNumbers = seatNumbers == null ? List.of() : List.copyOf(seatNumbers);
    }

    /**
     * Creates a standing selection.
     *
     * @param quantity the number of places; positive
     * @throws IllegalArgumentException if {@code quantity} is not positive
     */
    public static InventorySelectionDTO standing(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        return new InventorySelectionDTO(quantity, List.of());
    }

    /**
     * Creates a seated selection; the quantity is the number of seats.
     *
     * @param seatNumbers the seat labels; at least one, non-blank and distinct; copied
     * @throws IllegalArgumentException if {@code seatNumbers} is {@code null} or empty, or has a null,
     *                                  blank or repeated label
     */
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

    /**
     * Converts this selection to the domain value object, with no order key and no hold end.
     *
     * @return a standing selection if there are no seat labels, otherwise a seated one
     * @throws IllegalArgumentException if a standing quantity is not positive, or the seat labels are
     *                                  blank or repeated
     */
    public InventorySelection toDomainSelection() {
        if (isStandingSelection()) {
            return InventorySelection.standing(quantity);
        }

        return InventorySelection.seated(seatNumbers);
    }

    /** @return the number of places or seats selected */
    public int getQuantity() {
        return quantity;
    }

    /** @return the selected seat labels as an unmodifiable list; empty for a standing selection */
    public List<String> getSeatNumbers() {
        return seatNumbers;
    }

    /** @return {@code true} if there are no seat labels, which makes this a standing selection */
    public boolean isStandingSelection() {
        return seatNumbers.isEmpty();
    }

    public boolean isSeatedSelection() {
        return !seatNumbers.isEmpty();
    }
}