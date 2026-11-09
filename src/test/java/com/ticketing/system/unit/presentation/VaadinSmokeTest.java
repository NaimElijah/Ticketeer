package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static com.ticketing.system.unit.presentation.ViewHarness.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.reporting.application.service.SalesSummaryCalculator;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.shared.dto.MarketStateDTO;
import com.ticketing.system.shared.dto.SystemAnalyticsDTO;
import com.ticketing.system.ui.components.kit.LkSearchPanel;
import com.ticketing.system.ui.components.kit.LkSideNav;
import com.ticketing.system.ui.components.kit.LkStatusDot;
import com.ticketing.system.ui.layouts.WorkspaceLayout;
import com.ticketing.system.ui.presenters.account.MyAccountPresenter;
import com.ticketing.system.ui.presenters.account.MyInvitationsPresenter;
import com.ticketing.system.ui.presenters.account.RefundPresenter;
import com.ticketing.system.ui.presenters.admin.GlobalHistoryPresenter;
import com.ticketing.system.ui.presenters.admin.OrgTreePresenter;
import com.ticketing.system.ui.presenters.admin.SystemAnalyticsPresenter;
import com.ticketing.system.ui.presenters.company.ManagerListPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.support.DisplayTime;
import com.ticketing.system.ui.views.account.MyAccountView;
import com.ticketing.system.ui.views.account.MyInvitationsView;
import com.ticketing.system.ui.views.admin.GlobalHistoryView;
import com.ticketing.system.ui.views.admin.OrganizationalTreeView;
import com.ticketing.system.ui.views.admin.SystemAnalyticsView;
import com.ticketing.system.ui.views.company.ManagerListView;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.router.Route;

/**
 * Views built without Spring and read for what they show. {@code HexagonalRulesTest} keeps every view in the
 * package Vaadin routes.
 *
 * <p>Until #189 this class booted a Spring context, for little more than {@link DisplayTime}, which
 * {@link InboxViewHarness#displayTime()} gives, and most of its tests only asserted that classes load and that
 * constructors do not throw, which behaviour tests already prove.
 */
class VaadinSmokeTest {

    private final DisplayTime displayTime = InboxViewHarness.displayTime();
    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- the workspace drawer ----

    @Test
    void GivenTheWorkspaceDrawerItems_WhenTheirRoutesAreRead_ThenTargetsAreNavigableWithoutParameters() throws Exception {
        // Regression for the /owner workspace crash. WorkspaceLayout's drawer builds one
        // RouterLink per entry via LkSideNav.items() -> link.setRoute(target). Vaadin's
        // RouterLink.setRoute(Class) throws when the target's @Route template carries a
        // *mandatory* parameter (e.g. "owner/policies/:companyId/:eventId"), and that throw
        // propagated out of the layout constructor ("Constructor threw exception"), so every
        // owner route 500'd. A drawer target must be reachable with no params — its @Route
        // may only contain optional (":x?") or wildcard (":x*") parameter segments.
        Field field = WorkspaceLayout.class.getDeclaredField("DRAWER_ITEMS");
        field.setAccessible(true);
        List<?> entries = (List<?>) field.get(null);

        for (Object entry : entries) {
            Method itemAccessor = entry.getClass().getDeclaredMethod("item");
            itemAccessor.setAccessible(true);
            LkSideNav.Item item = (LkSideNav.Item) itemAccessor.invoke(entry);
            Class<?> target = item.target();

            Route route = target.getAnnotation(Route.class);
            assertNotNull(route,
                target.getSimpleName() + " (a WorkspaceLayout drawer target) has no @Route");

            for (String segment : route.value().split("/")) {
                if (segment.startsWith(":")) {
                    assertTrue(segment.endsWith("?") || segment.endsWith("*"),
                        target.getSimpleName() + " is a WorkspaceLayout drawer target, but its @Route \""
                            + route.value() + "\" has the mandatory parameter \"" + segment
                            + "\" — RouterLink.setRoute() throws when the drawer is built. Make the"
                            + " parameter optional (\"" + segment + "?\") or point the drawer at a"
                            + " parameterless route.");
                }
            }
        }
    }

    // ---- what a view shows for its presenter's outcome ----

    // #192: a failed history load is shown as an error, never as "no orders yet".
    @Test
    void GivenHistoryLoadFailed_WhenMyAccountViewIsBuilt_ThenItShowsTheErrorAndNoEmptyHistory() {
        MyAccountPresenter presenter = mock(MyAccountPresenter.class);
        when(presenter.loadHistory()).thenReturn(new MyAccountPresenter.Outcome.Failure("database unreachable"));

        MyAccountView view = new MyAccountView(presenter, mock(RefundPresenter.class), mock(SessionIdentity.class),
                displayTime);

        String text = view.getElement().getTextRecursively();
        assertTrue(text.contains("Could not load your orders and tickets: database unreachable"),
                "expected the load error in: " + text);
        assertFalse(text.contains("No orders yet"), "a failed load must not read as an empty history: " + text);
        assertFalse(text.contains("My Orders") || text.contains("My Tickets"),
                "a failed load shows no order or ticket section: " + text);
        // The counts are set as inner HTML, which the text above leaves out, so look for their block.
        assertFalse(hasDescendantWithClass(view.getElement(), "acct-hero-stats"),
                "a failed load shows no order or ticket counts, so no \"0 orders\"");
    }

    @Test
    void GivenANotAuthenticatedOrgTreeOutcome_WhenOrganizationalTreeViewIsBuilt_ThenItAsksTheAdminToSignInAgain() {
        OrgTreePresenter orgTreePresenter = mock(OrgTreePresenter.class);
        when(orgTreePresenter.load(any(), any(), anyBoolean())).thenReturn(new OrgTreePresenter.Outcome.NotAuthenticated());

        String shown = text(new OrganizationalTreeView(orgTreePresenter));

        assertTrue(shown.contains("Your session has expired — please sign in again."), "expected the banner in: " + shown);
    }

    @Test
    void GivenAnEmptyRoster_WhenManagerListViewIsBuilt_ThenItNamesTheCompanyAndShowsNoManagerRow() {
        ManagerListPresenter presenter = mock(ManagerListPresenter.class);
        when(presenter.loadRoster(any())).thenReturn(
            new ManagerListPresenter.Outcome.Success("Acme", List.of(), List.of()));

        ManagerListView view = new ManagerListView(presenter, displayTime);

        String shown = text(view);
        assertTrue(shown.contains("Roster and permissions for Acme."), "expected the company's roster in: " + shown);
        assertTrue(shown.contains("Active Managers") && shown.contains("Pending Invitations"),
            "expected both rosters' headings in: " + shown);
        assertEquals(List.of(), find(view, LkStatusDot.class), "an empty roster shows no manager's status");
    }

    @Test
    void GivenNoInvitations_WhenMyInvitationsViewIsBuilt_ThenItSaysThereAreNone() {
        MyInvitationsPresenter presenter = mock(MyInvitationsPresenter.class);
        when(presenter.load(any())).thenReturn(
            new MyInvitationsPresenter.Outcome.Success(List.of(), List.of()));

        String shown = text(new MyInvitationsView(presenter, displayTime));

        assertTrue(shown.contains("You have no pending invitations right now."), "expected the empty state in: " + shown);
        assertFalse(shown.contains("History"), "no invitation history to show: " + shown);
    }

    @Test
    void GivenOneOrderOnThePlatform_WhenGlobalHistoryViewIsBuilt_ThenItShowsTheOrdersBuyer() {
        ViewHarness.signInAdmin();
        SystemAdminService admin = mock(SystemAdminService.class);
        PurchaseRecordDTO order = new PurchaseRecordDTO(7, 42, null, LocalDateTime.of(2027, 7, 15, 17, 0), 150.00,
            PurchaseHistoryDTO.RefundStatus.NONE, List.of(), List.of(), "dana");
        when(admin.viewGlobalHistory(any(), any())).thenReturn(List.of(new PurchaseHistoryDTO(List.of(order))));

        String shown = text(new GlobalHistoryView(new GlobalHistoryPresenter(admin, new SalesSummaryCalculator()),
            displayTime));

        assertTrue(shown.contains("dana"), "expected the order's buyer in: " + shown);
        assertFalse(shown.contains("No orders match"), "a loaded order is no empty state: " + shown);
    }

    // ---- the dashboard's WSEP line reports the last probe and its time (#187 review follow-up 1) ----

    private static final LocalDateTime LAST_CHECK = LocalDateTime.of(2027, 7, 15, 17, 0, 5);
    private static final DateTimeFormatter CHECK_FMT = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm:ss", Locale.US);

    @Test
    void GivenBothServicesAnsweredTheLastProbe_WhenSystemAnalyticsViewIsBuilt_ThenItSaysReachableAndWhen() {
        String text = analyticsViewText(new MarketStateDTO("OPEN", null, null, true, true, LAST_CHECK, true));

        String checkedAt = displayTime.format(LAST_CHECK, CHECK_FMT);
        assertTrue(text.contains("Payment gateway: reachable · Ticket issuer: reachable (at last check, " + checkedAt + ")"),
            "expected both reachable with the check time in: " + text);
    }

    @Test
    void GivenTheIssuerFailedTheLastProbe_WhenSystemAnalyticsViewIsBuilt_ThenItSaysUnreachable() {
        String text = analyticsViewText(new MarketStateDTO("OPEN", null, null, true, false, LAST_CHECK, true));

        assertTrue(text.contains("Payment gateway: reachable · Ticket issuer: unreachable (at last check, "),
            "expected the issuer unreachable at the last check in: " + text);
    }

    @Test
    void GivenNoProbeHasRun_WhenSystemAnalyticsViewIsBuilt_ThenItSaysNotCheckedYet() {
        String text = analyticsViewText(new MarketStateDTO("UNINITIALIZED", null, null, false, false, null, true));

        assertTrue(text.contains("Payment gateway and ticket issuer: not checked yet"),
            "expected the services as not checked yet in: " + text);
        assertFalse(text.contains("unreachable"), "an unchecked service is not reported down: " + text);
    }

    // ---- the live top-bar search panel (upstream#281) ----

    @Test
    void GivenRecentSearches_WhenTheLiveSearchPanelOpens_ThenItListsThem() {
        String shown = text(new LkSearchPanel(List.of("Coldplay", "Adele"), query -> List.of()));

        assertTrue(shown.contains("Coldplay") && shown.contains("Adele"), "expected the recent searches in: " + shown);
    }

    private String analyticsViewText(MarketStateDTO market) {
        SystemAnalyticsPresenter presenter = mock(SystemAnalyticsPresenter.class);
        SystemAnalyticsDTO analytics = new SystemAnalyticsDTO(0L, 0d, 0d, 0d, 0d, 0d, 0L, 0L, 0L, 0L, 0L, 5);
        when(presenter.load()).thenReturn(new SystemAnalyticsPresenter.Outcome.Success(market, analytics));
        return new SystemAnalyticsView(presenter, displayTime).getElement().getTextRecursively();
    }

    private static boolean hasDescendantWithClass(Element element, String className) {
        return element.getClassList().contains(className)
                || element.getChildren().anyMatch(child -> hasDescendantWithClass(child, className));
    }
}
