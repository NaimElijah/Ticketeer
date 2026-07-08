package com.ticketing.system.Presentation.session;
import com.ticketing.system.identity.domain.Session;

import com.ticketing.system.Core.Application.dto.NotificationDTO;
import com.ticketing.system.Presentation.components.NotificationBellComponent;
import com.vaadin.flow.server.VaadinSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Session-scoped holder for the notification inbox delivered on login (UC-37).
 * Populated by LoginPresenter immediately after a successful login, read by
 * MainLayout to build the bell panel and badge count.
 */
public final class NotificationSession {

    private static final String NOTIFICATIONS_KEY = "notificationSession.notifications";
    private static final String BELL_KEY           = "notificationSession.bell";

    private NotificationSession() {}

    /**
     * Store the pending notifications delivered on login. Replaces any previous list.
     * TODO(I.5 / #225): this is a login-only snapshot — notifications generated mid-session
     *  (PURCHASE_CONFIRMED, CART_EXPIRING, etc.) won't appear in the bell until the user
     *  logs out and back in. Real-time delivery is deferred to Grade ג'.
     */
    public static void store(List<NotificationDTO> notifications) {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null || notifications == null) return;
        s.setAttribute(NOTIFICATIONS_KEY, List.copyOf(notifications));
    }

    /** All notifications delivered in this session, oldest-first. Empty list if none. */
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

    /** Mark every notification in the session snapshot READ. */
    public static void markAllRead() {
        List<NotificationDTO> updated = getAll().stream()
                .map(n -> new NotificationDTO(n.notificationId(), n.type(), "READ", n.message(), n.createdAt()))
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

    /** Register the current bell component so the scheduler can call refresh() on it. */
    public static void setBell(NotificationBellComponent bell) {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return;
        s.setAttribute(BELL_KEY, bell);
    }

    /** Returns the bell registered for this session, or null if none is attached. */
    public static NotificationBellComponent getBell() {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return null;
        return (NotificationBellComponent) s.getAttribute(BELL_KEY);
    }

    /** Clear the inbox — call this on sign-out. */
    public static void clear() {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return;
        s.setAttribute(NOTIFICATIONS_KEY, null);
        s.setAttribute(BELL_KEY, null);
    }
}
