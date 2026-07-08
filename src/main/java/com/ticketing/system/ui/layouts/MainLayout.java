package com.ticketing.system.ui.layouts;
import com.ticketing.system.identity.domain.Admin;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Buyer-facing shell — composes {@link LkTopBar} with the tickethub
 * design system. Rebuilds the navbar on every navigation via
 * {@link AfterNavigationObserver} so the avatar menu reflects current
 * {@link AuthSession} state and the top-nav highlights the active section.
 *
 * <p>
 * System-admin entry points are deliberately not exposed here — the
 * admin workspace lives at its own endpoint behind a separate sign-in.
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

    private final CartBadgePresenter cartBadgePresenter;
    private LkTopBar topBar;
    private final SignOutFlow signOutFlow;
    private final NotificationBellPresenter bellPresenter;

    public MainLayout(CartBadgePresenter cartBadgePresenter, SignOutFlow signOutFlow,
            NotificationBellPresenter bellPresenter) {
        this.cartBadgePresenter = cartBadgePresenter;
        this.signOutFlow = signOutFlow;
        this.bellPresenter = bellPresenter;
        rebuildTopBar(null);
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        rebuildTopBar(findActiveLabel(event));
    }

    private void rebuildTopBar(String activeLabel) {
        if (topBar != null)
            topBar.getElement().removeFromParent();

        boolean signedIn = AuthSession.isSignedIn();
        String name = signedIn ? AuthSession.displayName() : "Guest";

        int cartSize = 0;
        Long cartDeadlineMs = null;

        VaadinSession session = VaadinSession.getCurrent();
        if (session != null) {
            session.lock();
            try {
                long now = System.currentTimeMillis();
                Long lastFetchMs = (Long) session.getAttribute("cart.fetch.time");
                Integer cachedSize = (Integer) session.getAttribute("cart.size");
                Long cachedDeadlineMs = (Long) session.getAttribute("cart.deadline");

                if (lastFetchMs != null && (now - lastFetchMs) < 10_000 && cachedSize != null) {
                    cartSize = cachedSize;
                    cartDeadlineMs = cachedDeadlineMs;
                } else {
                    // The presenter owns the service call + error handling; the shell only
                    // reads the typed Outcome and keeps caching the result in the Vaadin session.
                    switch (cartBadgePresenter.loadBadge()) {
                        case CartBadgePresenter.Outcome.Cart c -> {
                            cartSize = c.count();
                            if (c.remainingSeconds() > 0) {
                                cartDeadlineMs = now + c.remainingSeconds() * 1000L;
                            }
                        }
                        case CartBadgePresenter.Outcome.Empty ignored -> { /* no cart — leave 0 */ }
                        case CartBadgePresenter.Outcome.Failure ignored -> { /* don't fail topbar render */ }
                    }
                    session.setAttribute("cart.size", cartSize);
                    session.setAttribute("cart.deadline", cartDeadlineMs);
                    session.setAttribute("cart.fetch.time", now);
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
                .cart(CartView.class, cartSize, cartDeadlineMs)
                .bell(new NotificationBellComponent(bellPresenter::markRead));

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
            return p.substring(0, Math.min(2, p.length())).toUpperCase();
        }
        return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
    }
}
