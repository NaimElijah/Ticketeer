package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/**
 * Account dropdown — avatar / name / email header + nav menu.
 * Ports {@code AccountMenu}, with {@link #buyer(String, String, String)}
 * and {@link #admin(String, String)} factories matching the reference.
 */
public class LkAccountMenu extends Div {

    public LkAccountMenu(String avatarInitials, String name, String subline,
                         LkMenu menu, String avatarBg, String avatarFg) {
        addClassName("lk-acct");

        Div head = new Div();
        head.addClassName("lk-acct-h");

        Span avatar = new Span(avatarInitials);
        avatar.addClassName("lk-avatar");
        avatar.addClassName("lg");
        if (avatarBg != null) avatar.getStyle().set("background", avatarBg);
        if (avatarFg != null) avatar.getStyle().set("color", avatarFg);

        Div meta = new Div();
        Div nameDiv = new Div(); nameDiv.addClassName("lk-acct-name"); nameDiv.setText(name);
        Div subDiv  = new Div(); subDiv.addClassName("lk-acct-mail");  subDiv.setText(subline);
        meta.add(nameDiv, subDiv);

        head.add(avatar, meta);
        add(head, menu);
    }

    public static LkAccountMenu buyer(String initials, String name, String email) {
        LkMenu menu = new LkMenu(
            new LkMenu.Item("ticket", "My Tickets"),
            new LkMenu.Item("chart",  "Order History"),
            new LkMenu.Item("star",   "Saved Events"),
            new LkMenu.Item("card",   "Payment Methods"),
            new LkMenu.Item("gear",   "Account Settings"),
            new LkMenu.Divider(),
            new LkMenu.Item("building", "Switch to Organizer"),
            new LkMenu.Item("info",     "Help & Support"),
            new LkMenu.Divider(),
            new LkMenu.Item("logout", "Sign Out").danger()
        );
        return new LkAccountMenu(initials, name, email, menu, null, null);
    }

    public static LkAccountMenu admin(String initials, String name) {
        LkMenu menu = new LkMenu(
            new LkMenu.Item("gear",   "Admin Settings"),
            new LkMenu.Item("chart",  "Platform Analytics"),
            new LkMenu.Divider(),
            new LkMenu.Item("arrowLeft", "Back to Buyer Site"),
            new LkMenu.Divider(),
            new LkMenu.Item("logout", "Sign Out").danger()
        );
        return new LkAccountMenu(initials, name, "System administrator", menu, "#fff", "#c2410c");
    }
}
