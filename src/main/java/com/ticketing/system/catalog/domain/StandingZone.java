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
 * <p>Reservations are now tracked per-order-key so that the 3-phase checkout
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
                throw new IllegalStateException(
                        "Cannot release " + quantity + " tickets for order '" + key
                                + "'; only " + currentlyReserved + " reserved by that order");
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
                throw new IllegalStateException(
                        "Cannot confirm sale of " + quantity + " tickets for order '" + key
                                + "'; only " + currentlyReserved + " reserved by that order");
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

    //? Note: for riskness reasons, we'll just ignore this and we can just use the addPlaces and removePlaces methods to adjust capacity up or down as needed.
    // public void setStandingCapacity(int newCapacity) {
    //     synchronized (inventoryLock) {
    //         if (newCapacity < 0) {
    //             throw new IllegalArgumentException("Capacity cannot be negative");
    //         }

    //         if (newCapacity < reservedAmount + soldAmount) {
    //             throw new IllegalArgumentException("New capacity cannot be less than reserved + sold tickets");
    //         }

    //         this.capacity = newCapacity;
    //     }
    // }

    public void addPlaces(int amountToAdd) {
        // can add places without really checking, not harmful
        synchronized (inventoryLock) {
            validatePositiveQuantity(amountToAdd);
            this.capacity += amountToAdd;
            checkInvariants();
        }
    }

    public void removePlaces(int amountToRemove) {
        // can only remove places if it doesn't cause capacity to drop below reserved + sold,
        // otherwise we would have to cancel existing reservations or sales, which is not allowed.
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
            if (price < 0) {
                throw new IllegalStateException("StandingZone invariant violated: price must be >= 0 (was " + price + ")");
            }
        }
    }
}
