package com.ticketing.system.ui.layouts;

import com.ticketing.system.ui.components.NotificationBellComponent;
import com.ticketing.system.ui.presenters.notifications.NotificationBellPresenter;
import com.ticketing.system.ui.components.kit.LkAccountMenu;
import com.ticketing.system.ui.components.kit.LkMenu;
import com.ticketing.system.ui.components.kit.LkSideNav;
import com.ticketing.system.ui.components.kit.LkTopBar;
import com.ticketing.system.ui.security.Capabilities;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.SignOutFlow;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.views.account.MyAccountView;
import com.ticketing.system.ui.views.account.MyInvitationsView;
import com.ticketing.system.ui.views.account.MyProfileView;
import com.ticketing.system.ui.views.auth.LoginView;
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.ticketing.system.ui.views.company.CompanyEventListView;
import com.ticketing.system.ui.views.company.CompanyInquiryInboxView;
import com.ticketing.system.ui.views.company.CompanyOrgTreeView;
import com.ticketing.system.ui.views.company.CompanySalesView;
import com.ticketing.system.ui.views.company.ManagerListView;
import com.ticketing.system.ui.views.company.MyCompaniesView;
import com.ticketing.system.ui.views.company.OwnerAppointmentView;
import com.ticketing.system.ui.views.company.OwnerDashboardView;
import com.ticketing.system.ui.views.company.PurchasePolicyEditorView;
import com.ticketing.system.ui.views.landing.LandingView;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Owner workspace shell — main blue topbar with a " › Workspace" crumb
 * and a single-section left drawer of owner-only items. The system-admin
 * shell is a separate layout ({@code PlatformAdminLayout}) reached only
 * through its own sign-in endpoint, so owners never see admin nav and
 * vice versa.
 *
 * <p>The drawer items are filtered against the current user's
 * {@link Capabilities capability set} on every navigation — a manager
 * with only "respond to inquiries" sees just the dashboard + inquiries
 * entries; a founder sees everything. Items that lack a {@code cap()}
 * (the workspace home and "My Companies") are always shown.
 */
public class WorkspaceLayout extends AppLayout implements AfterNavigationObserver {

    /** A drawer entry paired with the capability that gates it ({@code null} = always shown). */
    private record DrawerEntry(LkSideNav.Item item, Capability cap) { }

    private static final List<DrawerEntry> DRAWER_ITEMS = List.of(
        new DrawerEntry(new LkSideNav.Item("building",  "Workspace",         OwnerDashboardView.class),       null),
        new DrawerEntry(new LkSideNav.Item("briefcase", "My Companies",      MyCompaniesView.class),          null),
        new DrawerEntry(new LkSideNav.Item("calendar",  "My Events",         CompanyEventListView.class),     Capability.VIEW_COMPANY_EVENTS),
        new DrawerEntry(new LkSideNav.Item("comment",   "Inquiries",         CompanyInquiryInboxView.class),  Capability.RESPOND_INQUIRIES),
        new DrawerEntry(new LkSideNav.Item("chart",     "Company Sales",     CompanySalesView.class),         Capability.VIEW_COMPANY_SALES),
        new DrawerEntry(new LkSideNav.Item("users",     "Managers",          ManagerListView.class),          Capability.APPOINT_MANAGER),
        new DrawerEntry(new LkSideNav.Item("crown",     "Appoint Co-Owner",  OwnerAppointmentView.class),     Capability.APPOINT_CO_OWNER),
        new DrawerEntry(new LkSideNav.Item("org",       "Organizational Tree", CompanyOrgTreeView.class),     Capability.VIEW_COMPANY_ORG_TREE),
        new DrawerEntry(new LkSideNav.Item("policy",    "Purchase Policies", PurchasePolicyEditorView.class), Capability.EDIT_PURCHASE_POLICIES)
    );

    private static final Map<Class<?>, String> OWNER_LABELS = Map.of(
        OwnerDashboardView.class,       "Workspace",
        MyCompaniesView.class,          "My Companies",
        CompanyEventListView.class,     "My Events",
        CompanyInquiryInboxView.class,  "Inquiries",
        CompanySalesView.class,         "Company Sales",
        ManagerListView.class,          "Managers",
        OwnerAppointmentView.class,     "Appoint Co-Owner",
        CompanyOrgTreeView.class,       "Organizational Tree",
        PurchasePolicyEditorView.class, "Purchase Policies"
    );

    private LkTopBar topBar;
    private LkSideNav ownerNav;
    private final Div drawerWrap = new Div();
    private final SignOutFlow signOutFlow;
    private final NotificationBellPresenter bellPresenter;

    public WorkspaceLayout(SignOutFlow signOutFlow, NotificationBellPresenter bellPresenter) {
        this.signOutFlow = signOutFlow;
        this.bellPresenter = bellPresenter;
        rebuildTopBar();
        addToDrawer(drawerWrap);
        rebuildDrawer(null);
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        rebuildTopBar();
        rebuildDrawer(findLabel(event, OWNER_LABELS));
    }

    private void rebuildTopBar() {
        if (topBar != null) topBar.getElement().removeFromParent();
        String name = AuthSession.isSignedIn() ? AuthSession.displayName() : "Owner";
        topBar = new LkTopBar(LkTopBar.Variant.MAIN)
            .brand("TicketHub", " › Workspace", LandingView.class)
            .nav(List.of(
                new LkTopBar.NavItem("Browse",     BrowseEventsView.class),
                new LkTopBar.NavItem("My Tickets", MyAccountView.class)
            ), null)
            .bell(new NotificationBellComponent(bellPresenter::markRead))
            .account(initials(name), name, buildOwnerMenu(name));
        addToNavbar(topBar);
    }

    private void rebuildDrawer(String activeLabel) {
        List<LkSideNav.Item> visible = new ArrayList<>();
        for (DrawerEntry e : DRAWER_ITEMS) {
            if (e.cap() == null || Capabilities.has(e.cap())) {
                visible.add(e.item());
            }
        }
        drawerWrap.removeAll();
        ownerNav = new LkSideNav("Owner").items(visible, activeLabel);
        drawerWrap.add(ownerNav);
    }

    private LkAccountMenu buildOwnerMenu(String name) {
        LkMenu menu = new LkMenu(
            new LkMenu.Item("ticket",    "My Account").onClick(() -> UI.getCurrent().navigate(MyAccountView.class)),
            new LkMenu.Item("users",     "My Profile").onClick(() -> UI.getCurrent().navigate(MyProfileView.class)),
            new LkMenu.Item("crown",     "My Invitations").onClick(() -> UI.getCurrent().navigate(MyInvitationsView.class)),
            new LkMenu.Item("briefcase", "My Companies").onClick(() -> UI.getCurrent().navigate(MyCompaniesView.class)),
            new LkMenu.Divider(),
            new LkMenu.Item("arrowLeft", "Back to Buyer Site").onClick(() -> UI.getCurrent().navigate(BrowseEventsView.class)),
            new LkMenu.Divider(),
            new LkMenu.Item("logout",    "Sign Out").danger().onClick(() -> {
                signOutFlow.execute();
                UI.getCurrent().navigate(LoginView.class);
            })
        );
        return new LkAccountMenu(initials(name), name, currentRoleLabel(), menu, null, null);
    }

    /** Subtitle shown under the user's name in the avatar dropdown — reflects current role. */
    private static String currentRoleLabel() {
        if (Capabilities.has(Capability.DISSOLVE_COMPANY)) return "Founder · Workspace";
        if (Capabilities.has(Capability.APPOINT_MANAGER))  return "Co-owner · Workspace";
        if (Capabilities.has(Capability.OWNER_WORKSPACE))  return "Manager · Workspace";
        return "Workspace";
    }

    private static String findLabel(AfterNavigationEvent event, Map<Class<?>, String> labels) {
        for (HasElement el : event.getActiveChain()) {
            String label = labels.get(el.getClass());
            if (label != null) return label;
        }
        return null;
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) return "??";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            String p = parts[0];
            return p.substring(0, Math.min(2, p.length())).toUpperCase();
        }
        return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
    }

}
