package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.founderOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkSideNav;
import com.ticketing.system.ui.components.kit.LkTopBar;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.layouts.PlatformAdminLayout;
import com.ticketing.system.ui.layouts.WorkspaceLayout;
import com.ticketing.system.ui.presenters.notifications.NotificationBellPresenter;
import com.ticketing.system.ui.presenters.order.CartBadgePresenter;
import com.ticketing.system.ui.security.SignOutFlow;
import com.ticketing.system.ui.views.account.MyAccountView;
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.ticketing.system.ui.views.company.CompanyEventListView;
import com.ticketing.system.ui.views.company.ManagerListView;
import com.ticketing.system.ui.views.company.OwnerDashboardView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;

/**
 * The shells give a keyboard and screen-reader user the page's structure (#188, A-M5, A-m6, P-M9, P-m1,
 * P-M4, U-A10, U-A11; WCAG 1.3.1, 2.4.1, 2.4.3, 4.1.2):
 * <ul>
 *   <li>the top bar's links and the side navigation sit in a named {@code nav} landmark, and the link to
 *       the page shown carries {@code aria-current="page"} instead of only a colour;</li>
 *   <li>each shell's first stop on Tab is a "skip to content" link to the page's content;</li>
 *   <li>after a navigation focus moves to the new page's h1 ({@code tabindex=-1}), so it is not lost to the
 *       body when the shell rebuilds its top bar, and the new page is announced.</li>
 * </ul>
 *
 * <p>A shell's Tab order follows {@code vaadin-app-layout}'s slots: navbar, then drawer, then content.
 * Elements inside a closed popover are not in it.
 */
class NavigationLandmarksTest {

    // ---- F8: navigation landmarks, skip link and focus after navigation (#188) ----

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-07-15T17:00:00Z"), ZoneOffset.UTC);
    private static final String PAGE_TITLE = "Page under test";

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- nav landmarks and the current page ----

    @Test
    void GivenTheTopBarNav_WhenItRenders_ThenItsLinksSitInANamedNavLandmark() {
        LkTopBar bar = topBarWithBrowseActive();

        for (String label : List.of("Browse", "My Tickets")) {
            assertInNamedNav(link(bar, label));
        }
    }

    @Test
    void GivenTheTopBarNavOnBrowse_WhenItRenders_ThenOnlyBrowseIsTheCurrentPage() {
        LkTopBar bar = topBarWithBrowseActive();

        assertEquals("page", link(bar, "Browse").getAttribute("aria-current"),
            "the link to the page shown must say so: " + A11y.describe(link(bar, "Browse")));
        assertNotCurrent(link(bar, "My Tickets"));
    }

    @Test
    void GivenTheSideNav_WhenItRenders_ThenItsLinksSitInANamedNavLandmark() {
        LkSideNav nav = sideNav("My Events");

        for (String label : List.of("Workspace", "My Events", "Managers")) {
            assertInNamedNav(link(nav, label));
        }
    }

    @Test
    void GivenTheSideNavOnMyEvents_WhenTheActiveItemMovesToManagers_ThenOnlyManagersIsTheCurrentPage() {
        LkSideNav nav = sideNav("My Events");
        assertEquals("page", link(nav, "My Events").getAttribute("aria-current"),
            "the link to the page shown must say so: " + A11y.describe(link(nav, "My Events")));

        nav.setActive("Managers");

        assertEquals("page", link(nav, "Managers").getAttribute("aria-current"),
            "the link to the page shown must say so: " + A11y.describe(link(nav, "Managers")));
        assertNotCurrent(link(nav, "My Events"));
        assertNotCurrent(link(nav, "Workspace"));
    }

    // ---- skip link ----

    @Test
    void GivenTheBuyerShell_WhenAPageIsShown_ThenTheFirstTabStopSkipsToItsContent() {
        assertFirstTabStopSkipsToContent(mainLayout());
    }

    @Test
    void GivenTheWorkspaceShell_WhenAPageIsShown_ThenTheFirstTabStopSkipsToItsContent() {
        assertFirstTabStopSkipsToContent(workspaceLayout());
    }

    @Test
    void GivenTheAdminShell_WhenAPageIsShown_ThenTheFirstTabStopSkipsToItsContent() {
        assertFirstTabStopSkipsToContent(adminLayout());
    }

    // ---- focus after navigation ----

    @Test
    void GivenTheBuyerShell_WhenItNavigatesToAPage_ThenFocusMovesToThePagesH1() {
        assertFocusMovesToH1(mainLayout());
    }

    @Test
    void GivenTheWorkspaceShell_WhenItNavigatesToAPage_ThenFocusMovesToThePagesH1() {
        assertFocusMovesToH1(workspaceLayout());
    }

    @Test
    void GivenTheAdminShell_WhenItNavigatesToAPage_ThenFocusMovesToThePagesH1() {
        assertFocusMovesToH1(adminLayout());
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static LkTopBar topBarWithBrowseActive() {
        return new LkTopBar(LkTopBar.Variant.MAIN).nav(List.of(
            new LkTopBar.NavItem("Browse", BrowseEventsView.class),
            new LkTopBar.NavItem("My Tickets", MyAccountView.class)), "Browse");
    }

    private static LkSideNav sideNav(String active) {
        return new LkSideNav("Owner").items(List.of(
            new LkSideNav.Item("building", "Workspace", OwnerDashboardView.class),
            new LkSideNav.Item("calendar", "My Events", CompanyEventListView.class),
            new LkSideNav.Item("users", "Managers", ManagerListView.class)), active);
    }

    private static Element link(Component root, String label) {
        return A11y.elements(root, e -> A11y.isLink(e) && label.equals(A11y.name(e))).stream()
            .findFirst()
            .orElseThrow(() -> new AssertionError("no link named \"" + label + "\" in: "
                + A11y.elements(root, A11y::isLink).stream().map(A11y::describe).toList()));
    }

    private static void assertInNamedNav(Element link) {
        for (Element a = link.getParent(); a != null; a = a.getParent()) {
            if ("navigation".equals(A11y.role(a))) {
                String label = a.getAttribute("aria-label");
                String labelledBy = a.getAttribute("aria-labelledby");
                assertTrue((label != null && !label.isBlank()) || (labelledBy != null && !labelledBy.isBlank()),
                    "the nav landmark must be named (aria-label), to tell it from the page's other nav: "
                        + A11y.describe(a));
                return;
            }
        }
        throw new AssertionError("the link must sit in a nav landmark (a nav element or role=navigation): "
            + A11y.describe(link));
    }

    private static void assertNotCurrent(Element link) {
        String current = link.getAttribute("aria-current");
        assertTrue(current == null || "false".equals(current),
            "only the page shown is current: " + A11y.describe(link));
    }

    private MainLayout mainLayout() {
        ViewHarness.signInMember();
        CartBadgePresenter badge = mock(CartBadgePresenter.class);
        when(badge.loadBadge()).thenReturn(new CartBadgePresenter.Outcome.Empty());
        return new MainLayout(badge, mock(SignOutFlow.class), mock(NotificationBellPresenter.class), CLOCK);
    }

    private WorkspaceLayout workspaceLayout() {
        ViewHarness.signInMember();
        harness.memberships(founderOf(3, "Acme"));
        return new WorkspaceLayout(mock(SignOutFlow.class), mock(NotificationBellPresenter.class));
    }

    private PlatformAdminLayout adminLayout() {
        ViewHarness.signInAdmin();
        return new PlatformAdminLayout(mock(SignOutFlow.class), mock(NotificationBellPresenter.class));
    }

    // Shows a page in the shell as the router does: the page becomes the content, then the shell hears of
    // the navigation.
    private LkPage navigateTo(AppLayout layout) {
        harness.attach(layout);
        LkPage page = new LkPage(PAGE_TITLE);
        layout.showRouterLayoutContent(page);
        AfterNavigationEvent event = mock(AfterNavigationEvent.class);
        when(event.getActiveChain()).thenReturn(List.<HasElement>of(page, layout));
        ((AfterNavigationObserver) layout).afterNavigation(event);
        return page;
    }

    private void assertFirstTabStopSkipsToContent(AppLayout layout) {
        LkPage page = navigateTo(layout);

        List<Element> order = tabOrder(layout);
        assertFalse(order.isEmpty(), "the shell has nothing to Tab to");
        Element first = order.get(0);
        String href = first.getAttribute("href");
        assertTrue(A11y.isLink(first) && href != null && href.startsWith("#") && href.length() > 1,
            "the first Tab stop must be a skip link to the content (href=\"#id\"); it is: " + A11y.describe(first));
        Element target = A11y.byId(layout.getElement(), href.substring(1))
            .orElseThrow(() -> new AssertionError("the skip link points at \"" + href + "\", which is not on the page"));
        assertTrue(!target.equals(layout.getElement())
                && (A11y.within(page.getElement(), target) || A11y.within(target, page.getElement())),
            "the skip link must lead to the page's content, not " + A11y.describe(target));
    }

    private void assertFocusMovesToH1(AppLayout layout) {
        LkPage page = navigateTo(layout);

        Element h1 = A11y.elements(page, e -> "h1".equals(e.getTag())).get(0);
        assertEquals("-1", h1.getAttribute("tabindex"),
            "the h1 must take focus from a script without joining the Tab order (tabindex=-1)");
        List<List<Object>> calls = harness.javaScriptCalls();
        assertTrue(calls.stream().anyMatch(call -> focuses(call, h1)),
            "after a navigation focus must move to the page's h1; scripts sent: " + calls);
    }

    private static boolean focuses(List<Object> call, Element h1) {
        String expression = String.valueOf(call.get(0));
        if (!expression.contains("focus")) {
            return false;
        }
        return call.subList(1, call.size()).contains(h1) || expression.contains("h1");
    }

    // navbar, then drawer, then content, as vaadin-app-layout lays out its slots.
    private static List<Element> tabOrder(AppLayout layout) {
        Map<String, List<Element>> slots = Map.of("navbar", new ArrayList<>(), "drawer", new ArrayList<>(),
            "", new ArrayList<>());
        layout.getElement().getChildren().forEach(child -> {
            String slot = String.valueOf(child.getAttribute("slot"));
            String key = slot.startsWith("navbar") ? "navbar" : slot.startsWith("drawer") ? "drawer" : "";
            slots.get(key).add(child);
        });
        List<Element> order = new ArrayList<>();
        for (String slot : List.of("navbar", "drawer", "")) {
            for (Element child : slots.get(slot)) {
                A11y.elements(child).stream()
                    .filter(e -> !inPopover(e) && A11y.inTabOrder(e, layout.getElement()))
                    .forEach(order::add);
            }
        }
        return order;
    }

    private static boolean inPopover(Element e) {
        for (Element a = e.getParent(); a != null; a = a.getParent()) {
            if ("vaadin-popover".equals(a.getTag())) {
                return true;
            }
        }
        return false;
    }
}
