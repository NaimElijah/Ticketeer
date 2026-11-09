package com.ticketing.system.ui.session;

import com.ticketing.system.shared.dto.NotificationDTO;
import com.ticketing.system.ui.components.NotificationBellComponent;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Session-scoped holder for the notification inbox (UC-37). {@code LoginView} fills it with the
 * notifications delivered on a successful login, the polling scheduler {@linkplain #merge merges}
 * later ones in, and {@link NotificationBellComponent} reads it to build the bell panel and badge count.
 *
 * <p>The inbox belongs to the Vaadin session, but each bell to its {@link UI}: every browser tab of
 * one session is its own UI with its own bell, so a bell is kept on its UI, never on the session.
 */
public final class NotificationSession {

    private static final String NOTIFICATIONS_KEY = "notificationSession.notifications";

    private NotificationSession() {}

    /**
     * Store the pending notifications delivered on login. Replaces any previous list.
     *
     * <p>Limitation (I.5 / #225): a notification generated mid-session (PURCHASE_CONFIRMED,
     * CART_EXPIRING, etc.) reaches the bell only when the polling scheduler's next tick merges it in
     * (every 60 seconds by default), not at once. Real-time delivery is deferred to Grade ג'.
     */
    public static void store(List<NotificationDTO> notifications) {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null || notifications == null) return;
        s.setAttribute(NOTIFICATIONS_KEY, List.copyOf(notifications));
    }

    /**
     * All notifications delivered in this session: each batch {@link #merge merged} in front of the
     * earlier ones, the login's batch last, and each batch in the order it was delivered. Empty list
     * if none.
     */
    @SuppressWarnings("unchecked")
    public static List<NotificationDTO> getAll() {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return Collections.emptyList();
        Object value = s.getAttribute(NOTIFICATIONS_KEY);
        return value instanceof List<?> list ? (List<NotificationDTO>) list : Collections.emptyList();
    }

    /** Count of notifications that have not been read yet (status != "READ"). */
    public static int getUnreadCount() {
        return (int) getAll().stream()
                .filter(n -> !"READ".equals(n.status()))
                .count();
    }

    /** Mark one notification READ in the session snapshot. */
    public static void markRead(String notificationId) {
        if (notificationId == null || notificationId.isBlank()) return;
        List<NotificationDTO> updated = getAll().stream()
                .map(n -> notificationId.equals(n.notificationId())
                        ? new NotificationDTO(n.notificationId(), n.type(), "READ", n.message(), n.createdAt())
                        : n)
                .toList();
        store(updated);
    }

    /**
     * Prepend fresh notifications to the existing list, deduplicating by notificationId.
     * Called by the polling scheduler when new notifications arrive mid-session.
     */
    public static void merge(List<NotificationDTO> fresh) {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null || fresh == null || fresh.isEmpty()) return;
        List<NotificationDTO> existing = getAll();
        Set<String> existingIds = existing.stream()
                .map(NotificationDTO::notificationId)
                .collect(Collectors.toSet());
        List<NotificationDTO> combined = new ArrayList<>();
        fresh.stream()
                .filter(n -> !existingIds.contains(n.notificationId()))
                .forEach(combined::add);
        combined.addAll(existing);
        s.setAttribute(NOTIFICATIONS_KEY, List.copyOf(combined));
    }

    /**
     * Registers {@code bell} as {@code ui}'s bell, so the polling scheduler, running in that UI, can
     * refresh it. Replaces any bell the UI had; other UIs' bells are untouched.
     *
     * @param ui   the UI the bell is attached to; not {@code null}
     * @param bell the bell; not {@code null}
     */
    public static void setBell(UI ui, NotificationBellComponent bell) {
        ComponentUtil.setData(ui, NotificationBellComponent.class, bell);
    }

    /**
     * Unregisters {@code bell} from {@code ui}, if it is still that UI's bell. A bell attached to the UI
     * in the meantime, as a new layout's bell can be before the old layout's detaches, stays.
     *
     * @param ui   the UI the bell detached from; not {@code null}
     * @param bell the detached bell
     */
    public static void removeBell(UI ui, NotificationBellComponent bell) {
        if (ComponentUtil.getData(ui, NotificationBellComponent.class) == bell) {
            ComponentUtil.setData(ui, NotificationBellComponent.class, null);
        }
    }

    /** Returns the current UI's bell, or null when there is no current UI or it has no bell attached. */
    public static NotificationBellComponent getBell() {
        UI ui = UI.getCurrent();
        return ui == null ? null : ComponentUtil.getData(ui, NotificationBellComponent.class);
    }

    /**
     * Clear the inbox — call this on sign-out. The bells stay registered while attached: each rebuilds
     * from the cleared inbox on its next refresh.
     */
    public static void clear() {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return;
        s.setAttribute(NOTIFICATIONS_KEY, null);
    }
}
