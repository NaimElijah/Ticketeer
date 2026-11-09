package com.ticketing.system.ui.layouts;

import com.ticketing.system.ui.components.NotificationBellComponent;
import com.ticketing.system.ui.presenters.notifications.NotificationBellPresenter;
import com.ticketing.system.ui.presenters.order.CartBadgePresenter;
import com.ticketing.system.ui.components.kit.LkAccountMenu;
import com.ticketing.system.ui.components.kit.LkMenu;
import com.ticketing.system.ui.components.kit.LkTopBar;
import com.ticketing.system.ui.security.Capabilities;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.SignOutFlow;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.CartBadgeCache;
import com.ticketing.system.ui.support.DisplayLocale;
import com.ticketing.system.ui.views.admin.AdminDashboardView;
import com.ticketing.system.ui.views.company.CompanyRegistrationView;
import com.ticketing.system.ui.views.account.MyAccountView;
import com.ticketing.system.ui.views.account.MyInvitationsView;
import com.ticketing.system.ui.views.account.MyProfileView;
import com.ticketing.system.ui.views.account.SupportInboxView;
import com.ticketing.system.ui.views.auth.LoginView;
import com.ticketing.system.ui.views.auth.RegisterView;
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.ticketing.system.ui.views.catalog.EventDetailsView;
import com.ticketing.system.ui.views.landing.LandingView;
import com.ticketing.system.ui.views.company.MyCompaniesView;
import com.ticketing.system.ui.views.company.OwnerDashboardView;
import com.ticketing.system.ui.views.order.CartView;
import com.ticketing.system.ui.views.order.CheckoutView;
import com.ticketing.system.ui.views.order.OrderConfirmationView;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;
import com.vaadin.flow.server.VaadinSession;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Buyer-facing shell — composes {@link LkTopBar} with the tickethub
 * design system. Rebuilds the navbar on every navigation via
 * {@link AfterNavigationObserver} so the avatar menu reflects current
 * {@link AuthSession} state and the top-nav highlights the active section.
 *
 * <p>
 * The nav shows an "Admin" item, to {@link AdminDashboardView}, only to a user
 * holding {@link Capability#ADMIN_WORKSPACE}. The admin workspace has its own
 * shell ({@code PlatformAdminLayout}) behind a separate sign-in.
 */
public class MainLayout extends AppLayout implements AfterNavigationObserver {

    /**
     * View class → top-nav label that should light up while that view is active.
     */
    private static final Map<Class<?>, String> NAV_LABELS = Map.of(
            BrowseEventsView.class, "Browse",
            EventDetailsView.class, "Browse",
            MyAccountView.class, "My Tickets",
            CartView.class, "My Tickets",
            CheckoutView.class, "My Tickets",
            OrderConfirmationView.class, "My Tickets");

    private static final long MILLIS_PER_SECOND = 1000L;

    private final CartBadgePresenter cartBadgePresenter;
    private LkTopBar topBar;
    private final SignOutFlow signOutFlow;
    private final NotificationBellPresenter bellPresenter;
    private final Clock clock;

    /**
     * @param cartBadgePresenter reads the cart for the top bar's badge whenever the cached badge is stale
     * @param signOutFlow        runs when the member picks "Sign Out" in the account menu
     * @param bellPresenter      stores a notification's read mark, and renders its age
     * @param clock              the application clock (ADR-008), for the cart badge's cache age and the
     *                           seconds its countdown has left
     */
    public MainLayout(CartBadgePresenter cartBadgePresenter, SignOutFlow signOutFlow,
            NotificationBellPresenter bellPresenter, Clock clock) {
        this.cartBadgePresenter = cartBadgePresenter;
        this.signOutFlow = signOutFlow;
        this.bellPresenter = bellPresenter;
        this.clock = clock;
        // First, so it is the first Tab stop; the top bar is re-added after it on every navigation.
        addToNavbar(PageFocus.skipLink());
        rebuildTopBar(null);
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        rebuildTopBar(findActiveLabel(event));
        PageFocus.afterNavigation(getContent());
    }

    // The cached hold end is on the server's clock; the browser gets only the seconds left (ADR-008).
    private static Long secondsLeft(Long deadlineMs, long nowMs) {
        return deadlineMs == null ? null : Math.max(0, (deadlineMs - nowMs) / MILLIS_PER_SECOND);
    }

    private void rebuildTopBar(String activeLabel) {
        if (topBar != null)
            topBar.getElement().removeFromParent();

        boolean signedIn = AuthSession.isSignedIn();
        String name = signedIn ? AuthSession.displayName() : "Guest";

        int cartSize = 0;
        Long cartDeadlineMs = null;
        long now = clock.millis();

        VaadinSession session = VaadinSession.getCurrent();
        if (session != null) {
            session.lock();
            try {
                Optional<CartBadgeCache.Badge> cached = CartBadgeCache.fresh(now);
                if (cached.isPresent()) {
                    cartSize = cached.get().size();
                    cartDeadlineMs = cached.get().deadlineMs();
                } else {
                    // The presenter owns the service call + error handling; the shell only
                    // reads the typed Outcome and keeps caching the result in the Vaadin session.
                    switch (cartBadgePresenter.loadBadge()) {
                        case CartBadgePresenter.Outcome.Cart c -> {
                            cartSize = c.count();
                            if (c.remainingSeconds() > 0) {
                                cartDeadlineMs = now + c.remainingSeconds() * MILLIS_PER_SECOND;
                            }
                        }
                        case CartBadgePresenter.Outcome.Empty ignored -> { /* no cart — leave 0 */ }
                        case CartBadgePresenter.Outcome.Failure ignored -> { /* don't fail topbar render */ }
                    }
                    CartBadgeCache.store(new CartBadgeCache.Badge(cartSize, cartDeadlineMs), now);
                }
            } finally {
                session.unlock();
            }
        }

        List<LkTopBar.NavItem> nav = new ArrayList<>();
        nav.add(new LkTopBar.NavItem("Browse", BrowseEventsView.class));
        nav.add(new LkTopBar.NavItem("My Tickets", MyAccountView.class));

        if (Capabilities.has(Capability.ADMIN_WORKSPACE)) {
            nav.add(new LkTopBar.NavItem("Admin", AdminDashboardView.class));
        }

        LkTopBar bar = new LkTopBar(LkTopBar.Variant.MAIN)
                .brand("TicketHub", LandingView.class)
                .nav(nav, activeLabel)
                .cart(CartView.class, cartSize, secondsLeft(cartDeadlineMs, now))
                .bell(new NotificationBellComponent(bellPresenter::markRead, bellPresenter::relativeTime));

        if (signedIn) {
            bar.account(initials(name), name, buildMemberMenu(name));
        } else {
            bar.guestActions(LoginView.class, RegisterView.class);
        }

        topBar = bar;
        addToNavbar(topBar);
    }

    /** Member-persona account menu — wired to {@link AuthSession} + view routes. */
    private LkAccountMenu buildMemberMenu(String name) {
        LkMenu menu = new LkMenu(
                new LkMenu.Item("ticket", "My Account").onClick(() -> UI.getCurrent().navigate(MyAccountView.class)),
                new LkMenu.Item("users", "My Profile").onClick(() -> UI.getCurrent().navigate(MyProfileView.class)),
                new LkMenu.Item("crown", "My Invitations")
                        .onClick(() -> UI.getCurrent().navigate(MyInvitationsView.class)),
                new LkMenu.Item("briefcase", "My Companies")
                        .onClick(() -> UI.getCurrent().navigate(MyCompaniesView.class)),
                new LkMenu.Item("comment", "Support Inbox")
                        .onClick(() -> UI.getCurrent().navigate(SupportInboxView.class)),
                new LkMenu.Divider());

        // Capability-driven owner workspace vs become-an-organizer CTA.
        if (Capabilities.has(Capability.OWNER_WORKSPACE)) {
            menu.add(new LkMenu.Item("building", "Workspace")
                    .onClick(() -> UI.getCurrent().navigate(OwnerDashboardView.class)));
        } else if (Capabilities.has(Capability.REGISTER_COMPANY)) {
            menu.add(new LkMenu.Item("plus", "Become an Organizer")
                    .hint("free")
                    .onClick(() -> UI.getCurrent().navigate(CompanyRegistrationView.class)));
        }

        menu.add(
                new LkMenu.Divider(),
                new LkMenu.Item("logout", "Sign Out").danger().onClick(() -> {
                    signOutFlow.execute();
                    UI.getCurrent().navigate(LoginView.class);
                }));
        return new LkAccountMenu(initials(name), name, "Signed in member", menu, null, null);
    }

    private static String findActiveLabel(AfterNavigationEvent event) {
        for (HasElement el : event.getActiveChain()) {
            String label = NAV_LABELS.get(el.getClass());
            if (label != null)
                return label;
        }
        return null;
    }

    private static String initials(String name) {
        if (name == null || name.isBlank())
            return "??";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            String p = parts[0];
            return p.substring(0, Math.min(2, p.length())).toUpperCase(DisplayLocale.PAGE);
        }
        return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase(DisplayLocale.PAGE);
    }
}
