package com.ticketing.system.Presentation.presenters.order;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.ticketing.system.Core.Application.dto.ActiveOrderDTO;
import com.ticketing.system.Core.Application.dto.CardDetailsDTO;
import com.ticketing.system.Core.Application.dto.CheckoutResultDTO;
import com.ticketing.system.Core.Application.events.OrderExpiredEvent;
import com.ticketing.system.Core.Application.services.CheckoutService;
import com.ticketing.system.Core.Application.services.ReservationService;
import com.ticketing.system.shared.exception.DomainException;
import com.ticketing.system.shared.exception.IdempotencyConflictException;
import com.ticketing.system.shared.exception.InsufficientInventoryException;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.shared.exception.PaymentGatewayException;
import com.ticketing.system.shared.exception.PaymentGatewayUnreachableException;
import com.ticketing.system.shared.exception.PolicyViolationException;
import com.ticketing.system.Presentation.components.Money;
import com.ticketing.system.Presentation.session.SessionIdentity;

@Component
@Slf4j
public class CheckoutPresenter {

    private static final String CURRENCY = "USD";

    private final ReservationService reservationService;
    private final CheckoutService    checkoutService;
    private final SessionIdentity    identity;

    private final Set<ExpiryListener> expiryListeners = ConcurrentHashMap.newKeySet();

    @Autowired
    public CheckoutPresenter(ReservationService reservationService,
                             CheckoutService    checkoutService,
                             SessionIdentity    identity) {
        this.reservationService = reservationService;
        this.checkoutService    = checkoutService;
        this.identity           = identity;
    }

    // ---- identity -------------------------------------------------------

    public Identity resolveIdentity() {
        if (identity.isMember()) {
            return new Identity(true, identity.memberToken(), null, identity.memberUserId());
        }
        return new Identity(false, null, identity.guestSessionId(), 0);
    }

    public record Identity(boolean member, String memberToken, String guestSessionId, int userId) { }

    // ---- load -----------------------------------------------------------

    public LoadOutcome loadOrder(int userId, String memberToken, String guestSessionId) {
        try {
            // Use the cart's exact lookup: viewMyActiveOrder tries the member key (token -> userId) and
            // FALLS BACK to the guest key, so a cart stored under userId (with sessionId null) is still
            // found. The old two-branch version committed to one key with no fallback -> empty checkout.
            return classify(reservationService.viewMyActiveOrder(identity.credential()));
        } catch (RuntimeException e) {
            return new LoadOutcome.Failure(e.getMessage());
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

    public record Pricing(long subtotalCents, long totalCents) { }

    // ---- pay ------------------------------------------------------------

    public PayOutcome payAsMember(String memberToken, String idempotencyKey,
                                  String cardNumber, String cvc, String expiry, String holder) {
        CardDetailsDTO card = buildCard(cardNumber, cvc, expiry, holder);
        return runPay(() -> checkoutService.checkoutMember(memberToken, idempotencyKey, CURRENCY, card));
    }

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
            if (cause instanceof PaymentGatewayException)        return new PayOutcome.PaymentDeclined(cause.getMessage());
            if (cause instanceof InsufficientInventoryException) return new PayOutcome.SoldOut(cause.getMessage());
            if (cause instanceof InvalidStateTransitionException) return new PayOutcome.OrderExpired("Order expired during checkout");
            if (cause instanceof IdempotencyConflictException)   return new PayOutcome.DuplicateSubmission();
            // Surface the reason only for domain (business) exceptions — their messages are safe to show.
            // Anything else is an unexpected/internal error: log it server-side, show only the generic message.
            if (cause instanceof DomainException)                return new PayOutcome.Failure(cause.getMessage());
            log.error("Checkout failed unexpectedly", e);
            return new PayOutcome.Failure(null);
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

    public interface ExpiryListener {
        boolean matches(int userId, String sessionId);
        void onExpired();
    }

    public void registerExpiryListener(ExpiryListener listener) {
        expiryListeners.add(listener);
    }

    public void unregisterExpiryListener(ExpiryListener listener) {
        expiryListeners.remove(listener);
    }

    @EventListener
    public void onOrderExpired(OrderExpiredEvent event) {
        for (ExpiryListener listener : expiryListeners) {
            if (listener.matches(event.userId(), event.sessionId())) {
                listener.onExpired();
            }
        }
    }

    // ---- outcomes -------------------------------------------------------

    public sealed interface LoadOutcome {
        record Loaded(ActiveOrderDTO order, Pricing pricing) implements LoadOutcome { }
        record Empty()                implements LoadOutcome { }
        record NotAuthenticated()     implements LoadOutcome { }
        record Failure(String reason) implements LoadOutcome { }
    }

    public sealed interface PayOutcome {
        record Success(CheckoutResultDTO result)     implements PayOutcome { }
        record PolicyRejected(String reason)         implements PayOutcome { }
        record PaymentDeclined(String reason)        implements PayOutcome { }
        record SoldOut(String reason)                implements PayOutcome { }
        record OrderExpired(String reason)           implements PayOutcome { }
        record DuplicateSubmission()                 implements PayOutcome { }
        record GatewayUnavailable()                  implements PayOutcome { }
        record Failure(String reason)                implements PayOutcome { }
    }
}