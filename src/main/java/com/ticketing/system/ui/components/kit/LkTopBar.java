package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Nav;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.popover.PopoverPosition;
import com.vaadin.flow.router.RouterLink;

import java.util.List;

/**
 * Top app bar — slot-based fluent API. Compose by chaining
 * {@link #brand(String)}, {@link #nav}, {@link #cart}, {@link #bell(Component)},
 * {@link #account}, etc. Items render
 * in call order (DOM order); the right slot is auto-margined to the
 * right edge by the kit's {@code .lk-topbar-right} CSS.
 *
 * <p>Three shell variants — {@link Variant#MAIN} (buyer and owner workspace, blue),
 * {@link Variant#ADMIN}, and {@link Variant#PLATFORM} (system admin, orange). Only
 * {@code PLATFORM} changes the bar's styling; {@code ADMIN} is styled as {@code MAIN} is.
 */
public class LkTopBar extends Header {

    /** The shell the bar belongs to; see the class comment. */
    public enum Variant { MAIN, ADMIN, PLATFORM }

    /**
     * One link of the main navigation.
     *
     * @param label  the link's text, which {@link LkTopBar#nav} matches against the active label
     * @param target the view the link opens; {@code null} makes it plain text
     */
    public record NavItem(String label, Class<? extends Component> target) { }

    // Accessible names of the popover panels (#188).
    private static final String SEARCH_PANEL = "Search";
    private static final String NOTIFICATIONS = "Notifications";
    private static final String ACCOUNT_PANEL = "Account menu";
    private static final String HOLD_CHIP_NAME = "Cart held for";
    private static final String ACCOUNT_NAME_SEPARATOR = ", ";
    // Names the main navigation landmark, to tell it from a shell's side navigation (#188).
    private static final String MAIN_NAV = "Main";

    private final Variant variant;
    private final Span rightSlot = new Span();
    private boolean rightAttached;

    /** An empty bar of the given shell variant; add its parts by chaining. */
    public LkTopBar(Variant variant) {
        this.variant = variant;
        addClassName("lk-topbar");
        if (variant == Variant.PLATFORM) addClassName("lk-topbar-admin");
        getStyle().set("width", "100%").set("box-sizing", "border-box");
        rightSlot.addClassName("lk-topbar-right");
    }

    // ---------------------------------------------------------------------
    // Left / centre slots
    // ---------------------------------------------------------------------

    /** Adds an inert brand line; see {@link #brand(String, String, Class)}. */
    public LkTopBar brand(String text) { return brand(text, null, null); }

    /** Adds an inert brand line with a crumb; see {@link #brand(String, String, Class)}. */
    public LkTopBar brand(String text, String crumb) { return brand(text, crumb, null); }

    /** Brand line that navigates to {@code target} when clicked. */
    public LkTopBar brand(String text, Class<? extends Component> target) {
        return brand(text, null, target);
    }

    /**
     * Adds the brand line. With a {@code target} it is a link there, reachable and announced
     * as one (#188; WCAG 2.1.1, 4.1.2; {@code TopBarA11yTest}); without one it is inert text.
     *
     * @param text   the brand name
     * @param crumb  text after the name, such as " › Workspace", or {@code null}
     * @param target the view the brand links to, or {@code null} for none
     * @return this top bar, for chaining
     */
    public LkTopBar brand(String text, String crumb, Class<? extends Component> target) {
        Component brand = target == null ? new Span() : new RouterLink("", target);
        brand.getElement().getClassList().add("lk-brand");
        Span name = new Span();
        name.getElement().setProperty("innerHTML", "<b>" + escape(text) + "</b>");
        brand.getElement().appendChild(new LkIcon("ticket", 20).getElement(), name.getElement());
        if (crumb != null) {
            Span c = new Span(crumb);
            c.addClassName("lk-brand-crumb");
            brand.getElement().appendChild(c.getElement());
        }
        add(brand);
        return this;
    }

    /**
     * Adds the main navigation links, in a {@code nav} landmark named "Main"; the link to
     * {@code activeLabel} is highlighted and marked as the current page (#188; WCAG 1.3.1, 4.1.2).
     *
     * @param items       the links, in order
     * @param activeLabel the label of the link to the page shown, or {@code null} for none
     * @return this top bar, for chaining
     */
    public LkTopBar nav(List<NavItem> items, String activeLabel) {
        Nav nav = new Nav();
        nav.addClassName("lk-topnav");
        nav.setAriaLabel(MAIN_NAV);
        for (NavItem item : items) {
            if (item.target != null) {
                RouterLink link = new RouterLink(item.label, item.target);
                link.addClassName("lk-topnav-link");
                if (item.label.equals(activeLabel)) {
                    link.addClassName("on");
                    link.getElement().setAttribute(Lk.ARIA_CURRENT, Lk.CURRENT_PAGE);
                }
                nav.add(link);
            } else {
                Span a = new Span(item.label);
                a.addClassName("lk-topnav-link");
                if (item.label.equals(activeLabel)) a.addClassName("on");
                nav.add(a);
            }
        }
        add(nav);
        return this;
    }

    /**
     * Adds the search trigger, which opens {@code panel} in a popup named "Search".
     *
     * @param panel the popup's content, such as a live {@link LkSearchPanel}
     */
    public LkTopBar search(Component panel) {
        Span trigger = new Span();
        trigger.addClassName("lk-topbar-search");
        trigger.add(new LkIcon("search", 16));
        Span txt = new Span("Search events, artists, venues…");
        txt.addClassName("lk-search-txt");
        trigger.add(txt);
        LkPopover pop = new LkPopover(SEARCH_PANEL, trigger, panel)
            .position(PopoverPosition.BOTTOM_END).width("392px");
        pop.addClassName("lk-search-wrap");
        add(pop);
        return this;
    }

    /** Adds the search trigger with {@link LkSearchPanel#defaults()}, fixed sample content. */
    public LkTopBar searchDefault() { return search(LkSearchPanel.defaults()); }

    /**
     * Add a single right-aligned text/icon link (e.g. "← Back to site").
     *
     * @param label    the link's text
     * @param iconName the {@link LkIcon} glyph name before the text; {@code null} for none
     * @param target   the view the link opens; {@code null} makes it plain text
     */
    public LkTopBar rightLink(String label, String iconName, Class<? extends Component> target) {
        Span spacer = new Span();
        spacer.getStyle().set("flex", "1");
        add(spacer);
        if (target != null) {
            RouterLink link = new RouterLink();
            link.setRoute(target);
            decorateRightLink(link, label, iconName);
            add(link);
        } else {
            Span a = new Span();
            decorateRightLink(a, label, iconName);
            add(a);
        }
        return this;
    }

    private static void decorateRightLink(Component link, String label, String iconName) {
        link.getElement().getClassList().add("lk-topnav-link");
        link.getElement().getClassList().add("on");
        link.getElement().getStyle()
            .set("opacity", ".92")
            .set("display", "inline-flex")
            .set("align-items", "center")
            .set("gap", "5px");
        if (iconName != null) link.getElement().appendChild(new LkIcon(iconName, 14).getElement());
        link.getElement().appendChild(new Span(label).getElement());
    }

    /**
     * Add an icon+label action into the right slot, next to the bell / account.
     * Unlike {@link #rightLink} this adds no flex spacer, so several actions sit
     * together at the upper-right edge (the slot is auto-margined right by CSS).
     */
    public LkTopBar rightAction(String label, String iconName, Class<? extends Component> target) {
        ensureRightSlot();
        if (target != null) {
            RouterLink link = new RouterLink();
            link.setRoute(target);
            decorateRightLink(link, label, iconName);
            rightSlot.add(link);
        } else {
            Span a = new Span();
            decorateRightLink(a, label, iconName);
            rightSlot.add(a);
        }
        return this;
    }

    // ---------------------------------------------------------------------
    // Right slot — bell / account / guest actions
    // ---------------------------------------------------------------------

    /**
     * Adds a bell whose popup shows a fixed {@code panel}; the bell is not updated afterwards.
     *
     * @param panel      the popup's content
     * @param badgeCount the unread count shown on the bell; {@code null} or empty shows no badge
     * @param ariaLabel  the bell's and popup's accessible name; {@code null} for "Notifications"
     */
    public LkTopBar bell(Component panel, String badgeCount, String ariaLabel) {
        ensureRightSlot();
        NativeButton bell = new NativeButton();
        bell.addClassName("lk-bell");
        String name = ariaLabel == null ? NOTIFICATIONS : ariaLabel;
        bell.getElement().setAttribute("aria-label", name);
        bell.add(new LkIcon("bell", 18));
        if (badgeCount != null && !badgeCount.isEmpty()) {
            Span badge = new Span(badgeCount);
            badge.addClassName("lk-bell-badge");
            bell.add(badge);
        }
        LkPopover pop = new LkPopover(name, bell, panel).position(PopoverPosition.BOTTOM_END);
        rightSlot.add(pop);
        return this;
    }

    /** Slot a pre-built bell component (e.g. {@code NotificationBellComponent}) into the right slot. */
    public LkTopBar bell(Component bellComponent) {
        ensureRightSlot();
        rightSlot.add(bellComponent);
        return this;
    }

    /**
     * Adds a bell with fixed sample notifications ({@link LkNotifPanel#buyer()} or
     * {@link LkNotifPanel#admin()}) and a fixed badge count.
     *
     * @param adminVariant whether to show the admin sample instead of the buyer one
     */
    public LkTopBar bellDefault(boolean adminVariant) {
        return bell(adminVariant ? LkNotifPanel.admin() : LkNotifPanel.buyer(),
                    adminVariant ? "5" : "3",
                    adminVariant ? "Admin alerts" : NOTIFICATIONS);
    }

    /**
     * Cart trigger — bell-shaped icon button that navigates to {@code target}.
     * Shows an item-count badge when {@code itemCount > 0} and a live-ticking
     * timer chip while the cart's hold has time left. Both decorations vanish
     * when the cart is empty / no item still has a TTL.
     *
     * @param target      the view the button opens, or {@code null} for none
     * @param itemCount   the cart's item count
     * @param secondsLeft the seconds the hold has left, computed on the server's clock (ADR-008), or
     *                    {@code null} for no hold; zero or less shows no timer
     */
    public LkTopBar cart(Class<? extends Component> target, int itemCount, Long secondsLeft) {
        ensureRightSlot();
        NativeButton btn = new NativeButton();
        btn.addClassName("lk-bell");
        btn.getElement().setAttribute("aria-label",
            "Shopping cart" + (itemCount > 0 ? " · " + itemCount + " items" : ""));
        btn.add(new LkIcon("cart", 18));
        if (itemCount > 0) {
            Span badge = new Span(String.valueOf(itemCount));
            badge.addClassName("lk-bell-badge");
            btn.add(badge);
        }
        if (target != null) {
            btn.addClickListener(e -> com.vaadin.flow.component.UI.getCurrent().navigate(target));
        }
        rightSlot.add(btn);

        if (secondsLeft != null && secondsLeft > 0) {
            Span chip = new Span();
            chip.addClassName("bz-timer");
            chip.getStyle().set("margin-left", "6px");
            chip.add(new LkIcon("clock", 13));
            // The clock icon says what the time is to a sighted user; the hidden words say it to a screen
            // reader (#188, WCAG 1.1.1). Not live: a region would read every tick.
            Span what = new Span(HOLD_CHIP_NAME);
            what.addClassName(Lk.VISUALLY_HIDDEN);
            chip.add(what);
            Span text = new Span(" --:--");
            chip.add(text);
            rightSlot.add(chip);
            LkCountdown.start(text.getElement(), secondsLeft, " ");
        }
        return this;
    }

    /** Adds the account button with the theme's avatar colours; see {@link #account(String, String, Component, String, String)}. */
    public LkTopBar account(String initials, String tooltip, Component menu) {
        return account(initials, tooltip, menu, null, null);
    }

    /**
     * Adds the avatar button, which opens {@code menu} in a popup named "Account menu".
     *
     * @param initials the initials drawn in the avatar; hidden from screen readers
     * @param tooltip  the user's name, shown as the tooltip and appended to the button's accessible name;
     *                 {@code null} for neither
     * @param menu     the popup's content, such as an {@link LkAccountMenu}
     * @param bg       a CSS background colour for the avatar; {@code null} for the theme's
     * @param fg       a CSS text colour for the initials; {@code null} for the theme's
     */
    public LkTopBar account(String initials, String tooltip, Component menu, String bg, String fg) {
        ensureRightSlot();
        NativeButton avatar = new NativeButton();
        avatar.addClassName("lk-avatar");
        // The initials say nothing to a screen reader; the name says what the button opens and whose it is.
        Span shownInitials = new Span(initials);
        shownInitials.getElement().setAttribute(Lk.ARIA_HIDDEN, "true");
        avatar.add(shownInitials);
        avatar.getElement().setAttribute(Lk.ARIA_LABEL,
            tooltip == null ? ACCOUNT_PANEL : ACCOUNT_PANEL + ACCOUNT_NAME_SEPARATOR + tooltip);
        if (bg != null) avatar.getStyle().set("background", bg);
        if (fg != null) avatar.getStyle().set("color", fg);
        if (tooltip != null) avatar.getElement().setAttribute("title", tooltip);
        LkPopover pop = new LkPopover(ACCOUNT_PANEL, avatar, menu).position(PopoverPosition.BOTTOM_END);
        rightSlot.add(pop);
        return this;
    }

    /** "Sign in" link + "Register" ghost button for guest sessions. */
    public LkTopBar guestActions(Class<? extends Component> signInTarget,
                                 Class<? extends Component> registerTarget) {
        ensureRightSlot();
        RouterLink signIn = new RouterLink("Sign in", signInTarget);
        signIn.addClassName("lk-topnav-link");
        signIn.addClassName("on");
        signIn.getStyle().set("opacity", ".92");
        RouterLink reg = new RouterLink("Register", registerTarget);
        reg.addClassName("lk-btn");
        reg.addClassName(LkBtn.GHOST_LIGHT);
        reg.addClassName("lk-btn-s");
        rightSlot.add(signIn, reg);
        return this;
    }

    /** The shell variant this bar was built for. */
    public Variant getVariant() { return variant; }

    private void ensureRightSlot() {
        if (rightAttached) return;
        add(rightSlot);
        rightAttached = true;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
