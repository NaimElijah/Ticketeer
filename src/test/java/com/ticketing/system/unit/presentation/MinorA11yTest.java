package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.InboxViewHarness.displayTime;
import static com.ticketing.system.unit.presentation.InboxViewHarness.thread;
import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_ID;
import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_TOKEN;
import static com.ticketing.system.unit.presentation.ViewHarness.buttons;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.field;
import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static com.ticketing.system.unit.presentation.ViewHarness.founderOf;
import static com.ticketing.system.unit.presentation.ViewHarness.pageTitle;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.ZoneType;
import com.ticketing.system.organization.application.dto.OrganizationalTreeNodeDTO;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.reporting.application.service.MemberAccountService;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.RefundStatus;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.TicketRecordDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.TransactionRecordDTO;
import com.ticketing.system.sales.config.ReservationHoldProperties;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.shared.config.PlatformTimeProperties;
import com.ticketing.system.shared.dto.AppointmentInfoDTO;
import com.ticketing.system.shared.dto.CompanySummaryDTO;
import com.ticketing.system.shared.dto.EventSummaryDTO;
import com.ticketing.system.shared.dto.InventoryZoneDTO;
import com.ticketing.system.shared.dto.InvitationDTO;
import com.ticketing.system.shared.dto.MarketStateDTO;
import com.ticketing.system.shared.dto.MemberDTO;
import com.ticketing.system.shared.dto.SeatDTO;
import com.ticketing.system.shared.dto.SystemAnalyticsDTO;
import com.ticketing.system.ui.components.admin.OrgTreeLegend;
import com.ticketing.system.ui.components.admin.OrgTreeRenderer;
import com.ticketing.system.ui.components.auth.PasswordStrengthMeter;
import com.ticketing.system.ui.components.kit.LkChip;
import com.ticketing.system.ui.components.kit.LkFilterChip;
import com.ticketing.system.ui.components.kit.LkGrid;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkIconBtn;
import com.ticketing.system.ui.components.kit.LkStat;
import com.ticketing.system.ui.components.kit.LkTopBar;
import com.ticketing.system.ui.components.venue.PolicyRule;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.presenters.account.MyAccountPresenter;
import com.ticketing.system.ui.presenters.account.MyInvitationsPresenter;
import com.ticketing.system.ui.presenters.account.MyProfilePresenter;
import com.ticketing.system.ui.presenters.account.ReceiptPresenter;
import com.ticketing.system.ui.presenters.account.RefundPresenter;
import com.ticketing.system.ui.presenters.admin.SystemAnalyticsPresenter;
import com.ticketing.system.ui.presenters.auth.LoginPresenter;
import com.ticketing.system.ui.presenters.auth.PasswordStrength;
import com.ticketing.system.ui.presenters.catalog.BrowseEventsPresenter;
import com.ticketing.system.ui.presenters.catalog.EventDetailsPresenter;
import com.ticketing.system.ui.presenters.catalog.SeatPickerPresenter;
import com.ticketing.system.ui.presenters.company.CompanyEventListPresenter;
import com.ticketing.system.ui.presenters.company.EventManagementPresenter;
import com.ticketing.system.ui.presenters.company.ManagerListPresenter;
import com.ticketing.system.ui.presenters.messaging.AdminComplaintQueuePresenter;
import com.ticketing.system.ui.presenters.messaging.AdminSendMessagesPresenter;
import com.ticketing.system.ui.presenters.messaging.NewInquiryPresenter;
import com.ticketing.system.ui.presenters.order.CartPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.support.DisplayTime;
import com.ticketing.system.ui.views.account.MyAccountView;
import com.ticketing.system.ui.views.account.MyInvitationsView;
import com.ticketing.system.ui.views.account.MyProfileView;
import com.ticketing.system.ui.views.account.ReceiptView;
import com.ticketing.system.ui.views.admin.AdminComplaintQueueView;
import com.ticketing.system.ui.views.admin.AdminSendMessagesView;
import com.ticketing.system.ui.views.admin.SystemAnalyticsView;
import com.ticketing.system.ui.views.auth.LoginView;
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.ticketing.system.ui.views.catalog.EventDetailsView;
import com.ticketing.system.ui.views.catalog.SeatPickerView;
import com.ticketing.system.ui.views.company.CompanyEventListView;
import com.ticketing.system.ui.views.company.EventManagementView;
import com.ticketing.system.ui.views.company.ManagerListView;
import com.ticketing.system.ui.views.messaging.NewInquiryView;
import com.ticketing.system.ui.views.order.CartView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.PollEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.PageTitle;

/**
 * The smaller defects scan 7 found, each in its own block (#188, A-m4, A-m9, A-m10, A-m11, A-m14, A-m15,
 * P-m3, P-m4, P-m8, P-m11, P-V5, U-A13, U-A18, U-A20, U-A23, U-B3, U-B4, U-B6; WCAG 1.1.1, 1.3.1, 1.4.1,
 * 2.2.2, 2.4.2, 2.4.6, 2.5.3, 4.1.2, 4.1.3):
 * <ul>
 *   <li>the password strength meter says its tier and each rule's state in words;</li>
 *   <li>icon buttons have an aria-label, and repeated row buttons say which row they act on;</li>
 *   <li>the kit's grid has no empty header cell, and a table is named;</li>
 *   <li>decorative glyphs are hidden from screen readers, and a rating reads "Rating 4.5 of 5";</li>
 *   <li>the org tree's lists stay lists, and its avatar initials and legend dots are hidden;</li>
 *   <li>page titles and headings match what the page is;</li>
 *   <li>a read-only value is plain text, not a fake input inside a label;</li>
 *   <li>the auto-updating analytics say when they were last updated;</li>
 *   <li>the cart-hold chip says what its time is;</li>
 *   <li>the chosen company of a new inquiry is labelled and removable;</li>
 *   <li>the receipt's refund badges reflect the refund's state;</li>
 *   <li>the seat picker's instructions do not rely on colour;</li>
 *   <li>the alert-circle icon draws a glyph;</li>
 *   <li>"All members" and "All producers", which exclude each other, are a named radio group.</li>
 * </ul>
 */
class MinorA11yTest {

    // ---- F20: the minors (#188) ----

    private static final LocalDateTime PAID_AT = LocalDateTime.of(2027, 7, 1, 12, 0);
    private static final int ORDER_ID = 42;
    private static final Pattern NOT_MET = Pattern.compile("(?i)\\bnot met\\b");
    private static final Pattern MET = Pattern.compile("(?i)\\bmet\\b");
    // The hour and minute of a time, as a screen shows it whatever its full format.
    private static final DateTimeFormatter TIME_OF_DAY = DateTimeFormatter.ofPattern("HH:mm", Locale.US);
    private static final int POLL_INTERVAL_MS = 5_000;

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- PasswordStrengthMeter ----

    @Test
    void GivenTheStrengthMeter_WhenItRenders_ThenItsBarIsNamedPasswordStrength() {
        PasswordStrengthMeter meter = new PasswordStrengthMeter();

        Element bar = strengthBar(meter);
        assertTrue(A11y.name(bar).toLowerCase(Locale.ROOT).contains("password strength"),
            "the bar must be named \"Password strength\": " + A11y.describe(bar));
    }

    @Test
    void GivenAPasswordTyped_WhenTheMeterUpdates_ThenTheBarSaysItsTierInWords() {
        PasswordStrengthMeter meter = new PasswordStrengthMeter();

        meter.update("abcdefgh");

        Element bar = strengthBar(meter);
        assertEquals(PasswordStrength.of("abcdefgh").label(), bar.getAttribute("aria-valuetext"),
            "the bar must say its tier in words (aria-valuetext), not only a fraction: " + A11y.describe(bar));
    }

    @Test
    void GivenAPasswordTyped_WhenTheMeterUpdates_ThenTheTierLabelIsInALiveRegion() {
        PasswordStrengthMeter meter = new PasswordStrengthMeter();
        meter.update("abcdefgh");

        String tierText = PasswordStrength.of("abcdefgh").label();
        List<Element> showing = A11y.elements(meter, e -> A11y.text(e).contains(tierText));
        assertFalse(showing.isEmpty(), "the meter does not show its tier \"" + tierText + "\"");
        Element tier = showing.get(showing.size() - 1);
        assertTrue(A11y.politeLiveRegion(tier).isPresent(),
            "the tier label changes as the user types, so it must be live (aria-live=polite or role=status): "
                + A11y.describe(tier));
    }

    @Test
    void GivenAPasswordMeetingSomeRules_WhenTheMeterUpdates_ThenEachRuleSaysMetOrNotMetInWords() {
        PasswordStrengthMeter meter = new PasswordStrengthMeter();

        meter.update("abcdefgh");

        String met = A11y.text(ruleElement(meter, "8+ chars"));
        String unmet = A11y.text(ruleElement(meter, "digit"));
        assertTrue(MET.matcher(met).find() && !NOT_MET.matcher(met).find(),
            "a rule the password meets must say \"met\" in words, not only by its icon and colour; it reads \""
                + met + "\"");
        assertTrue(NOT_MET.matcher(unmet).find(),
            "a rule the password misses must say \"not met\" in words; it reads \"" + unmet + "\"");
    }

    // ---- LkIconBtn and row context ----

    @Test
    void GivenAnIconButtonWithATooltip_WhenItRenders_ThenItHasAnAriaLabelNotOnlyATitle() {
        LkIconBtn button = new LkIconBtn(new LkIcon("edit", 15), "Edit");

        assertEquals("Edit", button.getElement().getAttribute("aria-label"),
            "an icon-only button must be named by aria-label; a title is not read everywhere: "
                + A11y.describe(button.getElement()));
    }

    @Test
    void GivenAnEventRow_WhenMyEventsRenders_ThenEachIconButtonNamesTheEvent() {
        ViewHarness.signInMember();
        harness.memberships(founderOf(3, "Acme"));
        CompanyEventListPresenter presenter = mock(CompanyEventListPresenter.class);
        when(presenter.load(any(), any(), any())).thenReturn(new CompanyEventListPresenter.Outcome.Success(
            List.of(new EventDetailDTO("9", "Othello", null, "", EventCategory.THEATER, null, "3", "Acme",
                EventStatus.DRAFT, List.of(), ZoneOffset.UTC, List.of(), 0.0))));
        when(presenter.countries(any(), any())).thenReturn(new ListOutcome.Loaded<>(List.of()));
        CompanyEventListView view = new CompanyEventListView(presenter, displayTime());

        List<LkIconBtn> rowButtons = find(view, LkIconBtn.class);
        assertFalse(rowButtons.isEmpty(), "the event row has no icon buttons");
        for (LkIconBtn button : rowButtons) {
            assertAriaLabelNames(button.getElement(), "Othello");
        }
    }

    @Test
    void GivenAManager_WhenTheRosterRenders_ThenTheRevokeButtonNamesTheManager() {
        ViewHarness.signInMember();
        harness.memberships(founderOf(3, "Acme"));
        ManagerListPresenter presenter = mock(ManagerListPresenter.class);
        when(presenter.loadRoster(any())).thenReturn(new ManagerListPresenter.Outcome.Success("Acme",
            List.of(new AppointmentInfoDTO("a-9", 3, "Acme", 9, "carol", MEMBER_ID, "Manager", "ACTIVE",
                List.of("VIEW_SALES"), PAID_AT)), List.of()));
        ManagerListView view = new ManagerListView(presenter, displayTime());

        Element revoke = buttonNamedStartingWith(view, "Revoke");
        assertAriaLabelNames(revoke, "carol");
    }

    @Test
    void GivenAnOrder_WhenMyAccountRenders_ThenItsViewAndRefundButtonsNameTheOrder() {
        MyAccountView view = myAccount();

        Element eye = eyeButton(view);
        Element viewOrder = buttonsNamedStartingWith(view, "View").stream()
            .filter(e -> !e.equals(eye))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the order has no View button"));
        assertAriaLabelNames(viewOrder, String.valueOf(ORDER_ID));
        assertAriaLabelNames(buttonNamedStartingWith(view, "Request Refund"), String.valueOf(ORDER_ID));
    }

    @Test
    void GivenATicket_WhenMyAccountRenders_ThenItsViewTicketButtonNamesTheTicket() {
        MyAccountView view = myAccount();

        Element eye = eyeButton(view);
        String name = A11y.name(eye);
        assertTrue(eye.hasAttribute("aria-label") && (name.contains("Coldplay") || name.contains("A1")),
            "the ticket's eye button must say which ticket it opens in its aria-label (event or seat): "
                + A11y.describe(eye));
    }

    @Test
    void GivenAPendingInvitation_WhenMyInvitationsRenders_ThenAcceptAndRejectNameTheCompany() {
        ViewHarness.signInMember();
        MyInvitationsPresenter presenter = mock(MyInvitationsPresenter.class);
        when(presenter.load(any())).thenReturn(new MyInvitationsPresenter.Outcome.Success(List.of(
            new InvitationDTO("a-1", 3, "Acme", "Manager", "bob", List.of("VIEW_SALES"), "PENDING", PAID_AT)),
            List.of()));
        MyInvitationsView view = new MyInvitationsView(presenter, displayTime());

        assertNameIncludes(buttonNamedStartingWith(view, "Accept"), "Acme");
        assertNameIncludes(buttonNamedStartingWith(view, "Reject"), "Acme");
    }

    @Test
    void GivenACartLine_WhenTheCartRenders_ThenItsRemoveButtonNamesTheLine() {
        CartPresenter presenter = mock(CartPresenter.class);
        when(presenter.loadCart()).thenReturn(new CartPresenter.LoadOutcome.Shown(new CartPresenter.CartVM(
            List.of(new CartPresenter.CartVM.LineVM("Coldplay", "A1", 10.0, 5, 8)), 300), 1000));
        CartView view = new CartView(presenter);

        assertNameIncludes(buttonNamedStartingWith(view, "Remove"), "Coldplay");
    }

    @Test
    void GivenAnEvent_WhenBrowseRenders_ThenItsReserveButtonNamesTheEvent() {
        BrowseEventsView view = browse(4.5);

        assertNameIncludes(buttonNamedStartingWith(view, "Reserve"), "Hamlet");
    }

    // ---- LkGrid ----

    @Test
    void GivenAGridWithAnActionColumn_WhenItIsBuilt_ThenNoHeaderCellIsEmpty() {
        LkGrid grid = new LkGrid().col("Event", "name").col("", "act");
        grid.row(java.util.Map.of("name", "Hamlet", "act", new NativeButton("Reserve"))).build();

        List<Element> headers = A11y.elements(grid, e -> "th".equals(e.getTag()));
        assertEquals(2, headers.size(), "the grid has two columns");
        for (Element th : headers) {
            assertFalse(A11y.text(th).isBlank(), "every column header must say what its column holds (visually "
                + "hidden text is fine for an action column); found an empty " + A11y.describe(th));
        }
    }

    @Test
    void GivenBrowse_WhenItsEventsTableRenders_ThenTheTableIsNamed() {
        Element table = A11y.elements(browse(4.5), e -> "table".equals(e.getTag())).get(0);

        String name = A11y.name(table);
        String caption = table.getChildren().filter(c -> "caption".equals(c.getTag())).map(A11y::text)
            .findFirst().orElse("");
        assertFalse(name.isBlank() && caption.isBlank(),
            "the events table must have an accessible name (a caption, aria-label or aria-labelledby): "
                + A11y.describe(table));
    }

    // ---- decorative glyphs and ratings ----

    @Test
    void GivenAFilterChip_WhenItRenders_ThenItsCaretIsHidden() {
        assertGlyphsHidden(new LkFilterChip("Status", List.of("Open", "Closed")), "▾");
    }

    @Test
    void GivenAChipWithACaret_WhenItRenders_ThenTheCaretIsHidden() {
        assertGlyphsHidden(new LkChip("Category").withCaret(), "▾");
    }

    @Test
    void GivenAPolicyRule_WhenItRenders_ThenItsDragHandleAndCaretAreHidden() {
        PolicyRule rule = new PolicyRule("Quantity", "≤", "4", "tickets");

        assertGlyphsHidden(rule, "⋮⋮");
        assertGlyphsHidden(rule, "▾");
    }

    @Test
    void GivenTheSignInPage_WhenItRenders_ThenNoLinkReadsItsArrow() {
        LoginView view = new LoginView(mock(LoginPresenter.class), mock(SessionIdentity.class));

        List<Element> links = A11y.elements(view, A11y::isLink);
        assertFalse(links.isEmpty(), "the sign-in page has no links");
        for (Element link : links) {
            assertFalse(A11y.name(link).contains("→"),
                "the \"→\" is decoration and must be aria-hidden, out of the link's name: " + A11y.describe(link));
        }
    }

    @Test
    void GivenAnEventPage_WhenItRenders_ThenTheChooseTicketsButtonDoesNotReadItsArrow() {
        Element choose = buttonNamedStartingWith(eventPage(), "Choose Tickets");

        assertFalse(A11y.name(choose).contains("→"),
            "the \"→\" is decoration and must be aria-hidden, out of the button's name: " + A11y.describe(choose));
    }

    // A disabled button that ends in "→" invites an action that does not exist (PR #253 review 3).
    @Test
    void GivenACancelledEvent_WhenItsPageRenders_ThenTheDisabledTicketsButtonShowsNoArrow() {
        assertEndedButtonShowsNoArrow(EventStatus.CANCELED, "Event Cancelled");
    }

    @Test
    void GivenACompletedEvent_WhenItsPageRenders_ThenTheDisabledTicketsButtonShowsNoArrow() {
        assertEndedButtonShowsNoArrow(EventStatus.COMPLETED, "Event Ended");
    }

    @Test
    void GivenARatedEvent_WhenBrowseRenders_ThenItsRatingReadsAsRatingOutOfFive() {
        assertRatingReadAloud(browse(4.5));
    }

    @Test
    void GivenARatedEvent_WhenItsPageRenders_ThenItsRatingReadsAsRatingOutOfFive() {
        assertRatingReadAloud(eventPage());
    }

    // ---- org tree ----

    @Test
    void GivenAnOrgTree_WhenItRenders_ThenEachListKeepsItsListRole() {
        OrgTreeRenderer tree = new OrgTreeRenderer(orgTree());

        List<Element> lists = A11y.elements(tree, e -> "ul".equals(e.getTag()));
        assertEquals(2, lists.size(), "a founder with one appointee renders two nested lists");
        for (Element ul : lists) {
            assertEquals("list", ul.getAttribute("role"),
                "an unstyled list loses its list role in Safari, so it must say role=list: " + A11y.describe(ul));
        }
    }

    @Test
    void GivenAnOrgTree_WhenItRenders_ThenEachAvatarInitialIsHidden() {
        OrgTreeRenderer tree = new OrgTreeRenderer(orgTree());

        List<Element> avatars = A11y.elements(tree, e -> e.getClassList().contains("oc-avatar"));
        assertEquals(2, avatars.size(), "each of the two members has an avatar");
        for (Element avatar : avatars) {
            assertTrue(A11y.ariaHidden(avatar), "the avatar's initial repeats the name beside it and must be "
                + "aria-hidden: " + A11y.describe(avatar));
        }
    }

    @Test
    void GivenTheOrgTreeLegend_WhenItRenders_ThenItsColourDotsAreHidden() {
        OrgTreeLegend legend = new OrgTreeLegend();

        List<Element> dots = A11y.elements(legend, e -> "●".equals(e.getText()));
        assertEquals(3, dots.size(), "the legend has a dot for each role");
        for (Element dot : dots) {
            assertTrue(A11y.ariaHidden(dot), "the legend's dot is decoration and must be aria-hidden: "
                + A11y.describe(dot));
        }
    }

    // ---- page titles ----

    @Test
    void GivenTheEventPageInEditMode_WhenItIsEntered_ThenItsDocumentTitleSaysEditNotCreate() {
        ViewHarness.signInMember();
        EventManagementPresenter presenter = mock(EventManagementPresenter.class);
        when(presenter.load(any(), eq(5))).thenReturn(new EventManagementPresenter.LoadOutcome.Success(
            new EventDetailDTO("5", "Coldplay", 4.5, "Live", EventCategory.CONCERT, null, "3", "Acme",
                EventStatus.ON_SALE, List.of(), ZoneOffset.UTC, List.of("Coldplay"), 50.0)));
        EventManagementView view = new EventManagementView(presenter);

        view.beforeEnter(routeTo("eventId", "5"));

        String title = documentTitle(view);
        assertTrue(title.contains("Edit") && !title.contains("Create"),
            "editing an event must not be titled as creating one (HasDynamicTitle); the title is \"" + title + "\"");
    }

    @Test
    void GivenTheEventPageInCreateMode_WhenItIsEntered_ThenItsDocumentTitleSaysCreateNotEdit() {
        ViewHarness.signInMember();
        EventManagementPresenter presenter = mock(EventManagementPresenter.class);
        when(presenter.defaultTimeZone()).thenReturn(ZoneOffset.UTC);
        EventManagementView view = new EventManagementView(presenter);

        view.beforeEnter(routeTo("eventId", "new"));

        String title = documentTitle(view);
        assertTrue(title.contains("Create") && !title.contains("Edit"),
            "creating an event must not be titled as editing one (HasDynamicTitle); the title is \"" + title + "\"");
    }

    @Test
    void GivenSendMessages_WhenItRenders_ThenItsH1MatchesItsNavigationName() {
        ViewHarness.signInAdmin();
        AdminSendMessagesView view = sendMessagesView();

        assertEquals("Send Messages", pageTitle(view),
            "the page's h1 must match the nav item that opens it, \"Send Messages\"");
    }

    @Test
    void GivenTheComplaintQueue_WhenItRenders_ThenItsSubtitleShowsNoInternalTypeName() {
        ViewHarness.signInAdmin();
        AdminComplaintQueuePresenter presenter = mock(AdminComplaintQueuePresenter.class);
        when(presenter.load(any(), anyString())).thenReturn(new AdminComplaintQueuePresenter.Outcome.Success(
            List.of(thread("c1", "COMPLAINT", "ADMIN_GROUP"))));
        when(presenter.badge(any(), any())).thenReturn(Optional.empty());
        AdminComplaintQueueView view = new AdminComplaintQueueView(presenter, displayTime());

        String text = A11y.text(view.getElement());
        assertTrue(text.contains("Complaints members have sent to the TicketHub team."),
            "the subtitle must say what the queue holds, in words: " + text);
        assertFalse(text.contains("type=COMPLAINT"),
            "the subtitle must not show the internal \"Conversation type=COMPLAINT\": " + text);
    }

    // ---- Read-only profile values ----

    @Test
    void GivenTheProfile_WhenItRenders_ThenTheUsernameAndEmailArePlainTextNotFakeInputsInLabels() {
        ViewHarness.signInMember();
        MyProfilePresenter presenter = mock(MyProfilePresenter.class);
        when(presenter.load()).thenReturn(new MyProfilePresenter.Outcome.Success(
            new MemberDTO(MEMBER_ID, "alice", "alice@example.com")));
        MyProfileView view = new MyProfileView(presenter);

        for (String value : List.of("alice", "alice@example.com")) {
            List<Element> showing = A11y.elements(view, e -> value.equals(e.getText()));
            assertFalse(showing.isEmpty(), "the profile does not show " + value);
            for (Element e = showing.get(0); e != null; e = e.getParent()) {
                assertFalse("label".equals(e.getTag()) || e.getClassList().contains("lk-input"),
                    "\"" + value + "\" is read-only text, so it must not sit in a label or look like an input: "
                        + A11y.describe(e));
            }
        }
    }

    // ---- SystemAnalyticsView ----

    @Test
    void GivenTheAnalytics_WhenTheyLoad_ThenThePageSaysWhenTheyWereLastUpdated() {
        SystemAnalyticsView view = new SystemAnalyticsView(analyticsPresenter(), displayTime());

        assertTrue(A11y.text(view.getElement()).toLowerCase(Locale.ROOT).contains("last updated"),
            "figures that update on their own must say when they last did (\"Last updated …\"): "
                + A11y.text(view.getElement()));
    }

    @Test
    void GivenTheAnalytics_WhenTheyLoad_ThenLastUpdatedShowsTheClocksTime() {
        SystemAnalyticsView view = new SystemAnalyticsView(analyticsPresenter(), displayTime());

        String shown = lastUpdatedLine(view);
        String now = displayTime().formatNow(TIME_OF_DAY);
        assertTrue(shown.contains(now), "\"Last updated\" must give the time of the load, " + now
            + " on the platform clock as DisplayTime shows it; it reads \"" + shown + "\"");
    }

    @Test
    void GivenALoadedDashboard_WhenARefreshFails_ThenLastUpdatedStillShowsTheLastSuccess() {
        MovableClock clock = new MovableClock(InboxViewHarness.NOW.toInstant(ZoneOffset.UTC));
        DisplayTime displayTime = new DisplayTime(clock, new PlatformTimeProperties(ZoneOffset.UTC));
        SystemAnalyticsPresenter presenter = analyticsPresenter();
        when(presenter.load()).thenReturn(
            new SystemAnalyticsPresenter.Outcome.Success(
                new MarketStateDTO("OPEN", null, null, true, true, null, true),
                new SystemAnalyticsDTO(0L, 0d, 0d, 0d, 0d, 0d, 0L, 0L, 0L, 0L, 0L, 5)),
            new SystemAnalyticsPresenter.Outcome.Failure("analytics-down"));
        SystemAnalyticsView view = new SystemAnalyticsView(presenter, displayTime);
        harness.attach(view);
        String loadedAt = displayTime.formatNow(TIME_OF_DAY);
        clock.advance(Duration.ofMinutes(5));

        click(buttons(view, "Refresh").get(0));

        String shown = lastUpdatedLine(view);
        assertTrue(shown.contains(loadedAt) && !shown.contains(displayTime.formatNow(TIME_OF_DAY)),
            "a failed refresh updated nothing, so \"Last updated\" must still give the last success, " + loadedAt
                + "; it reads \"" + shown + "\"");
    }

    // Kept: figures refreshed on a timer are not read out on every tick. Each stat's value is a figure,
    // found by structure so the check holds whatever the figures are or how a locale writes them.
    @Test
    void GivenTheAnalytics_WhenTheyLoad_ThenTheAutoUpdatingFiguresAreNotLive() {
        SystemAnalyticsView view = new SystemAnalyticsView(analyticsPresenter(), displayTime());

        List<Element> figures = find(view, LkStat.class).stream()
            .map(stat -> stat.getElement().getChild(1))
            .toList();
        assertEquals(8, figures.size(), "the dashboard shows eight figures: three counts and five rates");
        List<Element> live = figures.stream().filter(A11y::inLiveRegion).toList();
        assertTrue(live.isEmpty(), "auto-updating figures in a live region would be read on every refresh: "
            + live.stream().map(A11y::describe).toList());
    }

    // ---- SystemAnalyticsView pause (WCAG 2.2.2, review Q) ----

    @Test
    void GivenTheAnalytics_WhenTheyLoad_ThenANamedToggleOffersToPauseTheAutoRefresh() {
        SystemAnalyticsView view = new SystemAnalyticsView(pollingAnalyticsPresenter(), displayTime());

        Element pause = pauseToggle(view);
        assertEquals("false", pause.getAttribute("aria-pressed"),
            "while the figures refresh on their own the pause toggle is not pressed: " + A11y.describe(pause));
    }

    @Test
    void GivenTheAutoRefreshRunning_WhenTheAdminPressesPause_ThenTheToggleIsPressed() {
        SystemAnalyticsView view = new SystemAnalyticsView(pollingAnalyticsPresenter(), displayTime());
        harness.attach(view);

        A11y.activate(pauseToggle(view));

        assertEquals("true", pauseToggle(view).getAttribute("aria-pressed"),
            "a paused auto-refresh must say so: " + A11y.describe(pauseToggle(view)));
    }

    @Test
    void GivenTheAutoRefreshPaused_WhenAPollTicks_ThenTheFiguresAreNotReloaded() {
        SystemAnalyticsPresenter presenter = pollingAnalyticsPresenter();
        SystemAnalyticsView view = new SystemAnalyticsView(presenter, displayTime());
        harness.attach(view);
        A11y.activate(pauseToggle(view));
        clearInvocations(presenter);

        pollTick();

        verify(presenter, never()).load();
    }

    @Test
    void GivenTheAutoRefreshPausedThenResumed_WhenAPollTicks_ThenTheFiguresReload() {
        SystemAnalyticsPresenter presenter = pollingAnalyticsPresenter();
        SystemAnalyticsView view = new SystemAnalyticsView(presenter, displayTime());
        harness.attach(view);
        A11y.activate(pauseToggle(view));
        A11y.activate(pauseToggle(view));
        clearInvocations(presenter);

        pollTick();

        assertEquals("false", pauseToggle(view).getAttribute("aria-pressed"), "a resumed auto-refresh is not paused");
        verify(presenter, times(1)).load();
    }

    // Kept: the auto-refresh the toggle pauses is real, so the pause tests above are not vacuous.
    @Test
    void GivenTheAutoRefreshRunning_WhenAPollTicks_ThenTheFiguresReload() {
        SystemAnalyticsPresenter presenter = pollingAnalyticsPresenter();
        SystemAnalyticsView view = new SystemAnalyticsView(presenter, displayTime());
        harness.attach(view);
        clearInvocations(presenter);

        pollTick();

        verify(presenter, times(1)).load();
    }

    // ---- cart-hold chip ----

    @Test
    void GivenACartOnHold_WhenTheTopBarRenders_ThenTheHoldChipSaysWhatItsTimeIs() {
        LkTopBar bar = new LkTopBar(LkTopBar.Variant.MAIN).cart(null, 2, 300L);

        Element chip = A11y.elements(bar, e -> e.getClassList().contains("bz-timer")).get(0);
        assertTrue(A11y.text(chip).toLowerCase(Locale.ROOT).contains("held for"),
            "the chip shows only a time; it must say what the time is (\"Cart held for\"): " + A11y.describe(chip)
                + " reads \"" + A11y.text(chip) + "\"");
    }

    // ---- NewInquiryView chosen company ----

    @Test
    void GivenAChosenCompany_WhenTheInquiryFormShowsIt_ThenItCanBeRemoved() {
        NewInquiryView view = inquiryWithAcmeChosen();

        List<Element> removers = A11y.elements(view, e -> A11y.isButton(e)
            && Pattern.compile("(?i)remove.*acme").matcher(A11y.name(e)).find());
        assertFalse(removers.isEmpty(), "the chosen company must have a button named \"Remove Acme\"");

        A11y.activate(removers.get(0));

        assertFalse(chosenCompany(view).isPresent(), "removing the chosen company must clear it");
    }

    @Test
    void GivenAChosenCompany_WhenTheInquiryFormShowsIt_ThenItIsLabelledAsTheCompany() {
        NewInquiryView view = inquiryWithAcmeChosen();
        Element chip = chosenCompany(view).orElseThrow(() -> new AssertionError("the chosen company is not shown"));
        Element search = field(view, TextField.class, "Production company").getElement();

        boolean labelled = false;
        for (Element a = chip; a != null && !labelled; a = a.getParent()) {
            String context = A11y.text(a).replace("Acme", "") + " " + A11y.name(a).replace("Acme", "");
            labelled = Pattern.compile("(?i)compan").matcher(context.replaceAll("(?i)remove", "")).find();
            if (A11y.within(search, a)) {
                break;
            }
        }
        assertTrue(labelled, "the chosen company must be labelled as the company the inquiry goes to (e.g. "
            + "\"Company: Acme\"), not shown as a bare chip: " + A11y.describe(chip));
    }

    // ---- ReceiptView refund badges ----

    @Test
    void GivenAPartlyRefundedReceipt_WhenItRenders_ThenTheRefundStatusSaysPartly() {
        MemberAccountService accountService = mock(MemberAccountService.class);
        when(accountService.viewMyReceipt(MEMBER_TOKEN, ORDER_ID)).thenReturn(receipt(RefundStatus.PARTIAL));
        SessionIdentity identity = mock(SessionIdentity.class);
        when(identity.memberToken()).thenReturn(MEMBER_TOKEN);
        ReceiptView view = new ReceiptView(new ReceiptPresenter(accountService), mock(RefundPresenter.class),
            identity, displayTime());

        view.beforeEnter(routeTo("receiptId", String.valueOf(ORDER_ID)));

        Element section = refundStatusSection(view);
        String text = A11y.text(section);
        assertTrue(Pattern.compile("(?i)\\bpart(ial|ly)").matcher(text).find(),
            "a partial refund must read as partial, not as every step ticked: \"" + text + "\"");
    }

    // ---- SeatPickerView instructions ----

    @Test
    void GivenASeatedZone_WhenThePickerRenders_ThenItsInstructionsDoNotRelyOnColour() {
        SeatPickerPresenter presenter = mock(SeatPickerPresenter.class);
        when(presenter.loadZone(anyInt(), anyInt())).thenReturn(new SeatPickerPresenter.LoadOutcome.Loaded(
            new InventoryZoneDTO(8, "Floor", "SEATED", 2, 2, 0, 0, 10.0,
                List.of(new SeatDTO("A1", 1, 0, "AVAILABLE"), new SeatDTO("A2", 2, 0, "AVAILABLE")), null),
            ZoneType.SEATED));
        SeatPickerView view = new SeatPickerView(presenter, new ReservationHoldProperties(10));
        view.beforeEnter(routeTo("eventId", "5", "zoneId", "8"));

        String text = A11y.text(view.getElement());
        assertTrue(Pattern.compile("(?i)\\bavailable seat\\b").matcher(text).find(),
            "the instruction must say which seats to choose by their state, \"an available seat\": " + text);
        assertFalse(Pattern.compile("(?i)\\b(green|blue)\\b").matcher(text).find(),
            "an instruction must not name a colour (\"click a green seat\"), which a colour-blind user cannot "
                + "follow; say \"an available seat\": " + text);
    }

    // ---- LkIcon ----

    @Test
    void GivenTheAlertCircleIcon_WhenItRenders_ThenItDrawsAGlyph() {
        String svg = new LkIcon("alert-circle", 16).getElement().getProperty("innerHTML", "");

        assertTrue(svg.contains("<path") || svg.contains("<circle"),
            "LkIcon(\"alert-circle\") must draw a glyph; it renders an empty svg: " + svg);
    }

    // ---- AdminSendMessagesView broadcast choice ----

    @Test
    void GivenTheComposer_WhenItRenders_ThenAllMembersAndAllProducersAreRadiosInANamedGroup() {
        ViewHarness.signInAdmin();
        AdminSendMessagesView view = sendMessagesView();

        List<Element> groups = new ArrayList<>();
        for (String choice : List.of("All members", "All producers")) {
            Element control = A11y.elements(view, e -> choice.equals(A11y.name(e)) && !A11y.role(e).isEmpty())
                .stream()
                .filter(e -> !"generic".equals(A11y.role(e)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no control named \"" + choice + "\""));
            assertEquals("radio", A11y.role(control), "\"All members\" and \"All producers\" exclude each other, "
                + "so each must be a radio, not a checkbox: " + A11y.describe(control));
            Element group = radioGroup(control).orElseThrow(() -> new AssertionError(
                "\"" + choice + "\" must sit in a radio group (role=radiogroup or a RadioButtonGroup): "
                    + A11y.describe(control)));
            assertFalse(groupName(group).isBlank(), "the radio group must be named: " + A11y.describe(group));
            groups.add(group);
        }
        // Two radios in two groups can both be on, which is the defect the radios fix.
        assertEquals(groups.get(0), groups.get(1), "\"All members\" and \"All producers\" must share one group");
    }

    // ---- assertions ------------------------------------------------------------------------------

    private static void assertAriaLabelNames(Element button, String rowContext) {
        String label = button.getAttribute("aria-label");
        assertTrue(label != null && label.contains(rowContext),
            "a button repeated on every row must say which row it acts on in its aria-label (\"… " + rowContext
                + "\"): " + A11y.describe(button));
    }

    private static void assertNameIncludes(Element button, String rowContext) {
        assertTrue(A11y.name(button).contains(rowContext),
            "a button repeated on every row must say which row it acts on (\"… " + rowContext + "\"): "
                + A11y.describe(button));
    }

    private static void assertGlyphsHidden(Component root, String glyph) {
        List<Element> glyphs = A11y.elements(root, e -> glyph.equals(e.getTextRecursively().trim())
            && e.getChildren().noneMatch(child -> !child.isTextNode()));
        assertFalse(glyphs.isEmpty(), "no \"" + glyph + "\" rendered");
        for (Element e : glyphs) {
            assertTrue(A11y.ariaHidden(e), "the \"" + glyph + "\" is decoration and must be aria-hidden: "
                + A11y.describe(e));
        }
    }

    private static void assertRatingReadAloud(Component view) {
        String text = A11y.text(view.getElement());
        assertFalse(text.contains("★"), "the star is decoration and must be aria-hidden: " + text);
        boolean namedElsewhere = A11y.elements(view, e -> A11y.name(e).contains("Rating 4.5 of 5")).size() > 0;
        assertTrue(text.contains("Rating 4.5 of 5") || namedElsewhere,
            "a rating must read \"Rating 4.5 of 5\": " + text);
    }

    // ---- arrangement -----------------------------------------------------------------------------

    private static Element strengthBar(PasswordStrengthMeter meter) {
        return A11y.elements(meter, e -> "vaadin-progress-bar".equals(e.getTag()) || "progressbar".equals(A11y.role(e)))
            .stream().findFirst().orElseThrow(() -> new AssertionError("the meter has no bar"));
    }

    // The smallest element that shows one rule's label: its chip.
    private static Element ruleElement(PasswordStrengthMeter meter, String rule) {
        List<Element> chips = A11y.elements(meter, e -> A11y.text(e).contains(rule)
            && e.getChildren().filter(c -> !c.isTextNode()).count() > 1);
        assertFalse(chips.isEmpty(), "the meter shows no rule \"" + rule + "\"");
        return chips.get(chips.size() - 1);
    }

    private static Element buttonNamedStartingWith(Component root, String prefix) {
        return buttonsNamedStartingWith(root, prefix).get(0);
    }

    private static List<Element> buttonsNamedStartingWith(Component root, String prefix) {
        List<Element> found = A11y.elements(root, e -> A11y.isButtonOrLink(e) && A11y.name(e).startsWith(prefix));
        assertFalse(found.isEmpty(), "no button named \"" + prefix + "…\" in: "
            + A11y.elements(root, A11y::isButtonOrLink).stream().map(A11y::describe).toList());
        return found;
    }

    private static Element eyeButton(Component view) {
        // The ticket row's only icon button.
        return find(view, LkIconBtn.class).stream()
            .map(Component::getElement)
            .findFirst()
            .orElseThrow(() -> new AssertionError("the ticket has no eye button"));
    }

    private static Optional<Element> radioGroup(Element radio) {
        for (Element a = radio.getParent(); a != null; a = a.getParent()) {
            if ("radiogroup".equals(A11y.role(a))) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    private static String groupName(Element group) {
        String name = A11y.name(group);
        return name.isBlank() ? group.getProperty("label", "") : name;
    }

    private MyAccountView myAccount() {
        ViewHarness.signInMember();
        MyAccountPresenter presenter = mock(MyAccountPresenter.class);
        when(presenter.loadHistory()).thenReturn(new MyAccountPresenter.Outcome.Success(
            new PurchaseHistoryDTO(List.of(receipt(RefundStatus.NONE)))));
        SessionIdentity identity = mock(SessionIdentity.class);
        when(identity.memberToken()).thenReturn(MEMBER_TOKEN);
        return new MyAccountView(presenter, mock(RefundPresenter.class), identity, displayTime());
    }

    private static PurchaseRecordDTO receipt(RefundStatus refundStatus) {
        TicketRecordDTO ticket = new TicketRecordDTO(1, 8, 5, ORDER_ID, "A1", 75.0, TicketStatus.ISSUED, "Coldplay",
            "Floor", "Acme", "CONCERT", null, "Tel Aviv", "TIX-1", false);
        List<TransactionRecordDTO> transactions = refundStatus == RefundStatus.NONE ? List.of() : List.of(
            new TransactionRecordDTO("PAYMENT_CHARGE", "WSEP", "TX-1", 75.0, "USD", PAID_AT),
            new TransactionRecordDTO("REFUND", "WSEP", "RF-1", 20.0, "USD", PAID_AT.plusDays(1)));
        return new PurchaseRecordDTO(ORDER_ID, MEMBER_ID, null, PAID_AT, 75.0, refundStatus, transactions,
            List.of(ticket), "alice");
    }

    private static BrowseEventsView browse(double rating) {
        BrowseEventsPresenter presenter = mock(BrowseEventsPresenter.class);
        when(presenter.search(any(), any())).thenReturn(new ListOutcome.Loaded<>(List.of(
            new EventSummaryDTO(1, "Hamlet", "ON_SALE", rating, "Acme", EventCategory.THEATER.name(), "Tel Aviv",
                List.of(), ZoneOffset.UTC, 50.0, 50.0, false, List.of()))));
        when(presenter.countries()).thenReturn(new ListOutcome.Loaded<>(List.of()));
        return new BrowseEventsView(presenter, mock(SessionIdentity.class), displayTime());
    }

    private static void assertEndedButtonShowsNoArrow(EventStatus status, String label) {
        EventDetailsView view = eventPage(status);

        Element button = find(view, NativeButton.class).stream()
            .map(Component::getElement)
            .filter(e -> e.getTextRecursively().contains(label))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the page has no \"" + label + "\" button"));
        assertTrue(A11y.disabledForAt(button), "the \"" + label + "\" button is disabled: " + A11y.describe(button));
        String shown = visibleText(button);
        assertFalse(shown.contains("→"), "a disabled \"" + label + "\" button must show no \"→\", which invites an "
            + "action that does not exist; it shows \"" + shown + "\"");
    }

    // The text a sighted user sees: every visible text node, aria-hidden ones included.
    private static String visibleText(Element e) {
        if (e.isTextNode()) {
            return e.getText();
        }
        if (!e.isVisible()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        e.getChildren().forEach(child -> text.append(visibleText(child)));
        return text.toString();
    }

    private static EventDetailsView eventPage() {
        return eventPage(EventStatus.ON_SALE);
    }

    private static EventDetailsView eventPage(EventStatus status) {
        EventDetailsPresenter presenter = mock(EventDetailsPresenter.class);
        when(presenter.load(any(), anyInt())).thenReturn(new EventDetailsPresenter.Outcome.Success(
            new EventDetailDTO("5", "Coldplay", 4.5, "Live", EventCategory.CONCERT, null, "3", "Acme",
                status, List.of(), ZoneOffset.UTC, List.of(), 10.0),
            List.of(new InventoryZoneDTO(8, "Floor", "STANDING", 100, 50, 0, 50, 10.0, List.of(), null)),
            3, 3, null));
        EventDetailsView view = new EventDetailsView(presenter, mock(SessionIdentity.class),
            new ReservationHoldProperties(10));
        view.beforeEnter(routeTo("eventId", "5"));
        return view;
    }

    private static OrganizationalTreeNodeDTO orgTree() {
        OrganizationalTreeNodeDTO carol = new OrganizationalTreeNodeDTO(11, "carol", "Manager", false,
            List.of(Permission.VIEW_SALES), new ArrayList<>());
        return new OrganizationalTreeNodeDTO(10, "founder", "Owner", true, List.of(), new ArrayList<>(List.of(carol)));
    }

    private static String documentTitle(Component view) {
        if (view instanceof HasDynamicTitle dynamic) {
            return dynamic.getPageTitle();
        }
        PageTitle annotation = view.getClass().getAnnotation(PageTitle.class);
        return annotation == null ? "" : annotation.value();
    }

    private static AdminSendMessagesView sendMessagesView() {
        AdminSendMessagesPresenter presenter = mock(AdminSendMessagesPresenter.class);
        when(presenter.load(any())).thenReturn(new AdminSendMessagesPresenter.Outcome.Success(List.of()));
        return new AdminSendMessagesView(presenter, displayTime());
    }

    private static String lastUpdatedLine(SystemAnalyticsView view) {
        return A11y.elements(view, e -> e.getText().startsWith("Last updated")).stream()
            .map(Element::getText)
            .findFirst()
            .orElseThrow(() -> new AssertionError("the page has no \"Last updated\" line: "
                + A11y.text(view.getElement())));
    }

    // A clock a test moves forward, to tell one load's time from the next.
    private static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("the test reads the clock in UTC only");
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    // The analytics presenter with the auto-refresh on, as it is outside tests.
    private static SystemAnalyticsPresenter pollingAnalyticsPresenter() {
        SystemAnalyticsPresenter presenter = analyticsPresenter();
        when(presenter.refreshIntervalMs()).thenReturn(POLL_INTERVAL_MS);
        return presenter;
    }

    // The control that pauses and resumes the auto-refresh: a toggle button (aria-pressed) named for it.
    private static Element pauseToggle(Component view) {
        return A11y.elements(view, e -> A11y.isButton(e) && e.hasAttribute("aria-pressed")
                && Pattern.compile("(?i)pause|auto-?refresh").matcher(A11y.name(e)).find())
            .stream()
            .findFirst()
            .orElseThrow(() -> new AssertionError("figures that refresh on their own need a toggle button "
                + "(aria-pressed) named to pause the auto-refresh (WCAG 2.2.2); the page's buttons are "
                + A11y.elements(view, A11y::isButton).stream().map(A11y::describe).toList()));
    }

    // A poll tick from the browser, as UI.setPollInterval makes it send.
    private static void pollTick() {
        UI ui = UI.getCurrent();
        ComponentUtil.fireEvent(ui, new PollEvent(ui, true));
    }

    private static SystemAnalyticsPresenter analyticsPresenter() {
        SystemAnalyticsPresenter presenter = mock(SystemAnalyticsPresenter.class);
        MarketStateDTO market = new MarketStateDTO("OPEN", null, null, true, true, null, true);
        SystemAnalyticsDTO analytics = new SystemAnalyticsDTO(0L, 0d, 0d, 0d, 0d, 0d, 0L, 0L, 0L, 0L, 0L, 5);
        when(presenter.load()).thenReturn(new SystemAnalyticsPresenter.Outcome.Success(market, analytics));
        return presenter;
    }

    private NewInquiryView inquiryWithAcmeChosen() {
        NewInquiryPresenter presenter = mock(NewInquiryPresenter.class);
        when(presenter.searchCompanies(anyString())).thenReturn(new NewInquiryPresenter.SearchOutcome.Success(
            List.of(new CompanySummaryDTO(3, "Acme", null))));
        NewInquiryView view = new NewInquiryView(presenter);
        harness.attach(view);
        field(view, TextField.class, "Production company").setValue("Ac");
        click(buttons(view, "Acme").get(0));
        return view;
    }

    private static Optional<Element> chosenCompany(NewInquiryView view) {
        return A11y.elements(view, e -> "Acme".equals(e.getText().trim()) && !A11y.isButton(e)).stream().findFirst();
    }

    private static Element refundStatusSection(ReceiptView view) {
        Element heading = A11y.elements(view, e -> "heading".equals(A11y.role(e))
                && "Refund status".equals(A11y.text(e)))
            .stream().findFirst().orElseThrow(() -> new AssertionError("the receipt has no Refund status heading"));
        return heading.getParent();
    }
}
