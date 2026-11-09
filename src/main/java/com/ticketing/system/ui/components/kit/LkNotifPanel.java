package com.ticketing.system.ui.components.kit;

import com.ticketing.system.shared.dto.NotificationDTO;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Notification dropdown panel — header + scrollable list. The panel the application shows is
 * built by {@link #fromDTOs}, from the user's notifications. The static factories {@link #buyer()}
 * and {@link #admin()} build panels of fixed sample notifications that belong to no user.
 *
 * <p>The title is a heading, an unread row says "Unread" in visually hidden text as well as by
 * its tint and dot, and an empty inbox is a message rather than a row that does nothing
 * (#188; WCAG 1.3.1, 1.4.1, 4.1.2; {@code NotificationBellA11yTest}).
 */
public class LkNotifPanel extends Div {

    /**
     * One row of the panel.
     *
     * @param notificationId the notification's id, handed to the panel's row-click callback; {@code null}
     *                       or empty keeps that callback from running for this row
     * @param iconName       the {@link LkIcon} glyph name shown at the row's start
     * @param title          the row's heading
     * @param body           the row's text
     * @param time           the age text, already formatted
     * @param unread         whether the row is shown as unread
     * @param onClick        runs when the row is clicked; {@code null} for none
     */
    public record Item(String notificationId, String iconName, String title, String body, String time, boolean unread, Runnable onClick) { }

    private static final String UNREAD = "Unread";

    private final NativeButton markAll;
    private final Div list = new Div();

    /**
     * A panel whose rows act only through their own {@link Item#onClick()}.
     *
     * @param headerTitle the heading
     * @param items       the rows, in order; an empty list leaves the list empty
     */
    public LkNotifPanel(String headerTitle, List<Item> items) {
        this(headerTitle, items, null);
    }

    /**
     * @param headerTitle the heading
     * @param items       the rows, in order
     * @param onRowClick  receives the id of a clicked row that has one, after that row's own
     *                    {@link Item#onClick()}; {@code null} for none
     */
    public LkNotifPanel(String headerTitle, List<Item> items, Consumer<String> onRowClick) {
        addClassName("lk-notif");

        Div head = new Div();
        head.addClassName("lk-notif-h");
        H2 title = new H2(headerTitle);
        title.addClassName("lk-notif-title");
        markAll = new NativeButton("Mark All Read");
        markAll.addClassName("lk-link-btn");
        head.add(title, markAll);
        add(head);

        list.addClassName("lk-notif-list");
        for (Item it : items) {
            NativeButton row = new NativeButton();
            row.addClassName("lk-notif-item");
            if (it.unread) {
                row.addClassName("unread");
                // The tint and the dot say "unread" only to the eye.
                Span unreadText = new Span(UNREAD);
                unreadText.addClassName(Lk.VISUALLY_HIDDEN);
                row.add(unreadText);
            }

            if (it.onClick != null) {
                row.addClickListener(e -> it.onClick().run());
            }
            addRowContent(row, it);
            if (it.unread) {
                Span dot = new Span();
                dot.addClassName("lk-notif-dot");
                row.add(dot);
            }
            if (onRowClick != null && it.notificationId() != null && !it.notificationId().isEmpty()) {
                String nid = it.notificationId();
                row.addClickListener(e -> onRowClick.accept(nid));
            }
            list.add(row);
        }
        add(list);
    }

    private static void addRowContent(Component row, Item it) {
        Span icoSlot = new Span();
        icoSlot.addClassName("lk-notif-ic");
        icoSlot.add(new LkIcon(it.iconName, 17));

        Span body = new Span();
        body.addClassName("lk-notif-body");
        Span t = new Span(it.title); t.addClassName("lk-notif-t");
        Span d = new Span(it.body);  d.addClassName("lk-notif-d");
        Span tm = new Span(it.time); tm.addClassName("lk-notif-time");
        body.add(t, d, tm);

        row.getElement().appendChild(icoSlot.getElement(), body.getElement());
    }

    // Laid out as a row, but plain text: there is nothing to do with an empty inbox.
    private LkNotifPanel showEmpty(String title, String body) {
        Div message = new Div();
        message.addClassName("lk-notif-item");
        message.addClassName("lk-notif-empty");
        addRowContent(message, new Item("", "bell", title, body, "", false, null));
        list.add(message);
        return this;
    }

    /** Runs {@code handler} when "Mark All Read" is clicked; each call adds another listener. */
    public LkNotifPanel onMarkAllRead(Runnable handler) {
        markAll.addClickListener(e -> handler.run());
        return this;
    }

    /** Hide the "Mark All Read" action — used when there is nothing to mark. */
    public LkNotifPanel hideMarkAll() {
        markAll.setVisible(false);
        return this;
    }

    /** A "Notifications" panel of five fixed sample rows, none tied to a notification id. */
    public static LkNotifPanel buyer() {
        return new LkNotifPanel("Notifications", List.of(
            new Item("", "ticket", "Your Coldplay seats are confirmed", "Receipt #TKT-20847 · $504.00 paid", "2m", true, null),
            new Item("", "clock",  "Cart expiring soon",                "Hapoel TLV · 2 tickets release in 2 min", "5m", true, null),
            new Item("", "star",   "Price drop on a watched event",     "Mashina · 35-Year Tour — now from $150", "1h", true, null),
            new Item("", "card",   "Refund processed",                  "Othello at Habima · $120.00 returned", "Yesterday", false, null),
            new Item("", "bell",   "New from Live Nation Israel",       "Eden Hason · Live — on sale now", "2d", false, null)
        ));
    }

    /** An "Admin alerts" panel of five fixed sample rows, none tied to a notification id. */
    public static LkNotifPanel admin() {
        return new LkNotifPanel("Admin alerts", List.of(
            new Item("", "comment",  "5 open complaints",          "Oldest is 2 days old — review the queue",        "2d", true,  null),
            new Item("", "chart",    "Daily sales report ready",   "Yesterday: $48,250 across 12 companies",         "6h", true,  null),
            new Item("", "building", "New company registration",   "BlueWave Productions awaiting review",           "8h", true,  null),
            new Item("", "policy",   "Policy flagged for review",  "Coldplay · MOTS age-gate needs a check",         "1d", true,  null),
            new Item("", "warning",  "Refund spike detected",      "Othello at Habima — 14 refunds in 1h",           "1d", true,  null)
        ));
    }

    private static final Map<String, String> TYPE_ICON = Map.ofEntries(
        Map.entry("PURCHASE_CONFIRMED",              "ticket"),
        Map.entry("REFUND_ISSUED",                   "card"),
        Map.entry("EVENT_CANCELLED",                 "warning"),
        Map.entry("CART_EXPIRING",                   "clock"),
        Map.entry("EVENT_SOLD_OUT",                  "chart"),
        Map.entry("COMPANY_CLOSED",                  "building"),
        Map.entry("ROLE_CHANGED",                    "users"),
        Map.entry("OWNER_APPOINTMENT_PENDING",       "crown"),
        Map.entry("MANAGER_APPOINTMENT_PENDING",     "crown"),
        Map.entry("MANAGER_REVOKED",                 "warning"),
        Map.entry("DIRECT_MESSAGE",                  "comment"),
        Map.entry("PURCHASE_FAILED",                 "warning"),
        Map.entry("TICKET_RESERVATION_SUCCESS",      "ticket"),
        Map.entry("TICKET_RESERVATION_FAILURE",      "warning"),
        Map.entry("REMOVE_TICKET_RESERVATION_SUCCESS", "ticket"),
        Map.entry("REMOVE_TICKET_RESERVATION_FAILURE", "warning")
    );

    private static final Map<String, String> TYPE_TITLE = Map.ofEntries(
        Map.entry("PURCHASE_CONFIRMED",              "Purchase confirmed"),
        Map.entry("REFUND_ISSUED",                   "Refund processed"),
        Map.entry("EVENT_CANCELLED",                 "Event cancelled"),
        Map.entry("CART_EXPIRING",                   "Cart expiring soon"),
        Map.entry("EVENT_SOLD_OUT",                  "Event sold out"),
        Map.entry("COMPANY_CLOSED",                  "Company closed"),
        Map.entry("ROLE_CHANGED",                    "Role updated"),
        Map.entry("OWNER_APPOINTMENT_PENDING",       "Appointment pending"),
        Map.entry("MANAGER_APPOINTMENT_PENDING",     "Appointment pending"),
        Map.entry("MANAGER_REVOKED",                 "Role revoked"),
        Map.entry("DIRECT_MESSAGE",                  "New message"),
        Map.entry("PURCHASE_FAILED",                 "Purchase failed"),
        Map.entry("TICKET_RESERVATION_SUCCESS",      "Reservation confirmed"),
        Map.entry("TICKET_RESERVATION_FAILURE",      "Reservation failed"),
        Map.entry("REMOVE_TICKET_RESERVATION_SUCCESS", "Reservation removed"),
        Map.entry("REMOVE_TICKET_RESERVATION_FAILURE", "Failed to remove reservation")
    );

    /**
     * Build a real notification panel from the notifications in the session's inbox (UC-37).
     *
     * @param age renders each notification's creation time as a short age ("5m", "Yesterday")
     */
    public static LkNotifPanel fromDTOs(List<NotificationDTO> notifications,
                                         Runnable onMarkAllRead,
                                         Consumer<String> onRowClick,
                                         Function<LocalDateTime, String> age) {
        if (notifications == null || notifications.isEmpty()) {
            return new LkNotifPanel("Notifications", List.of())
                .showEmpty("You're all caught up", "No new notifications")
                .hideMarkAll();
        }
        List<Item> items = notifications.stream()
            .map(n -> new Item(
                n.notificationId(),
                TYPE_ICON.getOrDefault(n.type(), "bell"),
                TYPE_TITLE.getOrDefault(n.type(), "Notification"),
                n.message() == null ? "" : n.message(),
                age.apply(n.createdAt()),
                !"READ".equals(n.status()),
                null
            ))
            .toList();
        LkNotifPanel panel = new LkNotifPanel("Notifications", items, onRowClick);
        if (onMarkAllRead != null) panel.onMarkAllRead(onMarkAllRead);
        return panel;
    }
}
