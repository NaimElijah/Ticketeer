package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.InboxViewHarness.displayTime;
import static com.ticketing.system.unit.presentation.InboxViewHarness.thread;
import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_ID;
import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_TOKEN;
import static com.ticketing.system.unit.presentation.ViewHarness.button;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.field;
import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.catalog.config.VenueLimitsProperties;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.ZoneType;
import com.ticketing.system.organization.application.dto.OrganizationalTreeNodeDTO;
import com.ticketing.system.organization.application.dto.ProductionCompanyDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.RefundStatus;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.TicketRecordDTO;
import com.ticketing.system.sales.config.ReservationHoldProperties;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.shared.dto.AppointmentInfoDTO;
import com.ticketing.system.shared.dto.CompanyDashboardDTO;
import com.ticketing.system.shared.dto.EventSummaryDTO;
import com.ticketing.system.shared.dto.InventoryZoneDTO;
import com.ticketing.system.shared.dto.MemberSearchResultDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.shared.dto.SeatDTO;
import com.ticketing.system.ui.components.kit.LkDateRangeField;
import com.ticketing.system.ui.components.kit.LkFilterChip;
import com.ticketing.system.ui.components.kit.LkSelect;
import com.ticketing.system.ui.components.kit.LkTextRows;
import com.ticketing.system.ui.components.messaging.MdReplyBar;
import com.ticketing.system.ui.components.venue.VkSeat;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.presenters.account.MyAccountPresenter;
import com.ticketing.system.ui.presenters.account.RefundPresenter;
import com.ticketing.system.ui.presenters.admin.OrgTreePresenter;
import com.ticketing.system.ui.presenters.catalog.BrowseEventsPresenter;
import com.ticketing.system.ui.presenters.catalog.EventDetailsPresenter;
import com.ticketing.system.ui.presenters.catalog.SeatPickerPresenter;
import com.ticketing.system.ui.presenters.company.ManagerListPresenter;
import com.ticketing.system.ui.presenters.company.OwnerDashboardPresenter;
import com.ticketing.system.ui.presenters.company.VenueMapPresenter;
import com.ticketing.system.ui.presenters.messaging.AdminComplaintQueuePresenter;
import com.ticketing.system.ui.presenters.messaging.AdminInboxPresenter;
import com.ticketing.system.ui.presenters.messaging.AdminSendMessagesPresenter;
import com.ticketing.system.ui.presenters.messaging.CompanyInquiryInboxPresenter;
import com.ticketing.system.ui.presenters.messaging.SupportInboxPresenter;
import com.ticketing.system.ui.presenters.order.CartPresenter;
import com.ticketing.system.ui.presenters.order.CheckoutPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.account.MyAccountView;
import com.ticketing.system.ui.views.account.SupportInboxView;
import com.ticketing.system.ui.views.admin.AdminComplaintQueueView;
import com.ticketing.system.ui.views.admin.AdminInboxView;
import com.ticketing.system.ui.views.admin.AdminSendMessagesView;
import com.ticketing.system.ui.views.admin.OrganizationalTreeView;
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.ticketing.system.ui.views.catalog.EventDetailsView;
import com.ticketing.system.ui.views.catalog.SeatPickerView;
import com.ticketing.system.ui.views.company.CompanyInquiryRespondView;
import com.ticketing.system.ui.views.company.ManagerListView;
import com.ticketing.system.ui.views.company.OwnerDashboardView;
import com.ticketing.system.ui.views.company.VenueMapEditorView;
import com.ticketing.system.ui.views.order.CartView;
import com.ticketing.system.ui.views.order.CheckoutView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.popover.Popover;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.router.AfterNavigationEvent;

/**
 * After an action rebuilds part of the page, keyboard focus lands somewhere sensible on it, instead of on
 * the document (#188, A-M7, P-m9, P-M10, U-B1, U-B2, U-B6; WCAG 2.4.3 Focus Order, 4.1.3). Many of these
 * actions replace the control that had focus with a new one; left alone, the browser drops focus to the
 * body, and a keyboard user starts again from the top of the page while a screen reader says nothing.
 *
 * <p>The server cannot see the browser's focus. A test passes when the control that was used is still on
 * the page and focusable (it was updated in place, so the browser kept focus on it), or when, after the
 * action, the page asked the browser to focus an element still on it (the next row, the list, the same
 * control rebuilt, or the new message's field). A focus call on a removed control does not count: the
 * browser never runs it.
 */
class FocusAfterRebuildTest {

    // ---- F18: focus after rebuilds (#188) ----

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-07-15T17:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2027, 7, 1, 12, 0);
    private static final String SEARCH_MEMBERS = "Search members";
    private static final List<String> CATEGORIES = List.of("All categories", "Concerts", "Sports", "Theatre");

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- CartView ----

    @Test
    void GivenACartOfTwoLines_WhenTheBuyerRemovesTheFirst_ThenFocusMovesToTheNextLinesRemove() {
        CartPresenter presenter = mock(CartPresenter.class);
        CartPresenter.CartVM.LineVM first = new CartPresenter.CartVM.LineVM("Coldplay", "A1", 10.0, 5, 8);
        CartPresenter.CartVM.LineVM second = new CartPresenter.CartVM.LineVM("Hamlet", "B2", 20.0, 6, 9);
        when(presenter.loadCart()).thenReturn(new CartPresenter.LoadOutcome.Shown(
            new CartPresenter.CartVM(List.of(first, second), 300), 3000));
        when(presenter.removeLine(any())).thenReturn(new CartPresenter.RemoveOutcome.Removed(
            new CartPresenter.CartVM(List.of(second), 300), 2000));
        CartView view = attached(new CartView(presenter));
        Component remove = removeButtons(view).get(0);

        assertFocusMovesTo(view, () -> click(remove),
            t -> A11y.name(t).startsWith("Remove") && A11y.name(t).contains("Hamlet"),
            "removing a cart line", "the next line's Remove button (named for Hamlet)");
    }

    @Test
    void GivenACartOfOneLine_WhenTheBuyerRemovesIt_ThenFocusMovesToTheEmptyCartMessage() {
        CartPresenter presenter = mock(CartPresenter.class);
        CartPresenter.CartVM.LineVM only = new CartPresenter.CartVM.LineVM("Coldplay", "A1", 10.0, 5, 8);
        when(presenter.loadCart()).thenReturn(new CartPresenter.LoadOutcome.Shown(
            new CartPresenter.CartVM(List.of(only), 300), 1000));
        when(presenter.removeLine(any())).thenReturn(new CartPresenter.RemoveOutcome.Removed(
            new CartPresenter.CartVM(List.of(), 0), 0));
        CartView view = attached(new CartView(presenter));
        Component remove = removeButtons(view).get(0);

        assertFocusMovesTo(view, () -> click(remove),
            t -> A11y.text(t).toLowerCase(Locale.ROOT).contains("cart is empty"),
            "removing the cart's last line", "the message saying the cart is empty");
    }

    // ---- MyAccountView ----

    @Test
    void GivenARefundableOrder_WhenTheMemberConfirmsItsRefund_ThenFocusMovesToTheOrdersViewButton() {
        ViewHarness.signInMember();
        MyAccountPresenter accountPresenter = mock(MyAccountPresenter.class);
        when(accountPresenter.loadHistory()).thenReturn(new MyAccountPresenter.Outcome.Success(
            new PurchaseHistoryDTO(List.of(order()))));
        RefundPresenter refundPresenter = mock(RefundPresenter.class);
        when(refundPresenter.requestRefund(any(), anyInt(), any()))
            .thenReturn(new RefundPresenter.Outcome.Success("RF-1", 75.0));
        SessionIdentity identity = mock(SessionIdentity.class);
        when(identity.memberToken()).thenReturn(MEMBER_TOKEN);
        MyAccountView view = attached(new MyAccountView(accountPresenter, refundPresenter, identity, displayTime()));
        Component refund = button(view, "Request Refund");

        assertFocusMovesTo(view, () -> {
            click(refund);
            assertTrue(harness.confirmOpenDialog(), "a refund asks for confirmation first");
        }, t -> A11y.name(t).startsWith("View") && A11y.name(t).contains("42"),
            "a refund", "the refunded order's View button (named for order 42)");
    }

    // ---- AdminInboxView ----

    @Test
    void GivenAnOpenConversation_WhenTheAdminSendsAReply_ThenFocusStaysOnTheConversation() {
        ViewHarness.signInAdmin();
        AdminInboxPresenter presenter = adminInboxPresenter();
        when(presenter.reply(any(), any(), any())).thenReturn(new AdminInboxPresenter.ActionOutcome.Success());
        AdminInboxView view = attached(new AdminInboxView(presenter, displayTime()));

        assertReplyKeepsFocus(view);
    }

    @Test
    void GivenAnOpenConversation_WhenTheAdminClosesIt_ThenFocusStaysOnTheConversation() {
        ViewHarness.signInAdmin();
        AdminInboxPresenter presenter = adminInboxPresenter();
        when(presenter.load(any())).thenReturn(
            new AdminInboxPresenter.Outcome.Success(List.of(thread("c1", "DIRECT", "MEMBER"))),
            new AdminInboxPresenter.Outcome.Success(List.of(thread("c1", "DIRECT", "MEMBER", "CLOSED"))));
        when(presenter.close(any(), any())).thenReturn(new AdminInboxPresenter.ActionOutcome.Success());
        AdminInboxView view = attached(new AdminInboxView(presenter, displayTime()));
        Component close = button(view, "Close Conversation");

        assertKeptOrMovesTo(view, close.getElement(), () -> click(close),
            t -> "heading".equals(A11y.role(t)) && A11y.text(t).contains("Subject c1"),
            "closing the conversation", "the conversation's heading, \"Subject c1\"");
        verify(presenter).close(any(), eq("c1"));
    }

    // ---- SupportInboxView ----

    @Test
    void GivenAnOpenInquiry_WhenTheMemberSendsAReply_ThenFocusStaysOnTheConversation() {
        ViewHarness.signInMember();
        SupportInboxPresenter presenter = mock(SupportInboxPresenter.class);
        when(presenter.load(any())).thenReturn(
            new SupportInboxPresenter.Outcome.Success(List.of(thread("c1", "INQUIRY", "COMPANY"))));
        when(presenter.badge(any(), any())).thenReturn(Optional.empty());
        when(presenter.reply(any(), any(), any())).thenReturn(new SupportInboxPresenter.ActionOutcome.Success());
        SupportInboxView view = attached(new SupportInboxView(presenter, displayTime()));

        assertReplyKeepsFocus(view);
    }

    // ---- CompanyInquiryRespondView ----

    @Test
    void GivenAnOpenInquiry_WhenTheCompanySendsAReply_ThenFocusStaysOnTheInquiry() {
        ViewHarness.signInMember();
        CompanyInquiryInboxPresenter presenter = mock(CompanyInquiryInboxPresenter.class);
        when(presenter.loadOne(any(), any())).thenReturn(
            new CompanyInquiryInboxPresenter.SingleOutcome.Success(thread("c1", "INQUIRY", "COMPANY")));
        when(presenter.reply(any(), any(), any())).thenReturn(new CompanyInquiryInboxPresenter.ActionOutcome.Success());
        CompanyInquiryRespondView view = attached(new CompanyInquiryRespondView(presenter, displayTime()));
        view.beforeEnter(routeTo("id", "c1"));

        assertReplyKeepsFocus(view);
    }

    // ---- AdminComplaintQueueView ----

    @Test
    void GivenTheComplaintQueue_WhenTheAdminChangesTheStatusFilter_ThenFocusStaysOnTheFilter() {
        ViewHarness.signInAdmin();
        AdminComplaintQueuePresenter presenter = mock(AdminComplaintQueuePresenter.class);
        when(presenter.load(any(), anyString())).thenReturn(new AdminComplaintQueuePresenter.Outcome.Success(
            List.of(thread("c1", "COMPLAINT", "ADMIN_GROUP"))));
        when(presenter.badge(any(), any())).thenReturn(Optional.empty());
        AdminComplaintQueueView view = attached(new AdminComplaintQueueView(presenter, displayTime()));
        Element trigger = namedStartingWith(view, "Status");

        assertKeptOrMovesTo(view, trigger, () -> choose(view, "Open"), t -> "Status: Open".equals(A11y.name(t)),
            "changing the status filter", "the status filter's trigger, now \"Status: Open\"");
        verify(presenter).load(any(), eq("Open"));
    }

    // ---- LkFilterChip ----

    @Test
    void GivenAFilterChip_WhenTheUserTogglesAnOption_ThenFocusStaysOnThatOption() {
        LkFilterChip chip = attached(new LkFilterChip("Status", List.of("Open", "Closed")));
        Element open = option(chip, "Open");
        ViewHarness.openPopoverHolding(open);

        List<Element> focused = harness.focusCallsDuring(() -> A11y.activate(open));

        Optional<Element> endsOn = ViewHarness.focusEndsOn(focused);
        boolean onOpen = endsOn.isPresent()
            ? A11y.within(endsOn.get(), chip.getElement()) && "Open".equals(A11y.name(endsOn.get()))
            : A11y.within(open, chip.getElement()) && A11y.inTabOrder(open, chip.getElement())
                && "true".equals(open.getAttribute("aria-checked"));
        assertTrue(onOpen, "after a toggle focus must stay on the option \"Open\": it must be "
            + "updated in place, or the rebuilt option focused last; the option is "
            + (A11y.within(open, chip.getElement()) ? "still there" : "gone") + " and the focus calls were, in order, "
            + focused.stream().map(A11y::describe).toList());
    }

    @Test
    void GivenAFilterChipWithAnOptionTicked_WhenTheUserClearsIt_ThenFocusMovesToTheFirstOption() {
        LkFilterChip chip = attached(new LkFilterChip("Status", List.of("Open", "Closed"), false, List.of("Open")));
        Element clear = namedStartingWith(chip, "Clear");
        ViewHarness.openPopoverHolding(clear);

        assertFocusMovesTo(chip, () -> A11y.activate(clear),
            t -> "Open".equals(A11y.name(t)) && "checkbox".equals(A11y.role(t)),
            "clearing the filter chip", "the menu's first option, \"Open\"");
    }

    // ---- ManagerListView ----

    @Test
    void GivenTwoManagers_WhenTheOwnerRevokesTheFirst_ThenFocusMovesToTheNextManagersRevoke() {
        ViewHarness.signInMember();
        harness.memberships(ViewHarness.founderOf(3, "Acme"));
        ManagerListPresenter presenter = mock(ManagerListPresenter.class);
        AppointmentInfoDTO carol = manager(9, "carol");
        AppointmentInfoDTO dave = manager(10, "dave");
        when(presenter.loadRoster(any())).thenReturn(
            new ManagerListPresenter.Outcome.Success("Acme", List.of(carol, dave), List.of()),
            new ManagerListPresenter.Outcome.Success("Acme", List.of(dave), List.of()));
        when(presenter.revoke(any(), anyInt(), anyInt())).thenReturn(new ManagerListPresenter.ActionOutcome.Success());
        ManagerListView view = attached(new ManagerListView(presenter, displayTime()));
        Element revoke = namedStartingWith(view, "Revoke");

        assertFocusMovesTo(view, () -> {
            A11y.activate(revoke);
            assertTrue(harness.confirmOpenDialog(), "revoking asks for confirmation first");
        }, t -> A11y.name(t).startsWith("Revoke") && A11y.name(t).contains("dave"),
            "revoking a manager", "the next manager's Revoke button (named for dave)");
    }

    @Test
    void GivenOneManager_WhenTheOwnerRevokesThem_ThenFocusMovesToTheRosterHeading() {
        ViewHarness.signInMember();
        harness.memberships(ViewHarness.founderOf(3, "Acme"));
        ManagerListPresenter presenter = mock(ManagerListPresenter.class);
        when(presenter.loadRoster(any())).thenReturn(
            new ManagerListPresenter.Outcome.Success("Acme", List.of(manager(9, "carol")), List.of()),
            new ManagerListPresenter.Outcome.Success("Acme", List.of(), List.of()));
        when(presenter.revoke(any(), anyInt(), anyInt())).thenReturn(new ManagerListPresenter.ActionOutcome.Success());
        ManagerListView view = attached(new ManagerListView(presenter, displayTime()));
        Element revoke = namedStartingWith(view, "Revoke");

        assertFocusMovesTo(view, () -> {
            A11y.activate(revoke);
            assertTrue(harness.confirmOpenDialog(), "revoking asks for confirmation first");
        }, t -> "heading".equals(A11y.role(t)), "revoking the last manager", "the roster's heading");
    }

    // ---- LkTextRows ----

    @Test
    void GivenTwoArtistRows_WhenTheUserRemovesTheFirst_ThenFocusStaysOnTheRows() {
        LkTextRows rows = attached(new LkTextRows("Artists", "+ Add artist"));
        rows.setValues(List.of("Coldplay", "Adele"));
        Element remove = namedStartingWith(rows, "Remove");

        assertKeptOrMovesTo(rows, remove, () -> A11y.activate(remove),
            t -> A11y.isButton(t) && "Remove Artists row 1".equals(A11y.name(t)),
            "removing an artist row", "the Remove button of the row now first (\"Remove Artists row 1\")");
        assertEquals(List.of("Adele"), rows.getValues(), "the first artist row must be gone");
    }

    // ---- OwnerDashboardView ----

    @Test
    void GivenAMemberOfTwoCompanies_WhenTheySwitchCompany_ThenFocusStaysOnTheCompanySelector() {
        ViewHarness.signInMember();
        harness.memberships(ViewHarness.founderOf(1, "Acme"), ViewHarness.founderOf(2, "Beta"));
        MyCompanyDTO acme = new MyCompanyDTO(1, "Acme", "Founder");
        MyCompanyDTO beta = new MyCompanyDTO(2, "Beta", "Founder");
        CompanyDashboardDTO stats = new CompanyDashboardDTO(0, null, null, null, 4.5);
        OwnerDashboardPresenter presenter = mock(OwnerDashboardPresenter.class);
        when(presenter.loadFor(any(), any())).thenReturn(
            new OwnerDashboardPresenter.Outcome.Success(List.of(acme, beta), acme, stats));
        when(presenter.loadFor(any(), eq(2))).thenReturn(
            new OwnerDashboardPresenter.Outcome.Success(List.of(acme, beta), beta, stats));
        OwnerDashboardView view = attached(new OwnerDashboardView(presenter));
        Element selector = namedStartingWith(view, "Company");

        assertKeptOrMovesTo(view, selector, () -> choose(view, "Beta"), t -> "Company: Beta".equals(A11y.name(t)),
            "switching company", "the company selector, now \"Company: Beta\"");
        verify(presenter).loadFor(any(), eq(2));
    }

    // ---- CheckoutView ----

    // Pay is disabled only on the server, set and cleared within the payment's one round trip, so the browser
    // never sees the disable and Pay keeps its focus: the buyer corrects the card and pays again from there.
    @Test
    void GivenADeclinedPayment_WhenThePaymentEnds_ThenFocusStaysOnPay() {
        ViewHarness.signInMember();
        CheckoutPresenter presenter = checkoutPresenter();
        when(presenter.payAsMember(any(), any(), any(), any(), any(), any()))
            .thenReturn(new CheckoutPresenter.PayOutcome.PaymentDeclined("declined"));
        CheckoutView view = filledCheckout(presenter);
        NativeButton pay = payButton(view);

        assertKeptOrMovesTo(view, pay.getElement(), () -> click(pay), t -> t.equals(pay.getElement()),
            "a declined payment", "Pay");
        verify(presenter).payAsMember(any(), any(), any(), any(), any(), any());
    }

    // ---- EventDetailsView zone map (review A3) ----

    @Test
    void GivenAnEventWithTwoZones_WhenTheBuyerPicksTheOtherZone_ThenFocusStaysOnThatZonePressed() {
        EventDetailsView view = attached(eventPage());
        Element balcony = zoneOnMap(view, "Balcony");

        assertKeptOrMovesTo(view, balcony, () -> A11y.activate(balcony),
            t -> A11y.name(t).contains("Balcony") && "true".equals(t.getAttribute("aria-pressed")),
            "picking a zone on the map", "the Balcony zone's button, now pressed");
    }

    // ---- VenueMapEditorView zone list (review A3, C4) ----

    @Test
    void GivenTwoZones_WhenTheOwnerChoosesTheFirstInTheZoneList_ThenFocusStaysOnThatZonesControl() {
        VenueMapEditorView view = attached(venueEditorWithTwoZones());
        Element first = zoneInList(view, "Zone 1");

        assertKeptOrMovesTo(view, first, () -> A11y.fireBubbling(first, view.getElement(), "click"),
            t -> "Zone 1".equals(A11y.name(t)) && "true".equals(t.getAttribute("aria-pressed")),
            "choosing a zone in the zone list", "Zone 1's control in the list, now pressed");
    }

    @Test
    void GivenTwoZones_WhenTheOwnerRemovesTheFirst_ThenFocusMovesToTheNextZonesRemove() {
        VenueMapEditorView view = attached(venueEditorWithTwoZones());
        Element remove = namedStartingWith(view, "Remove Zone 1");

        assertFocusMovesTo(view, () -> A11y.activate(remove), t -> "Remove Zone 2".equals(A11y.name(t)),
            "removing a zone", "the next zone's remove button, \"Remove Zone 2\"");
    }

    @Test
    void GivenTwoZones_WhenTheOwnerRemovesTheLast_ThenFocusMovesToTheRemainingZonesRemove() {
        VenueMapEditorView view = attached(venueEditorWithTwoZones());
        Element remove = namedStartingWith(view, "Remove Zone 2");

        assertFocusMovesTo(view, () -> A11y.activate(remove), t -> "Remove Zone 1".equals(A11y.name(t)),
            "removing the last zone in the list", "the remaining zone's remove button, \"Remove Zone 1\"");
    }

    // ---- SeatPickerView selection rail (review A4) ----

    @Test
    void GivenTwoSeatsInTheRail_WhenTheBuyerRemovesTheFirst_ThenFocusMovesToTheNextSeatsRemove() {
        SeatPickerView view = attached(seatPicker());
        click(seat(view, "A1"));
        click(seat(view, "A2"));
        Element remove = namedStartingWith(view, "Remove seat A1");

        assertFocusMovesTo(view, () -> A11y.activate(remove), t -> A11y.name(t).startsWith("Remove seat A2"),
            "removing a seat from the selection", "the next seat's remove button, \"Remove seat A2\"");
    }

    @Test
    void GivenOneSeatInTheRail_WhenTheBuyerRemovesIt_ThenFocusMovesToTheRailsHint() {
        SeatPickerView view = attached(seatPicker());
        click(seat(view, "A1"));
        Element remove = namedStartingWith(view, "Remove seat A1");

        assertFocusMovesTo(view, () -> A11y.activate(remove),
            t -> A11y.text(t).toLowerCase(Locale.ROOT).contains("choose an available seat"),
            "removing the last seat from the selection", "the rail's hint saying to choose a seat");
    }

    // ---- AdminSendMessagesView recipients (review A5) ----

    @Test
    void GivenSearchResults_WhenTheAdminAddsOne_ThenFocusMovesToTheSearchField() {
        AdminSendMessagesView view = attached(sendMessagesView());
        TextField search = field(view, TextField.class, SEARCH_MEMBERS);
        search.setValue("al");
        Element alice = namedStartingWith(view, "alice");

        assertFocusMovesTo(view, () -> A11y.activate(alice), t -> t.equals(search.getElement()),
            "adding a recipient from the results", "the member search field, to search for the next one");
    }

    @Test
    void GivenTwoRecipients_WhenTheAdminRemovesTheFirst_ThenFocusMovesToTheNextRecipientsRemove() {
        AdminSendMessagesView view = attached(sendMessagesView());
        addRecipient(view, "alice");
        addRecipient(view, "alan");
        Element remove = namedStartingWith(view, "Remove alice");

        assertFocusMovesTo(view, () -> A11y.activate(remove), t -> "Remove alan".equals(A11y.name(t)),
            "removing a recipient", "the next recipient's remove button, \"Remove alan\"");
    }

    @Test
    void GivenOneRecipient_WhenTheAdminRemovesIt_ThenFocusMovesToTheSearchField() {
        AdminSendMessagesView view = attached(sendMessagesView());
        addRecipient(view, "alice");
        Element remove = namedStartingWith(view, "Remove alice");
        TextField search = field(view, TextField.class, SEARCH_MEMBERS);

        assertFocusMovesTo(view, () -> A11y.activate(remove), t -> t.equals(search.getElement()),
            "removing the only recipient", "the member search field");
    }

    // ---- LkSelect and LkDateRangeField picks (PR #253 review 1) ----

    @Test
    void GivenAnOpenSelect_WhenTheUserPicksAnOption_ThenFocusMovesToAnElementOutsideTheClosedList() {
        LkSelect select = attached(new LkSelect("All categories", CATEGORIES).label("Category"));
        openPopover(select);
        Element concerts = option(select, "Concerts");

        assertFocusLeavesTheList(select, () -> A11y.activate(concerts), "picking a category");
    }

    @Test
    void GivenAnOpenSelect_WhenTheUserPicksAnOption_ThenThePopoverCloses() {
        LkSelect select = attached(new LkSelect("All categories", CATEGORIES).label("Category"));
        openPopover(select);

        A11y.activate(option(select, "Concerts"));

        assertPopoverClosed(select, "a select takes one choice");
    }

    @Test
    void GivenAnOpenDateRange_WhenTheUserPicksAPreset_ThenFocusMovesToAnElementOutsideTheClosedList() {
        LkDateRangeField range = attached(new LkDateRangeField().label("Date Range"));
        openPopover(range);
        Element today = option(range, "Today");

        assertFocusLeavesTheList(range, () -> A11y.activate(today), "picking a date range");
    }

    @Test
    void GivenAnOpenDateRange_WhenTheUserPicksAPreset_ThenThePopoverCloses() {
        LkDateRangeField range = attached(new LkDateRangeField().label("Date Range"));
        openPopover(range);

        A11y.activate(option(range, "Today"));

        assertPopoverClosed(range, "a date range takes one preset");
    }

    @Test
    void GivenBrowse_WhenTheBuyerPicksACategory_ThenFocusMovesToAnElementOutsideTheClosedList() {
        BrowseEventsView view = attached(browse());
        LkSelect category = find(view, LkSelect.class).stream()
            .filter(select -> A11y.text(select.getElement()).startsWith("Category"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Browse has no Category select"));
        openPopover(category);
        Element concerts = option(category, "Concerts");

        assertFocusLeavesTheList(view, () -> A11y.activate(concerts), "picking a category on Browse");
    }

    // ---- OrganizationalTreeView company list (PR #253 review 2) ----

    @Test
    void GivenTheOrgTreeOnAcme_WhenTheAdminActivatesBeta_ThenFocusStaysOnBetasButton() {
        ViewHarness.signInAdmin();
        OrganizationalTreeView view = attached(new OrganizationalTreeView(orgTreePresenter()));
        Element beta = companyButton(view, "Beta Productions");

        assertKeptOrMovesTo(view, beta, () -> A11y.activate(beta),
            t -> A11y.isButton(t) && A11y.name(t).contains("Beta Productions"),
            "choosing a company in the org tree", "Beta Productions' button in the company list");
    }

    // ---- assertions ------------------------------------------------------------------------------

    // The pick rebuilds the option list, so focus must be moved to an element on the page outside the
    // list's popover: the trigger, in practice.
    private void assertFocusLeavesTheList(Component root, Runnable pick, String what) {
        List<Element> focused = harness.focusCallsDuring(pick);

        Optional<Element> endsOn = ViewHarness.focusEndsOn(focused);
        assertTrue(endsOn.filter(t -> A11y.within(t, root.getElement()) && !inPopover(t)).isPresent(),
            "after " + what + " the option pressed is gone with the rebuilt list, so focus must end on an element "
                + "on the page outside the list (its trigger); the page asked to focus, in order, "
                + focused.stream().map(A11y::describe).toList());
    }

    private static void assertPopoverClosed(Component field, String why) {
        List<Popover> popovers = find(field, Popover.class);
        assertFalse(popovers.isEmpty(), "the field has no popover");
        assertTrue(popovers.stream().noneMatch(Popover::isOpened),
            why + ", so its popover must close after a pick; it is still open");
    }

    // Focus ends on an element the predicate accepts: the last focus call's target when the action made any,
    // since the browser ends there, else the control used, when it is still on the page, focusable and as the
    // predicate wants it (updated in place, so the browser kept focus on it).
    private void assertKeptOrMovesTo(Component root, Element used, Runnable action, Predicate<Element> isTarget,
                                     String what, String target) {
        List<Element> focused = harness.focusCallsDuring(action);

        Optional<Element> endsOn = ViewHarness.focusEndsOn(focused);
        boolean onTarget = endsOn.isPresent()
            ? A11y.within(endsOn.get(), root.getElement()) && isTarget.test(endsOn.get())
            : A11y.within(used, root.getElement()) && A11y.inTabOrder(used, root.getElement()) && isTarget.test(used);
        assertTrue(onTarget, "after " + what + " focus must end on " + target + ": the control used is "
            + (A11y.within(used, root.getElement()) ? "still there as " + A11y.describe(used) : "gone")
            + ", and the page asked to focus, in order, " + focused.stream().map(A11y::describe).toList());
    }

    // The reload replaces the reply bar, so focus must reach the new bar's field, not only the page.
    private void assertReplyKeepsFocus(Component view) {
        MdReplyBar bar = find(view, MdReplyBar.class).get(0);
        TextField input = find(bar, TextField.class).get(0);
        input.setValue("Thanks, sorted.");

        assertFocusMovesTo(view, () -> click(button(bar, "Send")),
            t -> find(view, MdReplyBar.class).stream()
                .flatMap(current -> find(current, TextField.class).stream())
                .anyMatch(field -> field.getElement().equals(t)),
            "sending a reply", "the reply field of the reply bar now on the page");
    }

    // The last focus call the action made, where the browser ends, is to an element on the page that the
    // predicate accepts.
    private void assertFocusMovesTo(Component root, Runnable action, Predicate<Element> isTarget, String what,
                                    String target) {
        List<Element> focused = harness.focusCallsDuring(action);

        Optional<Element> endsOn = ViewHarness.focusEndsOn(focused);
        assertTrue(endsOn.filter(t -> A11y.within(t, root.getElement()) && isTarget.test(t)).isPresent(),
            "after " + what + " focus must end on " + target + "; the page asked to focus, in order, "
                + focused.stream().map(A11y::describe).toList());
    }

    // ---- arrangement -----------------------------------------------------------------------------

    private static OrgTreePresenter orgTreePresenter() {
        ProductionCompanyDTO acme = new ProductionCompanyDTO(1, "Acme", "desc", "ACTIVE", 10);
        ProductionCompanyDTO beta = new ProductionCompanyDTO(2, "Beta Productions", "desc", "ACTIVE", 11);
        OrganizationalTreeNodeDTO tree = new OrganizationalTreeNodeDTO(10, "founder", "Owner", true, List.of(),
            new ArrayList<>());
        OrgTreePresenter presenter = mock(OrgTreePresenter.class);
        when(presenter.load(any(), isNull(), anyBoolean()))
            .thenReturn(new OrgTreePresenter.Outcome.Success(List.of(acme, beta), acme, tree));
        when(presenter.load(any(), eq(1), anyBoolean()))
            .thenReturn(new OrgTreePresenter.Outcome.Success(List.of(acme, beta), acme, tree));
        when(presenter.load(any(), eq(2), anyBoolean()))
            .thenReturn(new OrgTreePresenter.Outcome.Success(List.of(acme, beta), beta, tree));
        return presenter;
    }

    private static Element companyButton(Component view, String company) {
        return A11y.elements(view, e -> A11y.isButton(e) && A11y.name(e).contains(company)).stream()
            .findFirst()
            .orElseThrow(() -> new AssertionError("no button for " + company + " in the company list"));
    }

    // The user opens the list, as a click on the trigger does in the browser.
    private static void openPopover(Component field) {
        find(field, Popover.class).forEach(popover -> popover.setOpened(true));
    }

    private static boolean inPopover(Element e) {
        for (Element a = e.getParent(); a != null; a = a.getParent()) {
            if ("vaadin-popover".equals(a.getTag())) {
                return true;
            }
        }
        return false;
    }

    private static BrowseEventsView browse() {
        BrowseEventsPresenter presenter = mock(BrowseEventsPresenter.class);
        when(presenter.search(any(), any())).thenReturn(new ListOutcome.Loaded<>(List.of(
            new EventSummaryDTO(1, "Hamlet", "ON_SALE", null, "Acme", EventCategory.THEATER.name(), "Tel Aviv",
                List.of(), ZoneOffset.UTC, 50.0, 50.0, false, List.of()))));
        when(presenter.countries()).thenReturn(new ListOutcome.Loaded<>(List.of()));
        return new BrowseEventsView(presenter, mock(SessionIdentity.class), displayTime());
    }

    private static EventDetailsView eventPage() {
        EventDetailsPresenter presenter = mock(EventDetailsPresenter.class);
        when(presenter.load(any(), anyInt())).thenReturn(new EventDetailsPresenter.Outcome.Success(
            new EventDetailDTO("5", "Coldplay", null, "Live", EventCategory.CONCERT, null, "3", "Acme",
                EventStatus.ON_SALE, List.of(), ZoneOffset.UTC, List.of(), 10.0),
            List.of(new InventoryZoneDTO(8, "Floor", "STANDING", 100, 50, 0, 50, 10.0, List.of(), null),
                new InventoryZoneDTO(9, "Balcony", "STANDING", 100, 50, 0, 50, 20.0, List.of(), null)),
            3, 3, null));
        EventDetailsView view = new EventDetailsView(presenter, mock(SessionIdentity.class),
            new ReservationHoldProperties(10));
        view.beforeEnter(routeTo("eventId", "5"));
        return view;
    }

    private static Element zoneOnMap(Component view, String zone) {
        return A11y.elements(view, e -> e.getClassList().contains("vk-zone") && A11y.name(e).contains(zone))
            .stream()
            .findFirst()
            .orElseThrow(() -> new AssertionError("no zone " + zone + " on the map"));
    }

    private static VenueMapEditorView venueEditorWithTwoZones() {
        VenueMapPresenter presenter = mock(VenueMapPresenter.class);
        when(presenter.defaultZoneName(any())).thenReturn("Zone 2");
        VenueMapEditorView view = new VenueMapEditorView(presenter,
            new VenueLimitsProperties(12, 49_500, 1_000_000, 100_000));
        click(button(view, "+ Seated"));
        return view;
    }

    // A zone's control in the editor's zone list: a toggle (aria-pressed) named by the zone.
    private static Element zoneInList(Component view, String zone) {
        return A11y.elements(view, e -> zone.equals(A11y.name(e)) && e.hasAttribute("aria-pressed")
                && !e.getClassList().contains("vk-zone"))
            .stream()
            .findFirst()
            .orElseThrow(() -> new AssertionError("no control for " + zone + " in the zone list"));
    }

    private static SeatPickerView seatPicker() {
        SeatPickerPresenter presenter = mock(SeatPickerPresenter.class);
        when(presenter.loadZone(anyInt(), anyInt())).thenReturn(new SeatPickerPresenter.LoadOutcome.Loaded(
            new InventoryZoneDTO(8, "Floor", "SEATED", 2, 2, 0, 0, 10.0,
                List.of(new SeatDTO("A1", 1, 0, "AVAILABLE"), new SeatDTO("A2", 2, 0, "AVAILABLE")), null),
            ZoneType.SEATED));
        SeatPickerView view = new SeatPickerView(presenter, new ReservationHoldProperties(10));
        view.beforeEnter(routeTo("eventId", "5", "zoneId", "8"));
        return view;
    }

    private static VkSeat seat(SeatPickerView view, String label) {
        return find(view, VkSeat.class).stream()
            .filter(s -> A11y.name(s.getElement()).startsWith("Seat " + label + ","))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no seat " + label + " on the picker"));
    }

    private static AdminSendMessagesView sendMessagesView() {
        ViewHarness.signInAdmin();
        AdminSendMessagesPresenter presenter = mock(AdminSendMessagesPresenter.class);
        when(presenter.load(any())).thenReturn(new AdminSendMessagesPresenter.Outcome.Success(List.of()));
        when(presenter.searchUsers(any(), any())).thenReturn(new AdminSendMessagesPresenter.SearchOutcome.Success(
            List.of(new MemberSearchResultDTO(7, "alice"), new MemberSearchResultDTO(8, "alan"))));
        return new AdminSendMessagesView(presenter, displayTime());
    }

    private static void addRecipient(AdminSendMessagesView view, String username) {
        field(view, TextField.class, SEARCH_MEMBERS).setValue("al");
        A11y.activate(namedStartingWith(view, username));
    }

    private <T extends Component> T attached(T component) {
        harness.attach(component);
        return component;
    }

    private static List<Component> removeButtons(Component view) {
        List<Component> found = new ArrayList<>();
        for (NativeButton b : find(view, NativeButton.class)) {
            if (A11y.name(b.getElement()).toLowerCase(Locale.ROOT).startsWith("remove")) {
                found.add(b);
            }
        }
        assertFalse(found.isEmpty(), "the cart shows no Remove buttons");
        return found;
    }

    private static Element namedStartingWith(Component root, String prefix) {
        return A11y.elements(root, e -> (A11y.isButtonOrLink(e) || "button".equals(A11y.role(e)))
                && A11y.name(e).startsWith(prefix))
            .stream()
            .findFirst()
            .orElseThrow(() -> new AssertionError("no control named \"" + prefix + "…\" in: "
                + A11y.elements(root, A11y::isButtonOrLink).stream().map(A11y::describe).toList()));
    }

    private static Element option(Component root, String label) {
        return A11y.elements(root, e -> label.equals(A11y.name(e))
                && Set.of("radio", "checkbox", "option", "menuitemradio", "menuitemcheckbox").contains(A11y.role(e)))
            .stream()
            .findFirst()
            .orElseThrow(() -> new AssertionError("no option \"" + label + "\" to choose"));
    }

    private static void choose(Component root, String label) {
        Element option = option(root, label);
        ViewHarness.openPopoverHolding(option);
        A11y.activate(option);
    }

    private static AdminInboxPresenter adminInboxPresenter() {
        AdminInboxPresenter presenter = mock(AdminInboxPresenter.class);
        when(presenter.load(any())).thenReturn(
            new AdminInboxPresenter.Outcome.Success(List.of(thread("c1", "DIRECT", "MEMBER"))));
        when(presenter.badge(any(), any())).thenReturn(Optional.empty());
        return presenter;
    }

    private static AppointmentInfoDTO manager(int userId, String username) {
        return new AppointmentInfoDTO("a-" + userId, 3, "Acme", userId, username, MEMBER_ID, "Manager", "ACTIVE",
            List.of("VIEW_SALES"), PAID_AT);
    }

    private static PurchaseRecordDTO order() {
        TicketRecordDTO ticket = new TicketRecordDTO(1, 8, 5, 42, "A1", 75.0, TicketStatus.ISSUED, "Coldplay",
            "Floor", "Acme", "CONCERT", null, "Tel Aviv", "TIX-1", false);
        return new PurchaseRecordDTO(42, MEMBER_ID, null, PAID_AT, 75.0, RefundStatus.NONE,
            List.of(), List.of(ticket), "alice");
    }

    private static CheckoutPresenter checkoutPresenter() {
        CheckoutPresenter presenter = mock(CheckoutPresenter.class);
        when(presenter.resolveIdentity()).thenReturn(new CheckoutPresenter.Identity(true, MEMBER_TOKEN, null, MEMBER_ID));
        LocalDateTime added = LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
        ActiveOrderDTO cart = new ActiveOrderDTO(MEMBER_ID, null, added, 300, 10.0,
            List.of(new ActiveOrderDTO.CartLineDTO(5, "Coldplay", 8, "A1", 10.0, added)));
        when(presenter.loadOrder()).thenReturn(
            new CheckoutPresenter.LoadOutcome.Loaded(cart, new CheckoutPresenter.Pricing(1000, 1000)));
        return presenter;
    }

    private CheckoutView filledCheckout(CheckoutPresenter presenter) {
        CheckoutView view = attached(new CheckoutView(presenter, CLOCK));
        view.beforeEnter(routeTo());
        view.afterNavigation(mock(AfterNavigationEvent.class));
        field(view, TextField.class, "Cardholder name").setValue("Alice Example");
        field(view, TextField.class, "Card number").setValue("4111 1111 1111 1111");
        field(view, TextField.class, "Expiry").setValue("12/30");
        field(view, TextField.class, "CVC").setValue("123");
        return view;
    }

    private static NativeButton payButton(CheckoutView view) {
        return find(view, NativeButton.class).stream()
            .filter(b -> b.getElement().getTextRecursively().trim().startsWith("Pay "))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no Pay button on the checkout"));
    }
}
