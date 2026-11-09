package com.ticketing.system.sales.application.service;
import com.ticketing.system.sales.application.port.out.MarketGate; // outbound port for the market-open gate (governance implements it)

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

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
// Catalog inbound port: catalog owns all Event/InventoryZone inventory mutation, and sales' application
// layer drives inventory through this port.
import com.ticketing.system.catalog.application.port.in.InventoryCommandPort;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.domain.CartLineItem;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.MarketNotOpenException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.ticketing.system.shared.exception.UserFacingErrors;


/**
 * Cart use cases for buyers, member and guest: holding tickets (UC-5, UC-9), removing them, and restoring,
 * renewing and abandoning the cart.
 *
 * <p>A cart is found by user id for a member and by guest session id for a guest. Every change takes the
 * cart's order lock before the inventory port takes the event lock, so locks are always acquired in the
 * same order. The order lock is real only on the in-memory adapters; on the Jpa adapters it is a no-op and
 * {@code @Version} guards the cart. A hold lasts the configured reservation window and is stamped with the cart's order key.
 * Inventory changes go through catalog's {@code InventoryCommandPort}.
 *
 * <p>The write methods are transactional. A member gets a notice after each successful reserve or
 * remove, delivered after commit; no notice is published for a guest, who has no user to notify.
 */
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
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param inventoryPort         catalog port that holds, releases and restores inventory
     * @param activeOrderRepository the carts
     * @param iSessionManager       authenticates member tokens and guest session ids
     * @param eventPublisher        publishes the reservation and removal notices
     * @param userRepository        the source of a member's age for purchase-policy checks
     * @param marketGate            tells whether the market is open for reservations
     * @param systemMetrics         records reservation counts
     * @param clock                 the source of "now" for holds (UTC wall time, ADR-008)
     */
    public ReservationService(
            InventoryCommandPort inventoryPort,
            ActiveOrderRepository activeOrderRepository,
            SessionManager iSessionManager,
            ApplicationEventPublisher eventPublisher,
            UserRepository userRepository,
            MarketGate marketGate,
            ISystemMetrics systemMetrics,
            Clock clock) {
        this.inventoryPort = inventoryPort;
        this.activeOrderRepository = activeOrderRepository;
        this.iSessionManager = iSessionManager;
        this.eventPublisher = eventPublisher;
        this.userRepository = userRepository;
        this.marketGate = marketGate;
        this.systemMetrics = systemMetrics;
        this.clock = clock;
    }

    // Holds and their expiry are UTC wall time read from the injected clock (ADR-008).
    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    // When a hold starting at addedAt runs out: the configured hold (constants.ticket-reservation-duration).
    private static LocalDateTime holdUntil(LocalDateTime addedAt) {
        return addedAt.plus(CartLineItem.getExpirationLimit());
    }

    // ---------------------------------------------------------------------
    // Public API - use these methods from new code/controllers/tests
    // ---------------------------------------------------------------------

    /**
     * Holds tickets for a member by adding the selection to the member's cart, creating the cart if the
     * member has none. The hold starts now and lasts the configured reservation window.
     *
     * <p>If the cart cannot take the selection after inventory was held, the hold is released again and
     * the original failure is rethrown. A member gets a success notice after commit. A failure notice is
     * published as well, but the transaction rolls back with the rethrown failure, so it is never
     * delivered (NaimElijah/Ticketeer#63); the caller sees the exception.
     *
     * @param token        the member's session token
     * @param eventId      the event to hold tickets in
     * @param zoneId       the zone of the event
     * @param selectionDto the standing quantity or the seats to hold
     * @return the reservation, with the time its hold runs out
     * @throws IllegalArgumentException if the token is blank, the selection is missing, an id is not
     *     positive, the quantity is not positive, or a seat label is blank
     * @throws InvalidTokenException    if the token is not a live member token
     * @throws SessionExpiredException  if the token has expired
     * @throws MarketNotOpenException   if the market is closed
     * @throws IllegalStateException    if the member's cart is in checkout
     * @throws com.ticketing.system.shared.exception.UserNotFoundException if the member's user record
     *     cannot be found, which the purchase-policy age check needs
     * @throws com.ticketing.system.shared.exception.ConcurrentReservationException
     *     if another request changed the same cart or zone concurrently, detected on save or at commit
     * @throws RuntimeException         the inventory port's refusal, for example an unavailable seat or a
     *     purchase-policy violation, propagated unchanged
     */
    @Transactional
    public ReservationResultDTO reserveForMember(String token, int eventId, int zoneId,
            InventorySelectionDTO selectionDto) {
        return reserve(authenticateMember(token), eventId, zoneId, requireSelection(selectionDto));
    }

    /**
     * Holds tickets for a guest by adding the selection to the cart of the guest session, creating the
     * cart if the session has none. Behaves as {@link #reserveForMember}, except that no notice is
     * published.
     *
     * @param sessionId    the raw id of the guest's live session
     * @param eventId      the event to hold tickets in
     * @param zoneId       the zone of the event
     * @param selectionDto the standing quantity or the seats to hold
     * @return the reservation, with the time its hold runs out
     * @throws IllegalArgumentException if the session id is blank, or for any argument that
     *     {@link #reserveForMember} refuses
     * @throws SessionExpiredException  if {@code sessionId} is not a live guest session's id
     * @throws MarketNotOpenException   if the market is closed
     * @throws IllegalStateException    if the guest's cart is in checkout
     * @throws com.ticketing.system.shared.exception.ConcurrentReservationException
     *     if another request changed the same cart or zone concurrently, detected on save or at commit
     * @throws RuntimeException         the inventory port's refusal, propagated unchanged
     */
    @Transactional
    public ReservationResultDTO reserveForGuest(String sessionId, int eventId, int zoneId,
            InventorySelectionDTO selectionDto) {
        return reserve(authenticateGuest(sessionId), eventId, zoneId, requireSelection(selectionDto));
    }

    /**
     * Removes held tickets from a member's cart and releases them back to inventory. Removing is allowed
     * while the market is closed, and also for lines whose hold has run out.
     *
     * <p>If the cart cannot be updated after inventory was released, the hold is restored and the
     * original failure is rethrown. A member gets a success notice after commit. A failure notice is
     * published as well, but the transaction rolls back with the rethrown failure, so it is never
     * delivered (NaimElijah/Ticketeer#63); the caller sees the exception.
     *
     * @param token        the member's session token
     * @param eventId      the event of the held tickets
     * @param zoneId       the zone of the event
     * @param selectionDto the standing quantity or the seats to remove
     * @return the removed selection; its expiry does not belong to a hold that still exists
     * @throws IllegalArgumentException if the token is blank, an argument is invalid as for
     *     {@link #reserveForMember}, the member has no cart, or the cart does not hold the selection
     * @throws InvalidTokenException    if the token is not a live member token
     * @throws SessionExpiredException  if the token has expired
     * @throws IllegalStateException    if the member's cart is in checkout
     * @throws com.ticketing.system.shared.exception.ConcurrentReservationException
     *     if another request changed the same cart or zone concurrently, detected on save or at commit
     * @throws RuntimeException         the inventory port's refusal, propagated unchanged
     */
    @Transactional
    public ReservationResultDTO removeForMember(String token, int eventId, int zoneId,
            InventorySelectionDTO selectionDto) {
        return remove(authenticateMember(token), eventId, zoneId, requireSelection(selectionDto));
    }

    /**
     * Removes held tickets from a guest's cart and releases them back to inventory. Behaves as
     * {@link #removeForMember}, except that no notice is published.
     *
     * @param sessionId    the raw id of the guest's live session
     * @param eventId      the event of the held tickets
     * @param zoneId       the zone of the event
     * @param selectionDto the standing quantity or the seats to remove
     * @return the removed selection; its expiry does not belong to a hold that still exists
     * @throws IllegalArgumentException if the session id is blank, or for any argument or cart state
     *     that {@link #removeForMember} refuses
     * @throws SessionExpiredException  if {@code sessionId} is not a live guest session's id
     * @throws IllegalStateException    if the guest's cart is in checkout
     * @throws com.ticketing.system.shared.exception.ConcurrentReservationException
     *     if another request changed the same cart or zone concurrently, detected on save or at commit
     * @throws RuntimeException         the inventory port's refusal, propagated unchanged
     */
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
                log.warn("Request rejected: cannot modify active order during checkout. eventId={}, zoneId={}, userId={}, orderKey={}",
                        eventId, zoneId, buyer.isMember() ? buyer.userId() : null, activeOrder.getOrderKey());
                throw new IllegalStateException("Cannot modify active order during checkout");
            }

            // Policy inputs computed from the order and the buyer's identity. The effective purchase
            // policy itself (company AND event, RESERVE stage) is enforced INSIDE the inventory port,
            // because catalog owns the event and the company policy.
            int totalQuantity = existingForEvent(activeOrder, eventId) + selection.getQuantity();
            Integer buyerAge = buyer.isMember() ? resolveMemberAge(buyer.userId()) : null;
            int buyerId = buyer.isMember() ? buyer.userId() : -1;

            // Reserve inventory through the port: it acquires the event buyer-lock, validates the policy,
            // reserves the (ownership-stamped) selection, saves the event, and releases the lock — then
            // returns the unit price to record on the cart line. Done before mutating the active order so
            // a reserve failure (e.g. not enough availability) leaves the cart untouched.
            // One reading of the clock per reservation: the cart line's hold starts at it, and the
            // reserved seats record the same expiry the line will have.
            LocalDateTime addedAt = now();
            double pricePerTicket = inventoryPort.reserve(eventId, zoneId, selection, activeOrder.getOrderKey(),
                    holdUntil(addedAt), buyerId, buyerAge, totalQuantity);
            inventoryReserved = true;

            // A failure while adding the line is undone by the catch block, which releases the hold just taken.
            addOrderReservation(activeOrder, eventId, zoneId, selection, pricePerTicket, addedAt);
            orderModified = true;

            activeOrderRepository.save(activeOrder);
            notifySuccessAfterUnlock = true;
            systemMetrics.record(MetricType.RESERVATION);
            return buildReservationResult(eventId, zoneId, selection, addedAt);

        } catch (RuntimeException e) {
            // Undo the cart line and the inventory hold taken so far, then rethrow the reserve's own failure.
            rollbackReservationIfNeeded(activeOrder, eventId, zoneId, selection, inventoryReserved,
                    orderModified);

            notifyFailureAfterUnlock = true;
            failureNotificationReason = memberReadableReason(e);
            // The failure notice is published inside this transaction, which the rethrow rolls back, so
            // it is never delivered (ADR-004); the caller sees the failure directly. #63 decides whether
            // failure alerts are wanted, and if so they must be published after the transaction ends.

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
                notifyReservationSuccessIfMember(buyer, eventId, zoneId, selection.getQuantity());
            }

            if (notifyFailureAfterUnlock) {
                notifyReservationFailureIfMember(buyer, eventId, zoneId, failureNotificationReason);
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
                log.warn("Request rejected: cannot modify active order during checkout. eventId={}, zoneId={}, userId={}, orderKey={}",
                        eventId, zoneId, buyer.isMember() ? buyer.userId() : null, activeOrder.getOrderKey());
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

            notifySuccessAfterUnlock = true;

            return buildReservationResult(eventId, zoneId, selection, now());

        } catch (RuntimeException e) {
            // Put back the inventory hold and the cart line removed so far, then rethrow the remove's own failure.
            rollbackRemoveIfNeeded(
                    activeOrder,
                    eventId,
                    zoneId,
                    selection,
                    orderModified,
                    inventoryReleased,
                    removedPricePerTicket);

            notifyFailureAfterUnlock = true;
            failureNotificationReason = memberReadableReason(e);

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
                notifyRemoveSuccessIfMember(buyer, eventId, zoneId, selection.getQuantity());
            }

            if (notifyFailureAfterUnlock) {
                notifyRemoveFailureIfMember(buyer, eventId, zoneId, failureNotificationReason);
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
            double price, LocalDateTime addedAt) {
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

    private BuyerContextDTO authenticateMember(String token) {
        return BuyerContextDTO.member(validateTokenAndGetUserId(token));
    }

    // A guest's credential is the id of a live guest session, as at checkout: a session id that was
    // never issued, has expired, or belongs to a member or admin session opens no cart, and neither
    // does a token (ADR-012, decision 4).
    private BuyerContextDTO authenticateGuest(String sessionId) {
        validateSessionId(sessionId);
        requireLiveGuestSession(sessionId);
        return BuyerContextDTO.guest(sessionId);
    }

    private void requireLiveGuestSession(String sessionId) {
        if (!iSessionManager.validateGuestSession(sessionId)) {
            log.warn("Request rejected: guest session is not live");
            throw new SessionExpiredException();
        }
    }

    private int validateTokenAndGetUserId(String token) {
        if (token == null || token.isBlank()) {
            log.warn("Request rejected: missing authentication token");
            throw new IllegalArgumentException("Missing authentication token");
        }

        // extractUserId throws for an invalid, revoked or expired token, or one not a member's, so no separate
        // validateToken call is needed.
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
    // effective-policy validation against the event + company policy. An unknown age passes the reserve
    // stage of an age policy, so a failed lookup must never stand in for one: a store failure, and a member
    // with no user row (UserNotFoundException), both fail the reservation (NaimElijah/Ticketeer#187).
    private Integer resolveMemberAge(int userId) {
        return userRepository.getUserById(userId).getAge();
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

    // Event/zone existence checks and the price read happen inside the catalog inventory port
    // (InventoryService), which owns the Event aggregate.

    // A member's cart is found by user id, a guest's by session id; a buyer with none gets a new, unsaved one.
    private ActiveOrder getOrCreateActiveOrder(BuyerContextDTO buyer) {
        if (buyer.isMember()) {
            ActiveOrder activeOrder = activeOrderRepository.getByUserId(buyer.userId());
            if (activeOrder == null) {
                log.info("No active order found for userId={}, creating new ActiveOrder", buyer.userId());
                activeOrder = new ActiveOrder(buyer.userId(), now());
            }
            return activeOrder;
        }

        return activeOrderRepository.getBySessionId(buyer.sessionId())
                .orElseGet(() -> {
                    log.info("No active order found for guest, creating new ActiveOrder");
                    return ActiveOrder.forGuest(buyer.sessionId(), now());
                });
    }

    // The buyer's existing cart, found as in getOrCreateActiveOrder; a buyer with none gets an
    // IllegalArgumentException, whose message reaches the user.
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

    // Best-effort undo of a reserve that failed after the inventory hold: removes the cart line it added
    // and releases the hold. A failing step is logged at WARN with its exception and not rethrown, so the
    // caller rethrows the reserve's own failure; the WARN is the only trace of a seat or cart line left behind.
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
        } catch (RuntimeException rollbackFailure) {
            log.warn("Rollback failed while removing cart lines after reserve failure. eventId={}, zoneId={}",
                    eventId,
                    zoneId,
                    rollbackFailure);
        }

        try {
            if (activeOrder != null) {
                // Release the just-reserved inventory through the port (it re-acquires the event
                // buyer-lock and verifies ownership via the order key). The order lock is still held.
                inventoryPort.release(eventId, zoneId, selection, activeOrder.getOrderKey());
            }
        } catch (RuntimeException rollbackFailure) {
            log.warn("Rollback failed while releasing inventory after reserve failure. eventId={}, zoneId={}",
                    eventId,
                    zoneId,
                    rollbackFailure);
        }
    }

    // Best-effort undo of a remove that failed after the inventory release: restores the hold, then puts the
    // cart line back. A failing step is logged at ERROR with its exception and not rethrown, so the caller
    // rethrows the remove's own failure.
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
        LocalDateTime readdedAt = now();   // the restored hold and the re-added cart line start together
        try {
            if (inventoryReleased && activeOrder != null) {
                inventoryPort.restore(eventId, zoneId, selection, activeOrder.getOrderKey(), holdUntil(readdedAt));
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
                addOrderReservation(activeOrder, eventId, zoneId, selection, pricePerTicket, readdedAt);
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
            // Publish a cross-context integration event; notifications consumes it after commit (ADR-004).
            eventPublisher.publishEvent(new TicketReservationSucceededNotice(buyer.userId(), eventId, zoneId, quantity));
        }
    }

    // The reason goes into the member's notification, so it follows the rule the UI applies to what a
    // user may read: a refusal's own message, and the generic text for anything else.
    private static String memberReadableReason(RuntimeException failure) {
        return UserFacingErrors.isUserFacing(failure) ? failure.getMessage() : UserFacingErrors.GENERIC_MESSAGE;
    }

    private void notifyReservationFailureIfMember(BuyerContextDTO buyer, int eventId, int zoneId, String reason) {
        if (buyer != null && buyer.isMember()) {
            // Publish a cross-context integration event; notifications consumes it after commit (ADR-004).
            eventPublisher.publishEvent(new TicketReservationFailedNotice(buyer.userId(), eventId, zoneId, reason));
        }
    }

    private void notifyRemoveSuccessIfMember(BuyerContextDTO buyer, int eventId, int zoneId, int quantity) {
        if (buyer.isMember()) {
            // Publish a cross-context integration event; notifications consumes it after commit (ADR-004).
            eventPublisher.publishEvent(new ReservationRemovalSucceededNotice(buyer.userId(), eventId, zoneId, quantity));
        }
    }

    private void notifyRemoveFailureIfMember(BuyerContextDTO buyer, int eventId, int zoneId, String reason) {
        if (buyer != null && buyer.isMember()) {
            // Publish a cross-context integration event; notifications consumes it after commit (ADR-004).
            eventPublisher.publishEvent(new ReservationRemovalFailedNotice(buyer.userId(), eventId, zoneId, reason));
        }
    }

    // The result of a reserve or remove; its expiry is the hold that starts at holdStart.
    private ReservationResultDTO buildReservationResult(int eventId, int zoneId, InventorySelectionDTO selection,
            LocalDateTime holdStart) {
        return new ReservationResultDTO(
                eventId,
                zoneId,
                selection.getQuantity(),
                selection.getSeatNumbers(),
                holdUntil(holdStart));
    }

    /**
     * The member's cart, with event names on its lines. A guest's cart is read by
     * {@link #restoreActiveOrderForGuest}.
     *
     * @param userId the member's user id
     * @return the cart, or {@code null} when the member has none
     */
    @Transactional(readOnly = true)
    public ActiveOrderDTO restoreActiveOrder(int userId) {
        ActiveOrder activeOrder = activeOrderRepository.getByUserId(userId);
        if (activeOrder == null) {
            log.info("No active order found for userId={}, returning null", userId);
            return null;
        }
        log.info("Active order found for userId={}, restoring ActiveOrderDTO", userId);
        ActiveOrderDTO activeOrderDTO = activeOrder.toDTO(now());
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
        return new ActiveOrderDTO(
                activeOrderDTO.userId(),
                activeOrderDTO.sessionId(),
                activeOrderDTO.createdAt(),
                activeOrderDTO.remainingSecondsBeforeExpiry(),
                activeOrderDTO.currentTotalPrice(),
                enrichedLines);
    }

    /**
     * The guest's active order, with event names on its lines.
     *
     * @param sessionId the raw id of the guest's live session
     * @return the order, or {@code null} when the guest has none
     * @throws SessionExpiredException if {@code sessionId} is not a live guest session's id: unknown,
     *     expired, a member's or admin's session, or a token (ADR-012, decision 4)
     */
    @Transactional(readOnly = true)
    public ActiveOrderDTO restoreActiveOrderForGuest(String sessionId) {
        requireLiveGuestSession(sessionId);
        return guestOrderWithEventNames(sessionId);
    }

    private ActiveOrderDTO guestOrderWithEventNames(String sessionId) {
        return activeOrderRepository.getBySessionId(sessionId)
                .map(order -> {
                    log.info("Active order found for guest, restoring ActiveOrderDTO orderKey={}", order.getOrderKey());
                    ActiveOrderDTO dto = order.toDTO(now());
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
                    log.info("No active order found for guest, returning null");
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

    /**
     * Restarts the hold timer of every line in the member's cart, so the buyer has a full hold window to
     * complete payment, and returns the cart as {@link #restoreActiveOrder} does.
     *
     * <p>The timers are renewed only while the cart is fully valid. A missing or empty cart, one in
     * checkout, or one with a line whose hold has run out is left untouched, so the expiry sweeper's
     * path still rejects a stale cart. The renewal runs under the cart's order lock, the one the expiry
     * sweeper takes first; that lock is real only on the in-memory adapters, and on the Jpa adapters
     * {@code @Version} guards the cart. No production code calls it yet.
     *
     * @param userId the member's user id
     * @return the cart, or {@code null} when the member has none
     */
    @Transactional
    public ActiveOrderDTO renewReservationsForMemberCheckout(int userId) {
        String orderLockKey = "user:" + userId;
        activeOrderRepository.lockForUpdate(orderLockKey);
        try {
            ActiveOrder order = activeOrderRepository.getByUserId(userId);
            LocalDateTime now = now();
            if (order != null && !order.isEmpty()
                    && !order.isCheckoutInProgress() && !order.hasExpiredItem(now)) {
                order.renewReservationTimers(now);
                activeOrderRepository.save(order);
                log.info("Renewed reservation timers on checkout entry for userId={}", userId);
            }
        } finally {
            activeOrderRepository.unlock(orderLockKey);
        }
        return restoreActiveOrder(userId);
    }

    /**
     * The guest counterpart of {@link #renewReservationsForMemberCheckout}, for the cart of a guest
     * session. No production code calls it yet.
     *
     * @param sessionId the raw id of the guest's live session
     * @return the cart, or {@code null} when the session has none
     * @throws SessionExpiredException if {@code sessionId} is not a live guest session's id; no hold
     *     is renewed then
     */
    @Transactional
    public ActiveOrderDTO renewReservationsForGuestCheckout(String sessionId) {
        requireLiveGuestSession(sessionId);
        String orderLockKey = "sess:" + sessionId;
        activeOrderRepository.lockForUpdate(orderLockKey);
        try {
            ActiveOrder order = activeOrderRepository.getBySessionId(sessionId).orElse(null);
            LocalDateTime now = now();
            if (order != null && !order.isEmpty()
                    && !order.isCheckoutInProgress() && !order.hasExpiredItem(now)) {
                order.renewReservationTimers(now);
                activeOrderRepository.save(order);
                log.info("Renewed reservation timers on guest checkout entry orderKey={}", order.getOrderKey());
            }
        } finally {
            activeOrderRepository.unlock(orderLockKey);
        }
        return guestOrderWithEventNames(sessionId);
    }

    /**
     * Releases the buyer's held inventory and deletes the buyer's cart. A buyer with no cart is a no-op.
     *
     * <p>Each line is released through the inventory port on its own, best-effort: a failed release is
     * logged at WARN and does not stop the others or the delete. The login cart merge calls it for a
     * member cart that a guest cart replaces. A lost race with another buyer in the zone is not absorbed
     * per line: it fails the whole call.
     *
     * @param buyer the member (cart found by user id) or guest (cart found by session id) whose cart to
     *     abandon
     * @throws IllegalArgumentException if {@code buyer} is {@code null}
     * @throws IllegalStateException if the cart is in checkout
     * @throws com.ticketing.system.shared.exception.ConcurrentReservationException if the call lost a race
     *     with another buyer in a zone it releases
     */
    @Transactional
    public void abandonActiveOrder(BuyerContextDTO buyer) {
        if (buyer == null) {
            throw new IllegalArgumentException("Buyer context is required");
        }
        String orderLockKey = buyer.isMember()
                ? "user:" + buyer.userId()
                : "sess:" + buyer.sessionId();

        // Lock the ACTIVE ORDER for update while we abandon it. Each per-line inventory release acquires
        // the EVENT buyer-lock INSIDE the inventory port (order-before-event ordering preserved); the
        // port owns the load/release/save under that lock.
        activeOrderRepository.lockForUpdate(orderLockKey);

        try {
            ActiveOrder activeOrder = buyer.isMember()
                    ? activeOrderRepository.getByUserId(buyer.userId())
                    : activeOrderRepository.getBySessionId(buyer.sessionId()).orElse(null);

            if (activeOrder == null) {
                if (buyer.isMember()) {
                    log.info("No active order to abandon for userId={}", buyer.userId());
                } else {
                    log.info("No active order to abandon for guest");
                }
                return;
            }

            if (activeOrder.isCheckoutInProgress()) {
                throw new IllegalStateException("Cannot abandon active order while checkout is in progress");
            }

            // Release any reserved inventory back to the events before deleting the active order.
            String orderKey = activeOrder.getOrderKey();
            List<ActiveOrderDTO.CartLineDTO> lines = activeOrder.toDTO(now()).lines();

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
            activeOrderRepository.delete(activeOrder);

            if (buyer.isMember()) {
                log.info("Abandoned active order for userId={}", buyer.userId());
            } else {
                log.info("Abandoned guest active order orderKey={}", orderKey);
            }

        } finally {
            activeOrderRepository.unlock(orderLockKey);
        }
    }

    /**
    * Removes a line from the cart, determining from the credential whether the buyer is a member or a guest.
    *
    * @param userTokenOrSessionId a member token or a guest's raw session id; a credential shaped like a
    *     JWT must validate as a member token
    * @param eventId              the event of the held tickets
    * @param zoneId               the zone of the event
    * @param selection            the standing quantity or the seats to remove
    * @return the removed selection, as {@link #removeForMember} returns it
    * @throws IllegalArgumentException if the credential is blank, or as {@link #removeForMember}
    *     refuses
    * @throws InvalidTokenException    if a JWT-shaped credential is not a live member token
    * @throws SessionExpiredException  if the member token has expired, or the credential is not a
    *     live guest session's id
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
     * JWTs have two dots; guest session IDs are UUIDs with none. A credential that looks
     * like a JWT must validate as a member token, or validation throws so the caller sees
     * the real failure.
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

    /**
     * The buyer's current cart, with event names, for a member token or a guest's session id.
     *
     * <p>The credential is tried as a member token first. Only one that is not a live member token
     * (refused by the session manager as invalid or expired) is tried as a guest session id.
     *
     * @param userOrSessionId a member token or a guest's raw session id
     * @return the cart, or {@code null} for a blank credential or a buyer with no cart
     * @throws SessionExpiredException if the credential is neither a live member token nor a live
     *     guest session id, or a member token expired between its check and the read of its user id
     * @throws InvalidTokenException if a member token was revoked between its check and the read of its
     *     user id
     * @throws RuntimeException a failure of the session or cart store, propagated unchanged
     */
    @Transactional(readOnly = true)
    public ActiveOrderDTO viewMyActiveOrder(String userOrSessionId) {
        if (userOrSessionId == null || userOrSessionId.isBlank()) {
            return null;
        }
        if (isLiveMemberToken(userOrSessionId)) {
            return restoreActiveOrder(iSessionManager.extractUserId(userOrSessionId));
        }
        // Only a live guest session's id passes here: a member's raw session id or a dead token gets
        // SessionExpiredException, never the cart bound to that id (ADR-012, decision 4).
        return restoreActiveOrderForGuest(userOrSessionId);
    }

    // Only the session manager's refusals mean "not a live member token". A store failure says nothing
    // about the credential, so it propagates rather than sending a member down the guest path, whose
    // refusal the UI would show as an expired session (NaimElijah/Ticketeer#187).
    private boolean isLiveMemberToken(String credential) {
        try {
            return iSessionManager.validateToken(credential);
        } catch (InvalidTokenException notALiveMemberToken) {
            return false;
        }
    }





}