package com.ticketing.system.Core.Application.services;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.Core.Application.dto.ActiveOrderDTO;
import com.ticketing.system.Core.Application.dto.ReservationResultDTO;
import com.ticketing.system.Core.Application.dto.BuyerContextDTO;
import com.ticketing.system.Core.Application.dto.InventorySelectionDTO;
import com.ticketing.system.Core.Application.interfaces.INotificationService;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.Core.Application.interfaces.ISystemMetrics;
import com.ticketing.system.Core.Application.interfaces.MetricType;
import com.ticketing.system.Core.Domain.events.InventorySelection;
import com.ticketing.system.Core.Domain.ActiveOrder.ActiveOrder;
import com.ticketing.system.Core.Domain.ActiveOrder.IActiveOrderRepository;
import com.ticketing.system.Core.Domain.events.Event;
import com.ticketing.system.Core.Domain.events.IEventRepository;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.Core.Domain.policies.purchase.PurchaseContext;
import com.ticketing.system.Core.Domain.policies.purchase.PurchaseStage;
import com.ticketing.system.Core.Domain.events.InventoryZone;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.MarketNotOpenException;


@Service
@Slf4j
public class ReservationService {
    private final IEventRepository eventRepository;
    private final IActiveOrderRepository activeOrderRepository;
    private final SessionManager iSessionManager;
    private final INotificationService notificationService;
    private final ProductionCompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final SystemAdminService systemAdminService;
    private final ISystemMetrics systemMetrics;

    @Value("${constants.ticket-reservation-duration}")
    private int reservationTimeoutMinutes;

    public ReservationService(
            IEventRepository eventRepository,
            IActiveOrderRepository activeOrderRepository,
            SessionManager iSessionManager,
            INotificationService notificationService,
            ProductionCompanyRepository companyRepository,
            UserRepository userRepository,
            SystemAdminService systemAdminService,
            ISystemMetrics systemMetrics) {
        this.eventRepository = eventRepository;
        this.activeOrderRepository = activeOrderRepository;
        this.iSessionManager = iSessionManager;
        this.notificationService = notificationService;
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.systemAdminService = systemAdminService;
        this.systemMetrics = systemMetrics;
    }

    // ---------------------------------------------------------------------
    // Public API - use these methods from new code/controllers/tests
    // ---------------------------------------------------------------------

    @Transactional
    public ReservationResultDTO reserveForMember(String token, int eventId, int zoneId,
            InventorySelectionDTO selectionDto) {
        return reserve(authenticateMember(token), eventId, zoneId, toDomainSelection(selectionDto));
    }

    @Transactional
    public ReservationResultDTO reserveForGuest(String sessionId, int eventId, int zoneId,
            InventorySelectionDTO selectionDto) {
        return reserve(authenticateGuest(sessionId), eventId, zoneId, toDomainSelection(selectionDto));
    }

    @Transactional
    public ReservationResultDTO removeForMember(String token, int eventId, int zoneId,
            InventorySelectionDTO selectionDto) {
        return remove(authenticateMember(token), eventId, zoneId, toDomainSelection(selectionDto));
    }

    @Transactional
    public ReservationResultDTO removeForGuest(String sessionId, int eventId, int zoneId,
            InventorySelectionDTO selectionDto) {
        return remove(authenticateGuest(sessionId), eventId, zoneId, toDomainSelection(selectionDto));
    }

    // ---------------------------------------------------------------------
    // Unified flows of reserve & remove
    // ---------------------------------------------------------------------

    private ReservationResultDTO reserve(BuyerContextDTO buyer, int eventId, int zoneId, InventorySelection selection) {
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
        if (!systemAdminService.isMarketOpen()) {
            throw new MarketNotOpenException();
        }

        Event event = null;
        ActiveOrder activeOrder = null;
        boolean inventoryReserved = false;
        boolean orderModified = false;

        boolean notifySuccessAfterUnlock = false;
        boolean notifyFailureAfterUnlock = false;
        String failureNotificationReason = null;

        String orderLockKey = buyer.isMember()
                ? "user:" + buyer.userId()
                : "sess:" + buyer.sessionId();

        //* locks activeOrder before event. Good — no deadlock risk between them. Just keep this rule in mind for any future service that touches both.
        // active-order lock remains per buyer/session
        activeOrderRepository.lockForUpdate(orderLockKey);
        // many buyers can reserve unrelated inventory in the same event concurrently:
        // StandingZone.inventoryLock protects standing counters & SeatedZone.seatLocks protect individual seats
        // structural event edits still block buyers because they use write lock. event lock becomes shared buyer lifecycle lock.
        // zone/seat locks help concurrency.
        eventRepository.lockForBuyerOperation(eventId);

        try {
            // Validate event and zone exist before doing anything else (e.g. before creating an active order if needed, before checking inventory, etc.) to fail fast on invalid input and avoid doing unnecessary work or creating entities that we will not use if the event/zone is invalid. This also ensures that we do not create an active order for a buyer if the event or zone they are trying to reserve does not exist, which keeps our data cleaner and avoids having orphaned active orders that are not associated with any valid events or zones.
            event = getEventOrThrow(eventId);
            InventoryZone zone = getZoneOrThrow(event, zoneId);
            activeOrder = getOrCreateActiveOrder(buyer);

            // CheckoutService now marks orders as CHECKOUT_IN_PROGRESS and releases the order lock during Phase 2. ReservationService should reject reserve attempts against an order in that state; otherwise a concurrent reservation can mutate the cart while checkout is pricing/charging based on a snapshot, causing inconsistencies.
            if (activeOrder.isCheckoutInProgress()) {
                log.warn("Request rejected: cannot modify active order during checkout. eventId={}, zoneId={}, userId={}, sessionId={}",
                        eventId, zoneId, buyer.isMember() ? buyer.userId() : null, buyer.isMember() ? null : buyer.sessionId());
                throw new IllegalStateException("Cannot modify active order during checkout");
            }

            // Effective purchase policy (company AND event) at the RESERVE stage: max + (member)
            // age are enforced; minimum and unknown (guest) age are deferred to checkout. Quantity
            // is the buyer's cart total for this event after this add, so MAX caps the whole order.
            validateReservePurchasePolicy(buyer, event, selection, activeOrder);

            double pricePerTicket = zone.getprice();

            // Bind the selection to this order's key so the zone records which order holds the reservation.
            // This enables the 3-phase checkout to verify ownership in Phase 3 without holding event locks during payment/issuance.
            InventorySelection selectionWithKey = selectionWithOrderKey(selection, activeOrder.getOrderKey());

            // Reserve inventory first before modifying the active order, so that if we fail to reserve inventory (e.g. not enough tickets available), we do not end up with an active order that has reservations that were not successfully made. This also ensures that we only modify the active order if we are able to successfully reserve the requested tickets, which keeps our data consistent and avoids having active orders that reflect reservations that do not actually exist.
            event.reserveInventory(zoneId, selectionWithKey);
            inventoryReserved = true;
            
            // Now that we have successfully reserved the inventory, we can safely modify the active order to reflect the new reservation. If any of the validations for modifying the active order fail (e.g. trying to reserve more tickets than allowed by purchase policy, etc.), we will throw an exception and roll back the inventory reservation in the catch block, ensuring that we do not end up with an active order that has reservations that were not successfully made.
            activeOrder.addReservation(eventId, zoneId, selection, pricePerTicket, LocalDateTime.now());
            orderModified = true;

            eventRepository.save(event);
            activeOrderRepository.save(activeOrder);
            // Notify the member of the successful reservation. For guests, we do not have a user ID to send notifications to, so we skip this step for guests. The notification can be used to trigger email notifications, app push notifications, etc. to inform the member of their successful reservation and any details they may need (e.g. event name, zone, quantity reserved, etc.).
            notifySuccessAfterUnlock = true;
            systemMetrics.record(MetricType.RESERVATION);
            // Return the reservation result, which includes details about the reservation such as event ID, zone ID, quantity reserved, seat numbers if applicable, and the expiration time of the reservation (based on the current time plus the configured reservation timeout). This information can be used by the frontend to show the user their current reservations and how long they have before they expire, etc.
            return buildReservationResult(eventId, zoneId, selection);

        } catch (RuntimeException e) {
            // If any exception occurs during the reservation process, we need to roll back any actions that were taken to keep our data consistent. This includes releasing any inventory that was reserved and removing any reservations that were added to the active order. We also log the error for monitoring and debugging purposes, and rethrow the exception to indicate that the reservation failed. The frontend can catch this exception and show an appropriate error message to the user (e.g. "Failed to reserve tickets: not enough availability", "Failed to reserve tickets: event not found", "Failed to reserve tickets: invalid input", etc.).
            rollbackReservationIfNeeded(event, activeOrder, eventId, zoneId, selection, inventoryReserved,
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
            try {
                eventRepository.unlockBuyerOperation(eventId);
            } finally {
                activeOrderRepository.unlock(orderLockKey);
            }

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

    private ReservationResultDTO remove(BuyerContextDTO buyer, int eventId, int zoneId, InventorySelection selection) {
        log.info("Entered remove: buyerType={}, eventId={}, zoneId={}, quantity={}, seats={}",
                buyer.isMember() ? "MEMBER" : "GUEST",
                eventId,
                zoneId,
                selection == null ? null : selection.getQuantity(),
                selection == null ? null : selection.getSeatNumbers());
        // Validate input arguments before doing anything else to fail fast on invalid input and avoid doing unnecessary work. This also ensures that we do not attempt to remove reservations for an event or zone that does not exist, which keeps our data consistent and avoids having active orders that reflect removals for events or zones that do not actually exist.
        validateReservationArguments(eventId, zoneId, selection);

        Event event = null;
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
        * Lock order first, then event.
        * This must stay consistent with reserve(...) and checkout-related flows
        * to avoid deadlocks.
        */
        activeOrderRepository.lockForUpdate(orderLockKey);

        /*
        * Use buyer-operation lock, NOT full update/write lock.
        *
        * remove(...) is like reserve(...): it is a buyer inventory operation.
        * It does not structurally edit the event, venue map, zones, rows, seats, price,
        * status, or policies.
        *
        * The event buyer-operation lock blocks structural event edits while allowing
        * multiple buyers to reserve/remove different inventory concurrently.
        * The actual inventory correctness is protected inside the zones:
        * - StandingZone uses its inventory lock.
        * - SeatedZone uses layout/read lock + sorted seat locks.
        */
        eventRepository.lockForBuyerOperation(eventId);

        try {
            // Validate event and zone exist before doing anything else (e.g. before checking the active order, etc.) to fail fast on invalid input and avoid doing unnecessary work or creating entities that we will not use if the event/zone is invalid. This also ensures that we do not attempt to remove reservations for an event or zone that does not exist, which keeps our data consistent and avoids having active orders that reflect removals for events or zones that do not actually exist.
            event = getEventOrThrow(eventId);
            InventoryZone zone = getZoneOrThrow(event, zoneId); // validates venue map + zone exists before touching the order
            removedPricePerTicket = zone.getprice();
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
            activeOrder.validateContainsReservation(eventId, zoneId, selection);
            // Bind the selection to this order's key so the zone can verify ownership when releasing.
            InventorySelection selectionWithKey = selectionWithOrderKey(selection, activeOrder.getOrderKey());

            /*
            * Release inventory first.
            *
            * Why this order?
            * If inventory release fails, the cart was not changed yet.
            * If inventory release succeeds but cart removal/save fails, the catch block
            * re-reserves the inventory.
            */
            event.releaseInventory(zoneId, selectionWithKey);
            inventoryReleased = true;

            /*
            * Now remove the reservation from the active order.
            * If this fails after inventory was released, rollbackRemoveIfNeeded(...)
            * will re-reserve the inventory.
            */
            activeOrder.removeReservation(eventId, zoneId, selection);
            orderModified = true;

            eventRepository.save(event);
            activeOrderRepository.save(activeOrder);

            // Notify the member of the successful removal. For guests, we do not have a user ID to send notifications to, so we skip this step for guests. The notification can be used to trigger email notifications, app push notifications, etc. to inform the member of their successful reservation removal and any details they may need (e.g. event name, zone, quantity removed, etc.).
            notifySuccessAfterUnlock = true;

            // Return the reservation result, which includes details about the removed reservation such as event ID, zone ID, quantity removed, seat numbers if applicable, and the expiration time of the reservation (based on the current time plus the configured reservation timeout). This information can be used by the frontend to show the user their current reservations and how long they have before they expire, etc.
            return buildReservationResult(eventId, zoneId, selection);

        } catch (RuntimeException e) {
            // If any exception occurs during the removal process, we need to roll back any actions that were taken to keep our data consistent. This includes re-reserving any inventory that was released and re-adding any reservations that were removed from the active order. We also log the error for monitoring and debugging purposes, and rethrow the exception to indicate that the removal failed. The frontend can catch this exception and show an appropriate error message to the user (e.g. "Failed to remove reservation: reservation not found in active order", "Failed to remove reservation: event not found", "Failed to remove reservation: invalid input", etc.).
            rollbackRemoveIfNeeded(
                    event,
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
            try {
                eventRepository.unlockBuyerOperation(eventId);
            } finally {
                activeOrderRepository.unlock(orderLockKey);
            }

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

    // Helper method to convert the InventorySelectionDTO from the API layer to the InventorySelection domain object that is used in the service layer and domain layer.
    private InventorySelection toDomainSelection(InventorySelectionDTO selectionDto) {
        if (selectionDto == null) {
            throw new IllegalArgumentException("Inventory selection is required");
        }
        return selectionDto.toDomainSelection();
    }

    /**
     * Returns a copy of the given selection that carries the provided {@code orderKey}.
     * Used to stamp every reserve/release call with the owning order's identity.
     */
    private InventorySelection selectionWithOrderKey(InventorySelection selection, String orderKey) {
        if (selection.isStandingSelection()) {
            return InventorySelection.standing(selection.getQuantity(), orderKey);
        } else {
            return InventorySelection.seated(selection.getSeatNumbers(), orderKey);
        }
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

    // Validation of the main input arguments for both reserve and remove flows. This method is called at the beginning of both flows to ensure that the input is valid before we do any work. This helps us fail fast on invalid input and avoid doing unnecessary work or creating entities that we will not use if the input is invalid. It also ensures that we do not attempt to reserve or remove inventory for an event or zone that does not exist, which keeps our data consistent and avoids having active orders that reflect reservations or removals for events or zones that do not actually exist.
    private void validateReservePurchasePolicy(BuyerContextDTO buyer, Event event,
            InventorySelection selection, ActiveOrder activeOrder) {
        int eventId = event.getId();
        var existingItems = activeOrder.getItems();
        long existingForEvent = existingItems == null ? 0L
                : existingItems.stream().filter(item -> item.geteventId() == eventId).count();
        int totalQuantity = (int) existingForEvent + selection.getQuantity();

        Integer buyerAge = buyer.isMember() ? resolveMemberAge(buyer.userId()) : null;
        int buyerId = buyer.isMember() ? buyer.userId() : -1;

        PurchaseContext context = new PurchaseContext(
                buyerId, buyerAge, eventId, event.getCompanyId(), totalQuantity, PurchaseStage.RESERVE);

        ProductionCompany company = companyRepository.getCompanyById(event.getCompanyId());
        event.validateEffectivePolicy(company == null ? null : company.getPurchasePolicy(), context);
    }

    private Integer resolveMemberAge(int userId) {
        try {
            User user = userRepository.getUserById(userId);
            return user == null ? null : user.getAge();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void validateReservationArguments(int eventId, int zoneId, InventorySelection selection) {
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

    // Helper method to get the event from the repository and validate that it exists. This is used in both reserve and remove flows to ensure that we are working with a valid event before we attempt to reserve or remove inventory, which keeps our data consistent and avoids having active orders that reflect reservations or removals for events that do not actually exist. By centralizing this logic in a helper method, we also avoid code duplication and keep the main flows cleaner and more focused on the reservation and removal logic rather than the details of how we look up events from the repository.
    private Event getEventOrThrow(int eventId) {
        Event event = eventRepository.findById(eventId);

        if (event == null) {
            log.warn("Request rejected: event not found. eventId={}", eventId);
            throw new IllegalArgumentException("Event not found: " + eventId);
        }

        return event;
    }

    // Helper method to get the inventory zone from the event's venue map and validate that it exists. This is used in both reserve and remove flows to ensure that we are working with a valid event and zone before we attempt to reserve or remove inventory, which keeps our data consistent and avoids having active orders that reflect reservations or removals for events or zones that do not actually exist. By centralizing this logic in a helper method, we also avoid code duplication and keep the main flows cleaner and more focused on the reservation and removal logic rather than the details of how we look up zones from events.
    private InventoryZone getZoneOrThrow(Event event, int zoneId) {
        if (event.getVenueMap() == null) {
            throw new IllegalStateException("Venue map is not configured for event: " + event.getId());
        }
        InventoryZone zone;
        try {
            zone = event.getVenueMap().getZone(zoneId);
        } catch (IllegalArgumentException e) {
            log.warn("Request rejected: zone not found. eventId={}, zoneId={}", event.getId(), zoneId);
            throw new IllegalArgumentException("Zone not found: " + zoneId);
        }
        if (zone == null) {
            log.warn("Request rejected: zone not found. eventId={}, zoneId={}", event.getId(), zoneId);
            throw new IllegalArgumentException("Zone not found: " + zoneId);
        }
        return zone;
    }

    //getZone inconsistency — ReservationService.getZoneOrThrow (line 320) catches IllegalArgumentException and checks for null. Means VenueMap.getZone 
    // sometimes throws and sometimes returns null. Worth tightening that contract to one or the other so callers don't need to handle both.

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
    private void rollbackReservationIfNeeded(Event event, ActiveOrder activeOrder, int eventId, int zoneId,
            InventorySelection selection, boolean inventoryReserved, boolean orderModified) {

        if (!inventoryReserved) {
            return;
        }

        try {
            if (orderModified && activeOrder != null) {
                activeOrder.removeReservation(eventId, zoneId, selection);
                activeOrderRepository.save(activeOrder);
            }
        } catch (RuntimeException ignored) {
            // best-effort rollback; the main exception is rethrown by caller
        }

        try {
            if (event != null) {
                // Pass the orderKey so the zone can verify ownership during rollback release.
                InventorySelection selectionWithKey = activeOrder != null
                        ? selectionWithOrderKey(selection, activeOrder.getOrderKey())
                        : selection;
                event.releaseInventory(zoneId, selectionWithKey);
                eventRepository.save(event);
            }
        } catch (RuntimeException ignored) {
            // best-effort rollback; the main exception is rethrown by caller
        }
    }

    // rollback the cart if inventory release fails, and rollback inventory if the later cart/save flow fails
    private void rollbackRemoveIfNeeded(Event event, ActiveOrder activeOrder, int eventId, int zoneId,
            InventorySelection selection, boolean orderModified,
            boolean inventoryReleased, double pricePerTicket) {
        if (!inventoryReleased && !orderModified) {
            return;
        }

        /*
        * If inventory was released, put it back under the same order ownership key.
        */
        try {
            if (inventoryReleased && event != null && activeOrder != null) {
                InventorySelection selectionWithKey = selectionWithOrderKey(selection, activeOrder.getOrderKey());
                event.reserveInventory(zoneId, selectionWithKey);
                eventRepository.save(event);
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
                activeOrder.addReservation(eventId, zoneId, selection, pricePerTicket, LocalDateTime.now());
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
            notificationService.notifyTicketReservationSuccess(buyer.userId(), eventId, zoneId, quantity);
        }
    }

    private void notifyReservationFailureIfMember(BuyerContextDTO buyer, int eventId, int zoneId, String reason) {
        if (buyer != null && buyer.isMember()) {
            notificationService.notifyTicketReservationFailure(buyer.userId(), eventId, zoneId, reason);
        }
    }

    private void notifyRemoveSuccessIfMember(BuyerContextDTO buyer, int eventId, int zoneId, int quantity) {
        if (buyer.isMember()) {
            notificationService.notifyRemoveTicketReservationSuccess(buyer.userId(), eventId, zoneId, quantity);
        }
    }

    private void notifyRemoveFailureIfMember(BuyerContextDTO buyer, int eventId, int zoneId, String reason) {
        if (buyer != null && buyer.isMember()) {
            notificationService.notifyRemoveTicketReservationFailure(buyer.userId(), eventId, zoneId, reason);
        }
    }

    // Helper method to build the reservation result DTO that is returned by the reserve and remove methods, which includes details about the reservation such as event ID, zone ID, quantity reserved/removed, seat numbers if applicable, and the expiration time of the reservation (based on the current time plus the configured reservation timeout). This information can be used by the frontend to show the user their current reservations and how long they have before they expire, etc.
    private ReservationResultDTO buildReservationResult(int eventId, int zoneId, InventorySelection selection) {
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
            Event event = eventRepository.findById(eventId);
            return (event != null) ? event.getName() : "Unknown Event";
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

        List<Integer> lockedEventIds = new ArrayList<>();
        // Lock the active order for update to prevent concurrent modifications while we are abandoning it. This ensures that
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
            // We need to lock each event for update to prevent concurrent modifications while we are releasing inventory. This ensures that we
            // do not accidentally oversell tickets or release inventory that is being modified by another process.
            String orderKey = activeOrder.getOrderKey();
            List<ActiveOrderDTO.CartLineDTO> lines = activeOrder.toDTO().lines();

            // Get the distinct event IDs from the active order lines to avoid locking the same event multiple times. 
            // We sort the event IDs to ensure a consistent locking order, which helps prevent deadlocks when multiple 
            // threads are trying to lock events in different orders.
            List<Integer> eventIds = lines.stream()
                    .map(ActiveOrderDTO.CartLineDTO::eventId)
                    .distinct()
                    .sorted()
                    .toList();

            // Lock each event for update to prevent concurrent modifications while we are releasing inventory. 
            // This ensures that we do not accidentally oversell tickets or release inventory that is being modified by another process. 
            // We also keep track of the locked event IDs so we can unlock them in reverse order after we are done releasing inventory 
            // and deleting the active order.
            for (Integer eventId : eventIds) {
                eventRepository.lockForUpdate(eventId);
                lockedEventIds.add(eventId);
            }

            // Release the reserved inventory for each line in the active order back to the corresponding event.
            for (ActiveOrderDTO.CartLineDTO line : lines) {
                try {
                    Event event = eventRepository.findById(line.eventId());

                    if (event == null) {
                        log.warn("Event {} not found while abandoning active order. Skipping inventory release.",
                                line.eventId());
                        continue;
                    }
                    // Create an InventorySelection for the line item. If the line has a seat number, we create a seated selection with that seat number. 
                    // If the line does not have a seat number, we create a standing selection with quantity 1. 
                    // We also pass the order key to the selection so that the event can verify ownership of the reservation when releasing inventory.
                    InventorySelection selection = (line.seatNumber() != null)
                            ? InventorySelection.seated(List.of(line.seatNumber()), orderKey)
                            : InventorySelection.standing(1, orderKey);

                    event.releaseInventory(line.zoneId(), selection);
                    eventRepository.save(event);
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
            for (int i = lockedEventIds.size() - 1; i >= 0; i--) {
                eventRepository.unlock(lockedEventIds.get(i));
            }

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