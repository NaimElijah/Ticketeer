package com.ticketing.system.sales.application.service;
import com.ticketing.system.sales.application.port.out.MarketGate; // outbound port for the market-open gate (governance implements it)

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.ticketing.system.shared.dto.CardDetailsDTO;
import com.ticketing.system.shared.dto.CheckoutResultDTO;
import com.ticketing.system.shared.dto.IssuanceRequestDTO;
import com.ticketing.system.shared.dto.IssuanceResultDTO;
import com.ticketing.system.shared.dto.PaymentRequestDTO;
import com.ticketing.system.shared.dto.PaymentResultDTO;
import org.springframework.context.ApplicationEventPublisher;
import com.ticketing.system.shared.event.PurchaseCompletedNotice;
import com.ticketing.system.shared.event.PurchaseFailedNotice;
import com.ticketing.system.sales.application.port.out.PaymentGateway;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.sales.application.port.out.TicketIssuer;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.domain.CartLineItem;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.domain.Ticket;
// Catalog inbound port + its sales-safe line DTO: catalog owns event pricing, policy validation,
// on-sale checks, and inventory confirm/release.
// EventRepository (a catalog outbound port taking int ids) is used only for the Phase-3 event
// buyer-lock, which must span the whole validate -> persist -> confirm critical section in sales.
import com.ticketing.system.catalog.application.port.in.InventoryCommandPort;
import com.ticketing.system.catalog.application.port.in.InventoryLineDTO;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.shared.exception.AuthenticationFailedException;
import com.ticketing.system.shared.exception.ConcurrentReservationException;
import com.ticketing.system.shared.exception.DomainException;
import com.ticketing.system.shared.exception.ExternalServiceUnavailableException;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.MarketNotOpenException;
import com.ticketing.system.shared.exception.PaymentGatewayException;
import com.ticketing.system.shared.exception.PaymentGatewayUnreachableException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.ticketing.system.shared.exception.TicketIssuanceFailedException;
import com.ticketing.system.shared.exception.TicketIssuerUnreachableException;
import com.ticketing.system.shared.exception.UserNotFoundException;
import com.ticketing.system.shared.exception.EntityNotFoundException;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.sales.domain.TransactionRecord;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;

/**
 * Checkout use case (UC-10): turns a buyer's cart into a receipt and tickets, for a member
 * ({@link #checkoutMember}) or a guest ({@link #checkoutGuest}).
 *
 * <p>The payment gateway and the ticket issuer are called outside any lock and database transaction,
 * so a slow external service never pins a connection; only the final database unit of Phase 3 is a
 * transaction, run by this service itself. The methods are therefore not {@code @Transactional}.
 * Market state is read through {@link MarketGate}. Purchase and failure notices are published as
 * {@code shared.event} events.
 */
@Service
@Slf4j
public class CheckoutService {

    // How many times Phase 3 runs before a lost concurrency race fails the checkout (see runPhase3).
    private static final int PHASE3_ATTEMPTS = 3;

    // The failure wrapper's message. "Tickets returned to stock" is also given for a failure before the order
    // was read, where nothing was held: tests and callers have long read that text on that path, so it was
    // kept there (NaimElijah/Ticketeer#187). A failed freeze save changed nothing, and says so.
    private static final String FAILED_TICKETS_RETURNED = "Checkout failed, tickets returned to stock";
    private static final String FAILED_NOTHING_CHANGED = "Checkout failed, nothing was changed";
    // What a buyer reads when the checkout lost a concurrency race (CheckoutPresenter shows a DomainException's
    // message). Lost at the freeze, the attempt changed nothing and the cart is intact, so trying again is the
    // remedy. Lost later, the rollback has released the holds, cleared the cart and refunded any charge, so the
    // buyer must choose again.
    private static final String CART_CHANGED = "Your cart changed while you were paying. Please try again.";
    private static final String PLACES_TAKEN =
            "These places were taken while you were paying. Any charge is being refunded; please choose again.";

    private final ActiveOrderRepository activeOrderRepository;
    // Retained ONLY for the Phase-3 event buyer-lock (lockEvents/unlockEvents): the lock must be held in
    // sales across the whole validate -> persist -> confirm section, spanning several port calls plus
    // sales' own ticket/receipt persistence, so it can't move inside a single port method. Takes int ids.
    private final EventRepository eventRepository;
    // Catalog inbound port: owns event pricing, purchase-policy validation, on-sale checks, and the
    // inventory confirm/release mutations. Its confirm/validate/release methods are CALLER-LOCKED — this
    // service holds the event buyer-lock around them (see InventoryCommandPort's locking contract).
    private final InventoryCommandPort inventoryPort;
    private final TicketRepository ticketRepository;
    private final OrderReceiptRepository orderReceiptRepository;
    private final TicketIssuer ticketIssuer;
    private final PaymentGateway paymentGateway;
    // Publisher for cross-context integration events (e.g. PurchaseCompletedNotice); the
    // notifications context listens for these instead of sales calling it directly.
    private final ApplicationEventPublisher eventPublisher;
    private final SessionManager sessionManager;
    private final UserRepository userRepository;
    private final MarketGate marketGate; // queried to enforce the UC-32 market-open gate (dependency-inverted away from governance)
    // Programmatic transactions for the checkout's Phase-3 DB work. Checkout cannot be a single
    // @Transactional method because the WSEP charge + issue (Phase 2, 20s HTTP timeouts) must run
    // OUTSIDE any transaction so a slow gateway never pins a DB connection. This template wraps only
    // the Phase-3 DB unit (tickets + receipt + RESERVED→SOLD confirm), between the external calls.
    private final TransactionTemplate transactionTemplate;
    // "now" for hold expiry, pricing and receipts: UTC wall time (ADR-008).
    private final Clock clock;

    // Completed checkouts by idempotency key, each tagged with its buyer so a key reused by another buyer
    // is a conflict rather than a replay. Bounded, and in memory, so lost on restart (SLR.7).
    private final CheckoutIdempotencyCache idempotencyCache =
            new CheckoutIdempotencyCache(CheckoutIdempotencyCache.DEFAULT_CAPACITY);

    // The checkout attempts running in this process, counted by order key. An attempt is counted just before
    // it saves the order frozen (CHECKOUT_IN_PROGRESS), so no other attempt can see the order frozen without
    // that count, and uncounted when it ends, however it ends. A frozen order with no count was frozen by an
    // attempt that is gone (the process restarted during its external calls), so a retry may roll it back.
    // A count, not a set: on the Jpa adapter the order lock is a no-op, so two attempts can both validate
    // the order and be counted, and the one whose save then loses on @Version must not uncount the one
    // still running. In memory, so it holds for one instance only.
    private final ConcurrentHashMap<String, Integer> attemptsInFlight = new ConcurrentHashMap<>();

    // idempotency means that if the same buyer (member or guest) submits the same
    // checkout request (identified by idempotency key) multiple times, only the
    // first one will be processed and the result will be returned for subsequent
    // ones.
    // This is crucial for preventing duplicate charges and orders if, for example,
    // the user accidentally clicks the "Buy" button twice or if there are network
    // issues causing retries.

    /**
     * Creates the service.
     *
     * @param activeOrderRepository the carts
     * @param eventRepository       catalog's event port, used only for the shared event lock Phase 3 holds
     * @param inventoryPort         catalog port that prices, validates and confirms inventory
     * @param ticketRepository      the tickets saved by a purchase
     * @param orderReceiptRepository the receipts saved by a purchase
     * @param ticketIssuer          issues the barcodes
     * @param paymentGateway        charges the card and refunds a failed purchase
     * @param eventPublisher        publishes the purchase and failure notices
     * @param sessionManager        authenticates member tokens and guest session ids
     * @param userRepository        the source of a member's age for purchase-policy checks
     * @param marketGate            tells whether the market is open
     * @param transactionManager    runs the Phase 3 database unit
     * @param clock                 the source of "now" (UTC wall time, ADR-008)
     */
    public CheckoutService(
            ActiveOrderRepository activeOrderRepository,
            EventRepository eventRepository,
            InventoryCommandPort inventoryPort,
            TicketRepository ticketRepository,
            OrderReceiptRepository orderReceiptRepository,
            TicketIssuer ticketIssuer,
            PaymentGateway paymentGateway,
            ApplicationEventPublisher eventPublisher,
            SessionManager sessionManager,
            UserRepository userRepository,
            MarketGate marketGate,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.activeOrderRepository = activeOrderRepository;
        this.eventRepository = eventRepository;
        this.inventoryPort = inventoryPort;
        this.ticketRepository = ticketRepository;
        this.orderReceiptRepository = orderReceiptRepository;
        this.ticketIssuer = ticketIssuer;
        this.paymentGateway = paymentGateway;
        this.eventPublisher = eventPublisher;
        this.sessionManager = sessionManager;
        this.userRepository = userRepository;
        this.marketGate = marketGate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // Result of the atomic Phase-3 persistence unit, surfaced to the orchestrator once the
    // transaction has committed: the receipt id and the persisted tickets feed the result DTO,
    // the lines feed the post-unlock purchase notification.
    private record Phase3Persisted(int orderReceiptId, List<ReceiptLine> receiptLines,
            List<CheckoutResultDTO.IssuedTicketDTO> issuedTickets) {
    }

    // The tickets saved for one order: their receipt lines plus the id/barcode pairs shown to the buyer.
    private record SavedTickets(List<ReceiptLine> receiptLines,
            List<CheckoutResultDTO.IssuedTicketDTO> issuedTickets) {
    }

    // How far an attempt got in freezing its order (CHECKOUT_IN_PROGRESS); decides what its failure rolls back.
    private enum FreezeState {
        NOT_ATTEMPTED,
        // The freeze save was running. A failure seen in this state is that save throwing, so it changed nothing.
        SAVING,
        FROZEN
    }

    // UC-32 / I.2.1 — no money moves while the trading market is closed. This is a
    // platform-wide gate, orthogonal to the per-event ON_SALE check enforced later
    // in Phase 3 (validateEventsStillOnSale). Its exception is rethrown raw by the
    // MarketNotOpenException catch of each checkout method, so it is not wrapped as a
    // generic checkout failure.
    private void requireMarketOpen() {
        if (!marketGate.isOpen()) {
            throw new MarketNotOpenException();
        }
    }

    /**
     * Buys a member's cart in the three phases that keep slow external calls out of any lock and
     * transaction. Phase 1 validates the cart and freezes it, under the cart's order lock. Phase 2 prices
     * the lines, then calls the payment gateway and the ticket issuer, holding no lock. Phase 3 takes the
     * order lock and the shared event locks again, checks that the cart and the holds are as Phase 1 saw
     * them, and in one database transaction saves the tickets and the receipt and confirms the inventory
     * sale. The cart is then consumed.
     *
     * <p>A repeated call with an {@code idempotencyKey} that already completed for this member returns
     * the first result without charging again, and does so even if the market has closed since. The
     * completed results are kept in memory, so a restart forgets them.
     *
     * <p>If a phase fails, the cart is unfrozen, the held places are returned to stock, the cart is
     * cleared, and, when the payment gateway was already charged and the sale did not commit, a refund of
     * the charged amount is requested from it. The member gets a purchase notice on success and a
     * failure notice on failure once the token has been accepted (delivered after commit, ADR-004).
     *
     * @param token          the member's session token
     * @param idempotencyKey the client's key for this purchase attempt; a retry sends the same key
     * @param currency       the currency the card is charged in; the gateway's answer must carry it
     * @param card           the payment card to charge
     * @return the receipt id, the total charged, the payment transaction id and the issued tickets
     * @throws MarketNotOpenException if the market is closed and the key has no completed purchase
     * @throws RuntimeException       for every other failure, wrapping it as the cause: an invalid
     *     token or input, an empty or expired cart, a cart that changed during checkout, a key already
     *     used by another buyer, a refusal by the payment gateway or the ticket issuer, or a lost
     *     concurrency race (re-typed as {@code ConcurrentReservationException})
     */
    public CheckoutResultDTO checkoutMember(String token, String idempotencyKey, String currency,
            CardDetailsDTO card) {
        int userId = -1;
        ActiveOrder order = null;
        PaymentResultDTO paymentResult = null;
        double totalPrice = 0.0;
        boolean inventorySaleConfirmed = false;
        // freeze: how far this call got in freezing the order. frozenOrderKey: this call is counted in
        // attemptsInFlight, from just before the freeze save until finally.
        FreezeState freeze = FreezeState.NOT_ATTEMPTED;
        String frozenOrderKey = null;
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
            Optional<CheckoutResultDTO> cached = idempotencyCache.find(idempotencyKey, buyerKey);
            if (cached.isPresent()) {
                return cached.get();
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

            // Freeze the order (CHECKOUT_IN_PROGRESS): a concurrent checkout or cart change is refused
            // while the external calls of Phase 2 run.
            order.markCheckoutInProgress();
            countAttemptInFlight(orderKey);
            frozenOrderKey = orderKey;
            freeze = FreezeState.SAVING;
            activeOrderRepository.save(order);
            freeze = FreezeState.FROZEN;

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
            // A unit that loses a concurrency race runs again (runPhase3). If it still fails, the whole
            // unit rolls back, inventorySaleConfirmed stays false, and the catch below returns the
            // inventory to stock and refunds the charge via the existing path.
            final int capturedUserId = userId;
            final double capturedTotalPrice = totalPrice;
            final PaymentResultDTO capturedPaymentResult = paymentResult;
            Phase3Persisted persisted = runPhase3(status -> {
                validateEventsStillOnSale(snapshotItems);
                // Fail-fast ownership check (read-only): the reservation must still be ours and RESERVED.
                validateCanConfirmInventorySale(snapshotItems, orderKey);

                int receiptId = orderReceiptRepository.nextId();
                LocalDateTime purchasedAt = LocalDateTime.now(clock);   // the receipt and its lines share it
                SavedTickets saved = saveTicketsAndBuildReceiptLines(capturedUserId, receiptId, pricedItems,
                        issuanceResult, purchasedAt);
                saveMemberReceipt(capturedUserId, receiptId, capturedTotalPrice, saved.receiptLines(),
                        capturedPaymentResult, issuanceResult, purchasedAt);

                // Point of no return: commit the sale last, once everything fallible has succeeded.
                confirmInventorySale(snapshotItems, orderKey);
                return new Phase3Persisted(receiptId, saved.receiptLines(), saved.issuedTickets());
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

            CheckoutResultDTO result = buildCheckoutResult(totalPrice, orderReceiptId, paymentResult,
                    persisted.issuedTickets());
            idempotencyCache.putIfAbsent(idempotencyKey, buyerKey, result);

            receiptLinesToNotifyAfterUnlock = receiptLines;
            checkoutSucceeded = true;

            return result;

        } catch (MarketNotOpenException marketClosed) {
            // Pre-Phase-1 gate failure: nothing was reserved or charged, so there is nothing to roll back.
            // Propagate it raw (callers/tests distinguish a closed market from a mid-checkout failure).
            throw marketClosed;
        } catch (Exception e) {
            handleCheckoutFailure(order, userId, orderLockKey, paymentResult, totalPrice, inventorySaleConfirmed,
                    freeze, e);
            throw new RuntimeException(failureMessage(freeze), asBuyerFacing(e, freeze));
        } finally {
            if (frozenOrderKey != null) {
                uncountAttemptInFlight(frozenOrderKey);
            }
            unlockEvents(lockedEventIds);

            if (orderLockKey != null) {
                unlockOrderKeepingTheOutcome(orderLockKey, order);
            }
            if (checkoutSucceeded && userId > 0 && receiptLinesToNotifyAfterUnlock != null) {
                notifyPurchaseCompleted(userId, totalPrice, receiptLinesToNotifyAfterUnlock);
            }
        }
    }

    /**
     * Buys a guest's cart: the guest counterpart of {@link #checkoutMember}, with the same three phases,
     * idempotent replay and failure handling. The cart is found by the guest session id, and the
     * idempotency key is bound to the session id and the email together.
     *
     * <p>The session must be live, which is checked after the replay lookup and the market gate, so a
     * completed purchase can be replayed after its session ended. No purchase notice is published, since
     * a guest has no user to notify. The guest's session id is never logged.
     *
     * @param guestSessionId the raw id of the guest's live session
     * @param guestEmail     the email the receipt belongs to
     * @param idempotencyKey the client's key for this purchase attempt; a retry sends the same key
     * @param currency       the currency the card is charged in
     * @param card           the payment card to charge
     * @param buyerAge       the buyer's age in years, for the purchase-policy age check
     * @return the receipt id, the total charged, the payment transaction id and the issued tickets
     * @throws MarketNotOpenException if the market is closed and the key has no completed purchase
     * @throws RuntimeException       for every other failure, wrapping it as the cause, as
     *     {@link #checkoutMember} does; a blank session id or email, or a session that is not live,
     *     is among them
     */
    public CheckoutResultDTO checkoutGuest(String guestSessionId, String guestEmail, String idempotencyKey,
            String currency, CardDetailsDTO card, int buyerAge) {
        ActiveOrder order = null;
        PaymentResultDTO paymentResult = null;
        double totalPrice = 0.0;
        boolean inventorySaleConfirmed = false;
        // freeze: how far this call got in freezing the order. frozenOrderKey: this call is counted in
        // attemptsInFlight, from just before the freeze save until finally.
        FreezeState freeze = FreezeState.NOT_ATTEMPTED;
        String frozenOrderKey = null;

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

            Optional<CheckoutResultDTO> cached = idempotencyCache.find(idempotencyKey, buyerKey);
            if (cached.isPresent()) {
                return cached.get();
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
            countAttemptInFlight(orderKey);
            frozenOrderKey = orderKey;
            freeze = FreezeState.SAVING;
            activeOrderRepository.save(order);
            freeze = FreezeState.FROZEN;

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
            Phase3Persisted persisted = runPhase3(status -> {
                validateEventsStillOnSale(snapshotItems);
                // Fail-fast ownership check (read-only): the reservation must still be ours and RESERVED.
                validateCanConfirmInventorySale(snapshotItems, orderKey);

                int receiptId = orderReceiptRepository.nextId();
                LocalDateTime purchasedAt = LocalDateTime.now(clock);   // the receipt and its lines share it
                SavedTickets saved = saveTicketsAndBuildReceiptLines(null, receiptId, pricedItems, issuanceResult,
                        purchasedAt);
                saveGuestReceipt(guestEmail, guestSessionId, receiptId, capturedTotalPrice, saved.receiptLines(),
                        capturedPaymentResult, issuanceResult, purchasedAt);

                // Point of no return: commit the sale last, once everything fallible has succeeded.
                confirmInventorySale(snapshotItems, orderKey);
                return new Phase3Persisted(receiptId, saved.receiptLines(), saved.issuedTickets());
            });
            inventorySaleConfirmed = true;
            int orderReceiptId = persisted.orderReceiptId();

            // Point of no return passed: the sale is committed and the receipt is the durable record.
            // Consuming the cart (buy() + delete) is best-effort — a cleanup hiccup must never turn a
            // committed purchase into a checkout failure (C2). The cart isn't saved: buy() leaves it
            // CHECKOUT_IN_PROGRESS, so a save would strand an empty, unmodifiable cart that wedges the
            // buyer's next reservation.
            finalizeConsumedOrder(order);

            CheckoutResultDTO result = buildCheckoutResult(totalPrice, orderReceiptId, paymentResult,
                    persisted.issuedTickets());
            idempotencyCache.putIfAbsent(idempotencyKey, buyerKey, result);

            return result;

        } catch (MarketNotOpenException marketClosed) {
            // Pre-Phase-1 gate failure: nothing reserved or charged, nothing to roll back. Propagate raw.
            throw marketClosed;
        } catch (Exception e) {
            handleGuestCheckoutFailure(order, orderLockKey, paymentResult, totalPrice,
                    inventorySaleConfirmed, freeze, e);
            throw new RuntimeException(failureMessage(freeze), asBuyerFacing(e, freeze));
        } finally {
            if (frozenOrderKey != null) {
                uncountAttemptInFlight(frozenOrderKey);
            }
            unlockEvents(lockedEventIds);
            if (orderLockKey != null) {
                unlockOrderKeepingTheOutcome(orderLockKey, order);
            }
        }
    }

    private static String failureMessage(FreezeState freeze) {
        return freeze == FreezeState.SAVING ? FAILED_NOTHING_CHANGED : FAILED_TICKETS_RETURNED;
    }

    // A lost concurrency race (a version conflict, a deadlock, a lock wait) is Spring's, and no DomainException,
    // so CheckoutPresenter would show generic text and log it as unexpected. Re-typed, the buyer is told what
    // happened to the cart and what to do; the race stays its cause. The failure handler has already logged
    // the original.
    private static Exception asBuyerFacing(Exception failure, FreezeState freeze) {
        if (failure instanceof ConcurrencyFailureException lostRace) {
            String message = freeze == FreezeState.SAVING ? CART_CHANGED : PLACES_TAKEN;
            return new ConcurrentReservationException(message, lostRace);
        }
        return failure;
    }

    // Releases the order lock, at the end of a checkout or of its rollback. A failure here must not replace
    // the checkout's own outcome, a committed purchase or the failure being thrown, nor skip the refund that
    // follows a rollback, so it is logged with its exception rather than thrown. The line names the order,
    // never the lock key: a guest's embeds the session id, the guest's credential.
    private void unlockOrderKeepingTheOutcome(String orderLockKey, ActiveOrder order) {
        try {
            activeOrderRepository.unlock(orderLockKey);
        } catch (RuntimeException unlockFailure) {
            log.warn("Checkout could not release the order lock. orderKey={}", orderKeyOf(order), unlockFailure);
        }
    }


    private int authenticateAndGetUserId(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("Missing authentication token");
        }

        // extractUserId throws for an invalid, revoked or expired token, or one not a member's, so no separate
        // validateToken call is needed.
        int userId = sessionManager.extractUserId(token);
        if (userId <= 0) {
            throw new AuthenticationFailedException();
        }

        return userId;
    }

    // Refuses a blank session id or email. Runs before the market gate because the idempotency cache key
    // is built from both; whether the session is live is checked later, by validateGuestSessionLive.
    private void validateGuestIdentityPresent(String guestSessionId, String guestEmail) {
        if (guestSessionId == null || guestSessionId.isBlank()) {
            throw new InvalidTokenException("guestSessionId is required");
        }

        if (guestEmail == null || guestEmail.isBlank()) {
            throw new InvalidTokenException("guestEmail is required");
        }
    }

    // Liveness check — the guest session must still be valid. Runs after the idempotency short-circuit so
    // a completed purchase can be replayed without a live session. Only a live guest row's raw id passes;
    // a member's token or session id is no guest credential (ADR-012, decision 4).
    private void validateGuestSessionLive(String guestSessionId) {
        if (!sessionManager.validateGuestSession(guestSessionId)) {
            throw new SessionExpiredException();
        }
    }

    // Refuses a blank idempotency key or currency, or a card without a number.
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

    private void validateOrderForCheckout(ActiveOrder order, Integer userId) {
        if (order == null) {
            throw new EntityNotFoundException("Active order not found");
        }

        order.validateCanCheckout(LocalDateTime.now(clock));
    }

    // Sorted, so every checkout locks its events in the same order, which prevents deadlocks.
    private List<Integer> extractSortedEventIds(List<CartLineItem> items) {
        return items.stream()
                .map(CartLineItem::geteventId)
                .distinct()
                .sorted()
                .toList();
    }

    // Phase 3 can lose a race it would win a moment later, and failing then refunds a charge for nothing:
    // two sales in one standing zone both move the zone's counts, so the second to commit loses on its
    // @Version; on PostgreSQL a sale can also be a deadlock victim or wait out a lock (ADR-015). Phase 3 is
    // database work only, it re-reads and re-validates everything, and each attempt's transaction rolls
    // back whole, so running it again is safe. Any other failure, and the last lost race, still fail the
    // checkout. A busy standing zone can exhaust the attempts; queueing its sales is #227.
    private <T> T runPhase3(TransactionCallback<T> phase3) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(phase3);
            } catch (ConcurrencyFailureException lostRace) {
                if (attempt == PHASE3_ATTEMPTS) {
                    throw lostRace;
                }
                log.info("Checkout Phase 3 lost a concurrency race; running it again. attempt={}", attempt);
            }
        }
    }

    private void lockEvents(List<Integer> eventIds) {
        for (Integer eventId : eventIds) {
            eventRepository.lockForBuyerOperation(eventId);
        }
    }

    // Unlocks in the reverse of the order lockEvents took them.
    private void unlockEvents(List<Integer> eventIds) {
        for (int i = eventIds.size() - 1; i >= 0; i--) {
            eventRepository.unlockBuyerOperation(eventIds.get(i));
        }
    }

    private void validateEventsStillOnSale(List<CartLineItem> boughtItems) {
        // Existence + sellable-status (ON_SALE or SOLD_OUT) checks live in the catalog inventory port,
        // which owns the Event aggregate. Runs under the Phase-3 event buyer-locks held by sales.
        inventoryPort.validateEventsOnSale(extractSortedEventIds(boughtItems));
    }

    // Prices every line in one pass, at one clock reading. Each line is priced with its event's total
    // quantity in the cart, so quantity-based discount policies apply.
    private List<PricedCartLine> priceItemsOnce(List<CartLineItem> boughtItems) {
        LocalDateTime pricingTime = LocalDateTime.now(clock);

        Map<Integer, Long> quantityByEvent = boughtItems.stream()
                .collect(Collectors.groupingBy(CartLineItem::geteventId, Collectors.counting()));

        List<PricedCartLine> pricedItems = new ArrayList<>();

        for (CartLineItem item : boughtItems) {
            int eventQuantity = quantityByEvent.get(item.geteventId()).intValue();

            // The event's discount-policy pricing runs inside the catalog inventory port (Phase 2, no
            // locks). It throws if the event no longer exists.
            double finalPrice = inventoryPort.priceTicket(
                    item.geteventId(),
                    eventQuantity,
                    item.getPriceAtReservation(),
                    pricingTime);

            pricedItems.add(new PricedCartLine(item, finalPrice));
        }

        return pricedItems;
    }

    private double sumPrices(List<PricedCartLine> pricedItems) {
        return pricedItems.stream()
                .mapToDouble(PricedCartLine::finalPrice)
                .sum();
    }

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

        // NaN compares false with everything, so it would pass the mismatch check below.
        if (!isValidSum(paymentResult.chargedAmount())) {
            throw new PaymentGatewayException("payment amount is not a valid sum");
        }

        if (Math.abs(paymentResult.chargedAmount() - expectedAmount) > 0.0001) {
            throw new PaymentGatewayException("payment amount mismatch");
        }
    }

    // A sum of money a gateway can report: finite and not negative. A free order's charge is 0.
    private static boolean isValidSum(double amount) {
        return Double.isFinite(amount) && amount >= 0;
    }

    private IssuanceResultDTO issueTickets(Integer buyerUserId, String buyerEmail, List<CartLineItem> boughtItems) {
        List<IssuanceRequestDTO.TicketIssuanceItemDTO> issuanceItems = boughtItems.stream()
                .map(item -> {
                    // Resolve the event name through the catalog inventory port (Phase 2, no locks); it
                    // throws if the event no longer exists.
                    String eventName = inventoryPort.eventName(item.geteventId());

                    return new IssuanceRequestDTO.TicketIssuanceItemDTO(
                            item.geteventId(),
                            eventName,
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

    // The Phase 3 ownership check: the order lock was released for Phase 2, so re-verify that the
    // reservations were not lost to expiry or cleanup before they are confirmed SOLD.
    private void validateCanConfirmInventorySale(List<CartLineItem> boughtItems, String orderKey) {
        // The read-only ownership/status check (seat still RESERVED and owned by this order, enough
        // reserved standing stock) lives in the catalog inventory port. It is CALLER-LOCKED: sales
        // holds the Phase-3 event buyer-locks across this check and the subsequent confirm, so the two
        // are atomic against structural edits.
        inventoryPort.validateCanConfirmSale(toInventoryLines(boughtItems), orderKey);
    }

    private void confirmInventorySale(List<CartLineItem> boughtItems, String orderKey) {
        // The RESERVED -> SOLD confirm (grouping, per-event save, and the partial-failure compensation
        // back to AVAILABLE) lives in the catalog inventory port. It is CALLER-LOCKED: sales holds
        // the Phase-3 event buyer-locks around it, and it runs inside sales' Phase-3 DB transaction.
        inventoryPort.confirmSale(toInventoryLines(boughtItems), orderKey);
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

    // Converts sales' cart line items into the sales-safe flat inventory lines the catalog inventory
    // port consumes for confirm/validate/release. A null seat label denotes one standing unit; the port
    // groups these by event and zone and translates them into domain selections at the boundary.
    private List<InventoryLineDTO> toInventoryLines(List<CartLineItem> items) {
        return items.stream()
                .map(item -> new InventoryLineDTO(item.geteventId(), item.getzoneId(), item.getSeatNumber()))
                .toList();
    }

    // The ticket's id is minted by the ticket repository, never taken from the issuer: issuer-side
    // counters restart with the process and would collide with tickets persisted by an earlier boot (#19).
    private SavedTickets saveTicketsAndBuildReceiptLines(
            Integer holderUserId,
            int orderReceiptId,
            List<PricedCartLine> pricedItems,
            IssuanceResultDTO issuanceResult,
            LocalDateTime purchasedAt) {
        List<ReceiptLine> receiptLines = new ArrayList<>();
        List<CheckoutResultDTO.IssuedTicketDTO> issuedTickets = new ArrayList<>();

        for (int i = 0; i < pricedItems.size(); i++) {
            PricedCartLine pricedItem = pricedItems.get(i);
            CartLineItem item = pricedItem.item();
            var barcode = issuanceResult.barcodes().get(i);
            int ticketId = ticketRepository.nextId();

            Ticket ticket = new Ticket(
                    item.geteventId(),
                    item.getzoneId(),
                    orderReceiptId,
                    item.getSeatNumber(),
                    pricedItem.finalPrice(),
                    ticketId,
                    barcode.barcodeValue());

            if (holderUserId != null) {
                ticket.setHolderUserId(holderUserId);
            }

            ticket.markIssued(barcode.barcodeValue());
            ticket.checkInvariants();

            ticketRepository.save(ticket);

            ReceiptLine line = new ReceiptLine(
                    ticketId,
                    pricedItem.finalPrice(),
                    item.geteventId(),
                    item.getzoneId(),
                    item.getSeatNumber(),
                    purchasedAt);

            line.checkInvariants();
            receiptLines.add(line);
            issuedTickets.add(new CheckoutResultDTO.IssuedTicketDTO(ticketId, barcode.barcodeValue()));
        }

        return new SavedTickets(receiptLines, issuedTickets);
    }

    private void saveMemberReceipt(
            int userId,
            int receiptId,
            double totalPrice,
            List<ReceiptLine> receiptLines,
            PaymentResultDTO paymentResult,
            IssuanceResultDTO issuanceResult,
            LocalDateTime purchasedAt) {
        OrderReceipt receipt = OrderReceipt.forMember(
                receiptId,
                userId,
                totalPrice,
                receiptLines,
                purchasedAt,
                buildPurchaseTransactions(paymentResult, issuanceResult));

        orderReceiptRepository.save(receipt);
    }

    private void saveGuestReceipt(
            String guestEmail,
            String guestSessionId,
            int receiptId,
            double totalPrice,
            List<ReceiptLine> receiptLines,
            PaymentResultDTO paymentResult,
            IssuanceResultDTO issuanceResult,
            LocalDateTime purchasedAt) {
        OrderReceipt receipt = OrderReceipt.forGuest(
                guestEmail,
                guestSessionId,
                receiptId,
                totalPrice,
                receiptLines,
                purchasedAt,
                buildPurchaseTransactions(paymentResult, issuanceResult));

        orderReceiptRepository.save(receipt);
    }

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

    private CheckoutResultDTO buildCheckoutResult(
            double totalPrice,
            int orderReceiptId,
            PaymentResultDTO paymentResult,
            List<CheckoutResultDTO.IssuedTicketDTO> issuedTickets) {
        return new CheckoutResultDTO(
                totalPrice,
                orderReceiptId,
                paymentResult.paymentTransactionId(),
                issuedTickets);
    }

    private void notifyPurchaseCompleted(int userId, double totalPrice, List<ReceiptLine> receiptLines) {
        // Publish a cross-context integration event; notifications consumes it after commit (ADR-004).
        eventPublisher.publishEvent(new PurchaseCompletedNotice(
                userId,
                totalPrice,
                receiptLines.stream()
                        .map(ReceiptLine::getTicketId)
                        .toList()));
    }

    private void handleCheckoutFailure(
            ActiveOrder order,
            int userId,
            String orderLockKey,
            PaymentResultDTO paymentResult,
            double totalPrice,
            boolean inventorySaleConfirmed,
            FreezeState freeze,
            Exception originalFailure) {
        log.atLevel(failureLogLevel(originalFailure)).setCause(originalFailure).log(
                "Checkout failed. userId={}, totalPrice={}, paymentDone={}, inventorySaleConfirmed={}",
                userId,
                totalPrice,
                paymentResult != null,
                inventorySaleConfirmed);

        // Reset the CHECKOUT_IN_PROGRESS status, return the reserved inventory to stock, and clear the cart
        // atomically under one lock scope. The guards inside the helper skip the rollback once the sale is
        // confirmed SOLD (a release would throw and the order is still CHECKOUT_IN_PROGRESS), when this
        // attempt's own freeze save failed, and when another attempt still in flight froze the order.
        // Mirrors handleGuestCheckoutFailure.
        rollbackReservedInventoryAtomically(orderLockKey, order, inventorySaleConfirmed, freeze);

        if (inventorySaleConfirmed) {
            log.error(
                    "Checkout failed after inventory was confirmed as SOLD. Manual recovery may be required. userId={}",
                    userId);
        }

        // Refund only if the sale did NOT commit. Once inventory is confirmed SOLD the purchase is final;
        // refunding here would leave the buyer holding tickets they were also refunded for (C2).
        if (paymentResult != null && !inventorySaleConfirmed) {
            safelyRefundPayment(paymentResult);
        }

        if (userId > 0) {
            // Publish a cross-context integration event; notifications consumes it after commit (ADR-004).
            eventPublisher.publishEvent(new PurchaseFailedNotice(userId, "Checkout failed."));
        }
    }

    // No failure notice is published, since a guest has no user id. The guest's session id is never
    // logged: it is the guest's credential.
    private void handleGuestCheckoutFailure(
            ActiveOrder order,
            String orderLockKey,
            PaymentResultDTO paymentResult,
            double totalPrice,
            boolean inventorySaleConfirmed,
            FreezeState freeze,
            Exception originalFailure) {
        log.atLevel(failureLogLevel(originalFailure)).setCause(originalFailure).log(
                "Guest checkout failed. orderKey={}, totalPrice={}, paymentDone={}, inventorySaleConfirmed={}",
                orderKeyOf(order),
                totalPrice,
                paymentResult != null,
                inventorySaleConfirmed);

        // Roll back inventory + cart + status atomically under one lock scope (see handleCheckoutFailure).
        rollbackReservedInventoryAtomically(orderLockKey, order, inventorySaleConfirmed, freeze);

        if (inventorySaleConfirmed) {
            log.error(
                    "Guest checkout failed after inventory was confirmed as SOLD. Manual recovery may be required. orderKey={}",
                    orderKeyOf(order));
        }

        // Refund only if the sale did NOT commit. Once inventory is confirmed SOLD the purchase is final;
        // refunding here would leave the buyer holding tickets they were also refunded for (C2).
        if (paymentResult != null && !inventorySaleConfirmed) {
            safelyRefundPayment(paymentResult);
        }
    }

    // A business refusal (a declined card, an expired cart, a policy rejection, a sold-out zone) or a lost
    // concurrency race is an outcome the buyer can act on, so it is WARN; ConcurrentReservationException is a
    // DomainException. An outage is ERROR though it is a DomainException: an unreachable payment gateway may
    // have charged the card with no receipt and no refund (NaimElijah/Ticketeer#99), and an unreachable
    // issuer or external service needs an operator too. Anything else is unexpected, so it is ERROR. The other
    // ERROR lines on the failure path (SOLD then failed, refund failed, rollback failed) stay ERROR whatever the
    // cause.
    private static Level failureLogLevel(Exception failure) {
        if (isOutage(failure)) {
            return Level.ERROR;
        }
        boolean expected = failure instanceof DomainException || failure instanceof ConcurrencyFailureException;
        return expected ? Level.WARN : Level.ERROR;
    }

    private static boolean isOutage(Exception failure) {
        return failure instanceof PaymentGatewayUnreachableException
                || failure instanceof TicketIssuerUnreachableException
                || failure instanceof ExternalServiceUnavailableException;
    }

    private void countAttemptInFlight(String orderKey) {
        attemptsInFlight.merge(orderKey, 1, Integer::sum);
    }

    private void uncountAttemptInFlight(String orderKey) {
        attemptsInFlight.computeIfPresent(orderKey, (key, attempts) -> attempts == 1 ? null : attempts - 1);
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
            boolean inventorySaleConfirmed,
            FreezeState freeze) {
        // Skip when the sale already committed (inventory is SOLD — a release would throw while the order is
        // still CHECKOUT_IN_PROGRESS) or there is no key to lock by (failure before Phase 1 acquired the order,
        // so nothing was reserved for this checkout to roll back). A Phase-1 validation failure on an order
        // that is not in checkout (e.g. an expired hold) still rolls back: its reserved tickets are returned
        // to stock and the cart is cleared. One that failed because another attempt still in flight froze
        // the order is skipped below, once the order is re-read.
        if (inventorySaleConfirmed || orderLockKey == null) {
            return;
        }
        // This attempt's freeze save failed, so it changed nothing. On the Jpa adapter the order lock is a
        // no-op, and that save loses on @Version to whatever committed since Phase 1 read the order: a
        // reservation from another tab, or another attempt's freeze. The order, its lines and its holds are
        // as that change left them, so a rollback here would release seats this attempt never froze
        // (NaimElijah/Ticketeer#187).
        if (freeze == FreezeState.SAVING) {
            log.info("Checkout rollback skipped: this attempt's freeze save failed, so it changed nothing "
                    + "(usually a concurrent cart change). orderKey={}", orderKeyOf(fallbackOrder));
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
            // Frozen by another attempt still in flight: resetting it, releasing its seats or clearing it
            // would let that attempt charge for seats it no longer holds. An order frozen by an attempt
            // that is no longer running (the process restarted during its external calls) has no count in
            // attemptsInFlight, so a retry rolls it back and the buyer can start over. The count is this
            // process's only, so with several instances an attempt running on another one looks dead here;
            // the expiry sweeper never releases a frozen order (NaimElijah/Ticketeer#214).
            if (order.isCheckoutInProgress() && freeze != FreezeState.FROZEN
                    && attemptsInFlight.containsKey(order.getOrderKey())) {
                log.warn("Checkout rollback skipped: the order is in another attempt's checkout. orderKey={}",
                        order.getOrderKey());
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
            // Already in the failure path — log, never mask the original checkout failure. A guest's lock
            // key embeds the session id, the guest's credential, so the line names the order instead.
            log.error("Atomic checkout rollback failed for orderKey={}", orderKeyOf(fallbackOrder), rollbackFailure);
        } finally {
            unlockEvents(lockedEventIds);
            unlockOrderKeepingTheOutcome(orderLockKey, fallbackOrder);
        }
    }

    /** The order's own key, never derived from a session id, so it is safe to log; null without an order. */
    private static String orderKeyOf(ActiveOrder order) {
        return order == null ? null : order.getOrderKey();
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

    private void returnTicketsToStock(ActiveOrder order) {
        // Release the still-held (RESERVED) lines back to AVAILABLE through the catalog inventory port
        // (which does the grouping, per-zone best-effort release, and per-event save). CALLER-LOCKED: this
        // runs inside rollbackReservedInventoryAtomically, which holds the event buyer-locks. The cart
        // clear stays here — the order is a sales aggregate.
        inventoryPort.releaseHeld(toInventoryLines(order.getItems()), order.getOrderKey());
        safelyClearCart(order);
    }

    // We clear and persist the cart after a rollback without letting a failure here mask the original
    // checkout failure. releaseHeld tries every event and throws only if one could not be released; the
    // clear is then skipped on purpose, since clearing lines whose holds remain would orphan those holds.
    // The expiry sweeper releases them later only if the order is not left frozen: the status reset
    // above is saved by the clear alone, and the sweeper never releases a frozen order (#214).
    private void safelyClearCart(ActiveOrder order) {
        try {
            order.clear();
            activeOrderRepository.save(order);
        } catch (RuntimeException clearFailure) {
            log.warn("Could not clear cart during checkout rollback for orderKey={}", order.getOrderKey(),
                    clearFailure);
        }
    }

    // Best-effort: a refund failure is logged, never thrown, so it cannot mask the checkout's own failure.
    // It refunds what the gateway says it charged: after an amount mismatch that differs from the order's
    // total, and the buyer is owed what was taken.
    private void safelyRefundPayment(PaymentResultDTO paymentResult) {
        double charged = paymentResult.chargedAmount();
        if (!isValidSum(charged)) {
            // The gateway reported no sum to send back, so whether it took money is unknown: an operator must
            // reconcile the transaction rather than checkout refund an invented amount.
            log.error("Refund not sent after checkout failure: the gateway reported no valid amount. "
                            + "transactionId={}, reportedAmount={} needs manual reconciliation",
                    paymentResult.paymentTransactionId(), charged);
            return;
        }
        try {
            paymentGateway.refund(paymentResult.paymentTransactionId(), charged);
        } catch (RuntimeException refundFailure) {
            log.error(
                    "Refund failed after checkout failure. transactionId={}, amount={}",
                    paymentResult.paymentTransactionId(),
                    charged,
                    refundFailure);
        }
    }

    private String memberBuyerKey(int userId) {
        return "member:" + userId;
    }

    private String guestBuyerKey(String guestSessionId, String guestEmail) {
        return "guest:" + guestSessionId + ":" + guestEmail.trim().toLowerCase();
    }

    private String memberOrderLockKey(int userId) {
        return "user:" + userId;
    }

    private String guestOrderLockKey(String guestSessionId) {
        return "sess:" + guestSessionId;
    }

    private void validatePurchasePolicies(List<CartLineItem> boughtItems, Integer userId, Integer buyerAge) {
        Map<Integer, Long> quantityByEvent = boughtItems.stream()
                .collect(Collectors.groupingBy(CartLineItem::geteventId, Collectors.counting()));

        // Buyer id for the policy context: -1 for a guest, else the member id.
        int buyerId = (userId == null) ? -1 : userId;

        for (Map.Entry<Integer, Long> entry : quantityByEvent.entrySet()) {
            int eventId = entry.getKey();
            int quantity = entry.getValue().intValue();

            // The effective policy (company AND event, CHECKOUT stage) is validated inside the catalog
            // inventory port, which owns the event + resolves the company policy. Runs in Phase 2 (no
            // locks); throws EventNotFoundException / a policy violation.
            inventoryPort.validatePurchasePolicy(eventId, buyerId, buyerAge, quantity);
        }
    }

    private Integer getBuyerAgeByUserId(int userId) {
        User user = userRepository.getUserById(userId);

        if (user == null) {
            throw new UserNotFoundException(userId);
        }

        return user.getAge();
    }


    // This is a simple struct to hold a cart line item along with its calculated
    // final price for the checkout. This allows us to calculate prices once and
    // keep the logic clean, especially when we need to build receipt lines later.
    private record PricedCartLine(
            CartLineItem item,
            double finalPrice) {
    }

}
