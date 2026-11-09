package com.ticketing.system.catalog.domain;

import java.util.HashMap;
import java.util.Map;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Transient;

/**
 * Counter-based zone — no addressable seats, just "N tickets available".
 *
 * <p>Reservations are tracked per order key so that the 3-phase checkout
 * can confirm (or release) only the portion that belongs to a specific
 * {@code ActiveOrder}, even after the event lock is released during
 * the payment/issuance phase.
 *
 * <p>When an {@link InventorySelection} carries no {@code orderKey} (null),
 * the reservation is stored under the sentinel key {@code "anonymous"}.
 * All synchronization happens via a private {@code inventoryLock}.
 */
@Entity
@DiscriminatorValue("STANDING")
public class StandingZone extends InventoryZone {

    @Column(nullable = false)
    private int capacity;
    /** Maps orderKey → quantity reserved by that order. used for tracking reservations per order. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "standing_zone_reservations", joinColumns = @JoinColumn(name = "zone_pk"))
    @MapKeyColumn(name = "order_key")
    @Column(name = "quantity")
    private Map<String, Integer> reservedByOrderKey = new HashMap<>();
    @Column(name = "sold_amount", nullable = false)
    private int soldAmount;
    @Transient
    private final Object inventoryLock = new Object();

    /** Sentinel used when no order key is provided (backwards-compatible callers). */
    private static final String ANONYMOUS_KEY = "anonymous";

    /** For JPA only — do not call from application code. */
    protected StandingZone() { }

    /**
     * Creates a zone with the given capacity and nothing reserved or sold.
     *
     * @param id       the zone's id within its map
     * @param name     the zone's name; non-blank, at most {@code TextLimits.MAX_TEXT_LENGTH} characters
     * @param capacity the number of places; not negative
     * @param price    the price of one place, from 0 to {@link #MAX_PRICE}
     * @throws com.ticketing.system.shared.exception.BusinessRuleViolationException if the name is too
     *     long; its message is shown to the user
     * @throws IllegalStateException if the name is blank, the capacity is negative, or the price is out
     *                               of range
     */
    public StandingZone(int id, String name, int capacity, double price) {
        super(id, name, price);
        this.capacity = capacity;
        this.soldAmount = 0;
        checkInvariants();
    }

    @Override
    public int getAvailableAmount() {
        synchronized (inventoryLock) {
            return capacity - totalReservedWithoutLock() - soldAmount;
        }
    }

    @Override
    public int getReservedAmount() {
        synchronized (inventoryLock) {
            return totalReservedWithoutLock();
        }
    }

    @Override
    public int getSoldAmount() {
        synchronized (inventoryLock) {
            return soldAmount;
        }
    }

    @Override
    public boolean checkAvailability(int quantity) {
        synchronized (inventoryLock) {
            validatePositiveQuantity(quantity);

            int availableAmount = capacity - totalReservedWithoutLock() - soldAmount;

            if (availableAmount < quantity) {
                throw new IllegalStateException("remaining " + availableAmount + " tickets available");
            }

            return true;
        }
    }

    @Override
    public boolean reserve(InventorySelection selection) {
        if (!selection.isStandingSelection()) {
            throw new IllegalArgumentException("Standing zone cannot reserve specific seats");
        }
        int quantity = selection.getQuantity();
        String key = resolveKey(selection.getOrderKey());

        synchronized (inventoryLock) {
            validatePositiveQuantity(quantity);

            int availableAmount = capacity - totalReservedWithoutLock() - soldAmount;

            if (availableAmount < quantity) {
                throw new IllegalStateException("remaining " + availableAmount + " tickets available");
            }

            reservedByOrderKey.merge(key, quantity, Integer::sum);
            checkInvariants();
            return true;
        }
    }

    @Override
    public boolean release(InventorySelection selection) {
        if (!selection.isStandingSelection()) {
            throw new IllegalArgumentException("Standing zone cannot release specific seats");
        }
        int quantity = selection.getQuantity();
        String key = resolveKey(selection.getOrderKey());

        synchronized (inventoryLock) {
            validatePositiveQuantity(quantity);

            int currentlyReserved = reservedByOrderKey.getOrDefault(key, 0);
            if (currentlyReserved < quantity) {
                // A member can read this refusal, so it names no order key (an internal handle).
                throw new IllegalStateException("Cannot release " + quantity
                        + " tickets: this order holds only " + currentlyReserved + " in this zone.");
            }

            if (currentlyReserved == quantity) {
                reservedByOrderKey.remove(key);
            } else {
                reservedByOrderKey.put(key, currentlyReserved - quantity);
            }
            checkInvariants();
            return true;
        }
    }

    @Override
    public boolean confirmSale(InventorySelection selection) {
        if (!selection.isStandingSelection()) {
            throw new IllegalArgumentException("Standing zone cannot confirm specific seats");
        }
        int quantity = selection.getQuantity();
        String key = resolveKey(selection.getOrderKey());

        synchronized (inventoryLock) {
            validatePositiveQuantity(quantity);

            int currentlyReserved = reservedByOrderKey.getOrDefault(key, 0);
            if (currentlyReserved < quantity) {
                // A member can read this refusal, so it names no order key (an internal handle).
                throw new IllegalStateException("Cannot confirm the sale of " + quantity
                        + " tickets: this order holds only " + currentlyReserved + " in this zone.");
            }

            if (currentlyReserved == quantity) {
                reservedByOrderKey.remove(key);
            } else {
                reservedByOrderKey.put(key, currentlyReserved - quantity);
            }
            soldAmount += quantity;
            checkInvariants();
            return true;
        }
    }

    /**
     * Return previously SOLD places to AVAILABLE (member refund) by decrementing the sold counter.
     * Distinct from {@link #release}, which frees RESERVED holds.
     *
     * @throws IllegalStateException if more places are returned than are currently sold
     */
    @Override
    public boolean returnSoldToStock(InventorySelection selection) {
        if (!selection.isStandingSelection()) {
            throw new IllegalArgumentException("Standing zone cannot return specific seats");
        }
        int quantity = selection.getQuantity();

        synchronized (inventoryLock) {
            validatePositiveQuantity(quantity);
            if (soldAmount < quantity) {
                throw new IllegalStateException(
                        "Cannot return " + quantity + " tickets to stock; only " + soldAmount + " sold");
            }
            soldAmount -= quantity;
            checkInvariants();
            return true;
        }
    }

    @Override
    public int getCapacity() {
        synchronized (inventoryLock) {
            return capacity;
        }
    }

    // There is deliberately no capacity setter: capacity changes only through addPlaces and removePlaces.
    /**
     * Adds places to the zone's capacity. Adding never harms existing holds or sales, so there is no
     * further check.
     *
     * @param amountToAdd the number of places to add; positive
     * @throws IllegalArgumentException if {@code amountToAdd} is not positive
     */
    public void addPlaces(int amountToAdd) {
        synchronized (inventoryLock) {
            validatePositiveQuantity(amountToAdd);
            this.capacity += amountToAdd;
            checkInvariants();
        }
    }

    /**
     * Removes places from the zone's capacity. Capacity may not drop below the reserved plus sold places:
     * that would cancel existing holds or sales.
     *
     * @param amountToRemove the number of places to remove; positive and at most the available places
     * @throws IllegalArgumentException if {@code amountToRemove} is not positive or exceeds the available
     *                                  places
     */
    public void removePlaces(int amountToRemove) {
        synchronized (inventoryLock) {
            validatePositiveQuantity(amountToRemove);

            int availableAmount = capacity - totalReservedWithoutLock() - soldAmount;
            if (amountToRemove > availableAmount) {
                throw new IllegalArgumentException(
                        "Cannot remove " + amountToRemove + " places; only "
                                + availableAmount + " places are available to remove");
            }

            this.capacity -= amountToRemove;
            checkInvariants();
        }
    }

    @Override
    public ZoneType getZoneType() {
        return ZoneType.STANDING;
    }

    private int totalReservedWithoutLock() {
        return reservedByOrderKey.values().stream().mapToInt(Integer::intValue).sum();
    }

    private String resolveKey(String orderKey) {
        return (orderKey != null && !orderKey.isBlank()) ? orderKey : ANONYMOUS_KEY;
    }

    private void validatePositiveQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
    }

    @Override
    public void checkInvariants() {
        synchronized (inventoryLock) {
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("StandingZone invariant violated: name must be non-blank");
            }
            if (capacity < 0) {
                throw new IllegalStateException("StandingZone invariant violated: capacity must be >= 0 (was " + capacity + ")");
            }
            int totalReserved = totalReservedWithoutLock();
            if (totalReserved < 0) {
                throw new IllegalStateException("StandingZone invariant violated: reservedAmount must be >= 0 (was " + totalReserved + ")");
            }
            if (totalReserved > capacity) {
                throw new IllegalStateException("StandingZone invariant violated: reservedAmount (" + totalReserved + ") must be <= capacity (" + capacity + ")");
            }
            if (!isPriceInBounds(price)) {
                throw new IllegalStateException("StandingZone invariant violated: price must be finite and in [0, "
                        + MAX_PRICE + "] (was " + price + ")");
            }
        }
    }
}
