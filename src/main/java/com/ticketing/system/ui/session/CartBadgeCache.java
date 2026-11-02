package com.ticketing.system.ui.session;

import java.time.Duration;
import java.util.Optional;

import com.vaadin.flow.server.VaadinSession;

/**
 * The top bar's cart badge, cached in the Vaadin session so that not every page reads the cart from
 * the server: {@code MainLayout} shows a cached badge for {@link #MAX_AGE}, then reads the cart again.
 * Whatever changes this session's cart calls {@link #invalidate()}, so the next page shows the cart as
 * it is (#187, UI-A12).
 */
public final class CartBadgeCache {

    /** How long a cached badge is shown before the cart is read again. */
    public static final Duration MAX_AGE = Duration.ofSeconds(10);

    private static final String SIZE_KEY = "cart.size";
    private static final String DEADLINE_KEY = "cart.deadline";
    private static final String FETCHED_AT_KEY = "cart.fetch.time";

    private CartBadgeCache() { }

    /**
     * The cart badge as last read.
     *
     * @param size       how many tickets the cart holds
     * @param deadlineMs when the cart's hold ends, in epoch milliseconds; {@code null} for no hold
     */
    public record Badge(int size, Long deadlineMs) { }

    /**
     * @param nowMs the current time, in epoch milliseconds, from the application clock
     * @return the cached badge while it is younger than {@link #MAX_AGE}; empty when there is none,
     *     it is older, or there is no Vaadin session
     */
    public static Optional<Badge> fresh(long nowMs) {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null) {
            return Optional.empty();
        }
        Long fetchedAtMs = (Long) session.getAttribute(FETCHED_AT_KEY);
        Integer size = (Integer) session.getAttribute(SIZE_KEY);
        if (fetchedAtMs == null || size == null || nowMs - fetchedAtMs >= MAX_AGE.toMillis()) {
            return Optional.empty();
        }
        return Optional.of(new Badge(size, (Long) session.getAttribute(DEADLINE_KEY)));
    }

    /**
     * Caches {@code badge} as read at {@code nowMs}; does nothing without a Vaadin session.
     *
     * @param badge the badge just read
     * @param nowMs when it was read, in epoch milliseconds, from the application clock
     */
    public static void store(Badge badge, long nowMs) {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null) {
            return;
        }
        session.setAttribute(SIZE_KEY, badge.size());
        session.setAttribute(DEADLINE_KEY, badge.deadlineMs());
        session.setAttribute(FETCHED_AT_KEY, nowMs);
    }

    /**
     * Drops the cached badge, so the next page reads the cart again. Call it after anything that changes
     * this session's cart: a reservation, a removal, a checkout attempt, a member's sign-in, which restores
     * and merges their held order, or a sign-out, which pins a guest session with a cart of its own. An
     * admin's sign-in keeps the guest session, and so the cart. Does nothing without a Vaadin session.
     */
    public static void invalidate() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null) {
            return;
        }
        session.setAttribute(SIZE_KEY, null);
        session.setAttribute(DEADLINE_KEY, null);
        session.setAttribute(FETCHED_AT_KEY, null);
    }
}
