package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/**
 * Account dropdown — avatar / name / subline header + nav menu. The shells build
 * their own {@link LkMenu} and use the constructor; {@link #buyer(String, String, String)}
 * and {@link #admin(String, String)} build menus of fixed items that do nothing when clicked.
 */
public class LkAccountMenu extends Div {

    /**
     * @param avatarInitials the initials in the avatar circle
     * @param name           the user's name, in the header
     * @param subline        the line under the name, such as a role or an email
     * @param menu           the menu shown under the header
     * @param avatarBg       a CSS background colour for the avatar; {@code null} for the theme's
     * @param avatarFg       a CSS text colour for the initials; {@code null} for the theme's
     */
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

    /**
     * A menu of buyer items with the email as the subline. The items have no click handlers.
     *
     * @param initials the initials in the avatar circle
     * @param name     the user's name
     * @param email    shown under the name
     */
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

    /**
     * A menu of platform-admin items, with the subline "System administrator" and an orange-on-white
     * avatar. The items have no click handlers.
     *
     * @param initials the initials in the avatar circle
     * @param name     the admin's name
     */
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
