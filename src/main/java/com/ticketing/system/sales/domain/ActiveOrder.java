package com.ticketing.system.sales.domain;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.identity.domain.Session;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.catalog.domain.InventorySelection;
import com.ticketing.system.shared.InvariantChecked;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

/**
 * ActiveOrder = a cart in progress.
 *
 * <p>Carries a dual identity (auth rework / D9a):
 * <ul>
 *   <li><b>Guest cart:</b> {@code userId == null}, {@code sessionId != null}.
 *       Bound to a Guest Session row by sessionId; deleted when that Session
 *       is swept or ended.</li>
 *   <li><b>Member cart (active session):</b> {@code userId != null} and
 *       {@code sessionId != null}. The sessionId is the currently-attached
 *       Member session.</li>
 *   <li><b>Member cart (between sessions):</b> {@code userId != null},
 *       {@code sessionId == null}. Survives logout per II.3.1 / D9a;
 *       restored on next login by {@link #attachToSession(String)}.</li>
 * </ul>
 *
 * <p>Invariant: at least one of userId / sessionId is non-null at all times.
 */
// V3: mapped to JPA. The stable orderKey (a UUID generated at construction) is the @Id — it already
// uniquely identifies the order's inventory holds, so it is the natural primary key. userId and
// sessionId are nullable by-id columns (a cart has at least one of them); status is stored by name;
// @Version drives optimistic locking. items are owned value rows (@ElementCollection of @Embeddable).
// itemsLock (a thread guard) is @Transient — re-created by the field initializer on hydrate. A
// protected no-arg ctor lets Hibernate hydrate; the public ctor still enforces the invariants.
//
// Identity collapse (one cart per user / per guest session) is enforced in the repository's save,
// reproducing the in-memory behaviour even though each cart has a distinct orderKey.
@Entity
@Table(name = "active_orders")
public class ActiveOrder implements InvariantChecked {

    @Column(name = "user_id")
    private Integer userId;
    @Column(name = "session_id")
    private String sessionId;
    /** Status value: order is being processed by checkout — no new items or concurrent checkouts. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActiveOrderStatus status;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "active_order_items", joinColumns = @JoinColumn(name = "order_key"))
    private List<CartLineItem> items;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Transient
    private final Object itemsLock = new Object();
    /**
     * Stable identity assigned at construction. Passed to {@link com.ticketing.system.catalog.domain.InventorySelection}
     * so that {@link com.ticketing.system.catalog.domain.StandingZone} and
     * {@link com.ticketing.system.catalog.domain.SeatedZone} can record which
     * order holds each reservation. Enables the 3-phase checkout to verify ownership
     * in checkout's Phase 3 without holding event locks during checkout's Phase 2 (payment/issuance).
     */
    @Id
    @Column(name = "order_key")
    private String orderKey = UUID.randomUUID().toString();

    @Version
    private Long version;

    /** For JPA only — do not call from application code. */
    protected ActiveOrder() {
        this.items = new ArrayList<>();
    }

    public ActiveOrder(Integer userId, String sessionId) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.status = ActiveOrderStatus.PRE_CHECKOUT;
        this.items = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
        checkInvariants();
    }






    /**
     * Legacy convenience for existing Member-only callers. Equivalent to
     * {@code new ActiveOrder(userId, null)} — a Member cart in the
     * between-sessions state. New code should prefer
     * {@link #forMember(int, String)} or {@link #forGuest(String)}.
     */
    public ActiveOrder(int userId) {
        this(Integer.valueOf(userId), null);
    }


    /** Member cart attached to an active session. */
    public static ActiveOrder forMember(int userId, String sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("forMember requires a non-null sessionId");
        }
        return new ActiveOrder(Integer.valueOf(userId), sessionId);
    }


    /** Guest cart bound to a session (no userId yet). */
    public static ActiveOrder forGuest(String sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("forGuest requires a non-null sessionId");
        }
        return new ActiveOrder(null, sessionId);
    }










    /**
     * Generic reservation entry point used by application services.
     * Standing/seated branching stays inside the domain object instead of being
     * duplicated in ReservationService.
     */
    public void addReservation(int eventId, int zoneId, InventorySelection selection, double price,
            LocalDateTime addedAt) {
        ensureModifiable();
        if (selection == null) {
            throw new IllegalArgumentException("Inventory selection is required");
        }

        if (selection.isStandingSelection()) {
            addStandingReservation(eventId, zoneId, selection.getQuantity(), price, addedAt);
        } else {
            addSeatedReservation(eventId, zoneId, selection.getSeatNumbers(), price, addedAt);
        }
    }

    
    /**
     * Generic removal entry point used by application services.
     */
    public void removeReservation(int eventId, int zoneId, InventorySelection selection) {
        ensureModifiable();
        if (selection == null) {
            throw new IllegalArgumentException("Inventory selection is required");
        }

        if (selection.isStandingSelection()) {
            removeStandingSpots(eventId, zoneId, selection.getQuantity());
        } else {
            removeSeats(eventId, zoneId, selection.getSeatNumbers());
        }
    }











    // changed name from addReservation to addStandingReservation and added addSeatedReservation to support seated zones as well.
    public void addStandingReservation(int eventId, int zoneId, int quantity, double price, LocalDateTime addedAt) {
        synchronized (itemsLock) {
            ensureModifiable();

            if (quantity <= 0) {
                throw new IllegalArgumentException("Quantity must be positive");
            }

            for (int i = 0; i < quantity; i++) {
                items.add(new CartLineItem(eventId, zoneId, null, price, addedAt));
            }
        }
    }


    public void addSeatedReservation(int eventId, int zoneId, List<String> seatNumbers, double price,
            LocalDateTime addedAt) {
        synchronized (itemsLock) {
            ensureModifiable();
            if (seatNumbers == null || seatNumbers.isEmpty()) {
                throw new IllegalArgumentException("Seat numbers must be non-empty");
            }
            // check that we're not adding duplicate seat numbers for the same event and zone that already exist in the active order.
            this.checkThatNotAddingDuplicateSeatNumbersForEventAndZone(eventId, zoneId, seatNumbers);

            for (String seatNumber : seatNumbers) {
                items.add(new CartLineItem(eventId, zoneId, seatNumber, price, addedAt));
            }
        }
    }


    












    public void removeStandingSpots(int eventId, int zoneId, int quantity) {
        synchronized (itemsLock) {
            ensureModifiable();

            if (quantity <= 0) {
                throw new IllegalArgumentException("Quantity must be positive");
            }
            if (!hasReservationForEventWithoutLock(eventId)) {
                throw new IllegalArgumentException("Active order does not contain this event");
            }

            // check that enough standing tickets exist in the active order before attempting removal, to avoid partial removals if the quantity is greater than the number of reserved tickets.
            int existingTickets = countStandingTicketsWithoutLock(eventId, zoneId);

            if (existingTickets < quantity) {
                throw new IllegalArgumentException("Not enough reserved tickets to remove");
            }


            int removedCount = 0;
            for (int i = 0; i < items.size() && removedCount < quantity; i++) {
                CartLineItem item = items.get(i);
                if (item.geteventId() == eventId &&
                        item.getzoneId() == zoneId &&
                        item.getSeatNumber() == null) { // only match standing tickets; expired included (R1: manual removal)
                    items.remove(i);
                    removedCount++;
                    i--; // adjust index after removal
                }
            }
        }
    }
    


    public void removeSeats(int eventId, int zoneId, List<String> seatNumbers) {
        this.checkThatSeatNumbersListDoesNotContainDuplicates(seatNumbers);

        synchronized (itemsLock) {
            ensureModifiable();

            if (!hasReservationForEventWithoutLock(eventId)) {
                throw new IllegalArgumentException("Active order does not contain this event");
            }

            // check that all seatNumbers exist in the active order before attempting removal, to avoid partial removals if an invalid seatNumber is provided.
            this.validateContainsSeats(eventId, zoneId, seatNumbers);

            // start removals
            for (String seatNumber : seatNumbers) {
                // expired included (R1: a buyer may manually remove an expired seat line).
                boolean removed = items.removeIf(item -> item.geteventId() == eventId &&
                        item.getzoneId() == zoneId &&
                        seatNumber.equals(item.getSeatNumber()));

                if (!removed) {
                    // check just in case even though we already checked that the seat numbers do exist, to avoid silent failures if there's a bug in the validation logic.
                    throw new IllegalArgumentException("Seat not found in active order: " + seatNumber);
                }
            }
        }
    }


    









    /**
     * Validation-only method. This is useful before releasing inventory from the event,
     * so a bad remove request does not accidentally put tickets back in stock.
     */
    public void validateContainsReservation(int eventId, int zoneId, InventorySelection selection) {
        if (selection == null) {
            throw new IllegalArgumentException("Inventory selection is required");
        }

        synchronized (itemsLock) {
            if (!hasReservationForEventWithoutLock(eventId)) {
                throw new IllegalArgumentException("Active order does not contain this event");
            }

            if (selection.isStandingSelection()) {
                int existingTickets = countStandingTicketsWithoutLock(eventId, zoneId);
                if (existingTickets < selection.getQuantity()) {
                    throw new IllegalArgumentException("Not enough reserved tickets to remove");
                }
            } else {
                validateContainsSeats(eventId, zoneId, selection.getSeatNumbers());
            }
        }
    }













    private void checkThatNotAddingDuplicateSeatNumbersForEventAndZone(int eventId, int zoneId,
            List<String> seatNumbers) {
        for (String seatNumber : seatNumbers) {
            boolean exists = items.stream().anyMatch(item -> item.geteventId() == eventId &&
                    item.getzoneId() == zoneId &&
                    seatNumber.equals(item.getSeatNumber()) &&
                    !item.isExpired());

            if (exists) {
                throw new IllegalArgumentException(
                        "Duplicate seat number for event and zone being added even though it already exists in the active order: "
                                + seatNumber);
            }
        }
        // that there aren't duplicates in seatnumbers
        checkThatSeatNumbersListDoesNotContainDuplicates(seatNumbers);
    }
    

    private void checkThatSeatNumbersListDoesNotContainDuplicates(List<String> seatNumbers) {
        if (seatNumbers.size() != new HashSet<>(seatNumbers).size()) {
            throw new IllegalArgumentException("Duplicate seat numbers provided in input list: " + seatNumbers);
        }
    }


    

    public void validateContainsSeats(int eventId, int zoneId, List<String> seatNumbers) {
        synchronized (itemsLock) {
            for (String seatNumber : seatNumbers) {
                // expired included (R1: validation for manual removal of an expired seat line).
                boolean exists = items.stream().anyMatch(item -> item.geteventId() == eventId &&
                        item.getzoneId() == zoneId &&
                        seatNumber.equals(item.getSeatNumber()));

                if (!exists) {
                    throw new IllegalArgumentException("Active order does not contain seat: " + seatNumber);
                }
            }
        }
    }



    

    private int countStandingTicketsWithoutLock(int eventId, int zoneId) {
        int count = 0;

        for (CartLineItem item : items) {
            if (item.geteventId() == eventId &&
                    item.getzoneId() == zoneId &&
                    item.getSeatNumber() == null) { // only count standing tickets; expired included (R1)
                count = count + 1;
            }
        }

        return count;
    }





    


    public List<CartLineItem> getItems() {
        synchronized (itemsLock) {
            return new ArrayList<>(items);
        }
    }


    public List<CartLineItem> ReturnToStock() {
        synchronized (itemsLock) {
            List<CartLineItem> returnToStock = new ArrayList<>(items);
            items.clear();
            return returnToStock;
        }
    }

    public boolean isEmpty() {
        synchronized (itemsLock) {
            return items.isEmpty();
        }
    }
      public boolean hasExpiredItem() {
        synchronized (itemsLock) {
            for (CartLineItem item : items) {
                if (item.isExpired()) {
                    return true;
                }
            }

            return false;
        }
    }


    /**
     * Returns the userId, or {@code 0} for Guest carts. Preserved as
     * {@code int} for legacy callers (ReservationService, CheckoutService).
     * Prefer {@link #userIdOrNull()} when nullability matters.
     */
    public int getUserId() {
        return userId == null ? 0 : userId;
    }

    /** Returns the userId or {@code null} for Guest carts. */
    public Integer userIdOrNull() {
        return userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public boolean isMember() {
        return userId != null;
    }

    public boolean isGuest() {
        return userId == null;
    }

    public ActiveOrderStatus getStatus() {
        return status;
    }

    /**
     * Returns the stable key that identifies this order's inventory holds.
     * Passed into {@link com.ticketing.system.catalog.domain.InventorySelection}
     * on every reserve/release/confirmSale call so zones can enforce ownership.
     */
    public String getOrderKey() {
        return orderKey;
    }

    /**
     * Phase 1 of the 3-phase checkout: freeze the order so that no concurrent
     * checkout or new reservations can touch it during the payment/issuance phase.
     *
     * @throws IllegalStateException if the order is already in checkout
     */
    public void markCheckoutInProgress() {
        if (status == ActiveOrderStatus.CHECKOUT_IN_PROGRESS) {
            throw new IllegalStateException("Order is already in checkout progress");
        }
        this.status = ActiveOrderStatus.CHECKOUT_IN_PROGRESS;
    }

    /**
     * Rolls back Phase 1: resets the status to {@code PRE_CHECKOUT} (active) so the order
     * can be retried or abandoned after a checkout failure during Phase 2.
     *
     * @throws IllegalStateException if the order is not in checkout progress
     */
    public void cancelCheckoutInProgress() {
        if (status != ActiveOrderStatus.CHECKOUT_IN_PROGRESS) {
            throw new IllegalStateException("Order is already not in checkout progress");
        }
        this.status = ActiveOrderStatus.PRE_CHECKOUT;
    }

    /** Returns {@code true} while this order is locked by an ongoing checkout attempt. */
    public boolean isCheckoutInProgress() {
        return status == ActiveOrderStatus.CHECKOUT_IN_PROGRESS;
    }

    // Ensures that the active order can be modified (i.e., not in checkout progress).
    private void ensureModifiable() {
        if (isCheckoutInProgress()) {
            throw new IllegalStateException("Cannot modify active order while checkout is in progress");
        }
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /** Resets every line item's hold timer to a fresh full window starting at {@code now}. */
    public void renewReservationTimers(LocalDateTime now) {
        synchronized (itemsLock) {
            ensureModifiable();              // refuses while CHECKOUT_IN_PROGRESS (existing guard)
            for (CartLineItem item : items) {
                item.renew(now);
            }
        }
    }

    // returns the minimum remaining time among the items in the active order, which represents the time until the next item expires. If there are no items, returns Duration.ZERO.
    public Duration getRemainingTime() {
        synchronized (itemsLock) {
            if (items.isEmpty()) {
                return Duration.ZERO;
            }

            return items.stream()
                    .map(CartLineItem::getRemainingTime)
                    .min(Duration::compareTo)
                    .orElse(Duration.ZERO);
        }
    }

    // returns the total price of all items in the active order by summing up their individual prices at reservation time.
    // OR* avoid using domain total and let ReservationService build the DTO with event policies.   <------
    public double getRegularTotalPrice() {
        synchronized (itemsLock) {
            return items.stream()
                    .mapToDouble(CartLineItem::getPriceAtReservation)
                    .sum();
        }
    }



    /**
     * Guest→Member promotion: claim this cart for a user. Throws if the cart
     * already belongs to a different user.
     */
    public void attachToUser(int newUserId) {
        if (this.userId != null && this.userId != newUserId) {
            throw new IllegalStateException("cart already belongs to user " + this.userId);
        }
        this.userId = newUserId;
    }

    /**
     * Re-bind a (persistent) Member cart to a fresh session — used by D9a
     * restoration on next login.
     */
    public void attachToSession(String newSessionId) {
        if (newSessionId == null || newSessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must be non-blank");
        }
        this.sessionId = newSessionId;
    }

    /**
     * Marks the cart as orphaned from any active session — used on logout
     * for Member carts (D9a). The cart's userId is preserved; only the
     * sessionId pointer clears.
     */
    public void clearSession() {
        this.sessionId = null;
    }



  public boolean validateCanCheckout() {
        synchronized (itemsLock) {
            if (status == ActiveOrderStatus.CHECKOUT_IN_PROGRESS) {
                throw new IllegalStateException("Order is already being checked out");
            }

            if (items.isEmpty()) {
                throw new IllegalStateException("Cannot checkout an empty order");
            }

            for (CartLineItem item : items) {
                if (item.isExpired()) {
                    throw new IllegalStateException("Cannot checkout because one or more tickets expired");
                }
            }

            return true;
        }
    }


 public List<CartLineItem> buy() {
        synchronized (itemsLock) {
            if (items.isEmpty()) {
                throw new IllegalStateException("Cannot buy an empty order");
            }

            List<CartLineItem> ticketToBuy = new ArrayList<>(items);
            items.clear();

            return ticketToBuy;
        }
    }

    public void clear() {
        synchronized (itemsLock) {
            ensureModifiable();
            items.clear();
        }
    }


 public boolean hasReservationForEvent(int eventId) {
        synchronized (itemsLock) {
            for (CartLineItem item : items) {
                if (item.geteventId() == eventId && !item.isExpired()) {
                    return true;
                }
            }

            return false;
        }
    }


    




    // Removal-path helper: expired items are included so a buyer can manually remove an expired line (R1).
    private boolean hasReservationForEventWithoutLock(int eventId) {
        for (CartLineItem item : items) {
            if (item.geteventId() == eventId) {
                return true;
            }
        }
        return false;
    }


    public void expire() {
        throw new UnsupportedOperationException("UC-2: not implemented");
    }

 public boolean hasTicket(int eventId, int zoneId) {
        synchronized (itemsLock) {
            for (CartLineItem item : items) {
                if (item.geteventId() == eventId && item.getzoneId() == zoneId) {
                    return true;
                }
            }

            return false;
        }
    }




    public int countTickets(int eventId, int zoneId) {
        synchronized (itemsLock) {
            return countTicketsWithoutLock(eventId, zoneId);
        }
    }
    
    private int countTicketsWithoutLock(int eventId, int zoneId) {
        int count = 0;

        for (CartLineItem item : items) {
            if (item.geteventId() == eventId && item.getzoneId() == zoneId && !item.isExpired()) {
                count = count + 1;
            }
        }

        return count;
    }

    

    public ActiveOrderDTO toDTO() {
        synchronized (itemsLock) {
            List<ActiveOrderDTO.CartLineDTO> lineDTOs = new ArrayList<>();

            for (CartLineItem item : items) {
                lineDTOs.add(item.toDTO());
            }

            return new ActiveOrderDTO(
                    userIdOrNull(),
                    sessionId,
                    getCreatedAt(),
                    this.getRemainingTime().getSeconds(),
                    this.getRegularTotalPrice(),
                    lineDTOs
            );
        }
    }

    @Override
    public void checkInvariants() {
        // The constructor already enforces "at least one identity present" — re-verify here.
        if (userId == null && sessionId == null) {
            throw new IllegalStateException(
                    "ActiveOrder invariant violated: must have userId or sessionId (or both)");
        }
        if (userId != null && userId <= 0) {
            throw new IllegalStateException(
                    "ActiveOrder invariant violated: userId must be positive when set (was " + userId + ")");
        }
        if (sessionId != null && sessionId.isBlank()) {
            throw new IllegalStateException("ActiveOrder invariant violated: sessionId must be non-blank when set");
        }
        synchronized (itemsLock) {
            if (items == null) {
                throw new IllegalStateException("ActiveOrder invariant violated: items list must not be null");
            }
            for (CartLineItem item : items) {
                if (item == null) {
                    throw new IllegalStateException("ActiveOrder invariant violated: items list contains null");
                }
            }
        }
    }


}
