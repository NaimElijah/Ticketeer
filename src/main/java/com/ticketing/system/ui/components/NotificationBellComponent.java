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
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Self-contained bell icon + unread badge + notification dropdown.
 * Reads NotificationSession on construction; call refresh() to rebuild
 * from the current session state (hook for future real-time delivery, #225).
 */
public class NotificationBellComponent extends Span {

    private final Predicate<String> markRead;
    private final Function<LocalDateTime, String> age;

    /**
     * @param markRead stores the read of the notification id it is given and answers whether that
     *                 worked; the bell shows the item read only when it did, so the badge and the
     *                 store agree
     * @param age      renders a notification's creation time as a short age ("5m", "Yesterday")
     * @throws NullPointerException if either argument is null
     */
    public NotificationBellComponent(Predicate<String> markRead, Function<LocalDateTime, String> age) {
        this.markRead = Objects.requireNonNull(markRead, "markRead");
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
            NotificationSession.getAll().stream()
                    .filter(n -> !"READ".equals(n.status()))
                    .map(NotificationDTO::notificationId)
                    .filter(id -> id != null && !id.isEmpty())
                    .filter(markRead)
                    .forEach(NotificationSession::markRead);
            refresh();
        };

        java.util.function.Consumer<String> onMarkOne = notifId -> {
            if (markRead.test(notifId)) {
                NotificationSession.markRead(notifId);
            }
            refresh();
        };

        add(new LkPopover(bell, LkNotifPanel.fromDTOs(notifs, onMarkAll, onMarkOne, age))
                .position(PopoverPosition.BOTTOM_END));
    }
}
