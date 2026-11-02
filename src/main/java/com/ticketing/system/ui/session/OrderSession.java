package com.ticketing.system.ui.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.ticketing.system.shared.dto.CheckoutResultDTO;
import com.vaadin.flow.server.VaadinSession;

/**
 * Session-scoped holder for the just-completed checkout result, handed from
 * {@code CheckoutView} to {@code OrderConfirmationView} across the
 * {@code navigate("order-confirmed")} hop. Keeping the {@link VaadinSession}
 * read/write here (mirroring {@link AuthSession} / {@link GuestSession}) lets
 * {@code CheckoutPresenter} stay a Vaadin-free POJO.
 *
 * <p>Kept so that a refresh of the confirmation still shows the tickets: a guest has no account page
 * to find them on again (#187, UI-A7). It belongs to one visitor and one visit, so it is dropped when
 * <ul>
 *   <li>a new payment attempt starts ({@code CheckoutView});</li>
 *   <li>the visitor leaves the confirmation page for another page ({@code OrderConfirmationView});</li>
 *   <li>anyone signs in, member or admin ({@code LoginView}, {@code AdminLoginPresenter});</li>
 *   <li>the visit ends: a sign-out, a stored token that died, or a guest session renewed
 *       ({@link FreshGuestSession});</li>
 *   <li>it is {@link #MAX_AGE} old, on the application clock.</li>
 * </ul>
 */
public final class OrderSession {

    /** How long after the purchase the confirmation can still be opened. */
    public static final Duration MAX_AGE = Duration.ofMinutes(15);

    private static final String RESULT_KEY = "checkout.result";

    /** A kept result and when the purchase was made, on the application clock. */
    private record Kept(CheckoutResultDTO result, Instant purchasedAt) { }

    private OrderSession() { }

    /**
     * @param now the current instant, from the application clock
     * @return the kept checkout result; empty when there is none, no Vaadin session, or it is
     *     {@link #MAX_AGE} old, in which case it is dropped
     */
    public static Optional<CheckoutResultDTO> result(Instant now) {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return Optional.empty();
        Kept kept = (Kept) s.getAttribute(RESULT_KEY);
        if (kept == null) return Optional.empty();
        if (!now.isBefore(kept.purchasedAt().plus(MAX_AGE))) {
            clear();
            return Optional.empty();
        }
        return Optional.of(kept.result());
    }

    /**
     * Keeps {@code result} for the confirmation page; does nothing without a Vaadin session.
     *
     * @param result      the checkout result
     * @param purchasedAt when the purchase was made, from the application clock
     */
    public static void setResult(CheckoutResultDTO result, Instant purchasedAt) {
        VaadinSession s = VaadinSession.getCurrent();
        if (s != null) s.setAttribute(RESULT_KEY, new Kept(result, purchasedAt));
    }

    /** Drops the kept result; does nothing without a Vaadin session. */
    public static void clear() {
        VaadinSession s = VaadinSession.getCurrent();
        if (s != null) s.setAttribute(RESULT_KEY, null);
    }
}
