package com.ticketing.system.Presentation.components.kit;
import com.ticketing.system.identity.domain.Admin;

import com.ticketing.system.Core.Application.dto.NotificationDTO;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Notification dropdown panel — header + scrollable list + footer link.
 * Ports the React {@code NotifPanel}. Static factory methods
 * {@link #buyer()} and {@link #admin()} produce the two reference
 * variants populated with the mock data from {@code lumo-kit.jsx}.
 */
public class LkNotifPanel extends Div {

    public record Item(String notificationId, String iconName, String title, String body, String time, boolean unread, Runnable onClick) { }

    private final NativeButton markAll;

    public LkNotifPanel(String headerTitle, List<Item> items, String footerLabel) {
        this(headerTitle, items, footerLabel, null);
    }

    public LkNotifPanel(String headerTitle, List<Item> items, String footerLabel, Consumer<String> onRowClick) {
        addClassName("lk-notif");

        Div head = new Div();
        head.addClassName("lk-notif-h");
        Span title = new Span();
        title.getElement().setProperty("innerHTML", "<b>" + escape(headerTitle) + "</b>");
        markAll = new NativeButton("Mark All Read");
        markAll.addClassName("lk-link-btn");
        head.add(title, markAll);
        add(head);

        Div list = new Div();
        list.addClassName("lk-notif-list");
        for (Item it : items) {
            NativeButton row = new NativeButton();
            row.addClassName("lk-notif-item");
            if (it.unread) row.addClassName("unread");

            Span icoSlot = new Span();
            icoSlot.addClassName("lk-notif-ic");
            icoSlot.add(new LkIcon(it.iconName, 17));

            Span body = new Span();
            body.addClassName("lk-notif-body");
            Span t = new Span(it.title); t.addClassName("lk-notif-t");
            Span d = new Span(it.body);  d.addClassName("lk-notif-d");
            Span tm = new Span(it.time); tm.addClassName("lk-notif-time");
            body.add(t, d, tm);

            if (it.onClick != null) {
                row.addClickListener(e -> it.onClick().run());
            }
            row.add(icoSlot, body);
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

        NativeButton foot = new NativeButton(footerLabel);
        foot.addClassName("lk-notif-foot");
        add(foot);
    }

    public LkNotifPanel onMarkAllRead(Runnable handler) {
        markAll.addClickListener(e -> handler.run());
        return this;
    }

    /** Hide the "Mark All Read" action — used when there is nothing to mark. */
    public LkNotifPanel hideMarkAll() {
        markAll.setVisible(false);
        return this;
    }

    public static LkNotifPanel buyer() {
        return new LkNotifPanel("Notifications", List.of(
            new Item("", "ticket", "Your Coldplay seats are confirmed", "Receipt #TKT-20847 · $504.00 paid", "2m", true, null),
            new Item("", "clock",  "Cart expiring soon",                "Hapoel TLV · 2 tickets release in 2 min", "5m", true, null),
            new Item("", "star",   "Price drop on a watched event",     "Mashina · 35-Year Tour — now from $150", "1h", true, null),
            new Item("", "card",   "Refund processed",                  "Othello at Habima · $120.00 returned", "Yesterday", false, null),
            new Item("", "bell",   "New from Live Nation Israel",       "Eden Hason · Live — on sale now", "2d", false, null)
        ), "View all notifications");
    }

    public static LkNotifPanel admin() {
        return new LkNotifPanel("Admin alerts", List.of(
            new Item("", "comment",  "5 open complaints",          "Oldest is 2 days old — review the queue",        "2d", true,  null),
            new Item("", "chart",    "Daily sales report ready",   "Yesterday: $48,250 across 12 companies",         "6h", true,  null),
            new Item("", "building", "New company registration",   "BlueWave Productions awaiting review",           "8h", true,  null),
            new Item("", "policy",   "Policy flagged for review",  "Coldplay · MOTS age-gate needs a check",         "1d", true,  null),
            new Item("", "warning",  "Refund spike detected",      "Othello at Habima — 14 refunds in 1h",           "1d", true,  null)
        ), "View admin activity feed");
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
        Map.entry("MANAGER_REVOKED",                 "Role revoked"),
        Map.entry("DIRECT_MESSAGE",                  "New message"),
        Map.entry("PURCHASE_FAILED",                 "Purchase failed"),
        Map.entry("TICKET_RESERVATION_SUCCESS",      "Reservation confirmed"),
        Map.entry("TICKET_RESERVATION_FAILURE",      "Reservation failed"),
        Map.entry("REMOVE_TICKET_RESERVATION_SUCCESS", "Reservation removed"),
        Map.entry("REMOVE_TICKET_RESERVATION_FAILURE", "Failed to remove reservation")
    );

    /** Build a real notification panel from the DTOs delivered on login (UC-37). */
    public static LkNotifPanel fromDTOs(List<NotificationDTO> notifications,
                                         Runnable onMarkAllRead,
                                         Consumer<String> onRowClick) {
        if (notifications == null || notifications.isEmpty()) {
            return new LkNotifPanel("Notifications", List.of(
                new Item("", "bell", "You're all caught up", "No new notifications", "", false, null)
            ), "View all notifications").hideMarkAll();
        }
        List<Item> items = notifications.stream()
            .map(n -> new Item(
                n.notificationId(),
                TYPE_ICON.getOrDefault(n.type(), "bell"),
                TYPE_TITLE.getOrDefault(n.type(), "Notification"),
                n.message() == null ? "" : n.message(),
                relativeTime(n.createdAt()),
                !"READ".equals(n.status()),
                null
            ))
            .toList();
        LkNotifPanel panel = new LkNotifPanel("Notifications", items, "View all notifications", onRowClick);
        if (onMarkAllRead != null) panel.onMarkAllRead(onMarkAllRead);
        return panel;
    }

    private static String relativeTime(LocalDateTime createdAt) {
        if (createdAt == null) return "";
        LocalDateTime now = LocalDateTime.now();
        long minutes = ChronoUnit.MINUTES.between(createdAt, now);
        if (minutes < 1)   return "Just now";
        if (minutes < 60)  return minutes + "m";
        long hours = ChronoUnit.HOURS.between(createdAt, now);
        if (hours < 24)    return hours + "h";
        long days = ChronoUnit.DAYS.between(createdAt, now);
        if (days == 1)     return "Yesterday";
        return days + "d";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
