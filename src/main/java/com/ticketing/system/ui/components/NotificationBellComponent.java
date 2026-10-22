package com.ticketing.system.ui.components;

import com.ticketing.system.shared.dto.NotificationDTO;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkNotifPanel;
import com.ticketing.system.ui.components.kit.LkPopover;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.NotificationSession;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.popover.PopoverPosition;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Self-contained bell icon + unread badge + notification dropdown.
 * Reads NotificationSession on construction; call refresh() to rebuild
 * from the current session state (hook for future real-time delivery, #225).
 */
public class NotificationBellComponent extends Span {

    private final Consumer<String> onRead;
    private final Function<LocalDateTime, String> age;

    /**
     * @param onRead called with a notification id when the user marks it read
     * @param age    renders a notification's creation time as a short age ("5m", "Yesterday")
     */
    public NotificationBellComponent(Consumer<String> onRead, Function<LocalDateTime, String> age) {
        this.onRead = onRead;
        this.age = age;
        addAttachListener(e -> NotificationSession.setBell(this));
        addDetachListener(e -> NotificationSession.setBell(null));
        build();
    }

    /** Rebuild from current NotificationSession — safe to call after store(). */
    public void refresh() {
        removeAll();
        build();
    }

    private void build() {
        List<NotificationDTO> notifs = AuthSession.isSignedIn()
                ? NotificationSession.getAll().stream()
                        .sorted(java.util.Comparator.comparing(NotificationDTO::createdAt,
                                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())).reversed())
                        .toList()
                : List.of();

        int unread = (int) notifs.stream().filter(n -> !"READ".equals(n.status())).count();

        NativeButton bell = new NativeButton();
        bell.addClassName("lk-bell");
        bell.getElement().setAttribute("aria-label", "Notifications");
        bell.add(new LkIcon("bell", 18));

        if (unread > 0) {
            Span badge = new Span(String.valueOf(unread));
            badge.addClassName("lk-bell-badge");
            bell.add(badge);
        }

        Runnable onMarkAll = () -> {
            if (onRead != null) {
                NotificationSession.getAll().stream()
                        .filter(n -> !"READ".equals(n.status()))
                        .map(NotificationDTO::notificationId)
                        .filter(id -> id != null && !id.isEmpty())
                        .forEach(onRead);
            }
            NotificationSession.markAllRead();
            refresh();
        };

        java.util.function.Consumer<String> onMarkOne = notifId -> {
            NotificationSession.markRead(notifId);
            if (onRead != null) onRead.accept(notifId);
            refresh();
        };

        add(new LkPopover(bell, LkNotifPanel.fromDTOs(notifs, onMarkAll, onMarkOne, age))
                .position(PopoverPosition.BOTTOM_END));
    }
}
