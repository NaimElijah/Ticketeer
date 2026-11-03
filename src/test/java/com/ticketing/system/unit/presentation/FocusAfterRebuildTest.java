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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.RefundStatus;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.TicketRecordDTO;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.shared.dto.AppointmentInfoDTO;
import com.ticketing.system.shared.dto.CompanyDashboardDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.ui.components.kit.LkFilterChip;
import com.ticketing.system.ui.components.kit.LkTextRows;
import com.ticketing.system.ui.components.messaging.MdReplyBar;
import com.ticketing.system.ui.presenters.account.MyAccountPresenter;
import com.ticketing.system.ui.presenters.account.RefundPresenter;
import com.ticketing.system.ui.presenters.company.ManagerListPresenter;
import com.ticketing.system.ui.presenters.company.OwnerDashboardPresenter;
import com.ticketing.system.ui.presenters.messaging.AdminComplaintQueuePresenter;
import com.ticketing.system.ui.presenters.messaging.AdminInboxPresenter;
import com.ticketing.system.ui.presenters.messaging.CompanyInquiryInboxPresenter;
import com.ticketing.system.ui.presenters.messaging.SupportInboxPresenter;
import com.ticketing.system.ui.presenters.order.CartPresenter;
import com.ticketing.system.ui.presenters.order.CheckoutPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.account.MyAccountView;
import com.ticketing.system.ui.views.account.SupportInboxView;
import com.ticketing.system.ui.views.admin.AdminComplaintQueueView;
import com.ticketing.system.ui.views.admin.AdminInboxView;
import com.ticketing.system.ui.views.company.CompanyInquiryRespondView;
import com.ticketing.system.ui.views.company.ManagerListView;
import com.ticketing.system.ui.views.company.OwnerDashboardView;
import com.ticketing.system.ui.views.order.CartView;
import com.ticketing.system.ui.views.order.CheckoutView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.router.AfterNavigationEvent;

/**
 * After an action rebuilds part of the page, keyboard focus lands somewhere sensible on it, instead of on
 * the document (#188, A-M7, P-m9, P-M10, U-B1, U-B2, U-B6; WCAG 2.4.3 Focus Order, 4.1.3). Today each of
 * these actions removes the very control that had focus and builds a new one: the browser drops focus to
 * the body, so a keyboard user starts again from the top of the page and a screen reader says nothing.
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
    // What a status line says while the payment runs.
    private static final Predicate<String> PAYING =
        Pattern.compile("(?i)process|paying|payment in progress|busy|charging").asPredicate();

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

        assertFocusStaysOnPage(view, close, () -> click(close), "closing the conversation");
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

        assertFocusStaysOnPage(view, trigger, () -> choose(view, "Open"), "changing the status filter");
    }

    // ---- LkFilterChip ----

    @Test
    void GivenAFilterChip_WhenTheUserTogglesAnOption_ThenFocusStaysOnThatOption() {
        LkFilterChip chip = attached(new LkFilterChip("Status", List.of("Open", "Closed")));
        Element open = option(chip, "Open");

        int before = harness.focusTargets().size();
        A11y.activate(open);

        boolean keptInPlace = A11y.within(open, chip.getElement()) && A11y.focusable(open)
            && "true".equals(open.getAttribute("aria-checked"));
        List<Element> focused = after(before);
        boolean refocused = focused.stream().anyMatch(t -> A11y.within(t, chip.getElement())
            && "Open".equals(A11y.name(t)));
        assertTrue(keptInPlace || refocused, "after a toggle focus must stay on the option \"Open\": it must be "
            + "updated in place, or the rebuilt option focused; the option is "
            + (A11y.within(open, chip.getElement()) ? "still there" : "gone") + " and the focus calls were "
            + focused.stream().map(A11y::describe).toList());
    }

    @Test
    void GivenAFilterChipWithAnOptionTicked_WhenTheUserClearsIt_ThenFocusMovesToTheFirstOption() {
        LkFilterChip chip = attached(new LkFilterChip("Status", List.of("Open", "Closed"), false, List.of("Open")));
        Element clear = namedStartingWith(chip, "Clear");

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
        click(button(rows, "+ Add artist"));
        Element remove = namedStartingWith(rows, "Remove");

        assertFocusStaysOnPage(rows, remove, () -> A11y.activate(remove), "removing an artist row");
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

        assertFocusStaysOnPage(view, selector, () -> choose(view, "Beta"), "switching company");
    }

    // ---- CheckoutView ----

    @Test
    void GivenAFilledPayment_WhileThePaymentRuns_ThenThePayButtonOrAStatusLineSaysItIsBusy() {
        ViewHarness.signInMember();
        CheckoutPresenter presenter = checkoutPresenter();
        AtomicReference<String> busyDuringPayment = new AtomicReference<>("");
        CheckoutView[] holder = new CheckoutView[1];
        when(presenter.payAsMember(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            busyDuringPayment.set(busyState(holder[0]));
            return new CheckoutPresenter.PayOutcome.PaymentDeclined("declined");
        });
        holder[0] = filledCheckout(presenter);

        click(payButton(holder[0]));

        assertFalse(busyDuringPayment.get().isEmpty(), "while the payment runs the Pay button must say it is busy "
            + "(aria-busy=true), or a status line (role=status / aria-live=polite) must say it is processing; "
            + "the Pay button is " + A11y.describe(payButton(holder[0]).getElement()));
    }

    @Test
    void GivenADeclinedPayment_WhenThePaymentEnds_ThenFocusReturnsToThePage() {
        ViewHarness.signInMember();
        CheckoutPresenter presenter = checkoutPresenter();
        when(presenter.payAsMember(any(), any(), any(), any(), any(), any()))
            .thenReturn(new CheckoutPresenter.PayOutcome.PaymentDeclined("declined"));
        CheckoutView view = filledCheckout(presenter);
        NativeButton pay = payButton(view);
        int before = harness.focusTargets().size();

        click(pay);

        List<Element> focused = after(before);
        assertTrue(focused.stream().anyMatch(t -> A11y.within(t, view.getElement())),
            "the Pay button is disabled while paying, which drops its focus, so focus must be returned to the "
                + "page (the Pay button) after the payment; the focus calls were "
                + focused.stream().map(A11y::describe).toList());
    }

    // ---- assertions ------------------------------------------------------------------------------

    private void assertFocusStaysOnPage(Component root, Component used, Runnable action, String what) {
        assertFocusStaysOnPage(root, used.getElement(), action, what);
    }

    private void assertFocusStaysOnPage(Component root, Element used, Runnable action, String what) {
        int before = harness.focusTargets().size();

        action.run();

        boolean keptInPlace = A11y.within(used, root.getElement()) && A11y.inTabOrder(used, root.getElement());
        List<Element> focused = after(before);
        boolean refocused = focused.stream().anyMatch(t -> A11y.within(t, root.getElement()));
        assertTrue(keptInPlace || refocused, "after " + what + " focus must stay on the page: the control used ("
            + A11y.describe(used) + ") is " + (A11y.within(used, root.getElement()) ? "still there but not focusable"
            : "gone") + ", and the page asked to focus " + focused.stream().map(A11y::describe).toList()
            + " (an element still on the page is needed)");
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

    // After the action the page asked to focus an element on it that the predicate accepts.
    private void assertFocusMovesTo(Component root, Runnable action, Predicate<Element> isTarget, String what,
                                    String target) {
        int before = harness.focusTargets().size();

        action.run();

        List<Element> focused = after(before);
        assertTrue(focused.stream().anyMatch(t -> A11y.within(t, root.getElement()) && isTarget.test(t)),
            "after " + what + " focus must move to " + target + "; the page asked to focus "
                + focused.stream().map(A11y::describe).toList());
    }

    private List<Element> after(int before) {
        List<Element> all = harness.focusTargets();
        return new ArrayList<>(all.subList(before, all.size()));
    }

    // ---- arrangement -----------------------------------------------------------------------------

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
        A11y.activate(option(root, label));
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

    // What says the payment is running: aria-busy on the Pay button, or a status line saying so.
    private static String busyState(CheckoutView view) {
        Element pay = payButton(view).getElement();
        if ("true".equals(pay.getAttribute("aria-busy"))) {
            return "aria-busy";
        }
        return A11y.elements(view, e -> A11y.politeLiveRegion(e).filter(e::equals).isPresent()).stream()
            .map(A11y::text)
            .filter(PAYING)
            .findFirst()
            .orElse("");
    }
}
