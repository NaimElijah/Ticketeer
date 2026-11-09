package com.ticketing.system.ui.layouts;

import com.ticketing.system.ui.components.NotificationBellComponent;
import com.ticketing.system.ui.presenters.notifications.NotificationBellPresenter;
import com.ticketing.system.ui.components.kit.LkAccountMenu;
import com.ticketing.system.ui.components.kit.LkMenu;
import com.ticketing.system.ui.components.kit.LkSideNav;
import com.ticketing.system.ui.components.kit.LkTopBar;
import com.ticketing.system.ui.security.SignOutFlow;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.support.DisplayLocale;
import com.ticketing.system.ui.views.admin.AdminComplaintQueueView;
import com.ticketing.system.ui.views.admin.AdminInboxView;
import com.ticketing.system.ui.views.admin.AdminSendMessagesView;
import com.ticketing.system.ui.views.admin.AdminDashboardView;
import com.ticketing.system.ui.views.admin.GlobalHistoryView;
import com.ticketing.system.ui.views.admin.OrganizationalTreeView;
import com.ticketing.system.ui.views.admin.SystemAnalyticsView;
import com.ticketing.system.ui.views.auth.AdminLoginView;
import com.ticketing.system.ui.views.landing.LandingView;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;

import java.util.List;
import java.util.Map;

/**
 * Platform-admin shell — orange "Event Ticket Platform · Admin" top bar
 * with a single-section drawer of admin-only items. Reached after
 * signing in at {@link AdminLoginView} against the persisted admin pool,
 * which issues an admin token; signing out lands there again.
 * Every view inside is gated by an admin {@link
 * com.ticketing.system.ui.security.Capability}, so members
 * who somehow type the URL bounce to the admin sign-in form.
 */
public class PlatformAdminLayout extends AppLayout implements AfterNavigationObserver {

    // Neutral, so it stays true whether the drawer is open or closed: the toggle does both (#188).
    private static final String DRAWER_TOGGLE_NAME = "Navigation menu";

    private static final Map<Class<?>, String> ADMIN_LABELS = Map.of(
        AdminDashboardView.class,      "Admin Workspace",
        SystemAnalyticsView.class,     "System Analytics",
        GlobalHistoryView.class,       "Global History",
        OrganizationalTreeView.class,  "Organizational Tree",
        AdminSendMessagesView.class,   "Send Messages",
        AdminComplaintQueueView.class, "Complaint Queue"
    );

    private LkTopBar topBar;
    private LkSideNav adminNav;
    private final SignOutFlow signOutFlow;
    private final NotificationBellPresenter bellPresenter;

    /**
     * @param signOutFlow   runs when the admin picks "Sign Out" in the account menu
     * @param bellPresenter renders a notification's age; its mark-read refuses an admin session
     */
    public PlatformAdminLayout(SignOutFlow signOutFlow, NotificationBellPresenter bellPresenter) {
        this.signOutFlow = signOutFlow;
        this.bellPresenter = bellPresenter;
        // First, so it is the first Tab stop; the top bar is re-added after it on every navigation.
        addToNavbar(PageFocus.skipLink());
        // Opens the drawer once a narrow window or zoom collapses it (#188); added before the top bar,
        // which is re-added on every navigation, so it stays ahead of it.
        DrawerToggle drawerToggle = new DrawerToggle();
        drawerToggle.setAriaLabel(DRAWER_TOGGLE_NAME);
        addToNavbar(drawerToggle);
        rebuildTopBar();
        buildDrawerOnce();
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        rebuildTopBar();
        adminNav.setActive(findLabel(event));
        PageFocus.afterNavigation(getContent());
    }

    private void rebuildTopBar() {
        if (topBar != null) topBar.getElement().removeFromParent();
        String name = AuthSession.isAdmin() ? AuthSession.displayName() : "Admin";
        topBar = new LkTopBar(LkTopBar.Variant.PLATFORM)
            // No navigation target — the brand is inert; "Back to Site" (right) is the way out.
            .brand("Event Ticket Platform", " · Admin")
            .rightLink("Back to Site", "arrowLeft", LandingView.class)
            .bell(new NotificationBellComponent(bellPresenter::markRead, bellPresenter::relativeTime))
            .account(initials(name), name, buildAdminMenu(name), "#fff", "#c2410c");
        addToNavbar(topBar);
    }

    private void buildDrawerOnce() {
        adminNav = new LkSideNav("Admin").items(List.of(
            new LkSideNav.Item("building", "Admin Workspace",     AdminDashboardView.class),
            new LkSideNav.Item("chart",    "System Analytics",    SystemAnalyticsView.class),
            new LkSideNav.Item("chart",    "Global History",      GlobalHistoryView.class),
            new LkSideNav.Item("org",      "Organizational Tree", OrganizationalTreeView.class),
            new LkSideNav.Item("comment",  "Send Messages",       AdminSendMessagesView.class),
            new LkSideNav.Item("warning",  "Complaint Queue",     AdminComplaintQueueView.class)
        ), null).platform();

        Div drawer = new Div();
        drawer.add(adminNav);
        addToDrawer(drawer);
    }

    private LkAccountMenu buildAdminMenu(String name) {
        LkMenu menu = new LkMenu(
            new LkMenu.Item("chart",     "System Analytics").onClick(() -> UI.getCurrent().navigate(SystemAnalyticsView.class)),
            new LkMenu.Item("comment",   "Admin Inbox").onClick(() -> UI.getCurrent().navigate(AdminInboxView.class)),
            new LkMenu.Divider(),
            new LkMenu.Item("arrowLeft", "Back to Site").onClick(() -> UI.getCurrent().navigate(LandingView.class)),
            new LkMenu.Divider(),
            new LkMenu.Item("logout",    "Sign Out").danger().onClick(() -> {
                signOutFlow.execute();
                UI.getCurrent().navigate(AdminLoginView.class);
            })
        );
        return new LkAccountMenu(initials(name), name, "System administrator", menu, "#fff", "#c2410c");
    }

    private String findLabel(AfterNavigationEvent event) {
        for (HasElement el : event.getActiveChain()) {
            String label = ADMIN_LABELS.get(el.getClass());
            if (label != null) return label;
        }
        return null;
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) return "??";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            String p = parts[0];
            return p.substring(0, Math.min(2, p.length())).toUpperCase(DisplayLocale.PAGE);
        }
        return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase(DisplayLocale.PAGE);
    }
}
