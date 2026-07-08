package com.ticketing.system.Core.Application.services;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ticketing.system.Core.Application.dto.CardDetailsDTO;
import com.ticketing.system.Core.Application.dto.CheckoutResultDTO;
import com.ticketing.system.Core.Application.dto.IssuanceRequestDTO;
import com.ticketing.system.Core.Application.dto.IssuanceResultDTO;
import com.ticketing.system.Core.Application.dto.PaymentRequestDTO;
import com.ticketing.system.Core.Application.dto.PaymentResultDTO;
import com.ticketing.system.Core.Application.interfaces.INotificationService;
import com.ticketing.system.Core.Application.interfaces.IPaymentGateway;
import com.ticketing.system.Core.Application.interfaces.ISessionManager;
import com.ticketing.system.Core.Application.interfaces.ITicketIssuer;
import com.ticketing.system.Core.Domain.ActiveOrder.ActiveOrder;
import com.ticketing.system.Core.Domain.ActiveOrder.CartLineItem;
import com.ticketing.system.Core.Domain.ActiveOrder.IActiveOrderRepository;
import com.ticketing.system.Core.Domain.Tickets.ITicketRepository;
import com.ticketing.system.Core.Domain.Tickets.Ticket;
import com.ticketing.system.Core.Domain.events.Event;
import com.ticketing.system.Core.Domain.events.EventStatus;
import com.ticketing.system.Core.Domain.events.IEventRepository;
import com.ticketing.system.Core.Domain.events.InventorySelection;
import com.ticketing.system.Core.Domain.events.InventoryZone;
import com.ticketing.system.Core.Domain.events.Seat;
import com.ticketing.system.Core.Domain.events.SeatStatus;
import com.ticketing.system.Core.Domain.events.SeatedZone;
import com.ticketing.system.shared.exception.AuthenticationFailedException;
import com.ticketing.system.shared.exception.ConcurrentReservationException;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.EventNotOnSaleException;
import com.ticketing.system.shared.exception.IdempotencyConflictException;
import com.ticketing.system.shared.exception.InsufficientInventoryException;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.MarketNotOpenException;
import com.ticketing.system.shared.exception.PaymentGatewayException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.ticketing.system.shared.exception.TicketIssuanceFailedException;
import com.ticketing.system.shared.exception.UserNotFoundException;
import com.ticketing.system.shared.exception.EntityNotFoundException;
import com.ticketing.system.Core.Domain.orders.IOrderReceiptRepository;
import com.ticketing.system.Core.Domain.orders.OrderReceipt;
import com.ticketing.system.Core.Domain.orders.ReceiptLine;
import com.ticketing.system.Core.Domain.orders.TransactionRecord;
import com.ticketing.system.Core.Domain.policies.purchase.PurchaseContext;
import com.ticketing.system.Core.Domain.users.IUserRepository;
import com.ticketing.system.Core.Domain.company.IProductionCompanyRepository;
import com.ticketing.system.Core.Domain.company.ProductionCompany;
import com.ticketing.system.Core.Domain.users.User;

@Service
@Slf4j
public class CheckoutService {

    private final IActiveOrderRepository activeOrderRepository;
    private final IEventRepository eventRepository;
    private final ITicketRepository ticketRepository;
    private final IOrderReceiptRepository orderReceiptRepository;
    private final ITicketIssuer ticketIssuer;
    private final IPaymentGateway paymentGateway;
    private final INotificationService notificationService;
    private final ISessionManager sessionManager;
    private final IUserRepository userRepository;
    private final IProductionCompanyRepository companyRepository;
    private final SystemAdminService systemAdminService;
    // Programmatic transactions for the checkout's Phase-3 DB work. Checkout cannot be a single
    // @Transactional method because the WSEP charge + issue (Phase 2, 20s HTTP timeouts) must run
    // OUTSIDE any transaction so a slow gateway never pins a DB connection. This template wraps only
    // the Phase-3 DB unit (tickets + receipt + RESERVED→SOLD confirm), between the external calls.
    private final TransactionTemplate transactionTemplate;

    // In-memory cache for completed checkouts to handle idempotency. Keyed by a
    // combination of buyer identity and idempotency key, since the same idempotency
    // key could be used
    // by different users (e.g. if they copy-paste it from a confirmation page). In
    // a real implementation this would likely be a distributed cache like Redis
    // with an expiration time.
    private final ConcurrentMap<String, IdempotencyCacheEntry> completedCheckoutsByIdempotencyKey = new ConcurrentHashMap<>();

    // idempotency means that if the same buyer (member or guest) submits the same
    // checkout request (identified by idempotency key) multiple times, only the
    // first one will be processed and the result will be returned for subsequent
    // ones.
    // This is crucial for preventing duplicate charges and orders if, for example,
    // the user accidentally clicks the "Buy" button twice or if there are network
    // issues causing retries.

    public CheckoutService(
            IActiveOrderRepository activeOrderRepository,
            IEventRepository eventRepository,
            ITicketRepository ticketRepository,
            IOrderReceiptRepository orderReceiptRepository,
            ITicketIssuer ticketIssuer,
            IPaymentGateway paymentGateway,
            INotificationService notificationService,
            ISessionManager sessionManager,
            IUserRepository userRepository,
            IProductionCompanyRepository companyRepository,
            SystemAdminService systemAdminService,
            PlatformTransactionManager transactionManager) {
        this.activeOrderRepository = activeOrderRepository;
        this.eventRepository = eventRepository;
        this.ticketRepository = ticketRepository;
        this.orderReceiptRepository = orderReceiptRepository;
        this.ticketIssuer = ticketIssuer;
        this.paymentGateway = paymentGateway;
        this.notificationService = notificationService;
        this.sessionManager = sessionManager;
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.systemAdminService = systemAdminService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // Result of the atomic Phase-3 persistence unit, surfaced to the orchestrator once the
    // transaction has committed: the receipt id feeds the result DTO, the lines feed the
    // post-unlock purchase notification.
    private record Phase3Persisted(int orderReceiptId, List<ReceiptLine> receiptLines) {
    }

    // UC-32 / I.2.1 — no money moves while the trading market is closed. This is a
    // platform-wide gate, orthogonal to the per-event ON_SALE check enforced later
    // in Phase 3 (validateEventsStillOnSale). Called as a guard clause before the
    // checkout try-block so it propagates cleanly instead of being wrapped as a
    // generic checkout failure.
    private void requireMarketOpen() {
        if (!systemAdminService.isMarketOpen()) {
            throw new MarketNotOpenException();
        }
    }

    // The checkoutMember and checkoutGuest methods follow a similar flow but have
    // some differences in how they identify the buyer (user ID for members, guest
    // session ID + email for guests) and how they retrieve the active order (by
    // user ID vs. by guest session ID). They both implement the same core steps of
    // validating input, checking the cache for idempotency, locking the order and
    // events, pricing items, processing payment, issuing tickets, confirming
    // inventory sale, saving receipts, and handling errors. The separation into two
    // methods allows us to handle member-specific and guest-specific logic cleanly
    // while still sharing common helper methods for the core checkout steps.
    //
    // 3-Phase checkout structure (reduces lock hold time during slow I/O):
    // Phase 1 (short order lock): validate, snapshot items, mark
    // CHECKOUT_IN_PROGRESS, release lock.
    // Phase 2 (no domain locks): price items, charge payment, issue tickets.
    // Phase 3 (short order + event locks): verify reservations still belong to this
    // order,
    // confirm inventory sale, persist, mark bought.
    public CheckoutResultDTO checkoutMember(String token, String idempotencyKey, String currency,
            CardDetailsDTO card) {
        int userId = -1;
        ActiveOrder order = null;
        PaymentResultDTO paymentResult = null;
        double totalPrice = 0.0;
        boolean inventorySaleConfirmed = false;
        boolean checkoutSucceeded = false;
        List<ReceiptLine> receiptLinesToNotifyAfterUnlock = null;

        // orderLockKey is for locking the ActiveOrder to prevent concurrent
        // modifications during checkout.
        String orderLockKey = null;
        // lockedEventIds tracks which events we have locked in Phase 3 so we can unlock
        // them in the finally block.
        List<Integer> lockedEventIds = List.of();

        try {
            userId = authenticateAndGetUserId(token);
            validatePaymentInput(idempotencyKey, currency, card, userId);

            String buyerKey = memberBuyerKey(userId);

            // Idempotency short-circuit BEFORE the market gate: a completed purchase must be returned even
            // if the market has since closed (C1). (Pre-flight validation failures above are still wrapped
            // by the catch as a checkout failure, matching the existing contract.)
            CheckoutResultDTO cached = getCachedCheckoutResult(idempotencyKey, buyerKey);
            if (cached != null) {
                return cached;
            }

            // UC-32 / I.2.1 — no money moves while the market is closed (checked after the idempotency
            // short-circuit). Rethrown raw by the MarketNotOpenException catch below.
            requireMarketOpen();

            // ---------------------------------------------------------------
            // Phase 1: short lock — validate, snapshot, freeze order
            // ---------------------------------------------------------------
            orderLockKey = memberOrderLockKey(userId);
            activeOrderRepository.lockForUpdate(orderLockKey);

            order = activeOrderRepository.getByUserId(userId);
            validateOrderForCheckout(order, userId);

            List<CartLineItem> snapshotItems = List.copyOf(order.getItems());
            String orderKey = order.getOrderKey();

            // Mark the order as checkout in progress and save it to the repository. This is
            // important for preventing other concurrent checkout
            // attempts on the same order and for providing visibility into the order's
            // state during the checkout process.
            // By marking the order as CHECKOUT_IN_PROGRESS, we can also implement logic in
            // other parts of the system
            // (e.g. inventory management) to treat this order differently while it is in
            // this state.
            order.markCheckoutInProgress();
            activeOrderRepository.save(order);

            activeOrderRepository.unlock(orderLockKey);

            // ---------------------------------------------------------------
            // Phase 2: no domain locks — slow external calls - cart is frozen here,
            // ---------------------------------------------------------------
            List<Integer> eventIds = extractSortedEventIds(snapshotItems);
            Integer buyerAge = getBuyerAgeByUserId(userId);
            validatePurchasePolicies(snapshotItems, userId, buyerAge);

            List<PricedCartLine> pricedItems = priceItemsOnce(snapshotItems);
            totalPrice = sumPrices(pricedItems);

            paymentResult = chargePayment(userId, null, totalPrice, idempotencyKey, currency, card);
            validatePaymentResult(paymentResult, totalPrice, currency);

            IssuanceResultDTO issuanceResult = issueTickets(userId, null, snapshotItems);
            validateIssuanceResult(issuanceResult, snapshotItems, userId);

            // ---------------------------------------------------------------
            // Phase 3: short locks — verify reservation ownership, re-check cart snapshot,
            // confirm, persist
            // ---------------------------------------------------------------
            activeOrderRepository.lockForUpdate(orderLockKey);
            order = activeOrderRepository.getByUserId(userId);

            validateOrderStillInCheckout(order);
            validateCheckoutSnapshotStillMatches(order, snapshotItems);

            lockedEventIds = eventIds;
            lockEvents(lockedEventIds);

            // ─── Atomic Phase-3 DB unit (one transaction) ───
            // Re-validate, persist tickets + receipt, then the irreversible RESERVED→SOLD confirm —
            // all committed together or not at all. The WSEP charge/issue already ran in Phase 2,
            // OUTSIDE this (and any) transaction, so no DB connection is held across an HTTP call.
            // If anything here fails — including a @Version conflict at commit when two buyers race
            // the same seat — the whole unit rolls back, inventorySaleConfirmed stays false, and the
            // catch below returns the inventory to stock and refunds the charge via the existing path.
            final int capturedUserId = userId;
            final double capturedTotalPrice = totalPrice;
            final PaymentResultDTO capturedPaymentResult = paymentResult;
            Phase3Persisted persisted = transactionTemplate.execute(status -> {
                validateEventsStillOnSale(snapshotItems);
                // Fail-fast ownership check (read-only): the reservation must still be ours and RESERVED.
                validateCanConfirmInventorySale(snapshotItems, orderKey);

                int receiptId = orderReceiptRepository.nextId();
                List<ReceiptLine> lines = saveTicketsAndBuildReceiptLines(capturedUserId, receiptId, pricedItems,
                        issuanceResult);
                saveMemberReceipt(capturedUserId, receiptId, capturedTotalPrice, lines, capturedPaymentResult, issuanceResult);

                // Point of no return: commit the sale last, once everything fallible has succeeded.
                confirmInventorySale(snapshotItems, orderKey);
                return new Phase3Persisted(receiptId, lines);
            });
            inventorySaleConfirmed = true;
            int orderReceiptId = persisted.orderReceiptId();
            List<ReceiptLine> receiptLines = persisted.receiptLines();

            // Point of no return passed: the sale is committed and the receipt is the durable record.
            // Consuming the cart (buy() + delete) is best-effort — a cleanup hiccup must never turn a
            // committed purchase into a checkout failure (C2). The cart isn't saved: buy() leaves it
            // CHECKOUT_IN_PROGRESS, so a save would strand an empty, unmodifiable cart that wedges the
            // buyer's next reservation.
            finalizeConsumedOrder(order);

            CheckoutResultDTO result = buildCheckoutResult(totalPrice, orderReceiptId, paymentResult, issuanceResult);
            cacheCheckoutResult(idempotencyKey, buyerKey, result);

            receiptLinesToNotifyAfterUnlock = receiptLines;
            checkoutSucceeded = true;

            return result;

        } catch (MarketNotOpenException marketClosed) {
            // Pre-Phase-1 gate failure: nothing was reserved or charged, so there is nothing to roll back.
            // Propagate it raw (callers/tests distinguish a closed market from a mid-checkout failure).
            throw marketClosed;
        } catch (Exception e) {
            handleCheckoutFailure(order, userId, orderLockKey, paymentResult, totalPrice, inventorySaleConfirmed,
                    e);
            // failure handling does not mutate inventory without locks
            // checkout failure handling will: reset CHECKOUT_IN_PROGRESS safely, refund
            // payment if needed, not release inventory without locks, not clear the cart
            // unsafely.
            throw new RuntimeException("Checkout failed, tickets returned to stock", e);
        } finally {
            unlockEvents(lockedEventIds);

            if (orderLockKey != null && activeOrderRepository != null) {
                try {
                    activeOrderRepository.unlock(orderLockKey);
                } catch (Exception ignored) {
                }
            }
            if (checkoutSucceeded && userId > 0 && receiptLinesToNotifyAfterUnlock != null) {
                try {
                    notifyPurchaseCompleted(userId, totalPrice, receiptLinesToNotifyAfterUnlock);
                } catch (RuntimeException notificationFailure) {
                    log.warn("Purchase completed but notification failed for userId={}", userId, notificationFailure);
                }
            }
        }
    }

    // The checkoutMember and checkoutGuest methods follow a similar flow but have
    // some differences in how they identify the buyer (user ID for members, guest
    // session ID + email for guests) and how they retrieve the active order (by
    // user ID vs. by guest session ID). They both implement the same core steps of
    // validating input, checking the cache for idempotency, locking the order and
    // events, pricing items, processing payment, issuing tickets, confirming
    // inventory sale, saving receipts, and handling errors. The separation into two
    // methods allows us to handle member-specific and guest-specific logic cleanly
    // while still sharing common helper methods for the core checkout steps.
    //
    // See checkoutMember for the 3-phase description.
    public CheckoutResultDTO checkoutGuest(String guestSessionId, String guestEmail, String idempotencyKey,
            String currency, CardDetailsDTO card, int buyerAge) {
        ActiveOrder order = null;
        PaymentResultDTO paymentResult = null;
        double totalPrice = 0.0;
        boolean inventorySaleConfirmed = false;

        String orderLockKey = null;
        List<Integer> lockedEventIds = List.of();

        try {
            // Presence + input + idempotency short-circuit run BEFORE the market gate so a completed
            // purchase is returned even if the market has since closed (C1). The session-liveness check is
            // deferred to just after the gate: it doesn't affect the cache key, and the market gate must
            // win over a stale-session error to honour the market-closed contract.
            validateGuestIdentityPresent(guestSessionId, guestEmail);
            validatePaymentInput(idempotencyKey, currency, card, null);

            String buyerKey = guestBuyerKey(guestSessionId, guestEmail);

            CheckoutResultDTO cached = getCachedCheckoutResult(idempotencyKey, buyerKey);
            if (cached != null) {
                return cached;
            }

            requireMarketOpen();
            validateGuestSessionLive(guestSessionId);

            // ---------------------------------------------------------------
            // Phase 1: short lock — validate, snapshot, freeze order
            // ---------------------------------------------------------------
            orderLockKey = guestOrderLockKey(guestSessionId);
            activeOrderRepository.lockForUpdate(orderLockKey);

            order = activeOrderRepository.getBySessionId(guestSessionId)
                    .orElseThrow(() -> new EntityNotFoundException("Active guest order not found"));
            validateOrderForCheckout(order, null);

            List<CartLineItem> snapshotItems = List.copyOf(order.getItems());
            String orderKey = order.getOrderKey();

            order.markCheckoutInProgress();
            activeOrderRepository.save(order);

            activeOrderRepository.unlock(orderLockKey);

            // ---------------------------------------------------------------
            // Phase 2: no domain locks — slow external calls
            // ---------------------------------------------------------------
            List<Integer> eventIds = extractSortedEventIds(snapshotItems);
            validatePurchasePolicies(snapshotItems, null, buyerAge);

            List<PricedCartLine> pricedItems = priceItemsOnce(snapshotItems);
            totalPrice = sumPrices(pricedItems);

            paymentResult = chargePayment(null, guestEmail, totalPrice, idempotencyKey, currency, card);
            validatePaymentResult(paymentResult, totalPrice, currency);

            IssuanceResultDTO issuanceResult = issueTickets(null, guestEmail, snapshotItems);
            validateIssuanceResult(issuanceResult, snapshotItems, null);

            // ---------------------------------------------------------------
            // Phase 3: short locks — verify ownership, confirm, persist
            // ---------------------------------------------------------------
            activeOrderRepository.lockForUpdate(orderLockKey);
            order = activeOrderRepository.getBySessionId(guestSessionId)
                    .orElseThrow(() -> new EntityNotFoundException("Active guest order not found in Phase 3"));

            validateOrderStillInCheckout(order);
            validateCheckoutSnapshotStillMatches(order, snapshotItems);

            lockedEventIds = eventIds;
            lockEvents(lockedEventIds);

            // ─── Atomic Phase-3 DB unit (one transaction) ─── see checkoutMember for the rationale:
            // the WSEP charge/issue already ran in Phase 2, outside any transaction; this unit commits
            // tickets + receipt + the RESERVED→SOLD confirm together, or rolls back and the catch refunds.
            final double capturedTotalPrice = totalPrice;
            final PaymentResultDTO capturedPaymentResult = paymentResult;
            Phase3Persisted persisted = transactionTemplate.execute(status -> {
                validateEventsStillOnSale(snapshotItems);
                // Fail-fast ownership check (read-only): the reservation must still be ours and RESERVED.
                validateCanConfirmInventorySale(snapshotItems, orderKey);

                int receiptId = orderReceiptRepository.nextId();
                List<ReceiptLine> lines = saveTicketsAndBuildReceiptLines(null, receiptId, pricedItems, issuanceResult);
                saveGuestReceipt(guestEmail, guestSessionId, receiptId, capturedTotalPrice, lines, capturedPaymentResult,
                        issuanceResult);

                // Point of no return: commit the sale last, once everything fallible has succeeded.
                confirmInventorySale(snapshotItems, orderKey);
                return new Phase3Persisted(receiptId, lines);
            });
            inventorySaleConfirmed = true;
            int orderReceiptId = persisted.orderReceiptId();

            // Point of no return passed: the sale is committed and the receipt is the durable record.
            // Consuming the cart (buy() + delete) is best-effort — a cleanup hiccup must never turn a
            // committed purchase into a checkout failure (C2). The cart isn't saved: buy() leaves it
            // CHECKOUT_IN_PROGRESS, so a save would strand an empty, unmodifiable cart that wedges the
            // buyer's next reservation.
            finalizeConsumedOrder(order);

            CheckoutResultDTO result = buildCheckoutResult(totalPrice, orderReceiptId, paymentResult, issuanceResult);
            cacheCheckoutResult(idempotencyKey, buyerKey, result);

            return result;

        } catch (MarketNotOpenException marketClosed) {
            // Pre-Phase-1 gate failure: nothing reserved or charged, nothing to roll back. Propagate raw.
            throw marketClosed;
        } catch (Exception e) {
            handleGuestCheckoutFailure(order, guestSessionId, orderLockKey, paymentResult, totalPrice,
                    inventorySaleConfirmed, e);
            throw new RuntimeException("Checkout failed, tickets returned to stock", e);
        } finally {
            unlockEvents(lockedEventIds);
            if (orderLockKey != null) {
                try {
                    activeOrderRepository.unlock(orderLockKey);
                } catch (Exception ignored) {
                }
            }
        }
    }

    // Helper methods for checkout flow steps (authentication, validation, pricing,
    // payment, ticket issuance, inventory confirmation, receipt saving,
    // notifications, caching) and error handling are defined below to keep the main
    // checkout methods clean and focused on the overall flow. These helper methods
    // encapsulate specific pieces of logic and can be reused across both member and
    // guest checkout flows where applicable.

    // We validate member checkout identity by checking that the authentication
    // token is present and valid (i.e. corresponds to an active session in our
    // session manager) and that the user ID can be extracted from the token. This
    // ensures that we can associate the checkout with a specific member for
    // tracking and that we have a valid user ID to process the order. If any of
    // these validations fail, we throw an exception to prevent the checkout from
    // proceeding.
    private int authenticateAndGetUserId(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("Missing authentication token");
        }

        if (!sessionManager.validateToken(token)) {
            throw new AuthenticationFailedException();
        }

        int userId = sessionManager.extractUserId(token);
        if (userId <= 0) {
            throw new UserNotFoundException("Invalid user id in token");
        }

        return userId;
    }

    // We validate guest checkout identity by checking that the guest session ID is
    // present and valid (i.e. corresponds to an active guest session in our session
    // manager) and that the guest email is present. This ensures that we can
    // associate the checkout with a specific guest session for tracking and that we
    // have an email to send the receipt to. If any of these validations fail, we
    // throw an exception to prevent the checkout from proceeding.
    // Presence checks only — needed to form the idempotency cache key, so they run before the market gate.
    private void validateGuestIdentityPresent(String guestSessionId, String guestEmail) {
        if (guestSessionId == null || guestSessionId.isBlank()) {
            throw new InvalidTokenException("guestSessionId is required");
        }

        if (guestEmail == null || guestEmail.isBlank()) {
            throw new InvalidTokenException("guestEmail is required");
        }
    }

    // Liveness check — the guest session must still be valid. Runs after the idempotency short-circuit so
    // a completed purchase can be replayed without a live session.
    private void validateGuestSessionLive(String guestSessionId) {
        if (!sessionManager.validateCredential(guestSessionId)) {
            throw new SessionExpiredException();
        }
    }

    // We validate payment input by checking that the idempotency key, currency, and
    // payment method token are all present and valid. The idempotency key is
    // required to ensure that we can handle duplicate checkout attempts correctly.
    // The currency is required to ensure that we know which currency the payment
    // should be processed in. The payment method token is required to have a valid
    // reference to the payment method that the user wants to use for the
    // transaction. If any of these validations fail, we throw an exception to
    // prevent the checkout from proceeding with invalid payment information.
    private void validatePaymentInput(String idempotencyKey, String currency, CardDetailsDTO card,
            Integer userId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Missing idempotency key");
        }

        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Missing currency");
        }

        if (card == null || card.cardNumber() == null || card.cardNumber().isBlank()) {
            throw new IllegalArgumentException("Missing card details");
        }
    }

    // We validate the active order for checkout by checking that it exists and that
    // it is in a state that allows checkout (e.g. not empty, not already bought,
    // etc.). This ensures that we have a valid order to process and that we don't
    // allow checkouts on orders that are not ready for it. If any of these
    // validations fail, we throw an exception to prevent the checkout from
    // proceeding with an invalid order.
    private void validateOrderForCheckout(ActiveOrder order, Integer userId) {
        if (order == null) {
            throw new EntityNotFoundException("Active order not found");
        }

        if (!order.validateCanCheckout()) {
            throw new InvalidStateTransitionException("Order cannot checkout");
        }
    }

    // We extract the unique event IDs from the list of cart line items and sort
    // them to ensure a consistent locking order. This is important for preventing
    // deadlocks when we lock events for update during the checkout process. By
    // always locking events in a consistent order (e.g. by event ID), we can reduce
    // the likelihood of two concurrent checkouts trying to lock the same set of
    // events in different orders and blocking each other indefinitely.
    private List<Integer> extractSortedEventIds(List<CartLineItem> items) {
        return items.stream()
                .map(CartLineItem::geteventId)
                .distinct()
                .sorted()
                .toList();
    }

    //
    private void lockEvents(List<Integer> eventIds) {
        for (Integer eventId : eventIds) {
            eventRepository.lockForBuyerOperation(eventId);
        }
    }

    // ? Note: checkout Phase 3 still blocks structural event editing, but it no
    // longer blocks unrelated buyer operations unnecessarily.
    //
    private void unlockEvents(List<Integer> eventIds) {
        for (int i = eventIds.size() - 1; i >= 0; i--) {
            eventRepository.unlockBuyerOperation(eventIds.get(i));
        }
    }

    private void validateEventsStillOnSale(List<CartLineItem> boughtItems) {
        List<Integer> eventIds = extractSortedEventIds(boughtItems);

        for (Integer eventId : eventIds) {
            Event event = eventRepository.findById(eventId);

            if (event == null) {
                throw new EventNotFoundException("Event not found: " + eventId);
            }

            // SOLD_OUT is allowed: an event that sold out while these tickets were reserved must still
            // let the holders complete their purchase (mirrors Event.validateCanConfirmSale). Rejecting
            // it here blocked the sale of the last tickets of any event.
            if (event.getStatus() != EventStatus.ON_SALE && event.getStatus() != EventStatus.SOLD_OUT) {
                throw new EventNotOnSaleException(
                        eventId, "" + event.getStatus());
            }
        }
    }

    // We price all items at once before processing payment to ensure that the total
    // price is consistent with what the user saw at checkout and to avoid issues
    // where prices might change between individual item pricing calls. This also
    // allows us to apply any relevant discounts or promotions that depend on the
    // overall purchase (e.g. "buy 2 get 1 free" or "10% off if you buy more than 3
    // tickets").
    private List<PricedCartLine> priceItemsOnce(List<CartLineItem> boughtItems) {
        LocalDateTime pricingTime = LocalDateTime.now();

        Map<Integer, Long> quantityByEvent = boughtItems.stream()
                .collect(Collectors.groupingBy(CartLineItem::geteventId, Collectors.counting()));

        List<PricedCartLine> pricedItems = new ArrayList<>();

        for (CartLineItem item : boughtItems) {
            Event event = eventRepository.findById(item.geteventId());
            if (event == null) {
                throw new EventNotFoundException("Event not found: " + item.geteventId());
            }

            int eventQuantity = quantityByEvent.get(item.geteventId()).intValue();

            double finalPrice = event.calculatePriceforoneticket(
                    eventQuantity,
                    item.getPriceAtReservation(),
                    pricingTime);

            pricedItems.add(new PricedCartLine(item, finalPrice));
        }

        return pricedItems;
    }

    // We sum the final prices of all priced cart lines to get the total price for
    // the checkout. This total price is what we will charge the customer and what
    // we will use for the payment validation. By summing the final prices from our
    // pricing logic, we ensure that any discounts or promotions that were applied
    // are reflected in the total amount charged to the customer.
    private double sumPrices(List<PricedCartLine> pricedItems) {
        return pricedItems.stream()
                .mapToDouble(PricedCartLine::finalPrice)
                .sum();
    }

    // We charge the payment using the payment gateway by creating a
    // PaymentRequestDTO with all the necessary information (buyer identity, total
    // price, currency, payment method token, idempotency key) and calling the
    // charge method on the payment gateway. This encapsulates the interaction with
    // the payment gateway and allows us to handle any exceptions or errors that
    // might occur during the payment process in a consistent way. The result from
    // the payment gateway will be validated to ensure that the charge was
    // successful and that the amount and currency match what we expected.
    private PaymentResultDTO chargePayment(Integer buyerUserId, String buyerEmail, double totalPrice,
            String idempotencyKey, String currency, CardDetailsDTO card) {
        PaymentRequestDTO requestToPay = new PaymentRequestDTO(
                idempotencyKey,
                totalPrice,
                currency,
                card,
                buyerUserId,
                buyerEmail);

        return paymentGateway.charge(requestToPay);
    }

    // We validate the payment result from the payment gateway by checking that it
    // is not null, that it contains a valid payment transaction ID, that the
    // gateway name is present, that the charge time is present, and that the
    // charged amount and currency match what we expected. This ensures that we only
    // proceed with successful payments that match our expected values and that we
    // can handle any discrepancies or errors in the payment result appropriately.
    // If any of these validations fail, we throw an exception to prevent the
    // checkout from proceeding with an invalid payment result.
    private void validatePaymentResult(PaymentResultDTO paymentResult, double expectedAmount, String expectedCurrency) {
        if (paymentResult == null) {
            throw new PaymentGatewayException("payment gateway returned null result");
        }

        if (paymentResult.paymentTransactionId() <= 0) {
            throw new PaymentGatewayException("payment transaction id must be positive");
        }

        if (paymentResult.gatewayName() == null || paymentResult.gatewayName().isBlank()) {
            throw new PaymentGatewayException("gateway name is missing");
        }

        if (paymentResult.chargedAt() == null) {
            throw new PaymentGatewayException("payment charge time is missing");
        }

        if (paymentResult.currency() == null || !paymentResult.currency().equalsIgnoreCase(expectedCurrency)) {
            throw new PaymentGatewayException("payment currency mismatch");
        }

        if (Math.abs(paymentResult.chargedAmount() - expectedAmount) > 0.0001) {
            throw new PaymentGatewayException("payment amount mismatch");
        }
    }

    // We issue tickets using the ticket issuer by creating an IssuanceRequestDTO
    // with all the necessary information (buyer identity, list of items being
    // purchased) and calling the issue method on the ticket issuer. This
    // encapsulates the interaction with the ticket issuer and allows us to handle
    // any exceptions or errors that might occur during the ticket issuance process
    // in a consistent way. The result from the ticket issuer will be validated to
    // ensure that the tickets were issued successfully and that we have all the
    // necessary information (e.g. ticket IDs, barcodes) to proceed with saving
    // receipts and confirming inventory sale.
    private IssuanceResultDTO issueTickets(Integer buyerUserId, String buyerEmail, List<CartLineItem> boughtItems) {
        List<IssuanceRequestDTO.TicketIssuanceItemDTO> issuanceItems = boughtItems.stream()
                .map(item -> {
                    Event event = eventRepository.findById(item.geteventId());
                    if (event == null) {
                        throw new EventNotFoundException("Event not found: " + item.geteventId());
                    }

                    return new IssuanceRequestDTO.TicketIssuanceItemDTO(
                            item.geteventId(),
                            event.getName(),
                            item.getzoneId(),
                            item.getSeatNumber());
                })
                .toList();

        IssuanceRequestDTO issuanceRequest = new IssuanceRequestDTO(
                buyerUserId,
                buyerEmail,
                issuanceItems);

        return ticketIssuer.issue(issuanceRequest);
    }

    // We validate the issuance result from the ticket issuer by checking that it is
    // not null, that it contains a valid issuance transaction ID, that the issuer
    // name is present, that the issuance time is present, and that the list of
    // issued barcodes matches the number of items we attempted to purchase. We also
    // validate each issued barcode to ensure that it contains a valid ticket ID and
    // a non-blank barcode value. This ensures that we only proceed with successful
    // ticket issuances that match our expected values and that we can handle any
    // discrepancies or errors in the issuance result appropriately. If any of these
    // validations fail, we throw an exception to prevent the checkout from
    // proceeding with an invalid ticket issuance result.
    private void validateIssuanceResult(
            IssuanceResultDTO issuanceResult,
            List<CartLineItem> boughtItems,
            Integer userId) {
        if (issuanceResult == null) {
            throw new TicketIssuanceFailedException("Ticket issuance failed");
        }

        if (issuanceResult.issuanceTransactionId() == null || issuanceResult.issuanceTransactionId().isBlank()) {
            throw new TicketIssuanceFailedException("Ticket issuance transaction id is missing");
        }

        if (issuanceResult.issuerName() == null || issuanceResult.issuerName().isBlank()) {
            throw new TicketIssuanceFailedException("Ticket issuer name is missing");
        }

        if (issuanceResult.issuedAt() == null) {
            throw new TicketIssuanceFailedException("Ticket issuance time is missing");
        }

        if (issuanceResult.barcodes() == null || issuanceResult.barcodes().isEmpty()) {
            throw new TicketIssuanceFailedException("Ticket issuance returned no barcodes");
        }

        if (issuanceResult.barcodes().size() != boughtItems.size()) {
            throw new TicketIssuanceFailedException("Ticket issuance count mismatch");
        }

        for (var barcode : issuanceResult.barcodes()) {
            if (barcode.ticketId() <= 0) {
                throw new TicketIssuanceFailedException("Issued ticket id must be positive");
            }

            if (barcode.barcodeValue() == null || barcode.barcodeValue().isBlank()) {
                throw new TicketIssuanceFailedException("Issued barcode value must not be blank");
            }
        }
    }

    private void validateOrderStillInCheckout(ActiveOrder order) {
        if (order == null) {
            throw new EntityNotFoundException("Active order disappeared during checkout");
        }

        if (!order.isCheckoutInProgress()) {
            throw new InvalidStateTransitionException("Active order is no longer in checkout progress");
        }
    }

    private void validateCheckoutSnapshotStillMatches(ActiveOrder order, List<CartLineItem> snapshotItems) {
        List<String> currentSignature = cartLineSignature(order.getItems());
        List<String> snapshotSignature = cartLineSignature(snapshotItems);

        if (!currentSignature.equals(snapshotSignature)) {
            throw new ConcurrentReservationException("Active order changed during checkout");
        }
    }

    private List<String> cartLineSignature(List<CartLineItem> items) {
        return items.stream()
                .map(this::cartLineSignature)
                .sorted()
                .toList();
    }

    private String cartLineSignature(CartLineItem item) {
        return item.geteventId()
                + "|"
                + item.getzoneId()
                + "|"
                + String.valueOf(item.getSeatNumber())
                + "|"
                + item.getPriceAtReservation();
    }

    // We validate that we can confirm the inventory sale for the items being
    // purchased by checking that the events and zones exist,
    // that the seat numbers (if applicable) are valid and reserved by the expected
    // orderKey, and that there is enough reserved
    // inventory under that orderKey for standing zones. This is the Phase 3
    // ownership check: after releasing locks during Phase 2
    // (payment/issuance), we re-verify that our reservations were not stolen by
    // expiry/cleanup before we confirm them as SOLD.
    private void validateCanConfirmInventorySale(List<CartLineItem> boughtItems, String orderKey) {
        Map<Integer, Map<Integer, List<CartLineItem>>> grouped = groupItemsByEventAndZone(boughtItems);

        for (Map.Entry<Integer, Map<Integer, List<CartLineItem>>> eventEntry : grouped.entrySet()) {
            Event event = eventRepository.findById(eventEntry.getKey());
            if (event == null) {
                throw new EventNotFoundException("Event not found: " + eventEntry.getKey());
            }

            for (Map.Entry<Integer, List<CartLineItem>> zoneEntry : eventEntry.getValue().entrySet()) {
                int zoneId = zoneEntry.getKey();
                List<CartLineItem> zoneItems = zoneEntry.getValue();

                InventoryZone zone = event.getVenueMap().getZone(zoneId);

                List<String> seatNumbers = extractSeatNumbers(zoneItems);

                if (seatNumbers.isEmpty()) {
                    if (zone.isSeated()) {
                        throw new InsufficientInventoryException("Seated cart item is missing seat numbers");
                    }

                    if (zone.getReservedAmount() < zoneItems.size()) {
                        throw new InsufficientInventoryException(
                                "Not enough reserved standing tickets to confirm sale");
                    }
                } else {
                    if (zone.isStanding()) {
                        throw new InsufficientInventoryException("Standing cart item cannot contain seat numbers");
                    }

                    if (!(zone instanceof SeatedZone seatedZone)) {
                        throw new InsufficientInventoryException("Zone is not a seated zone");
                    }

                    for (String seatNumber : seatNumbers) {
                        if (seatedZone.getSeatStatus(seatNumber) != SeatStatus.RESERVED) {
                            throw new ConcurrentReservationException(
                                    "Seat " + seatNumber + " is no longer RESERVED — reservation may have expired");
                        }
                        // Ownership check: ensure this checkout's order still holds the seat.
                        // Skip when the seat was reserved without an explicit order key (e.g. test
                        // setups that call seatedZone.reserve() directly without an orderKey — those
                        // reservations are stored under the anonymous sentinel and carry no ownership).
                        Seat seat = seatedZone.getSeatByLabel(seatNumber); // ? Note: ownership check below.
                        String seatOwner = seat.getReservedByOrderKey();
                        if (orderKey != null && !orderKey.equals(seatOwner)) {
                            throw new ConcurrentReservationException(
                                    "Seat " + seatNumber + " is held by a different order — cannot confirm sale");
                        }
                    }
                }
            }
        }
    }

    // We confirm the inventory sale for the items being purchased by calling
    // confirmInventorySale on the event for each zone,
    // passing the orderKey so each zone can verify that it still holds these
    // reservations before marking them SOLD.
    private void confirmInventorySale(List<CartLineItem> boughtItems, String orderKey) {
        Map<Integer, Map<Integer, List<CartLineItem>>> grouped = groupItemsByEventAndZone(boughtItems);

        // Confirming across multiple events/zones is not a single atomic step. Track each confirmed unit
        // so a mid-loop failure can be compensated (SOLD -> AVAILABLE): we must never leave a partial SOLD
        // end-state, so the normal !inventorySaleConfirmed rollback + refund stays correct (C3).
        List<ConfirmedUnit> confirmed = new ArrayList<>();
        try {
            for (Map.Entry<Integer, Map<Integer, List<CartLineItem>>> eventEntry : grouped.entrySet()) {
                Event event = eventRepository.findById(eventEntry.getKey());

                for (Map.Entry<Integer, List<CartLineItem>> zoneEntry : eventEntry.getValue().entrySet()) {
                    int zoneId = zoneEntry.getKey();
                    List<CartLineItem> zoneItems = zoneEntry.getValue();

                    List<String> seatNumbers = extractSeatNumbers(zoneItems);
                    InventorySelection selection = seatNumbers.isEmpty()
                            ? InventorySelection.standing(zoneItems.size(), orderKey)
                            : InventorySelection.seated(seatNumbers, orderKey);

                    event.confirmInventorySale(zoneId, selection);
                    confirmed.add(new ConfirmedUnit(event, zoneId, selection));
                }

                eventRepository.save(event);
            }
        } catch (RuntimeException confirmFailure) {
            compensateConfirmedSales(confirmed);
            throw confirmFailure;
        }
    }

    // Best-effort reversal (SOLD -> AVAILABLE) of units already confirmed when a multi-event confirm
    // fails partway, so no inventory is stranded SOLD. Runs under the event locks already held in Phase 3.
    // Failures are logged, not propagated — we are already on the failure path.
    private void compensateConfirmedSales(List<ConfirmedUnit> confirmed) {
        for (int i = confirmed.size() - 1; i >= 0; i--) {
            ConfirmedUnit unit = confirmed.get(i);
            try {
                unit.event().returnSoldToStock(unit.zoneId(), unit.selection());
                eventRepository.save(unit.event());
            } catch (RuntimeException compensationFailure) {
                log.error("Failed to compensate a confirmed sale during checkout rollback. zoneId={}",
                        unit.zoneId(), compensationFailure);
            }
        }
    }

    // Consume the cart after a committed sale: buy() empties it, delete removes it. Best-effort — a
    // cleanup failure here must not turn a committed purchase into a checkout failure (C2).
    private void finalizeConsumedOrder(ActiveOrder order) {
        try {
            order.buy();
            activeOrderRepository.delete(order);
        } catch (RuntimeException cleanupFailure) {
            log.warn("Sale committed but consumed-order cleanup failed for orderKey={}",
                    order.getOrderKey(), cleanupFailure);
        }
    }

    // We group the cart line items by event ID and then by zone ID to facilitate
    // the inventory validation and confirmation steps. This allows us to easily
    // access all items for a specific event and zone when we need to check the
    // inventory status or confirm the sale. The resulting data structure is a
    // nested map where the first key is the event ID, the second key is the zone
    // ID, and the value is a list of cart line items for that event and zone.
    private Map<Integer, Map<Integer, List<CartLineItem>>> groupItemsByEventAndZone(List<CartLineItem> items) {
        return items.stream()
                .collect(Collectors.groupingBy(
                        CartLineItem::geteventId,
                        Collectors.groupingBy(CartLineItem::getzoneId)));
    }

    // We extract the seat numbers from a list of cart line items for a specific
    // zone. If the zone is a standing zone, there should be no seat numbers and we
    // will return an empty list. If the zone is a seated zone, we will return the
    // list of seat numbers that are associated with the cart line items. This
    // helper method simplifies the logic in the inventory validation and
    // confirmation steps by providing a clear way to get the seat numbers for a
    // group of items in the same zone.
    private List<String> extractSeatNumbers(List<CartLineItem> zoneItems) {
        return zoneItems.stream()
                .map(CartLineItem::getSeatNumber)
                .filter(seatNumber -> seatNumber != null)
                .toList();
    }

    // We save the issued tickets to the database and build the receipt lines for
    // the order receipt. For each priced cart line, we create a corresponding
    // Ticket entity with the information from the cart line and the issuance result
    // (e.g. ticket ID, barcode). We then save each ticket to the database and
    // create a ReceiptLine for it that will be included in the order receipt. This
    // method encapsulates the logic for persisting the issued tickets and preparing
    // the data needed for the receipt in one place.
    private List<ReceiptLine> saveTicketsAndBuildReceiptLines(
            Integer holderUserId,
            int orderReceiptId,
            List<PricedCartLine> pricedItems,
            IssuanceResultDTO issuanceResult) {
        List<ReceiptLine> receiptLines = new ArrayList<>();

        for (int i = 0; i < pricedItems.size(); i++) {
            PricedCartLine pricedItem = pricedItems.get(i);
            CartLineItem item = pricedItem.item();
            var barcode = issuanceResult.barcodes().get(i);

            Ticket ticket = new Ticket(
                    item.geteventId(),
                    item.getzoneId(),
                    orderReceiptId,
                    item.getSeatNumber(),
                    pricedItem.finalPrice(),
                    barcode.ticketId(),
                    barcode.barcodeValue());

            if (holderUserId != null) {
                ticket.setHolderUserId(holderUserId);
            }

            ticket.markIssued(barcode.barcodeValue());
            ticket.checkInvariants();

            ticketRepository.save(ticket);

            ReceiptLine line = new ReceiptLine(
                    barcode.ticketId(),
                    pricedItem.finalPrice(),
                    item.geteventId(),
                    item.getzoneId(),
                    item.getSeatNumber(),
                    LocalDateTime.now());

            line.checkInvariants();
            receiptLines.add(line);
        }

        return receiptLines;
    }

    // We save the order receipt for a member by creating an OrderReceipt entity
    // with the member-specific information (user ID) and the details of the
    // purchase (total price, receipt lines, transactions) and then saving it to the
    // database. This method encapsulates the logic for creating and persisting the
    // order receipt for a member in one place. We also build the list of
    // transactions for the receipt by combining the payment transaction and the
    // ticket issuance transaction into a single list that will be included in the
    // receipt.
    private void saveMemberReceipt(
            int userId,
            int receiptId,
            double totalPrice,
            List<ReceiptLine> receiptLines,
            PaymentResultDTO paymentResult,
            IssuanceResultDTO issuanceResult) {
        OrderReceipt receipt = OrderReceipt.forMember(
                receiptId,
                userId,
                totalPrice,
                receiptLines,
                buildPurchaseTransactions(paymentResult, issuanceResult));

        orderReceiptRepository.save(receipt);
    }

    // We save the order receipt for a guest by creating an OrderReceipt entity with
    // the guest-specific information (guest email and session ID) and the details
    // of the purchase (total price, receipt lines, transactions) and then saving it
    // to the database. This method encapsulates the logic for creating and
    // persisting the order receipt for a guest in one place. We also build the list
    // of transactions for the receipt by combining the payment transaction and the
    // ticket issuance transaction into a single list that will be included in the
    // receipt.
    private void saveGuestReceipt(
            String guestEmail,
            String guestSessionId,
            int receiptId,
            double totalPrice,
            List<ReceiptLine> receiptLines,
            PaymentResultDTO paymentResult,
            IssuanceResultDTO issuanceResult) {
        OrderReceipt receipt = OrderReceipt.forGuest(
                guestEmail,
                guestSessionId,
                receiptId,
                totalPrice,
                receiptLines,
                buildPurchaseTransactions(paymentResult, issuanceResult));

        orderReceiptRepository.save(receipt);
    }

    // We build the list of transactions for the receipt by creating a
    // TransactionRecord for the payment charge and a TransactionRecord for the
    // ticket issuance. This allows us to have a clear record of the key
    // transactions that occurred during the checkout process, which can be useful
    // for customer service, refunds, or any future audits of the purchase history.
    // By encapsulating this logic in a helper method, we can easily maintain and
    // modify how we record transactions without affecting the main checkout flow.
    private List<TransactionRecord> buildPurchaseTransactions(
            PaymentResultDTO paymentResult,
            IssuanceResultDTO issuanceResult) {
        List<TransactionRecord> transactions = new ArrayList<>();

        transactions.add(TransactionRecord.paymentCharge(
                paymentResult.paymentTransactionId(),
                paymentResult.gatewayName(),
                paymentResult.chargedAmount(),
                paymentResult.currency(),
                paymentResult.chargedAt()));

        transactions.add(TransactionRecord.ticketIssuance(
                issuanceResult.issuanceTransactionId(),
                issuanceResult.issuerName(),
                issuanceResult.issuedAt()));

        return transactions;
    }

    // We build the checkout result DTO that will be returned to the caller of the
    // checkout method. This DTO contains the total price, the order receipt ID, the
    // payment transaction ID, and the list of issued ticket IDs. This allows the
    // caller to have all the relevant information about the completed checkout in a
    // single object. By encapsulating this logic in a helper method, we can easily
    // modify what information we include in the checkout result without affecting
    // the main checkout flow.
    private CheckoutResultDTO buildCheckoutResult(
            double totalPrice,
            int orderReceiptId,
            PaymentResultDTO paymentResult,
            IssuanceResultDTO issuanceResult) {
        return new CheckoutResultDTO(
                totalPrice,
                orderReceiptId,
                paymentResult.paymentTransactionId(),
                issuanceResult.barcodes()
                        .stream()
                        .map(barcode -> new CheckoutResultDTO.IssuedTicketDTO(
                                barcode.ticketId(), barcode.barcodeValue()))
                        .toList());
    }

    // We notify the user of the completed purchase by sending a notification with
    // the total price and the list of ticket IDs that were purchased. This allows
    // us to provide immediate feedback to the user that their purchase was
    // successful and to give them information about the tickets they bought. By
    // encapsulating this logic in a helper method, we can easily modify how we send
    // notifications or what information we include in the notification without
    // affecting the main checkout flow.
    private void notifyPurchaseCompleted(int userId, double totalPrice, List<ReceiptLine> receiptLines) {
        notificationService.notifyPurchaseCompleted(
                userId,
                totalPrice,
                receiptLines.stream()
                        .map(ReceiptLine::getTicketId)
                        .toList());
    }

    // We handle checkout failures by logging the error with relevant information
    // (user ID, total price, whether payment was done, whether inventory sale was
    // confirmed) and then attempting to roll back any changes that were made during
    // the checkout process. If the inventory sale was not confirmed, we try to
    // return the reserved tickets back to stock. If the payment was done, we try to
    // refund the payment. We also send a notification to the user that the purchase
    // failed. This method centralizes all the error handling and rollback logic for
    // member checkouts in one place, making it easier to maintain and ensuring that
    // we consistently handle failures across different failure points in the
    // checkout process.
    private void handleCheckoutFailure(
            ActiveOrder order,
            int userId,
            String orderLockKey,
            PaymentResultDTO paymentResult,
            double totalPrice,
            boolean inventorySaleConfirmed,
            Exception originalFailure) {
        log.error(
                "Checkout failed. userId={}, totalPrice={}, paymentDone={}, inventorySaleConfirmed={}",
                userId,
                totalPrice,
                paymentResult != null,
                inventorySaleConfirmed,
                originalFailure);

        // Reset the CHECKOUT_IN_PROGRESS status, return the reserved inventory to stock, and clear the cart
        // atomically under one lock scope. The guard inside the helper skips the rollback once the sale is
        // confirmed SOLD (a release would throw and the order is still CHECKOUT_IN_PROGRESS). Mirrors
        // handleGuestCheckoutFailure.
        rollbackReservedInventoryAtomically(orderLockKey, order, inventorySaleConfirmed);

        if (inventorySaleConfirmed) {
            log.error(
                    "Checkout failed after inventory was confirmed as SOLD. Manual recovery may be required. userId={}",
                    userId);
        }

        // Refund only if the sale did NOT commit. Once inventory is confirmed SOLD the purchase is final;
        // refunding here would leave the buyer holding tickets they were also refunded for (C2).
        if (paymentResult != null && !inventorySaleConfirmed) {
            safelyRefundPayment(paymentResult, totalPrice);
        }

        if (userId > 0) {
            try {
                notificationService.notifyPurchaseFailed(userId, "Checkout failed.");
            } catch (RuntimeException notificationFailure) {
                log.warn("Checkout failed and failure-notification also failed for userId={}", userId,
                        notificationFailure);
            }
        }
    }

    // We handle guest checkout failures by logging the error with relevant
    // information (guest session ID, total price, whether payment was done, whether
    // inventory sale was confirmed) and then attempting to roll back any changes
    // that were made during the checkout process. If the inventory sale was not
    // confirmed, we try to return the reserved tickets back to stock. If the
    // payment was done, we try to refund the payment. Since we don't have a user ID
    // for guests, we cannot send a notification about the failure, but we log
    // enough information to allow for manual follow-up if needed. This method
    // centralizes all the error handling and rollback logic for guest checkouts in
    // one place, making it easier to maintain and ensuring that we consistently
    // handle failures across different failure points in the checkout process.
    private void handleGuestCheckoutFailure(
            ActiveOrder order,
            String guestSessionId,
            String orderLockKey,
            PaymentResultDTO paymentResult,
            double totalPrice,
            boolean inventorySaleConfirmed,
            Exception originalFailure) {
        log.error(
                "Guest checkout failed. guestSessionId={}, totalPrice={}, paymentDone={}, inventorySaleConfirmed={}",
                guestSessionId,
                totalPrice,
                paymentResult != null,
                inventorySaleConfirmed,
                originalFailure);

        // Roll back inventory + cart + status atomically under one lock scope (see handleCheckoutFailure).
        rollbackReservedInventoryAtomically(orderLockKey, order, inventorySaleConfirmed);

        if (inventorySaleConfirmed) {
            log.error(
                    "Guest checkout failed after inventory was confirmed as SOLD. Manual recovery may be required. guestSessionId={}",
                    guestSessionId);
        }

        // Refund only if the sale did NOT commit. Once inventory is confirmed SOLD the purchase is final;
        // refunding here would leave the buyer holding tickets they were also refunded for (C2).
        if (paymentResult != null && !inventorySaleConfirmed) {
            safelyRefundPayment(paymentResult, totalPrice);
        }
    }

    // Atomic checkout-failure rollback: under a single order-write-lock + event-read-lock scope, reset the
    // CHECKOUT_IN_PROGRESS status, return the reserved inventory to stock, and clear the cart. Folding these
    // into one lock scope closes the window where a concurrent op (checkout retry, add-to-cart, expiry sweep)
    // could interleave between the status reset and the cart clear, and lets eventRepository.save run under the
    // event lock it requires. Reentrant-safe: in a Phase-3 failure the main flow already holds these locks, so
    // re-acquiring just bumps the hold count and the matching unlocks restore it.
    private void rollbackReservedInventoryAtomically(
            String orderLockKey,
            ActiveOrder fallbackOrder,
            boolean inventorySaleConfirmed) {
        // Skip when the sale already committed (inventory is SOLD — a release would throw while the order is
        // still CHECKOUT_IN_PROGRESS) or there is no key to lock by (failure before Phase 1 acquired the order,
        // so nothing was reserved for this checkout to roll back). A Phase-1 validation failure still rolls
        // back: the status reset below is guarded by isCheckoutInProgress(), so it is simply skipped while the
        // reserved tickets are still returned to stock and the cart is cleared.
        if (inventorySaleConfirmed || orderLockKey == null) {
            return;
        }

        activeOrderRepository.lockForUpdate(orderLockKey);
        List<Integer> lockedEventIds = List.of();
        try {
            // Re-fetch under the lock; never trust the possibly-stale main-flow reference.
            ActiveOrder order = getOrderByLockKey(orderLockKey);
            if (order == null) {
                order = fallbackOrder;
            }
            if (order == null) {
                return;
            }

            // Lock the order's events (sorted, for consistent ordering) so releaseInventory + save are legal.
            lockedEventIds = extractSortedEventIds(order.getItems());
            lockEvents(lockedEventIds);

            // Reset status FIRST so the order becomes modifiable (clear() refuses while CHECKOUT_IN_PROGRESS),
            // then return reserved inventory to stock and clear the cart — all under the held locks.
            if (order.isCheckoutInProgress()) {
                order.cancelCheckoutInProgress();
            }
            returnTicketsToStock(order);
        } catch (RuntimeException rollbackFailure) {
            // Already in the failure path — log, never mask the original checkout failure.
            log.error("Atomic checkout rollback failed for orderLockKey={}", orderLockKey, rollbackFailure);
        } finally {
            unlockEvents(lockedEventIds);
            activeOrderRepository.unlock(orderLockKey);
        }
    }

    private ActiveOrder getOrderByLockKey(String orderLockKey) {
        if (orderLockKey.startsWith("user:")) {
            int userId = Integer.parseInt(orderLockKey.substring("user:".length()));
            return activeOrderRepository.getByUserId(userId);
        }

        if (orderLockKey.startsWith("sess:")) {
            String sessionId = orderLockKey.substring("sess:".length());
            return activeOrderRepository.getBySessionId(sessionId).orElse(null);
        }

        throw new IllegalArgumentException("Unknown order lock key format: " + orderLockKey);
    }

    // We return the reserved tickets back to stock by iterating through the items
    // in the order, grouping them by event and zone, and then calling the
    // releaseInventory method on the event for each group of items. For standing
    // zones, we release the inventory by specifying the quantity of tickets being
    // released. For seated zones, we release the inventory by specifying the list
    // of seat numbers being released. After releasing the inventory for all items,
    // we clear the order and save it to persist the changes. This method assumes
    // that we are only trying to return tickets that were reserved but not yet
    // confirmed as sold, and it does not handle any cases where tickets might have
    // already been sold or where there might be other complications in the
    // inventory state.
    private void returnTicketsToStock(ActiveOrder order) {
        List<CartLineItem> returnToStock = order.getItems();
        String orderKey = order.getOrderKey();

        Map<Integer, Map<Integer, List<CartLineItem>>> grouped = groupItemsByEventAndZone(returnToStock);

        for (Map.Entry<Integer, Map<Integer, List<CartLineItem>>> eventEntry : grouped.entrySet()) {
            int eventId = eventEntry.getKey();
            Event event = eventRepository.findById(eventId);

            if (event == null) {
                continue;
            }

            // Release each (event, zone) independently: a single zone that can no longer be
            // released (e.g. its reservation was already confirmed/expired) must not abort
            // the
            // rollback for the remaining zones and events. We log and continue, then save
            // the
            // event if anything in it was actually released.
            boolean anyReleased = false;
            for (Map.Entry<Integer, List<CartLineItem>> zoneEntry : eventEntry.getValue().entrySet()) {
                int zoneId = zoneEntry.getKey();
                List<CartLineItem> zoneItems = zoneEntry.getValue();

                List<String> seatNumbers = extractSeatNumbers(zoneItems);

                try {
                    if (seatNumbers.isEmpty()) {
                        event.releaseInventory(zoneId, InventorySelection.standing(zoneItems.size(), orderKey));
                    } else {
                        event.releaseInventory(zoneId, InventorySelection.seated(seatNumbers, orderKey));
                    }
                    anyReleased = true;
                } catch (RuntimeException zoneReleaseFailure) {
                    log.warn(
                            "Could not release inventory during checkout rollback. eventId={}, zoneId={}, orderKey={}",
                            eventId, zoneId, orderKey, zoneReleaseFailure);
                }
            }

            if (anyReleased) {
                eventRepository.save(event);
            }
        }

        safelyClearCart(order);
    }

    // We clear and persist the cart after a rollback without letting a failure here
    // mask the
    // original checkout failure. The release loop above is resilient and always
    // reaches this point,
    // so the clear is no longer skipped by an earlier release throwing.
    private void safelyClearCart(ActiveOrder order) {
        try {
            order.clear();
            activeOrderRepository.save(order);
        } catch (RuntimeException clearFailure) {
            log.warn("Could not clear cart during checkout rollback for orderKey={}", order.getOrderKey(),
                    clearFailure);
        }
    }

    // We attempt to refund the payment during a checkout rollback. This is done as
    // a safety measure in case the checkout process fails after the payment was
    // charged, allowing us to return the funds to the customer. We wrap this in a
    // try-catch block to ensure that if any exceptions occur during the refund
    // (e.g. issues with the payment gateway), we log the error but do not let it
    // propagate further since we are already in an error handling flow and we want
    // to avoid masking the original failure with additional exceptions from the
    // refund process.
    private void safelyRefundPayment(PaymentResultDTO paymentResult, double totalPrice) {
        try {
            paymentGateway.refund(paymentResult.paymentTransactionId(), totalPrice);
        } catch (RuntimeException refundFailure) {
            log.error(
                    "Refund failed after checkout failure. transactionId={}, amount={}",
                    paymentResult.paymentTransactionId(),
                    totalPrice,
                    refundFailure);
        }
    }

    // We check the idempotency cache for a completed checkout result using the
    // idempotency key and the buyer key. If there is an existing cache entry for
    // the idempotency key, we check if the buyer key matches. If it does not match,
    // we throw an IdempotencyConflictException to indicate that there is a conflict
    // with the idempotency key being used by a different buyer. If it matches, we
    // return the cached checkout result. If there is no existing cache entry for
    // the idempotency key, we return null to indicate that there is no cached
    // result and that we should proceed with processing the checkout as normal.
    private CheckoutResultDTO getCachedCheckoutResult(String idempotencyKey, String buyerKey) {
        IdempotencyCacheEntry existing = completedCheckoutsByIdempotencyKey.get(idempotencyKey);

        if (existing == null) {
            return null;
        }

        if (!existing.buyerKey().equals(buyerKey)) {
            throw new IdempotencyConflictException(idempotencyKey);
        }

        return existing.result();
    }

    // We cache the checkout result in the idempotency cache by associating the
    // idempotency key with a new cache entry that contains the buyer key and the
    // checkout result. This allows us to return the same checkout result for
    // subsequent requests that use the same idempotency key and buyer key, while
    // also ensuring that if there is a conflict with the idempotency key being used
    // by a different buyer, we can detect it and throw an appropriate exception. By
    // using putIfAbsent, we ensure that we do not overwrite an existing cache entry
    // if one already exists for the same idempotency key, which helps maintain the
    // integrity of our idempotency handling.
    private void cacheCheckoutResult(String idempotencyKey, String buyerKey, CheckoutResultDTO result) {
        completedCheckoutsByIdempotencyKey.putIfAbsent(
                idempotencyKey,
                new IdempotencyCacheEntry(buyerKey, result));
    }

    // We generate a unique key for a member buyer based on their user ID. This key
    // is used to identify the buyer in the idempotency cache and other internal
    // mechanisms.
    private String memberBuyerKey(int userId) {
        return "member:" + userId;
    }

    // We generate a unique key for a guest buyer based on their session ID and
    // email. This key is used to identify the buyer in the idempotency cache and
    // other internal mechanisms. By combining the session ID and email, we can
    // create a more unique identifier for the guest buyer, which helps prevent
    // conflicts in the idempotency cache when multiple guests might be using the
    // same session or when a guest might have multiple sessions.
    private String guestBuyerKey(String guestSessionId, String guestEmail) {
        return "guest:" + guestSessionId + ":" + guestEmail.trim().toLowerCase();
    }

    // We generate a unique lock key for a member buyer based on their user ID. This
    // key is used to acquire a lock for the member's order during the checkout
    // process to prevent concurrent modifications and ensure that only one checkout
    // can be processed for the member at a time.
    private String memberOrderLockKey(int userId) {
        return "user:" + userId;
    }

    // We generate a unique lock key for a guest buyer based on their session ID.
    // This key is used to acquire a lock for the guest's order during the checkout
    // process to prevent concurrent modifications and ensure that only one checkout
    // can be processed for the guest at a time. By using the session ID, we can
    // allow guests to have multiple sessions (e.g. on different devices) while
    // still ensuring that each session is locked separately during checkout.
    private String guestOrderLockKey(String guestSessionId) {
        return "sess:" + guestSessionId;
    }

    private void validatePurchasePolicies(List<CartLineItem> boughtItems, Integer userId, Integer buyerAge) {
        Map<Integer, Long> quantityByEvent = boughtItems.stream()
                .collect(Collectors.groupingBy(CartLineItem::geteventId, Collectors.counting()));

        for (Map.Entry<Integer, Long> entry : quantityByEvent.entrySet()) {
            int eventId = entry.getKey();
            int quantity = entry.getValue().intValue();

            Event event = eventRepository.findById(eventId);

            if (event == null) {
                throw new EventNotFoundException(eventId);
            }

            int buyerId;
            if (userId == null) {
                buyerId = -1;
            } else {
                buyerId = userId;
            }

            PurchaseContext context = new PurchaseContext(
                    buyerId,
                    buyerAge,
                    event.getId(),
                    event.getCompanyId(),
                    quantity);

            ProductionCompany company = companyRepository.getCompanyById(event.getCompanyId());
            event.validateEffectivePolicy(company == null ? null : company.getPurchasePolicy(), context);
        }
    }

    private Integer getBuyerAgeByUserId(int userId) {
        User user = userRepository.getUserById(userId);

        if (user == null) {
            throw new UserNotFoundException("User not found: " + userId);
        }

        return user.getAge();
    }

    // helper record classes for internal use within the service - not part of the
    // public API

    // This is a simple struct to hold a cart line item along with its calculated
    // final price for the checkout. This allows us to calculate prices once and
    // keep the logic clean, especially when we need to build receipt lines later.
    private record PricedCartLine(
            CartLineItem item,
            double finalPrice) {
    }

    // A single (event, zone, selection) that was confirmed SOLD during confirmInventorySale — kept so a
    // partial-confirm failure can be compensated back to AVAILABLE (C3).
    private record ConfirmedUnit(
            Event event,
            int zoneId,
            InventorySelection selection) {
    }

    // This is the value stored in the idempotency cache. It includes the buyerKey
    // to detect conflicts (same idempotency key used by different buyers) and the
    // actual checkout result to return for repeated requests.
    private record IdempotencyCacheEntry(
            String buyerKey,
            CheckoutResultDTO result) {
    }

}
