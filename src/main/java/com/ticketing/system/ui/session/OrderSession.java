package com.ticketing.system.ui.session;
import com.ticketing.system.identity.domain.Session;

import com.ticketing.system.Core.Application.dto.CheckoutResultDTO;
import com.vaadin.flow.server.VaadinSession;

/**
 * Session-scoped holder for the just-completed checkout result, handed from
 * {@code CheckoutView} to {@code OrderConfirmationView} across the
 * {@code navigate("order-confirmed")} hop. Keeping the {@link VaadinSession}
 * read/write here (mirroring {@link AuthSession} / {@link GuestSession}) lets
 * {@code CheckoutPresenter} stay a Vaadin-free POJO.
 *
 * <p>Read-once: the confirmation view calls {@link #result()} then {@link #clear()},
 * so a refresh / re-navigation reroutes to Browse instead of re-showing a stale receipt.
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
