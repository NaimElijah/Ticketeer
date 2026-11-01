package com.ticketing.system.bootstrap.scheduling;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ticketing.system.sales.application.event.OrderExpiredEvent;
import com.ticketing.system.shared.metrics.ISystemMetrics;
import com.ticketing.system.shared.metrics.MetricType;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.domain.CartLineItem;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.domain.InventorySelection;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.domain.Session;

/**
 * UC-2 sweeper: scans for expired Sessions and ActiveOrders and releases
 * the resources they held.
 *
 * <p>Runs on a fixed delay (default 60s; override via
 * {@code sweeper.fixed-delay-ms}). Two passes per tick:
 *
 * <ol>
 *   <li><b>Expired sessions</b> — sessions past their {@code expiresAt}.
 *     For Guest sessions the attached cart is deleted and its tickets are
 *     released to inventory. For Member sessions the row is deleted but the
 *     cart (keyed by {@code userId}) is preserved per D9a — it will be
 *     re-attached to the next Member session on login.</li>
 *   <li><b>Expired carts</b> — ActiveOrders whose {@link CartLineItem} TTL
 *     (10 min from {@code addedAt}) has elapsed. The cart is unusable
 *     regardless of owner, so it's deleted and its tickets are released.
 *     This is the only place a Member's cart is destroyed without an
 *     explicit logout/end-session call.</li>
 * </ol>
 *
 * <p>The sweep and each of its passes are public so tests can drive them
 * directly with a controllable {@link Clock} (no need to wait for the
 * {@code @Scheduled} cadence).
 */
@Component
@Slf4j
public class SessionAndOrderSweeper {

    private final SessionRepository sessionRepository;
    private final ActiveOrderRepository activeOrderRepository;
    private final EventRepository eventRepository;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;
    private final ISystemMetrics systemMetrics;

    public SessionAndOrderSweeper(
            SessionRepository sessionRepository,
            ActiveOrderRepository activeOrderRepository,
            EventRepository eventRepository,
            Clock clock,
            ApplicationEventPublisher eventPublisher,
            ISystemMetrics systemMetrics) {
        this.sessionRepository = sessionRepository;
        this.activeOrderRepository = activeOrderRepository;
        this.eventRepository = eventRepository;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
        this.systemMetrics = systemMetrics;
    }
    
    @Scheduled(fixedDelayString = "${sweeper.fixed-delay-ms:60000}")
    // F3/#371: the tick's inventory releases + row deletes commit as one transaction; an optimistic-lock
    // conflict rolls the tick back and the next @Scheduled pass re-sweeps (retry via cadence — no Spring-Retry dep).
    @Transactional
    public void sweep() {
        Instant now = clock.instant();
        int sessionsCleaned = sweepExpiredSessions(now);
        int ordersCleaned = sweepExpiredOrders(now);
        if (sessionsCleaned > 0 || ordersCleaned > 0) {
            log.info("sweeper tick: {} session(s), {} order(s) cleaned up",
                    sessionsCleaned, ordersCleaned);
        }
    }

    /**
     * Returns the count of sessions deleted. Public so unit tests can drive
     * each pass independently of the {@code @Scheduled} cadence.
     *
     * <p>Each swept-out guest session is a visitor exit (analytics, UC-46), recorded only once the
     * surrounding transaction commits; without one, it is recorded at once.
     */
    public int sweepExpiredSessions(Instant now) {
        List<Session> expired = sessionRepository.findExpiredBefore(now);
        int visitorExits = 0;
        for (Session session : expired) {
            cleanUpAttachedCart(session, holdTime(now));
            sessionRepository.delete(session.getSessionId());
            // Member sessions persist their cart by userId, so they are not counted as exits.
            if (session.isGuest()) {
                visitorExits++;
            }
        }
        recordVisitorExitsAfterCommit(visitorExits);
        return expired.size();
    }

    // A tick that rolls back deleted nothing, and the next tick sweeps the same sessions again, so an
    // exit counted before the commit would be counted twice.
    private void recordVisitorExitsAfterCommit(int visitorExits) {
        if (visitorExits == 0) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            recordVisitorExits(visitorExits);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                recordVisitorExits(visitorExits);
            }
        });
    }

    private void recordVisitorExits(int visitorExits) {
        for (int i = 0; i < visitorExits; i++) {
            systemMetrics.record(MetricType.VISITOR_EXIT);
        }
    }

    /**
     * Returns the count of orders released and deleted; an order skipped because its checkout is in
     * progress or it is no longer expired is not counted. Public for the same reason. A line's hold is
     * judged against {@code now}, both in the scan and in the re-check before the release.
     */
    public int sweepExpiredOrders(Instant now) {
        LocalDateTime holdNow = holdTime(now);
        List<ActiveOrder> expired = activeOrderRepository.findExpired(holdNow);
        int released = 0;
        for (ActiveOrder order : expired) {
            // requireStillExpired=true: release/delete only a cart that is still expired at the re-check
            // (see releaseTicketsToInventory for what that re-check can see on each adapter). Publish the
            // OrderExpired event only when we actually released, so a salvaged cart doesn't trigger a
            // spurious "your order expired" notification.
            if (releaseTicketsToInventory(order, true, holdNow)) {
                eventPublisher.publishEvent(new OrderExpiredEvent(order.getUserId(), order.getSessionId()));
                released++;
            }
        }
        return released;
    }

    // Cart holds are stamped in UTC wall time (ADR-008); sessions use Instant.
    private static LocalDateTime holdTime(Instant now) {
        return LocalDateTime.ofInstant(now, ZoneOffset.UTC);
    }

    /**
     * D9a-aware cart cleanup. Guest sessions cascade-delete the cart
     * (Guest identity = the session itself). Member sessions leave the
     * cart alone; it lives on by userId until logout-restoration or its
     * own line-item expiry sweep.
     */
    private void cleanUpAttachedCart(Session session, LocalDateTime now) {
        if (!session.isGuest())
            return;
        Optional<ActiveOrder> cartOpt = activeOrderRepository.getBySessionId(session.getSessionId());
        if (cartOpt.isPresent()) {
            // requireStillExpired=false: the guest's session has ended, so release the attached cart
            // regardless of whether its line items have hit their own TTL yet.
            releaseTicketsToInventory(cartOpt.get(), false, now);
        }
    }

    /**
     * Acquires locks in the same order as checkout/reservation
     * (active-order lock first, then events sorted by id), releases
     * inventory, deletes the order, and unlocks in reverse.
     *
     * <p>Lock order: {@code activeOrder → events[sorted]} prevents
     * deadlocks with {@code ReservationService} and {@code CheckoutService}
     * which follow the identical acquire sequence.
     *
     * <p>The locks are real only on the in-memory adapters. On JPA, {@code lockForUpdate} and
     * {@code unlock} are no-ops, and the order checked here is the copy the scan loaded, not a fresh
     * read. There a buyer's concurrent change to the cart, or another write to one of its events, is
     * caught by {@code @Version} when the tick commits: the commit fails, the whole tick rolls back,
     * and the next tick sweeps again. #209 moves this logic out of bootstrap.
     *
     * <p>Policy:
     * If an ActiveOrder is CHECKOUT_IN_PROGRESS, the sweeper must not release/delete it.
     * Checkout failure already resets the order back to PRE_CHECKOUT.
     * Then the sweeper can clean it later if it is still expired.
     */
    private boolean releaseTicketsToInventory(ActiveOrder order, boolean requireStillExpired, LocalDateTime now) {
        // Derive the same lock key that ReservationService / CheckoutService use.
        String orderLockKey = order.isMember()
                ? "user:" + order.getUserId()
                : "sess:" + order.getSessionId();

        // Sort event IDs to guarantee a consistent lock order and avoid deadlocks.
        List<Integer> sortedEventIds = order.getItems().stream()
                .map(CartLineItem::geteventId)
                .distinct()
                .sorted()
                .toList();

        activeOrderRepository.lockForUpdate(orderLockKey);
        try {
            // If the order is in the middle of checkout, we should skip it and let the checkout flow handle it 
            // (either complete successfully or fail and revert to PRE_CHECKOUT). Releasing inventory underneath an 
            // active checkout risks causing a failed checkout for the user, which is disruptive. It's safer to let 
            // the checkout flow handle expiration after it finishes its current work, since it will check for expiration 
            // at that point and can clean up accordingly.
            
            // Note that this means expired orders that are CHECKOUT_IN_PROGRESS may not be cleaned up until their next expiration time 
            // (10 min after the last item was added), but this is an acceptable tradeoff to avoid disrupting active checkouts.
            if (order.isCheckoutInProgress()) {
                log.info("Skipping expired active order {} because checkout is in progress", order.getOrderKey());
                return false;
            }

            // Order-expiry path only: re-check expiry before releasing. A buyer may have manually removed the
            // expired line (R1) — or renewed the timers — since findExpired(), leaving a healthy cart we must
            // not destroy. On the in-memory adapters this runs under the order lock and can see that change; on
            // JPA it re-reads the scan's copy, and @Version catches the change at commit instead (#209). The
            // session-expiry path passes false (a guest's attached cart is released when the session ends,
            // regardless of item expiry).
            if (requireStillExpired && !order.isEmpty() && !order.hasExpiredItem(now)) {
                log.info("Skipping active order {} — items no longer expired (raced with manual removal/renew)",
                        order.getOrderKey());
                return false;
            }

            // Acquire locks for all events in the order in a consistent order to prevent deadlocks with concurrent checkouts/reservations.
            for (Integer eventId : sortedEventIds) {
                eventRepository.lockForUpdate(eventId);
            }
            
            try {
                // Group line items by event and zone to aggregate the release calls
                Map<Integer, Map<Integer, List<CartLineItem>>> grouped =
                        order.getItems().stream()
                                .collect(Collectors.groupingBy(
                                        CartLineItem::geteventId,
                                        Collectors.groupingBy(CartLineItem::getzoneId)
                                ));
                // For each (event, zone) group, release the appropriate quantity back to inventory
                for (Map.Entry<Integer, Map<Integer, List<CartLineItem>>> eventEntry : grouped.entrySet()) {
                    Event event = null;
                    try {
                        event = eventRepository.findById(eventEntry.getKey());
                    } catch (EventNotFoundException e) {
                        // If the event is not found, log a warning and skip releasing tickets for this event
                        log.warn("Event with ID {} not found while releasing tickets for order of user id {}. Skipping.", eventEntry.getKey(), order.getUserId());
                        continue;
                    }
                    if (event == null) {
                        log.warn("Event with ID {} not found while releasing tickets for order of user id {}. Skipping.", eventEntry.getKey(), order.getUserId());
                        continue;
                    }
                    // For each zone, release seated/standing independently. Each release is guarded so one
                    // failure (e.g. a double-release race with abandonActiveOrder, or an event mid-edit)
                    // can't abort cleanup of the remaining zones/events or the rest of the sweep tick. The
                    // event is saved only if at least one release in it actually succeeded.
                    boolean anyReleased = false;
                    for (Map.Entry<Integer, List<CartLineItem>> zoneEntry : eventEntry.getValue().entrySet()) {
                        int zoneId = zoneEntry.getKey();
                        List<CartLineItem> items = zoneEntry.getValue();
                        // Release seated seats (by seat label) and standing inventory (by quantity) independently.
                        List<String> seatNumbers = items.stream()
                                .map(CartLineItem::getSeatNumber)
                                .filter(s -> s != null)
                                .toList();

                        String orderKey = order.getOrderKey();
                        int standingCount = (int) items.stream().filter(i -> i.getSeatNumber() == null).count();
                        try {
                            if (standingCount > 0) {
                                event.releaseInventory(zoneId, InventorySelection.standing(standingCount, orderKey));
                                anyReleased = true;
                            }
                            if (!seatNumbers.isEmpty()) {
                                event.releaseInventory(zoneId, InventorySelection.seated(seatNumbers, orderKey));
                                anyReleased = true;
                            }
                        } catch (RuntimeException zoneReleaseFailure) {
                            log.warn("Sweeper: failed to release zone {} of event {} for order {}; continuing",
                                    zoneId, eventEntry.getKey(), order.getOrderKey(), zoneReleaseFailure);
                        }
                    }

                    if (anyReleased) {
                        eventRepository.save(event);
                    }
                }
                // Delete the order with its releases: inside the locks on the in-memory adapters, and in
                // the tick's one transaction on JPA, so nothing sees the order once its inventory is back.
                activeOrderRepository.delete(order);
                return true;
            } finally {
                // Unlock events in reverse order of acquisition.
                for (int i = sortedEventIds.size() - 1; i >= 0; i--) {
                    eventRepository.unlock(sortedEventIds.get(i));
                }
            }
        } finally {
            activeOrderRepository.unlock(orderLockKey);
        }
    }
}
