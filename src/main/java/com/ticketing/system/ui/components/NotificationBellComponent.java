package com.ticketing.system.ui.components;

import com.ticketing.system.shared.dto.NotificationDTO;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkNotifPanel;
import com.ticketing.system.ui.components.kit.LkPopover;
import com.ticketing.system.ui.presenters.notifications.NotificationBellPresenter.MarkReadOutcome;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.NotificationSession;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.popover.PopoverPosition;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Self-contained bell icon + unread badge + notification dropdown.
 * Reads NotificationSession on construction; call refresh() to rebuild
 * from the current session state (hook for future real-time delivery, #225).
 */
public class NotificationBellComponent extends Span {

    private final Function<String, MarkReadOutcome> markRead;
    private final Function<LocalDateTime, String> age;

    /**
     * @param markRead stores the read of the notification id it is given and answers how that went;
     *                 the bell shows the item read only on success, so the badge and the store agree,
     *                 and shows a failure's reason as a toast
     * @param age      renders a notification's creation time as a short age ("5m", "Yesterday")
     * @throws NullPointerException if either argument is null
     */
    public NotificationBellComponent(Function<String, MarkReadOutcome> markRead, Function<LocalDateTime, String> age) {
        this.markRead = Objects.requireNonNull(markRead, "markRead");
        this.age = Objects.requireNonNull(age, "age");
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

        // Stops at the first read that is not stored: with the store down, every later call would wait
        // out its own connection timeout, holding the session, and fail the same way.
        Runnable onMarkAll = () -> {
            List<String> unreadIds = NotificationSession.getAll().stream()
                    .filter(n -> !"READ".equals(n.status()))
                    .map(NotificationDTO::notificationId)
                    .filter(id -> id != null && !id.isEmpty())
                    .toList();
            for (String id : unreadIds) {
                if (!showReadOnceStored(id)) {
                    break;
                }
            }
            refresh();
        };

        java.util.function.Consumer<String> onMarkOne = notifId -> {
            showReadOnceStored(notifId);
            refresh();
        };

        add(new LkPopover(bell, LkNotifPanel.fromDTOs(notifs, onMarkAll, onMarkOne, age))
                .position(PopoverPosition.BOTTOM_END));
    }

    // Shows the item read only once the store has it read, and a refusal's reason as a toast. Returns
    // whether the read was stored.
    private boolean showReadOnceStored(String notificationId) {
        return switch (markRead.apply(notificationId)) {
            case MarkReadOutcome.Success success -> {
                NotificationSession.markRead(notificationId);
                yield true;
            }
            case MarkReadOutcome.Failure failure -> {
                Toasts.failure(failure.reason());
                yield false;
            }
            case MarkReadOutcome.NotAuthenticated notAuthenticated -> false;
        };
    }
}
