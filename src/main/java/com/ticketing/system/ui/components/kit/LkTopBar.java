package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.popover.PopoverPosition;
import com.vaadin.flow.router.RouterLink;

import java.util.List;

/**
 * Top app bar — slot-based fluent API. Compose by chaining
 * {@link #brand(String)}, {@link #nav}, {@link #searchDefault()},
 * {@link #bellDefault(boolean)}, {@link #account}, etc. Items render
 * in call order (DOM order); the right slot is auto-margined to the
 * right edge by the kit's {@code .lk-topbar-right} CSS.
 *
 * <p>Three shell variants — {@link Variant#MAIN} (buyer, blue),
 * {@link Variant#ADMIN} (organizer crumb, blue), {@link Variant#PLATFORM}
 * (system admin, orange).
 */
public class LkTopBar extends Header {

    public enum Variant { MAIN, ADMIN, PLATFORM }
    public record NavItem(String label, Class<? extends Component> target) { }

    private final Variant variant;
    private final Span rightSlot = new Span();
    private boolean rightAttached;

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

    public LkTopBar brand(String text) { return brand(text, null, null); }

    public LkTopBar brand(String text, String crumb) { return brand(text, crumb, null); }

    /** Brand line that navigates to {@code target} when clicked. */
    public LkTopBar brand(String text, Class<? extends Component> target) {
        return brand(text, null, target);
    }

    public LkTopBar brand(String text, String crumb, Class<? extends Component> target) {
        Span brand = new Span();
        brand.addClassName("lk-brand");
        brand.add(new LkIcon("ticket", 20));
        Span name = new Span();
        name.getElement().setProperty("innerHTML", "<b>" + escape(text) + "</b>");
        brand.add(name);
        if (crumb != null) {
            Span c = new Span(crumb);
            c.addClassName("lk-brand-crumb");
            brand.add(c);
        }
        if (target != null) {
            brand.getStyle().set("cursor", "pointer");
            brand.getElement().addEventListener("click", e ->
                com.vaadin.flow.component.UI.getCurrent().navigate(target));
        }
        add(brand);
        return this;
    }

    public LkTopBar nav(List<NavItem> items, String activeLabel) {
        Span nav = new Span();
        nav.addClassName("lk-topnav");
        for (NavItem item : items) {
            if (item.target != null) {
                RouterLink link = new RouterLink(item.label, item.target);
                link.addClassName("lk-topnav-link");
                if (item.label.equals(activeLabel)) link.addClassName("on");
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

    public LkTopBar search(Component panel) {
        Span trigger = new Span();
        trigger.addClassName("lk-topbar-search");
        trigger.add(new LkIcon("search", 16));
        Span txt = new Span("Search events, artists, venues…");
        txt.addClassName("lk-search-txt");
        trigger.add(txt);
        LkPopover pop = new LkPopover(trigger, panel)
            .position(PopoverPosition.BOTTOM_END).width("392px");
        pop.addClassName("lk-search-wrap");
        add(pop);
        return this;
    }

    public LkTopBar searchDefault() { return search(LkSearchPanel.defaults()); }

    /** Add a single right-aligned text/icon link (e.g. "← Back to site"). */
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

    public LkTopBar bell(Component panel, String badgeCount, String ariaLabel) {
        ensureRightSlot();
        NativeButton bell = new NativeButton();
        bell.addClassName("lk-bell");
        bell.getElement().setAttribute("aria-label", ariaLabel == null ? "Notifications" : ariaLabel);
        bell.add(new LkIcon("bell", 18));
        if (badgeCount != null && !badgeCount.isEmpty()) {
            Span badge = new Span(badgeCount);
            badge.addClassName("lk-bell-badge");
            bell.add(badge);
        }
        LkPopover pop = new LkPopover(bell, panel).position(PopoverPosition.BOTTOM_END);
        rightSlot.add(pop);
        return this;
    }

    /** Slot a pre-built bell component (e.g. {@code NotificationBellComponent}) into the right slot. */
    public LkTopBar bell(Component bellComponent) {
        ensureRightSlot();
        rightSlot.add(bellComponent);
        return this;
    }

    public LkTopBar bellDefault(boolean adminVariant) {
        return bell(adminVariant ? LkNotifPanel.admin() : LkNotifPanel.buyer(),
                    adminVariant ? "5" : "3",
                    adminVariant ? "Admin alerts" : "Notifications");
    }

    /**
     * Cart trigger — bell-shaped icon button that navigates to {@code target}.
     * Shows an item-count badge when {@code itemCount > 0} and a live-ticking
     * timer chip when {@code shortestDeadlineMs} is in the future (the
     * earliest expiry across cart lines). Both decorations vanish when the
     * cart is empty / no item still has a TTL.
     */
    public LkTopBar cart(Class<? extends Component> target, int itemCount, Long shortestDeadlineMs) {
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

        if (shortestDeadlineMs != null && shortestDeadlineMs > System.currentTimeMillis()) {
            Span chip = new Span();
            chip.addClassName("bz-timer");
            chip.getStyle().set("margin-left", "6px");
            chip.add(new LkIcon("clock", 13));
            Span text = new Span(" --:--");
            chip.add(text);
            double endMs = (double) shortestDeadlineMs.longValue();
            text.getElement().executeJs(
                "const text = this;" +
                "const chip = text.closest('.bz-timer');" +
                "const end  = $0;" +
                "function pad(n){return String(n).padStart(2,'0');}" +
                "function tick(){" +
                "  const s = Math.max(0, Math.floor((end - Date.now())/1000));" +
                "  text.textContent = ' ' + pad(Math.floor(s/60)) + ':' + pad(s%60);" +
                "  if (s <= 180 && chip) chip.classList.add('urgent');" +
                "  if (s > 0) setTimeout(tick, 1000);" +
                "  else if (chip) chip.remove();" +
                "}" +
                "tick();",
                endMs);
            rightSlot.add(chip);
        }
        return this;
    }

    public LkTopBar account(String initials, String tooltip, Component menu) {
        return account(initials, tooltip, menu, null, null);
    }

    public LkTopBar account(String initials, String tooltip, Component menu, String bg, String fg) {
        ensureRightSlot();
        NativeButton avatar = new NativeButton(initials);
        avatar.addClassName("lk-avatar");
        if (bg != null) avatar.getStyle().set("background", bg);
        if (fg != null) avatar.getStyle().set("color", fg);
        if (tooltip != null) avatar.getElement().setAttribute("title", tooltip);
        LkPopover pop = new LkPopover(avatar, menu).position(PopoverPosition.BOTTOM_END);
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
