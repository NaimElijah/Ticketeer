package com.ticketing.system.sales.application.service;
import com.ticketing.system.sales.application.port.out.MarketGate; // outbound port for the market-open gate (governance implements it — sales no longer imports governance)

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.shared.dto.ReservationResultDTO;
import com.ticketing.system.shared.dto.BuyerContextDTO;
import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import org.springframework.context.ApplicationEventPublisher;
import com.ticketing.system.shared.event.ReservationRemovalFailedNotice;
import com.ticketing.system.shared.event.ReservationRemovalSucceededNotice;
import com.ticketing.system.shared.event.TicketReservationFailedNotice;
import com.ticketing.system.shared.event.TicketReservationSucceededNotice;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.shared.metrics.ISystemMetrics;
import com.ticketing.system.shared.metrics.MetricType;
// Catalog inbound port: catalog now owns all Event/InventoryZone inventory mutation, so sales'
// application layer no longer imports any catalog.domain type — it drives inventory through this port.
import com.ticketing.system.catalog.application.port.in.InventoryCommandPort;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.MarketNotOpenException;


@Service
@Slf4j
public class ReservationService {
    // Catalog inbound port: owns event lock/load, purchase-policy validation, and inventory
    // reserve/release/restore. Sales locks the ACTIVE ORDER before every port call; the port acquires
    // the EVENT buyer-lock internally — preserving the global order-before-event acquisition order.
    private final InventoryCommandPort inventoryPort;
    private final ActiveOrderRepository activeOrderRepository;
    private final SessionManager iSessionManager;
    // Publisher for cross-context integration events (reservation success/failure notices); the
    // notifications context listens for these instead of sales calling it directly.
    private final ApplicationEventPublisher eventPublisher;
    private final UserRepository userRepository;
    private final MarketGate marketGate; // queried to enforce the UC-32 market-open gate (dependency-inverted away from governance)
    private final ISystemMetrics systemMetrics;

    @Value("${constants.ticket-reservation-duration}")
    private int reservationTimeoutMinutes;

    public ReservationService(
            InventoryCommandPort inventoryPort,
            ActiveOrderRepository activeOrderRepository,
            SessionManager iSessionManager,
            ApplicationEventPublisher eventPublisher,
            UserRepository userRepository,
            MarketGate marketGate,
            ISystemMetrics systemMetrics) {
        this.inventoryPort = inventoryPort;
        this.activeOrderRepository = activeOrderRepository;
        this.iSessionManager = iSessionManager;
        this.eventPublisher = eventPublisher;
        this.userRepository = userRepository;
        this.marketGate = marketGate;
        this.systemMetrics = systemMetrics;
    }

    // ---------------------------------------------------------------------
    // Public API - use these methods from new code/controllers/tests
    // ---------------------------------------------------------------------

    @Transactional
    public ReservationResultDTO reserveForMember(String token, int eventId, int zoneId,
            InventorySelectionDTO selectionDto) {
        return reserve(authenticateMember(token), eventId, zoneId, requireSelection(selectionDto));
    }

    @Transactional
    public ReservationResultDTO reserveForGuest(String sessionId, int eventId, int zoneId,
            InventorySelectionDTO selectionDto) {
        return reserve(authenticateGuest(sessionId), eventId, zoneId, requireSelection(selectionDto));
    }

    @Transactional
    public ReservationResultDTO removeForMember(String token, int eventId, int zoneId,
            InventorySelectionDTO selectionDto) {
        return remove(authenticateMember(token), eventId, zoneId, requireSelection(selectionDto));
    }

    @Transactional
    public ReservationResultDTO removeForGuest(String sessionId, int eventId, int zoneId,
            InventorySelectionDTO selectionDto) {
        return remove(authenticateGuest(sessionId), eventId, zoneId, requireSelection(selectionDto));
    }

    // ---------------------------------------------------------------------
    // Unified flows of reserve & remove
    // ---------------------------------------------------------------------

    private ReservationResultDTO reserve(BuyerContextDTO buyer, int eventId, int zoneId, InventorySelectionDTO selection) {
        log.info("Entered reserve: buyerType={}, eventId={}, zoneId={}, quantity={}, seats={}",
                buyer.isMember() ? "MEMBER" : "GUEST",
                eventId,
                zoneId,
                selection == null ? null : selection.getQuantity(),
                selection == null ? null : selection.getSeatNumbers());

        validateReservationArguments(eventId, zoneId, selection);

        // UC-32 / I.2.1 — no tickets may be held while the trading market is closed.
        // Checked before any locks are acquired. (Removing items from an existing
        // cart is intentionally NOT gated.)
        if (!marketGate.isOpen()) {
            throw new MarketNotOpenException();
        }

        ActiveOrder activeOrder = null;
        boolean inventoryReserved = false;
        boolean orderModified = false;

        boolean notifySuccessAfterUnlock = false;
        boolean notifyFailureAfterUnlock = false;
        String failureNotificationReason = null;

        String orderLockKey = buyer.isMember()
                ? "user:" + buyer.userId()
                : "sess:" + buyer.sessionId();

        //* Locks the ACTIVE ORDER before invoking the inventory port. The port acquires the EVENT
        // buyer-lock internally, so the global order-before-event acquisition order is preserved and
        // there is no deadlock risk between the two locks. Keep this ordering for any future service.
        activeOrderRepository.lockForUpdate(orderLockKey);

        try {
            // Get-or-create the buyer's active order and reject reserve attempts while checkout holds it.
            // (The order is only saved on success below, so a later port failure leaves no orphan order.)
            activeOrder = getOrCreateActiveOrder(buyer);

            // CheckoutService now marks orders as CHECKOUT_IN_PROGRESS and releases the order lock during Phase 2. ReservationService should reject reserve attempts against an order in that state; otherwise a concurrent reservation can mutate the cart while checkout is pricing/charging based on a snapshot, causing inconsistencies.
            if (activeOrder.isCheckoutInProgress()) {
                log.warn("Request rejected: cannot modify active order during checkout. eventId={}, zoneId={}, userId={}, sessionId={}",
                        eventId, zoneId, buyer.isMember() ? buyer.userId() : null, buyer.isMember() ? null : buyer.sessionId());
                throw new IllegalStateException("Cannot modify active order during checkout");
            }

            // Policy inputs computed from the order and the buyer's identity. The effective purchase
            // policy itself (company AND event, RESERVE stage) is enforced INSIDE the inventory port —
            // catalog owns the event + the company policy — so sales no longer touches catalog.domain.
            int totalQuantity = existingForEvent(activeOrder, eventId) + selection.getQuantity();
            Integer buyerAge = buyer.isMember() ? resolveMemberAge(buyer.userId()) : null;
            int buyerId = buyer.isMember() ? buyer.userId() : -1;

            // Reserve inventory through the port: it acquires the event buyer-lock, validates the policy,
            // reserves the (ownership-stamped) selection, saves the event, and releases the lock — then
            // returns the unit price to record on the cart line. Done before mutating the active order so
            // a reserve failure (e.g. not enough availability) leaves the cart untouched.
            double pricePerTicket = inventoryPort.reserve(
                    eventId, zoneId, selection, activeOrder.getOrderKey(), buyerId, buyerAge, totalQuantity);
            inventoryReserved = true;

            // Now that we have successfully reserved the inventory, we can safely modify the active order to reflect the new reservation. If any of the validations for modifying the active order fail (e.g. trying to reserve more tickets than allowed by purchase policy, etc.), we will throw an exception and roll back the inventory reservation in the catch block, ensuring that we do not end up with an active order that has reservations that were not successfully made.
            addOrderReservation(activeOrder, eventId, zoneId, selection, pricePerTicket);
            orderModified = true;

            activeOrderRepository.save(activeOrder);
            // Notify the member of the successful reservation. For guests, we do not have a user ID to send notifications to, so we skip this step for guests. The notification can be used to trigger email notifications, app push notifications, etc. to inform the member of their successful reservation and any details they may need (e.g. event name, zone, quantity reserved, etc.).
            notifySuccessAfterUnlock = true;
            systemMetrics.record(MetricType.RESERVATION);
            // Return the reservation result, which includes details about the reservation such as event ID, zone ID, quantity reserved, seat numbers if applicable, and the expiration time of the reservation (based on the current time plus the configured reservation timeout). This information can be used by the frontend to show the user their current reservations and how long they have before they expire, etc.
            return buildReservationResult(eventId, zoneId, selection);

        } catch (RuntimeException e) {
            // If any exception occurs during the reservation process, we need to roll back any actions that were taken to keep our data consistent. This includes releasing any inventory that was reserved and removing any reservations that were added to the active order. We also log the error for monitoring and debugging purposes, and rethrow the exception to indicate that the reservation failed. The frontend can catch this exception and show an appropriate error message to the user (e.g. "Failed to reserve tickets: not enough availability", "Failed to reserve tickets: event not found", "Failed to reserve tickets: invalid input", etc.).
            rollbackReservationIfNeeded(activeOrder, eventId, zoneId, selection, inventoryReserved,
                    orderModified);

            notifyFailureAfterUnlock = true;
            failureNotificationReason = "Reservation failed: " + e.getMessage();
            // Notify the member of the failed reservation attempt. For guests, we do not have a user ID to send notifications to, so we skip this step for guests. The notification can be used to trigger email notifications, app push notifications, etc. to inform the member that their reservation attempt failed and provide any details that may be relevant (e.g. event name, zone, quantity they tried to reserve, reason for failure if known, etc.).

            log.warn("reserve failed: eventId={}, zoneId={}, selectionQuantity={}, seats={}, reason={}",
                    eventId, zoneId,
                    selection == null ? null : selection.getQuantity(),
                    selection == null ? null : selection.getSeatNumbers(),
                    e.getMessage());

            throw e;

        } finally {
            // Only the ACTIVE ORDER lock is released here — the EVENT buyer-lock was acquired and
            // released inside the inventory port, so there is nothing to unlock for the event.
            activeOrderRepository.unlock(orderLockKey);

            if (notifySuccessAfterUnlock) {
                try {
                    notifyReservationSuccessIfMember(buyer, eventId, zoneId, selection.getQuantity());
                } catch (RuntimeException notificationFailure) {
                    log.warn("Reservation succeeded but notification failed. userId={}, eventId={}, zoneId={}",
                            buyer.isMember() ? buyer.userId() : null,
                            eventId,
                            zoneId,
                            notificationFailure);
                }
            }

            if (notifyFailureAfterUnlock) {
                try {
                    notifyReservationFailureIfMember(buyer, eventId, zoneId, failureNotificationReason);
                } catch (RuntimeException notificationFailure) {
                    log.warn(
                            "Reservation failed and failure-notification also failed. userId={}, eventId={}, zoneId={}",
                            buyer.isMember() ? buyer.userId() : null,
                            eventId,
                            zoneId,
                            notificationFailure);
                }
            }
        }

    }

    private ReservationResultDTO remove(BuyerContextDTO buyer, int eventId, int zoneId, InventorySelectionDTO selection) {
        log.info("Entered remove: buyerType={}, eventId={}, zoneId={}, quantity={}, seats={}",
                buyer.isMember() ? "MEMBER" : "GUEST",
                eventId,
                zoneId,
                selection == null ? null : selection.getQuantity(),
                selection == null ? null : selection.getSeatNumbers());
        // Validate input arguments before doing anything else to fail fast on invalid input and avoid doing unnecessary work. This also ensures that we do not attempt to remove reservations for an event or zone that does not exist, which keeps our data consistent and avoids having active orders that reflect removals for events or zones that do not actually exist.
        validateReservationArguments(eventId, zoneId, selection);

        ActiveOrder activeOrder = null;
        boolean orderModified = false;
        boolean inventoryReleased = false;
        double removedPricePerTicket = 0.0;

        boolean notifySuccessAfterUnlock = false;
        boolean notifyFailureAfterUnlock = false;
        String failureNotificationReason = null;

        String orderLockKey = buyer.isMember()
                ? "user:" + buyer.userId()
                : "sess:" + buyer.sessionId();

        /*
        * Lock the ACTIVE ORDER first; the EVENT buyer-lock is acquired inside the inventory port.
        * This keeps the global order-before-event acquisition order consistent with reserve(...) and
        * checkout to avoid deadlocks. The event buyer-lock (a shared read lock) blocks structural event
        * edits while allowing concurrent buyer operations; inventory correctness is protected by the
        * zone/seat locks inside the domain (StandingZone counter lock, SeatedZone seat locks).
        */
        activeOrderRepository.lockForUpdate(orderLockKey);

        try {
            activeOrder = getActiveOrderOrThrow(buyer);

            // CheckoutService marks orders CHECKOUT_IN_PROGRESS and releases the order lock during Phase 2.
            // Reject remove attempts in that state so a concurrent removal can't mutate the cart snapshot
            // checkout is pricing/charging against. Fails fast here (before releasing inventory) instead of
            // letting the domain throw mid-flow and forcing a rollback — mirrors the guard in reserve(...).
            if (activeOrder.isCheckoutInProgress()) {
                log.warn("Request rejected: cannot modify active order during checkout. eventId={}, zoneId={}, userId={}, sessionId={}",
                        eventId, zoneId, buyer.isMember() ? buyer.userId() : null, buyer.isMember() ? null : buyer.sessionId());
                throw new IllegalStateException("Cannot modify active order during checkout");
            }

            // Validate first so we do not release inventory for tickets that are not in the active order.
            validateOrderContainsReservation(activeOrder, eventId, zoneId, selection);

            /*
            * Release inventory through the port first (it acquires the event buyer-lock, releases the
            * ownership-stamped selection, saves the event, and returns the unit price).
            *
            * Why this order?
            * If inventory release fails, the cart was not changed yet.
            * If inventory release succeeds but cart removal/save fails, the catch block re-reserves
            * the inventory (using the captured price to rebuild the cart line).
            */
            removedPricePerTicket = inventoryPort.release(eventId, zoneId, selection, activeOrder.getOrderKey());
            inventoryReleased = true;

            /*
            * Now remove the reservation from the active order.
            * If this fails after inventory was released, rollbackRemoveIfNeeded(...)
            * will re-reserve the inventory.
            */
            removeOrderReservation(activeOrder, eventId, zoneId, selection);
            orderModified = true;

            activeOrderRepository.save(activeOrder);

            // Notify the member of the successful removal. For guests, we do not have a user ID to send notifications to, so we skip this step for guests. The notification can be used to trigger email notifications, app push notifications, etc. to inform the member of their successful reservation removal and any details they may need (e.g. event name, zone, quantity removed, etc.).
            notifySuccessAfterUnlock = true;

            // Return the reservation result, which includes details about the removed reservation such as event ID, zone ID, quantity removed, seat numbers if applicable, and the expiration time of the reservation (based on the current time plus the configured reservation timeout). This information can be used by the frontend to show the user their current reservations and how long they have before they expire, etc.
            return buildReservationResult(eventId, zoneId, selection);

        } catch (RuntimeException e) {
            // If any exception occurs during the removal process, we need to roll back any actions that were taken to keep our data consistent. This includes re-reserving any inventory that was released and re-adding any reservations that were removed from the active order. We also log the error for monitoring and debugging purposes, and rethrow the exception to indicate that the removal failed. The frontend can catch this exception and show an appropriate error message to the user (e.g. "Failed to remove reservation: reservation not found in active order", "Failed to remove reservation: event not found", "Failed to remove reservation: invalid input", etc.).
            rollbackRemoveIfNeeded(
                    activeOrder,
                    eventId,
                    zoneId,
                    selection,
                    orderModified,
                    inventoryReleased,
                    removedPricePerTicket);

            notifyFailureAfterUnlock = true;
            failureNotificationReason = "Remove reservation failed: " + e.getMessage();

            log.warn("remove failed: eventId={}, zoneId={}, selectionQuantity={}, seats={}, reason={}",
                    eventId, zoneId,
                    selection == null ? null : selection.getQuantity(),
                    selection == null ? null : selection.getSeatNumbers(),
                    e.getMessage());

            throw e;

        } finally {
            // Only the ACTIVE ORDER lock is released here — the EVENT buyer-lock is owned by the port.
            activeOrderRepository.unlock(orderLockKey);

            if (notifySuccessAfterUnlock) {
                try {
                    notifyRemoveSuccessIfMember(buyer, eventId, zoneId, selection.getQuantity());
                } catch (RuntimeException notificationFailure) {
                    log.warn("Remove reservation succeeded but notification failed. userId={}, eventId={}, zoneId={}",
                            buyer.isMember() ? buyer.userId() : null,
                            eventId,
                            zoneId,
                            notificationFailure);
                }
            }

            if (notifyFailureAfterUnlock) {
                try {
                    notifyRemoveFailureIfMember(buyer, eventId, zoneId, failureNotificationReason);
                } catch (RuntimeException notificationFailure) {
                    log.warn(
                            "Remove reservation failed and failure-notification also failed. userId={}, eventId={}, zoneId={}",
                            buyer.isMember() ? buyer.userId() : null,
                            eventId,
                            zoneId,
                            notificationFailure);
                }
            }
        }

    }

    // Guards that a selection DTO was supplied. The DTO stays sales-safe (no catalog.domain type): the
    // inventory port translates it into the domain value object at the catalog boundary.
    private InventorySelectionDTO requireSelection(InventorySelectionDTO selectionDto) {
        if (selectionDto == null) {
            throw new IllegalArgumentException("Inventory selection is required");
        }
        return selectionDto;
    }

    // Adds the reserved selection to the active order via ActiveOrder's primitive (non-catalog.domain)
    // entry points, branching standing vs seated here so sales never handles a catalog InventorySelection.
    private void addOrderReservation(ActiveOrder order, int eventId, int zoneId, InventorySelectionDTO selection,
            double price) {
        LocalDateTime addedAt = LocalDateTime.now();
        if (selection.isStandingSelection()) {
            order.addStandingReservation(eventId, zoneId, selection.getQuantity(), price, addedAt);
        } else {
            order.addSeatedReservation(eventId, zoneId, selection.getSeatNumbers(), price, addedAt);
        }
    }

    // Removes the selection from the active order via ActiveOrder's primitive entry points (see above).
    private void removeOrderReservation(ActiveOrder order, int eventId, int zoneId, InventorySelectionDTO selection) {
        if (selection.isStandingSelection()) {
            order.removeStandingSpots(eventId, zoneId, selection.getQuantity());
        } else {
            order.removeSeats(eventId, zoneId, selection.getSeatNumbers());
        }
    }

    // Validates (before releasing inventory) that the active order actually holds the selection, using
    // ActiveOrder's primitive validate overload so sales avoids the catalog InventorySelection type.
    private void validateOrderContainsReservation(ActiveOrder order, int eventId, int zoneId,
            InventorySelectionDTO selection) {
        order.validateContainsReservation(eventId, zoneId, selection.getQuantity(), selection.getSeatNumbers());
    }

    // The buyer's existing cart quantity for this event — fed into the RESERVE-stage policy MAX check.
    private int existingForEvent(ActiveOrder activeOrder, int eventId) {
        var existingItems = activeOrder.getItems();
        long existingForEvent = existingItems == null ? 0L
                : existingItems.stream().filter(item -> item.geteventId() == eventId).count();
        return (int) existingForEvent;
    }

    // ---------------------------------------------------------------------
    // Authentication / request parsing
    // ---------------------------------------------------------------------

    // Helper methods to authenticate the buyer based on the type of request (member vs guest) and extract the necessary information to create a BuyerContextDTO, which is used in the main flows to represent the buyer's context (e.g. whether they are a member or guest, their user ID if they are a member, their session ID if they are a guest, etc.). These methods also handle validation of the input tokens/session IDs and throw appropriate exceptions if the authentication fails (e.g. invalid token, expired token, missing session ID, etc.), which can be caught by the frontend to show appropriate error messages to the user.

    private BuyerContextDTO authenticateMember(String token) {
        return BuyerContextDTO.member(validateTokenAndGetUserId(token));
    }

    private BuyerContextDTO authenticateGuest(String sessionId) {
        validateSessionId(sessionId);
        return BuyerContextDTO.guest(sessionId);
    }

    private int validateTokenAndGetUserId(String token) {
        if (token == null || token.isBlank()) {
            log.warn("Request rejected: missing authentication token");
            throw new IllegalArgumentException("Missing authentication token");
        }

        if (!iSessionManager.validateToken(token)) {
            log.warn("Request rejected: invalid or expired token");
            throw new IllegalStateException("Invalid or expired authentication token");
        }

        int userId = iSessionManager.extractUserId(token);

        if (userId <= 0) {
            log.warn("Request rejected: invalid buyer id={}", userId);
            throw new IllegalArgumentException("Invalid buyer id");
        }

        return userId;
    }

    private void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("Request rejected: missing session ID");
            throw new IllegalArgumentException("Missing session ID");
        }
    }

    // Resolves a member's age for the purchase-policy context. Kept in sales because buyer identity is
    // a sales/identity concern; the age is passed as a primitive into the inventory port, which owns the
    // effective-policy validation against the event + company policy.
    private Integer resolveMemberAge(int userId) {
        try {
            User user = userRepository.getUserById(userId);
            return user == null ? null : user.getAge();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void validateReservationArguments(int eventId, int zoneId, InventorySelectionDTO selection) {
        if (eventId <= 0) {
            throw new IllegalArgumentException("eventId must be positive");
        }

        if (zoneId <= 0) {
            throw new IllegalArgumentException("zoneId must be positive");
        }

        if (selection == null) {
            throw new IllegalArgumentException("Inventory selection is required");
        }

        if (selection.getQuantity() <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        if (selection.isSeatedSelection()) {
            for (String seatNumber : selection.getSeatNumbers()) {
                if (seatNumber == null || seatNumber.isBlank()) {
                    throw new IllegalArgumentException("Seat number must be non-blank");
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // helper functions - these are not part of the main flows but are used by multiple public methods, so they help keep the main flows clean and avoid code duplication.
    // ---------------------------------------------------------------------

    // Event/zone existence checks and the price read now live inside the catalog inventory port
    // (InventoryService), which owns the Event aggregate — so ReservationService no longer loads events.

    // For members, we get or create an active order based on their user ID. For guests, we get or create an active order based on their session ID. This method abstracts away the logic of determining whether to use user ID or session ID and ensures that we always have an active order to work with in the main flows, which simplifies the logic in those flows and keeps them focused on the reservation and removal logic rather than the details of how we manage active orders for different types of buyers.
    private ActiveOrder getOrCreateActiveOrder(BuyerContextDTO buyer) {
        if (buyer.isMember()) {
            ActiveOrder activeOrder = activeOrderRepository.getByUserId(buyer.userId());
            if (activeOrder == null) {
                log.info("No active order found for userId={}, creating new ActiveOrder", buyer.userId());
                activeOrder = new ActiveOrder(buyer.userId());
            }
            return activeOrder;
        }

        return activeOrderRepository.getBySessionId(buyer.sessionId())
                .orElseGet(() -> {
                    log.info("No active order found for sessionId={}, creating new ActiveOrder", buyer.sessionId());
                    return ActiveOrder.forGuest(buyer.sessionId());
                });
    }

    // For members, we get the active order based on their user ID. For guests, we get the active order based on their session ID. This method abstracts away the logic of determining whether to use user ID or session ID and ensures that we can easily retrieve the active order for the buyer in the main flows, which simplifies the logic in those flows and keeps them focused on the reservation and removal logic rather than the details of how we manage active orders for different types of buyers. We also throw an exception if no active order is found for the buyer, which allows us to handle that case in the main flows (e.g. show an error message to the user if they try to remove a reservation but they do not have an active order).
    private ActiveOrder getActiveOrderOrThrow(BuyerContextDTO buyer) {
        if (buyer.isMember()) {
            ActiveOrder activeOrder = activeOrderRepository.getByUserId(buyer.userId());
            if (activeOrder == null) {
                throw new IllegalArgumentException("Active order not found");
            }
            return activeOrder;
        }

        return activeOrderRepository.getBySessionId(buyer.sessionId())
                .orElseThrow(() -> new IllegalArgumentException("Active order not found"));
    }

    // ---------------------------------------------------------------------
    // Rollback / notifications / DTOs
    // ---------------------------------------------------------------------

    // Helper method to roll back any changes made during the reservation or removal process if an exception occurs, in order to keep our data consistent. This includes releasing any inventory that was reserved and re-adding any reservations that were removed from the active order. We also log any exceptions that occur during the rollback process for monitoring and debugging purposes, but we do not rethrow those exceptions since we want to ensure that the original exception from the reservation or removal process is what gets propagated to indicate the failure of the operation, while still making a best effort to roll back any changes to keep our data consistent.
    private void rollbackReservationIfNeeded(ActiveOrder activeOrder, int eventId, int zoneId,
            InventorySelectionDTO selection, boolean inventoryReserved, boolean orderModified) {

        if (!inventoryReserved) {
            return;
        }

        try {
            if (orderModified && activeOrder != null) {
                removeOrderReservation(activeOrder, eventId, zoneId, selection);
                activeOrderRepository.save(activeOrder);
            }
        } catch (RuntimeException ignored) {
            // best-effort rollback; the main exception is rethrown by caller
        }

        try {
            if (activeOrder != null) {
                // Release the just-reserved inventory through the port (it re-acquires the event
                // buyer-lock and verifies ownership via the order key). The order lock is still held.
                inventoryPort.release(eventId, zoneId, selection, activeOrder.getOrderKey());
            }
        } catch (RuntimeException ignored) {
            // best-effort rollback; the main exception is rethrown by caller
        }
    }

    // rollback the cart if inventory release fails, and rollback inventory if the later cart/save flow fails
    private void rollbackRemoveIfNeeded(ActiveOrder activeOrder, int eventId, int zoneId,
            InventorySelectionDTO selection, boolean orderModified,
            boolean inventoryReleased, double pricePerTicket) {
        if (!inventoryReleased && !orderModified) {
            return;
        }

        /*
        * If inventory was released, put it back under the same order ownership key. restore(...)
        * re-reserves WITHOUT re-running the purchase policy (this is a compensating action).
        */
        try {
            if (inventoryReleased && activeOrder != null) {
                inventoryPort.restore(eventId, zoneId, selection, activeOrder.getOrderKey());
            }
        } catch (RuntimeException rollbackFailure) {
            log.error("Rollback failed while re-reserving inventory after remove failure. eventId={}, zoneId={}",
                    eventId,
                    zoneId,
                    rollbackFailure);
        }

        /*
        * If the active order was modified, put the reservation line back.
        */
        try {
            if (orderModified && activeOrder != null) {
                addOrderReservation(activeOrder, eventId, zoneId, selection, pricePerTicket);
                activeOrderRepository.save(activeOrder);
            }
        } catch (RuntimeException rollbackFailure) {
            log.error("Rollback failed while re-adding cart lines after remove failure. eventId={}, zoneId={}",
                    eventId,
                    zoneId,
                    rollbackFailure);
        }
    }

    private void notifyReservationSuccessIfMember(BuyerContextDTO buyer, int eventId, int zoneId, int quantity) {
        if (buyer.isMember()) {
            // Publish a cross-context integration event; the notifications listener delivers it in-line.
            eventPublisher.publishEvent(new TicketReservationSucceededNotice(buyer.userId(), eventId, zoneId, quantity));
        }
    }

    private void notifyReservationFailureIfMember(BuyerContextDTO buyer, int eventId, int zoneId, String reason) {
        if (buyer != null && buyer.isMember()) {
            // Publish a cross-context integration event; the notifications listener delivers it in-line.
            eventPublisher.publishEvent(new TicketReservationFailedNotice(buyer.userId(), eventId, zoneId, reason));
        }
    }

    private void notifyRemoveSuccessIfMember(BuyerContextDTO buyer, int eventId, int zoneId, int quantity) {
        if (buyer.isMember()) {
            // Publish a cross-context integration event; the notifications listener delivers it in-line.
            eventPublisher.publishEvent(new ReservationRemovalSucceededNotice(buyer.userId(), eventId, zoneId, quantity));
        }
    }

    private void notifyRemoveFailureIfMember(BuyerContextDTO buyer, int eventId, int zoneId, String reason) {
        if (buyer != null && buyer.isMember()) {
            // Publish a cross-context integration event; the notifications listener delivers it in-line.
            eventPublisher.publishEvent(new ReservationRemovalFailedNotice(buyer.userId(), eventId, zoneId, reason));
        }
    }

    // Helper method to build the reservation result DTO that is returned by the reserve and remove methods, which includes details about the reservation such as event ID, zone ID, quantity reserved/removed, seat numbers if applicable, and the expiration time of the reservation (based on the current time plus the configured reservation timeout). This information can be used by the frontend to show the user their current reservations and how long they have before they expire, etc.
    private ReservationResultDTO buildReservationResult(int eventId, int zoneId, InventorySelectionDTO selection) {
        return new ReservationResultDTO(
                eventId,
                zoneId,
                selection.getQuantity(),
                selection.getSeatNumbers(),
                LocalDateTime.now().plusMinutes(this.reservationTimeoutMinutes));
    }

    // method to restore an active order for a user (member) - this is used by the frontend when a user logs in or returns to the site after navigating away, to restore their active order and show them their current reservations in the cart. For members, we look up the active order by their user ID. For guests, we look up the active order by their session ID. If an active order is found, we convert it to an ActiveOrderDTO and return it. If no active order is found, we return null. We also enrich the DTO with event names for better UX in the frontend, so that the frontend can show the event names directly in the cart without needing to make extra calls to get event details for each line item.
    @Transactional(readOnly = true)
    public ActiveOrderDTO restoreActiveOrder(int userId) {
        ActiveOrder activeOrder = activeOrderRepository.getByUserId(userId);
        if (activeOrder == null) {
            log.info("No active order found for userId={}, returning null", userId);
            return null;
        }
        log.info("Active order found for userId={}, restoring ActiveOrderDTO", userId);
        ActiveOrderDTO activeOrderDTO = activeOrder.toDTO();
        // enrich the DTO with event and zone details for each line item (for better UX in the frontend; avoids extra calls from frontend to get event/zone details for each line)
        List<ActiveOrderDTO.CartLineDTO> enrichedLines = new ArrayList<>();
        for (ActiveOrderDTO.CartLineDTO line : activeOrderDTO.lines()) {
            String eventName = eventNameFor(line.eventId());
            enrichedLines.add(new ActiveOrderDTO.CartLineDTO(
                    line.eventId(),
                    eventName,
                    line.zoneId(),
                    line.seatNumber(),
                    line.pricePerTicket(),
                    line.addedAt()));
        }
        // return the same DTO but with enriched lines (event names) for better frontend UX; the frontend can ignore the extra eventName field if it wants and just use eventId, or it can show the event name directly in the cart without needing to make extra calls to get event details for each line item
        return new ActiveOrderDTO(
                activeOrderDTO.userId(),
                activeOrderDTO.sessionId(),
                activeOrderDTO.createdAt(),
                activeOrderDTO.remainingSecondsBeforeExpiry(),
                activeOrderDTO.currentTotalPrice(),
                enrichedLines);
    }

    @Transactional(readOnly = true)
    public ActiveOrderDTO restoreActiveOrderForGuest(String sessionId) {
        return activeOrderRepository.getBySessionId(sessionId)
                .map(order -> {
                    log.info("Active order found for sessionId={}, restoring ActiveOrderDTO", sessionId);
                    ActiveOrderDTO dto = order.toDTO();
                    List<ActiveOrderDTO.CartLineDTO> enrichedLines = new ArrayList<>();
                    for (ActiveOrderDTO.CartLineDTO line : dto.lines()) {
                        String eventName = eventNameFor(line.eventId());
                        enrichedLines.add(new ActiveOrderDTO.CartLineDTO(
                                line.eventId(), eventName, line.zoneId(),
                                line.seatNumber(), line.pricePerTicket(), line.addedAt()));
                    }
                    return new ActiveOrderDTO(dto.userId(), dto.sessionId(), dto.createdAt(),
                            dto.remainingSecondsBeforeExpiry(), dto.currentTotalPrice(), enrichedLines);
                })
                .orElseGet(() -> {
                    log.info("No active order found for sessionId={}, returning null", sessionId);
                    return null;
                });
    }

    /** Best-effort event name for cart-line enrichment — a since-deleted event must not break restore. */
    private String eventNameFor(int eventId) {
        try {
            return inventoryPort.eventName(eventId);
        } catch (EventNotFoundException e) {
            return "Unknown Event";
        }
    }

    // When a buyer enters the checkout page, reset the hold timer of every reserved ticket
    // in their active order to a fresh full window (CartLineItem.EXPIRATION_LIMIT, 10 min),
    // so the buyer gets the maximum time to complete payment. We only renew when the cart is
    // still fully valid: if it is empty, already in checkout, or has any expired item, we
    // leave it untouched so the existing expiry/sweeper path can reject the stale cart. The
    // reset runs under the per-order lock (the same lock the expiry sweeper acquires first),
    // so it cannot race the sweeper. The enriched DTO is built by reusing restoreActiveOrder.
    @Transactional
    public ActiveOrderDTO renewReservationsForMemberCheckout(int userId) {
        String orderLockKey = "user:" + userId;
        activeOrderRepository.lockForUpdate(orderLockKey);
        try {
            ActiveOrder order = activeOrderRepository.getByUserId(userId);
            if (order != null && !order.isEmpty()
                    && !order.isCheckoutInProgress() && !order.hasExpiredItem()) {
                order.renewReservationTimers(LocalDateTime.now());
                activeOrderRepository.save(order);
                log.info("Renewed reservation timers on checkout entry for userId={}", userId);
            }
        } finally {
            activeOrderRepository.unlock(orderLockKey);
        }
        return restoreActiveOrder(userId);
    }

    @Transactional
    public ActiveOrderDTO renewReservationsForGuestCheckout(String sessionId) {
        String orderLockKey = "sess:" + sessionId;
        activeOrderRepository.lockForUpdate(orderLockKey);
        try {
            ActiveOrder order = activeOrderRepository.getBySessionId(sessionId).orElse(null);
            if (order != null && !order.isEmpty()
                    && !order.isCheckoutInProgress() && !order.hasExpiredItem()) {
                order.renewReservationTimers(LocalDateTime.now());
                activeOrderRepository.save(order);
                log.info("Renewed reservation timers on checkout entry for sessionId={}", sessionId);
            }
        } finally {
            activeOrderRepository.unlock(orderLockKey);
        }
        return restoreActiveOrderForGuest(sessionId);
    }

    // Helper method to abandon the active order for a user (member or guest) - this is used by the frontend when a user explicitly clicks "Abandon Cart" or when they log out, to clear their active order and release any reserved inventory back to the events. For members, we look up the active order by their user ID. For guests, we look up the active order by their session ID. If an active order is found, we release any reserved inventory back to the events and then delete the active order. If no active order is found, we simply return without doing anything. This ensures that we do not leave any reserved inventory hanging around for abandoned carts, which keeps our inventory accurate and allows other users to purchase those tickets if they are still available.
    @Transactional
    public void abandonActiveOrder(BuyerContextDTO buyer) {
        if (buyer == null) {
            throw new IllegalArgumentException("Buyer context is required");
        }
        // Determine the lock key based on whether the buyer is a member or a guest.
        String orderLockKey = buyer.isMember()
                ? "user:" + buyer.userId()
                : "sess:" + buyer.sessionId();

        // Lock the ACTIVE ORDER for update while we abandon it. Each per-line inventory release acquires
        // the EVENT buyer-lock INSIDE the inventory port (order-before-event ordering preserved); the
        // port owns the load/release/save under that lock.
        activeOrderRepository.lockForUpdate(orderLockKey);

        try {
            // For members, we look up the active order by their user ID. For guests, we look up the active order by their session ID.
            ActiveOrder activeOrder = buyer.isMember()
                    ? activeOrderRepository.getByUserId(buyer.userId())
                    : activeOrderRepository.getBySessionId(buyer.sessionId()).orElse(null);

            if (activeOrder == null) {
                if (buyer.isMember()) {
                    log.info("No active order to abandon for userId={}", buyer.userId());
                } else {
                    log.info("No active order to abandon for sessionId={}", buyer.sessionId());
                }
                return;
            }

            if (activeOrder.isCheckoutInProgress()) {
                throw new IllegalStateException("Cannot abandon active order while checkout is in progress");
            }

            // Release any reserved inventory back to the events before deleting the active order.
            String orderKey = activeOrder.getOrderKey();
            List<ActiveOrderDTO.CartLineDTO> lines = activeOrder.toDTO().lines();

            // Release each reserved line back to its event through the inventory port (the port acquires
            // the event buyer-lock, releases the ownership-stamped selection, and saves). Best-effort per
            // line: a failure (e.g. a since-deleted event) is logged and does not abort the abandon.
            for (ActiveOrderDTO.CartLineDTO line : lines) {
                try {
                    // Seat label -> a seated selection; no seat -> one standing unit. The order key is
                    // passed separately so the port can verify ownership when releasing.
                    InventorySelectionDTO selection = (line.seatNumber() != null)
                            ? InventorySelectionDTO.seated(List.of(line.seatNumber()))
                            : InventorySelectionDTO.standing(1);

                    inventoryPort.release(line.eventId(), line.zoneId(), selection, orderKey);
                } catch (RuntimeException e) {
                    log.warn(
                            "Failed to release inventory while abandoning active order. eventId={}, zoneId={}, seatNumber={}, reason={}",
                            line.eventId(), line.zoneId(), line.seatNumber(), e.getMessage());
                }
            }
            // After releasing all reserved inventory, we can safely delete the active order from the repository.
            activeOrderRepository.delete(activeOrder);

            if (buyer.isMember()) {
                log.info("Abandoned active order for userId={}", buyer.userId());
            } else {
                log.info("Abandoned active order for sessionId={}", buyer.sessionId());
            }

        } finally {
            activeOrderRepository.unlock(orderLockKey);
        }
    }

    /**
    * Removes a line from the cart, automatically determining whether the user is a Member or Guest.
    * The UI just calls this method, no need to handle InventorySelectionDTO or user type.
    */
    @Transactional
    public ReservationResultDTO removeLine(String userTokenOrSessionId, int eventId, int zoneId,
            InventorySelectionDTO selection) {

        boolean isMember = isMember(userTokenOrSessionId);
        if (isMember) {
            return removeForMember(userTokenOrSessionId, eventId, zoneId, selection);
        } else {
            return removeForGuest(userTokenOrSessionId, eventId, zoneId, selection);
        }
    }

    /**
     * Determines if the given credential is a member JWT (vs a guest session ID).
     * JWTs have two dots; guest session IDs are UUIDs with none.
     * Only silences exceptions for the guest path — a credential that looks like
     * a JWT but fails validation throws so the caller sees the real failure.
     */
    private boolean isMember(String userTokenOrSessionId) {
        if (userTokenOrSessionId == null || userTokenOrSessionId.isBlank()) {
            throw new IllegalArgumentException("User token or session ID cannot be null or empty");
        }
        if (!looksLikeJwt(userTokenOrSessionId)) {
            return false;
        }
        int userId = validateTokenAndGetUserId(userTokenOrSessionId);
        return userId > 0;
    }

    private static boolean looksLikeJwt(String s) {
        int dot1 = s.indexOf('.');
        if (dot1 <= 0)
            return false;
        return s.indexOf('.', dot1 + 1) > dot1;
    }

    // Helper method to view the current active order for a user (member or guest). For members, we look up the active order by their user ID. For guests, we look up the active order by their session ID. If an active order is found, we convert it to an ActiveOrderDTO and return it. If no active order is found, we return null. This allows the frontend to show the user their current reservations in the cart when they navigate to the cart page, etc.
    @Transactional(readOnly = true)
    public ActiveOrderDTO viewMyActiveOrder(String userOrSessionId) {
        if (userOrSessionId == null || userOrSessionId.isBlank()) {
            return null;
        }
        try {
            if (iSessionManager.validateToken(userOrSessionId)) {
                int userId = iSessionManager.extractUserId(userOrSessionId);
                return restoreActiveOrder(userId);
            }
        } catch (Exception ignored) {
            // not a JWT token — fall through to guest path
        }
        return restoreActiveOrderForGuest(userOrSessionId);
    }

    

    // COMMENTED OUT: expireActiveOrders / safelyReleaseAndDelete are a second, orphaned cart-expiry
    // path that duplicates the live @Scheduled SessionAndOrderSweeper. They have no callers anywhere
    // in the codebase (dead code), and the live sweeper is the single source of truth for expiry-driven
    // inventory release. Keeping a callable public duplicate is a foot-gun: wiring it up would
    // reintroduce the "buyer loses seats mid-payment" bug the sweeper is careful to avoid. The
    // in-checkout guard below is preserved so that if this is ever revived it already matches the
    // sweeper's behavior. Left in (commented) rather than deleted for reference/history.
    //
    // public void expireActiveOrders() {
    //     List<ActiveOrder> expiredOrders = activeOrderRepository.findExpired();
    //     if (expiredOrders == null || expiredOrders.isEmpty()) return;
    //     for (ActiveOrder order : expiredOrders) {
    //         safelyReleaseAndDelete(order);
    //     }
    // }
    // private void safelyReleaseAndDelete(ActiveOrder order) {
    //     String lockKey = order.getUserId() != 0
    //         ? "user:" + order.getUserId()
    //         : "sess:" + order.getSessionId();
    //
    //     activeOrderRepository.lockForUpdate(lockKey);
    //     try {
    //         // Mirror SessionAndOrderSweeper: never release/delete an order that is mid-checkout.
    //         // Checkout's own failure handling resets it to PRE_CHECKOUT, after which a later sweep
    //         // can clean it up if still expired. Releasing inventory under an active checkout would
    //         // break a buyer who is currently paying.
    //         if (order.isCheckoutInProgress()) {
    //             log.info("Skipping expired order for userId={} sessionId={} because checkout is in progress",
    //                 order.getUserId(), order.getSessionId());
    //             return;
    //         }
    //         for (ActiveOrderDTO.CartLineDTO line : order.toDTO().lines()) {
    //             try {
    //                 Event event = eventRepository.findById(line.eventId());
    //                 if (event != null) {
    //                     eventRepository.lockForUpdate(line.eventId());
    //                     try {
    //                         InventorySelection selection = (line.seatNumber() != null)
    //                             ? InventorySelection.seated(List.of(line.seatNumber()))
    //                             : InventorySelection.standing(1);
    //                         event.releaseInventory(line.zoneId(), selection);
    //                         eventRepository.save(event);
    //                     } finally {
    //                         eventRepository.unlock(line.eventId());
    //                     }
    //                 }
    //             } catch (RuntimeException e) {
    //                 log.warn("Failed to release inventory for expired order ...", e.getMessage());
    //             }
    //         }
    //         activeOrderRepository.delete(order);
    //     } catch (Exception e) {
    //         log.error("Failed to expire order for userId={} sessionId={}",
    //             order.getUserId(), order.getSessionId(), e);
    //     } finally {
    //         activeOrderRepository.unlock(lockKey);
    //     }
    // }




}