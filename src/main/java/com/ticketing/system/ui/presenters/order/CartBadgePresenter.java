package com.ticketing.system.ui.presenters.order;

import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.ui.session.SessionIdentity;

import lombok.extern.slf4j.Slf4j;

/**
 * Read-only presenter for the buyer top-bar cart-count badge. A Vaadin-free POJO
 * that owns the {@link ReservationService} call and the error handling and returns
 * a sealed {@link Outcome} the layout switches on — so the shell never calls a
 * service or catches an exception itself.
 *
 * <p>Resolves the caller's credential (member token or guest sessionId) via
 * {@link SessionIdentity} — the same resolution {@code CheckoutPresenter} uses —
 * and degrades to {@link Outcome.Failure} so a backend hiccup can't break the
 * top-bar render.
 */
@Slf4j
@Component
public class CartBadgePresenter {

    private final ReservationService reservationService;
    private final SessionIdentity    identity;

    public CartBadgePresenter(ReservationService reservationService, SessionIdentity identity) {
        this.reservationService = reservationService;
        this.identity           = identity;
    }

    /** Cart size + remaining hold time for the current member/guest; Empty when none, Failure on error. */
    public Outcome loadBadge() {
        try {
            String credential = identity.credential();
            if (credential == null) {
                return new Outcome.Empty();
            }
            ActiveOrderDTO order = reservationService.viewMyActiveOrder(credential);
            if (order == null || order.lines().isEmpty()) {
                return new Outcome.Empty();
            }
            return new Outcome.Cart(order.lines().size(), order.remainingSecondsBeforeExpiry());
        } catch (RuntimeException e) {
            log.warn("Failed to load cart badge: {}", e.getMessage());
            return new Outcome.Failure(e.getMessage());
        }
    }

    public sealed interface Outcome {
        record Cart(int count, long remainingSeconds) implements Outcome { }
        record Empty()                                implements Outcome { }
        record Failure(String reason)                 implements Outcome { }
    }
}
