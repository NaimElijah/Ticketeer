package com.ticketing.system.ui.session;

import com.ticketing.system.shared.dto.CheckoutResultDTO;
import com.vaadin.flow.server.VaadinSession;

/**
 * Session-scoped holder for the just-completed checkout result, handed from
 * {@code CheckoutView} to {@code OrderConfirmationView} across the
 * {@code navigate("order-confirmed")} hop. Keeping the {@link VaadinSession}
 * read/write here (mirroring {@link AuthSession} / {@link GuestSession}) lets
 * {@code CheckoutPresenter} stay a Vaadin-free POJO.
 *
 * <p>Kept for the session, so a refresh of the confirmation still shows the tickets: a guest has no
 * account page to find them on again (#187, UI-A7). It belongs to the last order and its visitor, so
 * {@code CheckoutView} clears it when a new payment attempt starts, and
 * {@link FreshGuestSession#restore()} clears it on sign-out or when a stored token has died.
 */
public final class OrderSession {

    private static final String RESULT_KEY = "checkout.result";

    private OrderSession() { }

    /** The last checkout result, or {@code null} when none / no Vaadin session. */
    public static CheckoutResultDTO result() {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return null;
        return (CheckoutResultDTO) s.getAttribute(RESULT_KEY);
    }

    public static void setResult(CheckoutResultDTO result) {
        VaadinSession s = VaadinSession.getCurrent();
        if (s != null) s.setAttribute(RESULT_KEY, result);
    }

    public static void clear() {
        VaadinSession s = VaadinSession.getCurrent();
        if (s != null) s.setAttribute(RESULT_KEY, null);
    }
}
