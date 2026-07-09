package com.ticketing.system.unit.presentation;
import com.ticketing.system.identity.domain.Admin;

import com.ticketing.system.ui.components.company.EditPermissionsDialog;
import com.ticketing.system.ui.components.kit.LkBadge;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkConfirm;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkSearchPanel;
import com.ticketing.system.ui.components.kit.LkSideNav;
import com.ticketing.system.ui.components.kit.LkTextRows;
import com.ticketing.system.ui.components.venue.VkQuantitySelector;
import com.ticketing.system.ui.components.venue.VkSeat;
import com.ticketing.system.ui.components.venue.VkSeatLegend;
import com.ticketing.system.ui.components.venue.VkSeatedZonePicker;
import com.ticketing.system.ui.components.venue.VkStandingZone;
import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.layouts.WorkspaceLayout;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.views.admin.AdminComplaintQueueView;
import com.ticketing.system.ui.views.admin.AdminComplaintRespondView;
import com.ticketing.system.ui.views.admin.AdminDashboardView;
import com.ticketing.system.ui.views.admin.AdminInboxView;
import com.ticketing.system.ui.views.admin.AdminSendMessagesView;
import com.ticketing.system.ui.views.admin.GlobalHistoryView;
import com.ticketing.system.ui.views.admin.OrganizationalTreeView;
import com.ticketing.system.ui.views.admin.SystemAnalyticsView;
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.ticketing.system.ui.views.catalog.EventDetailsView;
import com.ticketing.system.ui.presenters.admin.AdminDashboardPresenter;
import com.ticketing.system.ui.presenters.admin.GlobalHistoryPresenter;
import com.ticketing.system.ui.presenters.admin.OrgTreePresenter;
import com.ticketing.system.ui.presenters.admin.SystemAnalyticsPresenter;
import com.ticketing.system.ui.presenters.catalog.BrowseEventsPresenter;
import com.ticketing.system.ui.presenters.catalog.EventDetailsPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.company.CompanyInquiryInboxView;
import com.ticketing.system.ui.views.company.CompanyInquiryRespondView;
import com.ticketing.system.ui.views.company.EventManagementView;
import com.ticketing.system.ui.presenters.company.EventManagementPresenter;
import com.ticketing.system.ui.views.company.ManagerListView;
import com.ticketing.system.ui.views.company.OwnerDashboardView;
import com.ticketing.system.ui.views.account.MyAccountView;
import com.ticketing.system.ui.views.account.MyInvitationsView;
import com.ticketing.system.ui.views.account.ReceiptView;
import com.ticketing.system.ui.views.account.SupportInboxView;
import com.ticketing.system.ui.presenters.account.MyAccountPresenter;
import com.ticketing.system.ui.presenters.account.ReceiptPresenter;
import com.ticketing.system.ui.presenters.account.RefundPresenter;
import com.ticketing.system.ui.views.landing.LandingView;
import com.ticketing.system.ui.views.messaging.NewInquiryView;
import com.ticketing.system.ui.views.messaging.SubmitComplaintView;
import com.ticketing.system.ui.presenters.company.ManagerListPresenter;
import com.ticketing.system.ui.presenters.company.OwnerDashboardPresenter;
import com.ticketing.system.ui.presenters.messaging.AdminComplaintQueuePresenter;
import com.ticketing.system.ui.presenters.messaging.AdminInboxPresenter;
import com.ticketing.system.ui.presenters.messaging.AdminSendMessagesPresenter;
import com.ticketing.system.ui.presenters.messaging.CompanyInquiryInboxPresenter;
import com.ticketing.system.ui.presenters.messaging.NewInquiryPresenter;
import com.ticketing.system.ui.presenters.account.MyInvitationsPresenter;
import com.ticketing.system.ui.presenters.landing.LandingPresenter;
import com.ticketing.system.ui.presenters.messaging.SubmitComplaintPresenter;
import com.ticketing.system.ui.presenters.messaging.SupportInboxPresenter;
import com.ticketing.system.shared.dto.AdminOverviewDTO;
import com.ticketing.system.shared.dto.CompanyDashboardDTO;
import com.ticketing.system.shared.dto.MarketStateDTO;
import com.ticketing.system.shared.dto.SystemAnalyticsDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.MessageDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.vaadin.flow.router.Route;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Smoke tests for the V2 Presentation layer.
 *
 * <p>Lightweight checks — boots the Spring context but does not render any
 * view. Verifies:
 *
 * <ul>
 *   <li>Vaadin Flow classes are on the classpath</li>
 *   <li>{@link MainLayout} and {@link WorkspaceLayout} are well-formed</li>
 *   <li>Representative buyer + owner + platform-admin views construct
 *       without throwing (catches kit-API misuse)</li>
 *   <li>Custom kit components instantiate without throwing</li>
 *   <li>{@link Toasts} utility is reachable</li>
 * </ul>
 *
 * <p>Rendering tests are intentionally out of scope — the V2 spec exempts UI
 * tests ("בדיקות אינן נדרשות עבור ממשק המשתמש"). Business behavior is
 * exercised by the application-layer acceptance tests instead.
 */
@SpringBootTest
@ActiveProfiles("test")
class VaadinSmokeTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void springContextLoadsWithVaadin() {
        // Reaching this method means @SpringBootTest succeeded. If Vaadin's
        // auto-configuration were broken or the starter were missing, context
        // load would have thrown.
        assertNotNull(context, "Spring context did not load");
    }

    @Test
    void vaadinClassesAreReachable() {
        assertDoesNotThrow(() -> Class.forName("com.vaadin.flow.spring.SpringServlet"),
            "Vaadin SpringServlet class not on classpath");
        assertDoesNotThrow(() -> Class.forName("com.vaadin.flow.server.VaadinService"),
            "Vaadin VaadinService class not on classpath");
        assertDoesNotThrow(() -> Class.forName("com.vaadin.flow.component.UI"),
            "Vaadin UI class not on classpath");
        assertDoesNotThrow(() -> Class.forName("com.vaadin.flow.component.applayout.AppLayout"),
            "Vaadin AppLayout class not on classpath (needed by MainLayout / WorkspaceLayout)");
    }

    @Test
    void presentationPackageIsConfigured() {
        // All view classes must live under the package configured in
        // application.yml `vaadin.allowed-packages`, otherwise Vaadin's
        // route scanner won't discover them.
        assertTrue(BrowseEventsView.class.getPackageName()
                .startsWith("com.ticketing.system.ui"),
            "BrowseEventsView must live under com.ticketing.system.ui");
        assertTrue(AdminDashboardView.class.getPackageName()
                .startsWith("com.ticketing.system.ui"),
            "AdminDashboardView must live under com.ticketing.system.ui");
    }

    @Test
    void layoutsAreLoadable() {
        // We can't instantiate the layouts here — they build RouterLinks in
        // their constructors and RouterLink calls VaadinService.getCurrent()
        // which is null outside a real UI context. So just verify the
        // classes load cleanly (proves imports + compilation are fine).
        // Actual layout rendering is verified by booting the app and
        // navigating to a route in a browser.
        assertNotNull(MainLayout.class, "MainLayout class did not load");
        assertNotNull(WorkspaceLayout.class, "WorkspaceLayout class did not load");
    }

    @Test
    void workspaceDrawerTargetsAreNavigableWithoutParameters() throws Exception {
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

    @Test
    void coreViewsInstantiate() {
        // Spot-check one MainLayout view and one WorkspaceLayout view as a
        // cheap canary for kit-API breakage. Both now take an injected
        // presenter, so resolve it from the Spring context.
        assertDoesNotThrow(
            () -> new BrowseEventsView(context.getBean(BrowseEventsPresenter.class),
                context.getBean(SessionIdentity.class)),
            "BrowseEventsView (root route) failed to construct");
        assertDoesNotThrow(
            () -> new GlobalHistoryView(context.getBean(GlobalHistoryPresenter.class)),
            "GlobalHistoryView (admin route) failed to construct");
    }

    @Test
    void eventDetailsViewInstantiates() {
        // Buyer event page wired to a presenter (#272). The constructor only builds the
        // bodyHolder shell — load + page build happen in beforeEnter — so bare mocks exercise
        // the construction path (a canary for kit-API breakage at wiring time).
        EventDetailsPresenter presenter = mock(EventDetailsPresenter.class);
        SessionIdentity sessionIdentity = mock(SessionIdentity.class);
        assertDoesNotThrow(() -> new EventDetailsView(presenter, sessionIdentity),
            "EventDetailsView failed to construct");
    }

    @Test
    void receiptViewInstantiates() {
        // Member receipt page wired to presenters (#276 + refund #284). The constructor only adds
        // the bodyHolder shell — load + render happen in beforeEnter — so bare mocks exercise the
        // construction path.
        ReceiptPresenter presenter = mock(ReceiptPresenter.class);
        RefundPresenter refundPresenter = mock(RefundPresenter.class);
        SessionIdentity sessionIdentity = mock(SessionIdentity.class);
        assertDoesNotThrow(() -> new ReceiptView(presenter, refundPresenter, sessionIdentity),
            "ReceiptView failed to construct");
    }

    @Test
    void myAccountViewInstantiates() {
        // Member account page (#284 refund affordance). It builds in the constructor, so the
        // presenter must return a (here empty) history; bare mocks for the rest.
        MyAccountPresenter presenter = mock(MyAccountPresenter.class);
        when(presenter.loadHistory()).thenReturn(new PurchaseHistoryDTO(List.of()));
        RefundPresenter refundPresenter = mock(RefundPresenter.class);
        SessionIdentity sessionIdentity = mock(SessionIdentity.class);
        assertDoesNotThrow(() -> new MyAccountView(presenter, refundPresenter, sessionIdentity),
            "MyAccountView failed to construct");
    }

    @Test
    void platformAdminViewsInstantiate() {
        // No-arg PlatformAdminLayout views. Sign-in is now the unified LoginView
        // (which is exercised by the buyer-side construction path). AdminDashboardView
        // now takes a presenter, so it has its own test below.
        OrgTreePresenter orgTreePresenter = mock(OrgTreePresenter.class);
        when(orgTreePresenter.load(any(), any(), anyBoolean())).thenReturn(new OrgTreePresenter.Outcome.NotAuthenticated());
        assertDoesNotThrow(() -> new OrganizationalTreeView(orgTreePresenter),
            "OrganizationalTreeView failed to construct");
    }

    @Test
    void adminDashboardViewInstantiates() {
        // Admin workspace landing wired to a presenter (#279). The constructor runs an initial
        // load() to build the KPI row, so the mock presenter returns a success outcome.
        AdminDashboardPresenter presenter = mock(AdminDashboardPresenter.class);
        when(presenter.load()).thenReturn(
            new AdminDashboardPresenter.Outcome.Success(new AdminOverviewDTO(0, 0, 0, 0.0)));
        assertDoesNotThrow(() -> new AdminDashboardView(presenter),
            "AdminDashboardView failed to construct");
    }

    @Test
    void systemAnalyticsViewInstantiates() {
        // System Analytics dashboard wired to a presenter (UC-46 / #43, #279). The constructor
        // runs an initial load(), so the mock presenter returns a success outcome to exercise the
        // market-card + KPI-stat build path without a UI context.
        SystemAnalyticsPresenter presenter = mock(SystemAnalyticsPresenter.class);
        MarketStateDTO market = new MarketStateDTO(
            "OPEN", LocalDateTime.now(), LocalDateTime.now(), true, true, true);
        SystemAnalyticsDTO analytics = new SystemAnalyticsDTO(0L, 0d, 0d, 0d, 0d, 0d, 0L, 0L, 0L, 0L, 0L, 5);
        when(presenter.load()).thenReturn(
            new SystemAnalyticsPresenter.Outcome.Success(market, analytics));
        assertDoesNotThrow(() -> new SystemAnalyticsView(presenter),
            "SystemAnalyticsView failed to construct");
    }

    @Test
    void eventManagementViewInstantiates() {
        // Owner create/edit-event screen (route owner/events/:eventId). The constructor builds the
        // whole form (incl. the create-mode Artists field and the side column) but doesn't invoke the
        // presenter — load/create happen on beforeEnter/click — so a bare mock exercises construction.
        EventManagementPresenter presenter = mock(EventManagementPresenter.class);
        assertDoesNotThrow(() -> new EventManagementView(presenter),
            "EventManagementView failed to construct");
    }

    @Test
    void managerListViewInstantiates() {
        // Owner-workspace view wired to a presenter (#264). A mock presenter
        // returning an empty success roster exercises the grid-building path
        // without a UI context — a canary for kit-API breakage.
        ManagerListPresenter presenter = mock(ManagerListPresenter.class);
        when(presenter.loadRoster(any())).thenReturn(
            new ManagerListPresenter.Outcome.Success("Acme", List.of(), List.of()));
        assertDoesNotThrow(() -> new ManagerListView(presenter),
            "ManagerListView failed to construct");
    }

    @Test
    void myInvitationsViewInstantiates() {
        // Member account view wired to a presenter (#275). A mock presenter
        // returning an empty success outcome exercises the grid-building path
        // without a UI context — a canary for kit-API breakage.
        MyInvitationsPresenter presenter = mock(MyInvitationsPresenter.class);
        when(presenter.load(any())).thenReturn(
            new MyInvitationsPresenter.Outcome.Success(List.of(), List.of()));
        assertDoesNotThrow(() -> new MyInvitationsView(presenter),
            "MyInvitationsView failed to construct");
    }

    @Test
    void ownerDashboardViewInstantiates() {
        // Owner-workspace hub wired to a presenter (#292). A mock presenter returning a
        // single-company success outcome exercises the stat-tile + tile-grid build path
        // without a UI context.
        OwnerDashboardPresenter presenter = mock(OwnerDashboardPresenter.class);
        MyCompanyDTO company = new MyCompanyDTO(1, "Acme", "Founder");
        when(presenter.loadFor(any(), any())).thenReturn(
            new OwnerDashboardPresenter.Outcome.Success(
                List.of(company), company, new CompanyDashboardDTO(0, 0, 0.0, 0, 4.5)));
        assertDoesNotThrow(() -> new OwnerDashboardView(presenter),
            "OwnerDashboardView failed to construct");
    }

    @Test
    void landingViewInstantiates() {
        // Public landing root wired to a presenter (#285). A mock presenter returning an
        // empty success outcome exercises the poster-row build path without a UI context.
        LandingPresenter presenter = mock(LandingPresenter.class);
        when(presenter.load()).thenReturn(
            new LandingPresenter.Outcome.Success(List.of(), List.of()));
        assertDoesNotThrow(() -> new LandingView(presenter),
            "LandingView failed to construct");
    }

    @Test
    void submitComplaintViewInstantiates() {
        // Messaging view wired to a presenter (#267). Construction doesn't invoke
        // submit(), so a bare mock presenter is enough to exercise the form-build
        // path without a UI context — a canary for kit-API breakage.
        SubmitComplaintPresenter presenter = mock(SubmitComplaintPresenter.class);
        assertDoesNotThrow(() -> new SubmitComplaintView(presenter),
            "SubmitComplaintView failed to construct");
    }

    @Test
    void supportInboxViewInstantiates() {
        // Member Support inbox wired to a presenter (#277). A mock presenter
        // returning one conversation with one message exercises the master-list +
        // thread + reply-bar build path without a UI context.
        SupportInboxPresenter presenter = mock(SupportInboxPresenter.class);
        ConversationDTO conv = new ConversationDTO(
            "conv-1", "COMPLAINT", "OPEN", 1, "MEMBER", 0, "ADMIN_GROUP",
            "Refund delay", LocalDateTime.now(), LocalDateTime.now(), 0,
            List.of(new MessageDTO("m-1", 1, "MEMBER", "Where's my refund?",
                LocalDateTime.now(), false)),
            "alice", "TicketHub Support");
        when(presenter.load(any())).thenReturn(
            new SupportInboxPresenter.Outcome.Success(List.of(conv)));
        assertDoesNotThrow(() -> new SupportInboxView(presenter),
            "SupportInboxView failed to construct");
    }

    @Test
    void companyInquiryInboxViewInstantiates() {
        // Company member-inquiry inbox wired to a presenter (#268). A mock presenter returning
        // one company + one inquiry with one message exercises the status-filter + master-list +
        // thread + reply-bar build path without a UI context.
        CompanyInquiryInboxPresenter presenter = mock(CompanyInquiryInboxPresenter.class);
        MyCompanyDTO company = new MyCompanyDTO(7, "Acme", "Founder");
        ConversationDTO conv = new ConversationDTO(
            "conv-1", "INQUIRY", "OPEN", 1, "MEMBER", 7, "COMPANY",
            "Wheelchair access", LocalDateTime.now(), LocalDateTime.now(), 0,
            List.of(new MessageDTO("m-1", 1, "MEMBER", "Is there step-free access?",
                LocalDateTime.now(), false)),
            "alice", "Acme");
        when(presenter.loadFor(any(), any())).thenReturn(
            new CompanyInquiryInboxPresenter.Outcome.Success(List.of(company), company, List.of(conv)));
        assertDoesNotThrow(() -> new CompanyInquiryInboxView(presenter),
            "CompanyInquiryInboxView failed to construct");
    }

    @Test
    void adminComplaintQueueViewInstantiates() {
        // Admin complaint queue wired to a presenter (#269). A mock presenter returning one
        // complaint with one message exercises the status-filter + master-list + thread +
        // reply-bar build path without a UI context. (The view now takes a presenter, so it
        // moved out of the no-arg platformAdminViewsInstantiate group.)
        AdminComplaintQueuePresenter presenter = mock(AdminComplaintQueuePresenter.class);
        ConversationDTO conv = new ConversationDTO(
            "conv-1", "COMPLAINT", "OPEN", 1, "MEMBER", 0, "ADMIN_GROUP",
            "Charged twice", LocalDateTime.now(), LocalDateTime.now(), 0,
            List.of(new MessageDTO("m-1", 1, "MEMBER", "My card was charged twice.",
                LocalDateTime.now(), false)),
            "alice", "TicketHub Support");
        when(presenter.load(any(), any())).thenReturn(
            new AdminComplaintQueuePresenter.Outcome.Success(List.of(conv)));
        assertDoesNotThrow(() -> new AdminComplaintQueueView(presenter),
            "AdminComplaintQueueView failed to construct");
    }

    @Test
    void adminInboxViewInstantiates() {
        // Admin outreach inbox wired to a presenter. A mock presenter returning one DIRECT conversation
        // with a message exercises the master-list + thread + reply-bar + close build path.
        AdminInboxPresenter presenter = mock(AdminInboxPresenter.class);
        ConversationDTO conv = new ConversationDTO(
            "conv-1", "DIRECT", "OPEN", 1, "ADMIN", 7, "MEMBER",
            "Heads up", LocalDateTime.now(), LocalDateTime.now(), 0,
            List.of(new MessageDTO("m-1", 1, "ADMIN", "hi alice", LocalDateTime.now(), false)),
            "TicketHub Support", "alice");
        when(presenter.load(any())).thenReturn(
            new AdminInboxPresenter.Outcome.Success(List.of(conv)));
        assertDoesNotThrow(() -> new AdminInboxView(presenter),
            "AdminInboxView failed to construct");
    }

    @Test
    void adminSendMessagesViewInstantiates() {
        // Admin "Send Messages" outreach wired to a presenter (II.6.3.2). A mock presenter returning
        // one sent row exercises the composer + recipient picker + history-grid build path without a
        // UI context.
        AdminSendMessagesPresenter presenter = mock(AdminSendMessagesPresenter.class);
        AdminSendMessagesPresenter.SentOutreach row = new AdminSendMessagesPresenter.SentOutreach(
            LocalDateTime.now(), "Maintenance window", 82481);
        when(presenter.load(any())).thenReturn(
            new AdminSendMessagesPresenter.Outcome.Success(List.of(row)));
        assertDoesNotThrow(() -> new AdminSendMessagesView(presenter),
            "AdminSendMessagesView failed to construct");
    }

    @Test
    void newInquiryViewInstantiates() {
        // Member "New inquiry" composer (II.3.10). The constructor builds the form shell; company
        // search + submit happen on interaction, so a bare mock presenter exercises construction.
        NewInquiryPresenter presenter = mock(NewInquiryPresenter.class);
        assertDoesNotThrow(() -> new NewInquiryView(presenter),
            "NewInquiryView failed to construct");
    }

    @Test
    void adminComplaintRespondViewInstantiates() {
        // Admin complaint respond page. The constructor only adds the content shell (load happens in
        // beforeEnter), so a bare mock presenter exercises construction.
        AdminComplaintQueuePresenter presenter = mock(AdminComplaintQueuePresenter.class);
        assertDoesNotThrow(() -> new AdminComplaintRespondView(presenter),
            "AdminComplaintRespondView failed to construct");
    }

    @Test
    void companyInquiryRespondViewInstantiates() {
        // Company inquiry respond page. The constructor only adds the content shell (load happens in
        // beforeEnter), so a bare mock presenter exercises construction.
        CompanyInquiryInboxPresenter presenter = mock(CompanyInquiryInboxPresenter.class);
        assertDoesNotThrow(() -> new CompanyInquiryRespondView(presenter),
            "CompanyInquiryRespondView failed to construct");
    }

    @Test
    void managerActionDialogsConstruct() {
        // V2-CADMIN-03 dialogs — build path only (open() needs a UI context).
        assertDoesNotThrow(() -> new LkConfirm("Revoke manager", "Remove Carol?",
                LkConfirm.Severity.danger)
                .confirmText("Revoke"),
            "LkConfirm failed to construct");
        assertDoesNotThrow(() -> new EditPermissionsDialog(
                "carol", "Manager", List.of("VIEW_SALES"), names -> { }),
            "EditPermissionsDialog failed to construct");
    }

    @Test
    void kitComponentsInstantiate() {
        // Generic kit primitives — these underpin every view.
        assertDoesNotThrow(() -> new LkIcon("ticket"),    "LkIcon failed");
        assertDoesNotThrow(() -> new LkBtn("Sign in"),    "LkBtn failed");
        assertDoesNotThrow(() -> new LkCard("Card"),      "LkCard failed");
        assertDoesNotThrow(() -> new LkBadge("OK"),       "LkBadge failed");
        // Repeatable add-row text input (event-creation Artists field).
        assertDoesNotThrow(() -> {
            LkTextRows rows = new LkTextRows("Artists", "+ Add artist").placeholder("e.g. The Beatles");
            rows.setValues(List.of("The Beatles", "Pink Floyd"));
            rows.readOnly(true);
        }, "LkTextRows failed");
        // Live top-bar search panel (#281) — debounced input + search-fn callback, no DI needed.
        assertDoesNotThrow(() -> new LkSearchPanel(List.of("Coldplay"), q -> List.of()),
            "live LkSearchPanel failed");
        // Domain components used by the venue / seat picker views.
        assertDoesNotThrow(() -> new VkSeat(VkSeat.State.free, "1"), "VkSeat failed");
        assertDoesNotThrow(VkSeatLegend::new,             "VkSeatLegend failed");
        assertDoesNotThrow(() -> {
            VkSeat seat = new VkSeat(VkSeat.State.free, "1");
            seat.setState(VkSeat.State.mine);
            seat.setState(VkSeat.State.sold);
            seat.setState(VkSeat.State.free);
        }, "VkSeat.setState failed");
        assertDoesNotThrow(() -> new VkSeatedZonePicker(List.of(), null),
            "VkSeatedZonePicker (empty) failed");
        assertDoesNotThrow(() -> new VkSeatedZonePicker(
                List.of(new VkSeatedZonePicker.SeatModel("A1", 0, 0, VkSeat.State.free),
                        new VkSeatedZonePicker.SeatModel("A2", 32, 0, VkSeat.State.held)),
                null), "VkSeatedZonePicker (with seats) failed");
        assertDoesNotThrow(() -> new VkQuantitySelector(100, 9000, null),
            "VkQuantitySelector (positive available) failed");
        assertDoesNotThrow(() -> new VkQuantitySelector(0, 9000, null),
            "VkQuantitySelector (sold out) failed");
        assertDoesNotThrow(() -> new VkStandingZone("GA", 12, 3, 100, "$45"),
            "VkStandingZone failed");
    }

    @Test
    void toastsUtilityIsLoadable() {
        // Just checking the class loads — we can't invoke Notification.show
        // outside a UI context.
        assertNotNull(Toasts.class);
    }
}
