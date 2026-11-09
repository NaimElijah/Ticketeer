package com.ticketing.system.ui.presenters.order;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.extern.slf4j.Slf4j;

import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.shared.dto.CardDetailsDTO;
import com.ticketing.system.shared.dto.CheckoutResultDTO;
import com.ticketing.system.sales.application.event.OrderExpiredEvent;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.shared.exception.DomainException;
import com.ticketing.system.shared.exception.IdempotencyConflictException;
import com.ticketing.system.shared.exception.InsufficientInventoryException;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.shared.exception.PaymentGatewayException;
import com.ticketing.system.shared.exception.PaymentGatewayUnreachableException;
import com.ticketing.system.shared.exception.PolicyViolationException;
import com.ticketing.system.shared.exception.TicketIssuanceFailedException;
import com.ticketing.system.ui.components.Money;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * Presenter for {@code CheckoutView}: resolves the visitor, loads and prices the active order, runs
 * the checkout for a member or a guest, and tells open checkout pages when their order expires. Holds
 * no Vaadin imports; a failure of the order read or the checkout comes back as a typed outcome.
 *
 * <p>The presenter keeps one piece of state, the set of registered {@link ExpiryListener}s, and is
 * safe to share between sessions.
 */
@Component
@Slf4j
public class CheckoutPresenter {

    private static final String CURRENCY = "USD";

    // ActiveOrder.validateCanCheckout refuses an empty cart and a cart whose hold ran out with the same
    // InvalidStateTransitionException type, so its text is all that tells the empty cart from an expiry.
    private static final String EMPTY_ORDER_MESSAGE = "Cannot checkout an empty order";

    private final ReservationService reservationService;
    private final CheckoutService    checkoutService;
    private final SessionIdentity    identity;

    private final Set<ExpiryListener> expiryListeners = ConcurrentHashMap.newKeySet();

    /**
     * Creates the presenter.
     *
     * @param reservationService reads the visitor's active order
     * @param checkoutService    runs the checkout
     * @param identity           resolves the visitor as a member or a guest
     */
    @Autowired
    public CheckoutPresenter(ReservationService reservationService,
                             CheckoutService    checkoutService,
                             SessionIdentity    identity) {
        this.reservationService = reservationService;
        this.checkoutService    = checkoutService;
        this.identity           = identity;
    }

    // ---- identity -------------------------------------------------------

    /** The visitor, resolved once: a live member's token and id, or a guest's live session id. */
    public Identity resolveIdentity() {
        return switch (identity.visitor()) {
            case SessionIdentity.Visitor.Member member -> new Identity(true, member.token(), null, member.userId());
            case SessionIdentity.Visitor.Guest guest ->
                    new Identity(false, null, guest.sessionId(), SessionIdentity.NO_MEMBER_USER_ID);
        };
    }

    /**
     * The visitor as {@link #resolveIdentity()} resolved it. Its {@code toString} shows neither credential.
     *
     * @param member         whether the visitor is a live member
     * @param memberToken    the member token, a credential; {@code null} for a guest
     * @param guestSessionId the guest session id, a credential; {@code null} for a member
     * @param userId         the member's user id, or {@link SessionIdentity#NO_MEMBER_USER_ID} for a guest
     */
    public record Identity(boolean member, String memberToken, String guestSessionId, int userId) {
        @Override
        public String toString() {
            return "Identity[member=" + member + ", userId=" + userId + "]";
        }
    }

    // ---- load -----------------------------------------------------------

    /**
     * The visitor's active order, priced. A guest may check out, so there is no signed-out outcome: a
     * visitor without a live member token is looked up as a guest.
     *
     * @return the order, {@link LoadOutcome.Empty} when there is none or it has no lines, or a failure
     *     with {@code ServiceErrors}' text
     */
    public LoadOutcome loadOrder() {
        try {
            // The cart's own lookup: viewMyActiveOrder tries the member key (token -> userId) and falls
            // back to the guest key, so a cart stored under the user id with no session id is still found.
            return classify(reservationService.viewMyActiveOrder(identity.credential()));
        } catch (RuntimeException e) {
            return new LoadOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    private LoadOutcome classify(ActiveOrderDTO order) {
        if (order == null || order.lines().isEmpty()) {
            return new LoadOutcome.Empty();
        }
        return new LoadOutcome.Loaded(order, price(order));
    }

    private Pricing price(ActiveOrderDTO order) {
        long subtotal = order.lines().stream()
                .mapToLong(l -> Money.toCents(l.pricePerTicket()))
                .sum();
        return new Pricing(subtotal, subtotal);
    }

    /**
     * The amounts the checkout page shows, in cents. Both are the sum of the order's line prices.
     *
     * @param subtotalCents the sum of the lines' prices
     * @param totalCents    the amount shown as the order's total
     */
    public record Pricing(long subtotalCents, long totalCents) { }

    // ---- pay ------------------------------------------------------------

    /**
     * Checks out the signed-in member's active order.
     *
     * @param memberToken    the member's token
     * @param idempotencyKey identifies this submission to the service's idempotency check
     * @param cardNumber     the card number as typed; whitespace is removed
     * @param cvc            the security code as typed
     * @param expiry         the expiry as typed, in the form {@code MM / YY}
     * @param holder         the card holder's name as typed
     * @return {@link PayOutcome.Success} with the result, otherwise the variant for the failure; see
     *     {@link PayOutcome}
     */
    public PayOutcome payAsMember(String memberToken, String idempotencyKey,
                                  String cardNumber, String cvc, String expiry, String holder) {
        CardDetailsDTO card = buildCard(cardNumber, cvc, expiry, holder);
        return runPay(() -> checkoutService.checkoutMember(memberToken, idempotencyKey, CURRENCY, card));
    }

    /**
     * Checks out a guest's active order.
     *
     * @param sessionId      the guest's session id
     * @param guestEmail     the email address the order is bought under
     * @param guestAge       the guest's age as entered
     * @param idempotencyKey identifies this submission to the service's idempotency check
     * @param cardNumber     the card number as typed; whitespace is removed
     * @param cvc            the security code as typed
     * @param expiry         the expiry as typed, in the form {@code MM / YY}
     * @param holder         the card holder's name as typed
     * @return {@link PayOutcome.Success} with the result, otherwise the variant for the failure; see
     *     {@link PayOutcome}
     */
    public PayOutcome payAsGuest(String sessionId, String guestEmail, int guestAge, String idempotencyKey,
                                 String cardNumber, String cvc, String expiry, String holder) {
        CardDetailsDTO card = buildCard(cardNumber, cvc, expiry, holder);
        return runPay(() -> checkoutService.checkoutGuest(
            sessionId, guestEmail, idempotencyKey, CURRENCY, card, guestAge));
    }

    private interface Charge { CheckoutResultDTO run(); }

    private PayOutcome runPay(Charge charge) {
        try {
            return new PayOutcome.Success(charge.run());
        } catch (RuntimeException e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            if (cause instanceof PolicyViolationException)       return new PayOutcome.PolicyRejected(cause.getMessage());
            // MUST precede the PaymentGatewayException arm: unreachable is a subtype of it. A timeout
            // is expected transport noise, not a bug -> warn (not error), and a GENERIC outcome that
            // carries no reason so no gateway/WSEP wording can leak to the buyer.
            if (cause instanceof PaymentGatewayUnreachableException) {
                log.warn("Checkout aborted: payment gateway unreachable", e);
                return new PayOutcome.GatewayUnavailable();
            }
            // Tickets are issued after the charge, so this order did not complete after money moved: the
            // buyer is told the charge is being refunded. A domain exception, but its message can carry
            // the external issuer's error text, so the outcome carries no reason.
            if (cause instanceof TicketIssuanceFailedException) {
                log.warn("Checkout aborted: ticket issuance failed", e);
                return new PayOutcome.OrderNotCompleted();
            }
            if (cause instanceof PaymentGatewayException)        return new PayOutcome.PaymentDeclined(cause.getMessage());
            if (cause instanceof InsufficientInventoryException) return new PayOutcome.SoldOut(cause.getMessage());
            // Every other refusal of this type is an expiry, before checkout or during it; an empty cart
            // falls through to the DomainException arm with its own reason.
            if (cause instanceof InvalidStateTransitionException
                    && !EMPTY_ORDER_MESSAGE.equals(cause.getMessage())) {
                return new PayOutcome.OrderExpired("Order expired during checkout");
            }
            if (cause instanceof IdempotencyConflictException)   return new PayOutcome.DuplicateSubmission();
            if (cause instanceof DomainException)                return new PayOutcome.Failure(cause.getMessage());
            // ServiceErrors decides what the buyer may read and logs an internal failure. It gets the
            // cause, as every arm above does: CheckoutService wraps a failure in a RuntimeException of its own.
            return new PayOutcome.Failure(ServiceErrors.userMessage(cause instanceof RuntimeException rc ? rc : e));
        }
    }

    // Builds the gateway card payload from the raw checkout-form inputs. Parses the
    // "MM / YY" expiry into month + 4-digit year; malformed parts fall back to 0 so
    // the gateway, not the presenter, is the single source of a decline.
    private static CardDetailsDTO buildCard(String cardNumber, String cvc, String expiry, String holder) {
        int month = 0;
        int year = 0;
        if (expiry != null && expiry.contains("/")) {
            String[] parts = expiry.split("/");
            month = parseIntOrZero(parts[0].trim());
            int yy = parts.length > 1 ? parseIntOrZero(parts[1].trim()) : 0;
            year = (yy > 0 && yy < 100) ? 2000 + yy : yy;
        }
        String digits = cardNumber == null ? "" : cardNumber.replaceAll("\\s+", "");
        return new CardDetailsDTO(
            digits,
            cvc == null ? "" : cvc.trim(),
            month,
            year,
            holder == null ? "" : holder.trim());
    }

    private static int parseIntOrZero(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- order-expiry push ---------------------------------------------

    /** A checkout page that wants to hear when its visitor's order expires. */
    public interface ExpiryListener {
        /**
         * Whether an expired order is this listener's visitor's.
         *
         * @param userId    the member's user id, or 0 when the order is a guest's
         * @param sessionId the session id the order was bound to; a member's order may have one, or
         *     {@code null}
         * @return {@code true} to have {@link #onExpired()} called
         */
        boolean matches(int userId, String sessionId);

        /** Called once for each expiry that {@link #matches} accepted. */
        void onExpired();
    }

    /**
     * Registers a checkout page for expiry notices. The presenter holds the listener until it is
     * unregistered, so a page unregisters it when it detaches.
     *
     * @param listener the page's listener; registering the same instance twice has no further effect
     */
    public void registerExpiryListener(ExpiryListener listener) {
        expiryListeners.add(listener);
    }

    /**
     * Stops expiry notices to a listener. Does nothing for a listener that is not registered.
     *
     * @param listener the listener to remove
     */
    public void unregisterExpiryListener(ExpiryListener listener) {
        expiryListeners.remove(listener);
    }

    /**
     * Tells each open checkout view whose order the sweeper expired. Runs once the sweep's transaction
     * has committed, so a sweep that rolls back tells no one; an expiry published outside a transaction
     * is passed on at once. A view that fails to take the news is logged at WARN with its exception and
     * the others are still told: the sweep has already committed, so the failure must not reach it.
     *
     * @param event the expired order's owner, a member's user id or a guest's session id
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void onOrderExpired(OrderExpiredEvent event) {
        for (ExpiryListener listener : expiryListeners) {
            try {
                if (listener.matches(event.userId(), event.sessionId())) {
                    listener.onExpired();
                }
            } catch (RuntimeException e) {
                log.warn("checkout view failed to take an order expiry userId={}", event.userId(), e);
            }
        }
    }

    // ---- outcomes -------------------------------------------------------

    /** Sealed outcome of {@link #loadOrder}, which the view switches on. */
    public sealed interface LoadOutcome {
        /**
         * The visitor has an order to check out.
         *
         * @param order   the active order
         * @param pricing its amounts
         */
        record Loaded(ActiveOrderDTO order, Pricing pricing) implements LoadOutcome { }

        /** The visitor has no order, or an order with no lines. */
        record Empty()                implements LoadOutcome { }

        /**
         * The read failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements LoadOutcome { }
    }

    /** Sealed outcome of {@link #payAsMember} and {@link #payAsGuest}, which the view switches on. */
    public sealed interface PayOutcome {
        /**
         * The checkout completed.
         *
         * @param result what the service returned for the order
         */
        record Success(CheckoutResultDTO result)     implements PayOutcome { }

        /**
         * The effective purchase policy (the company's and the event's) refused the order
         * ({@code PolicyViolationException}).
         *
         * @param reason the exception's message; {@code CheckoutView} shows its own text, so this does
         *     not reach the user
         */
        record PolicyRejected(String reason)         implements PayOutcome { }

        /**
         * The payment gateway raised a {@code PaymentGatewayException} other than an unreachable
         * gateway.
         *
         * @param reason the exception's message; {@code CheckoutView} shows its own text, so this does
         *     not reach the user
         */
        record PaymentDeclined(String reason)        implements PayOutcome { }

        /**
         * The seats or places are no longer available ({@code InsufficientInventoryException}).
         *
         * @param reason the exception's message; {@code CheckoutView} shows its own text, so this does
         *     not reach the user
         */
        record SoldOut(String reason)                implements PayOutcome { }

        /**
         * The order's hold ran out before or during the checkout.
         *
         * @param reason fixed text; {@code CheckoutView} shows its own wording
         */
        record OrderExpired(String reason)           implements PayOutcome { }

        /**
         * The idempotency key is already cached for a different buyer. A replay by the same buyer is a
         * {@link Success} carrying the first result.
         */
        record DuplicateSubmission()                 implements PayOutcome { }

        /**
         * The payment gateway could not be reached ({@code PaymentGatewayUnreachableException}). It
         * carries no reason.
         */
        record GatewayUnavailable()                  implements PayOutcome { }

        /** Tickets could not be issued after the charge; the charge is being refunded. */
        record OrderNotCompleted()                   implements PayOutcome { }

        /**
         * Any other failure, including an empty order.
         *
         * @param reason the text the user may read: a {@code DomainException}'s message as it is, else
         *     from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason)                implements PayOutcome { }
    }
}