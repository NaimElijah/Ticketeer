package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_ID;
import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_TOKEN;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.ZoneType;
import com.ticketing.system.reporting.application.service.MemberAccountService;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.RefundStatus;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.TicketRecordDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.TransactionRecordDTO;
import com.ticketing.system.sales.config.ReservationHoldProperties;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.shared.dto.CheckoutResultDTO;
import com.ticketing.system.shared.dto.EventSummaryDTO;
import com.ticketing.system.shared.dto.InventoryZoneDTO;
import com.ticketing.system.shared.dto.SeatDTO;
import com.ticketing.system.ui.components.kit.LkAuthCard;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.presenters.account.MyAccountPresenter;
import com.ticketing.system.ui.presenters.account.ReceiptPresenter;
import com.ticketing.system.ui.presenters.account.RefundPresenter;
import com.ticketing.system.ui.presenters.catalog.BrowseEventsPresenter;
import com.ticketing.system.ui.presenters.catalog.EventDetailsPresenter;
import com.ticketing.system.ui.presenters.catalog.SeatPickerPresenter;
import com.ticketing.system.ui.presenters.landing.LandingPresenter;
import com.ticketing.system.ui.session.OrderSession;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.account.MyAccountView;
import com.ticketing.system.ui.views.account.ReceiptView;
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.ticketing.system.ui.views.catalog.EventDetailsView;
import com.ticketing.system.ui.views.catalog.SeatPickerView;
import com.ticketing.system.ui.views.landing.LandingView;
import com.ticketing.system.ui.views.order.OrderConfirmationView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.dom.Element;

/**
 * Headings give the page its structure: each page has exactly one top-level heading naming it, a card's
 * title is a heading under it, and the sign-in card's title is the page's heading (#188, A-M8, P-m2,
 * U-A16; WCAG 1.3.1, 2.4.6, 2.4.10). Screen-reader users jump by heading; today card titles are plain
 * divs and six buyer pages have no h1 at all.
 *
 * <p>A heading is an {@code h1}..{@code h6} element, or {@code role=heading} with {@code aria-level}.
 */
class HeadingStructureTest {

    // ---- F6: headings (#188) ----

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-07-15T17:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2027, 7, 1, 12, 0);
    private static final int ORDER_ID = 42;
    private static final Set<Integer> CARD_TITLE_LEVELS = Set.of(2, 3);

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- shared components ----

    @Test
    void GivenACardWithATitle_WhenItRenders_ThenTheTitleIsALevelTwoOrThreeHeading() {
        LkCard card = new LkCard("Linked Editors");

        Element heading = headingSaying(card, "Linked Editors");

        assertTrue(CARD_TITLE_LEVELS.contains(level(heading)),
            "a card title sits under the page's h1, as an h2 or h3: " + A11y.describe(heading));
    }

    @Test
    void GivenTheSignInCard_WhenItRenders_ThenItsTitleIsThePagesH1() {
        LkAuthCard card = new LkAuthCard("Welcome back", "Sign in to buy tickets.");

        Element title = headingSaying(card, "Welcome back");

        assertEquals(1, level(title), "the sign-in card's title is the page's h1: " + A11y.describe(title));
    }

    // ---- one h1 per page ----

    @Test
    void GivenTheLanding_WhenItRenders_ThenItHasExactlyOneH1() {
        LandingPresenter presenter = mock(LandingPresenter.class);
        when(presenter.load()).thenReturn(new LandingPresenter.Outcome.Success(List.of(event()), List.of()));

        assertOneH1(new LandingView(presenter));
    }

    @Test
    void GivenBrowse_WhenItRenders_ThenItHasExactlyOneH1() {
        BrowseEventsPresenter presenter = mock(BrowseEventsPresenter.class);
        when(presenter.search(any(), any())).thenReturn(new ListOutcome.Loaded<>(List.of(event())));
        when(presenter.countries()).thenReturn(new ListOutcome.Loaded<>(List.of("Israel")));

        assertOneH1(new BrowseEventsView(presenter, mock(SessionIdentity.class), InboxViewHarness.displayTime()));
    }

    @Test
    void GivenAnEventPage_WhenItRenders_ThenItHasExactlyOneH1() {
        EventDetailsPresenter presenter = mock(EventDetailsPresenter.class);
        when(presenter.load(any(), anyInt())).thenReturn(new EventDetailsPresenter.Outcome.Success(
            new EventDetailDTO("5", "Coldplay", null, "Live", EventCategory.CONCERT, null, "3", "Acme",
                EventStatus.ON_SALE, List.of(), ZoneOffset.UTC, List.of(), 10.0),
            List.of(zone("STANDING", List.of())), 3, 3, null));
        EventDetailsView view = new EventDetailsView(presenter, mock(SessionIdentity.class),
            new ReservationHoldProperties(10));

        view.beforeEnter(routeTo("eventId", "5"));

        assertOneH1(view);
    }

    @Test
    void GivenTheSeatPicker_WhenItRenders_ThenItHasExactlyOneH1() {
        SeatPickerPresenter presenter = mock(SeatPickerPresenter.class);
        when(presenter.loadZone(anyInt(), anyInt())).thenReturn(new SeatPickerPresenter.LoadOutcome.Loaded(
            zone("SEATED", List.of(new SeatDTO("A1", 1, 0, "AVAILABLE"))), ZoneType.SEATED));
        SeatPickerView view = new SeatPickerView(presenter, new ReservationHoldProperties(10));

        view.beforeEnter(routeTo("eventId", "5", "zoneId", "8"));

        assertOneH1(view);
    }

    @Test
    void GivenTheOrderConfirmation_WhenItRenders_ThenItHasExactlyOneH1() {
        OrderSession.setResult(new CheckoutResultDTO(75.0, 42, 9001,
            List.of(new CheckoutResultDTO.IssuedTicketDTO(1, "TIX-0001-A1"))), CLOCK.instant());
        OrderConfirmationView view = new OrderConfirmationView(CLOCK);

        view.beforeEnter(routeTo());

        assertOneH1(view);
    }

    @Test
    void GivenMyAccount_WhenItRenders_ThenItHasExactlyOneH1() {
        ViewHarness.signInMember();
        MyAccountPresenter presenter = mock(MyAccountPresenter.class);
        when(presenter.loadHistory()).thenReturn(new MyAccountPresenter.Outcome.Success(
            new PurchaseHistoryDTO(List.of(order(RefundStatus.NONE, List.of())))));
        SessionIdentity identity = mock(SessionIdentity.class);
        when(identity.memberToken()).thenReturn(MEMBER_TOKEN);

        assertOneH1(new MyAccountView(presenter, mock(RefundPresenter.class), identity,
            InboxViewHarness.displayTime()));
    }

    // ---- receipt ----

    @Test
    void GivenARefundedReceipt_WhenItRenders_ThenRefundStatusIsAHeading() {
        MemberAccountService accountService = mock(MemberAccountService.class);
        when(accountService.viewMyReceipt(MEMBER_TOKEN, ORDER_ID)).thenReturn(order(RefundStatus.PARTIAL, List.of(
            new TransactionRecordDTO("PAYMENT_CHARGE", "WSEP", "TX-1", 75.0, "USD", PAID_AT),
            new TransactionRecordDTO("REFUND", "WSEP", "RF-1", 20.0, "USD", PAID_AT.plusDays(1)))));
        SessionIdentity identity = mock(SessionIdentity.class);
        when(identity.memberToken()).thenReturn(MEMBER_TOKEN);
        ReceiptView view = new ReceiptView(new ReceiptPresenter(accountService), mock(RefundPresenter.class),
            identity, InboxViewHarness.displayTime());

        view.beforeEnter(routeTo("receiptId", String.valueOf(ORDER_ID)));

        Element heading = headingSaying(view, "Refund status");
        assertTrue(level(heading) >= 2, "\"Refund status\" sits under the page's h1: " + A11y.describe(heading));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static void assertOneH1(Component page) {
        List<String> h1s = A11y.elements(page).stream()
            .filter(e -> isHeading(e) && level(e) == 1 && e.isVisible())
            .map(A11y::text)
            .toList();
        assertEquals(1, h1s.size(), "the page must have exactly one h1 naming it; it has: " + h1s
            + " among headings " + A11y.elements(page).stream().filter(HeadingStructureTest::isHeading)
                .map(e -> "h" + level(e) + " \"" + A11y.text(e) + "\"").toList());
    }

    private static Element headingSaying(Component root, String text) {
        return A11y.elements(root).stream()
            .filter(e -> isHeading(e) && text.equals(A11y.text(e)))
            .findFirst()
            .orElseThrow(() -> new AssertionError("\"" + text + "\" must be a heading; it renders as: "
                + A11y.elements(root).stream()
                    .filter(e -> text.equals(A11y.text(e)))
                    .map(A11y::describe)
                    .toList()));
    }

    private static boolean isHeading(Element e) {
        return "heading".equals(A11y.role(e));
    }

    private static int level(Element heading) {
        String tag = heading.getTag();
        if (tag.length() == 2 && tag.charAt(0) == 'h' && Character.isDigit(tag.charAt(1))) {
            return tag.charAt(1) - '0';
        }
        String ariaLevel = heading.getAttribute("aria-level");
        return ariaLevel == null ? 2 : Integer.parseInt(ariaLevel.trim());
    }

    private static EventSummaryDTO event() {
        return new EventSummaryDTO(1, "Hamlet", "ON_SALE", null, "Acme", EventCategory.THEATER.name(), "Tel Aviv",
            List.of(), ZoneOffset.UTC, 50.0, 50.0, false, List.of());
    }

    private static InventoryZoneDTO zone(String type, List<SeatDTO> seats) {
        return new InventoryZoneDTO(8, "Floor", type, 100, 50, 0, 50, 10.0, seats, null);
    }

    private static PurchaseRecordDTO order(RefundStatus refundStatus, List<TransactionRecordDTO> transactions) {
        TicketRecordDTO ticket = new TicketRecordDTO(1, 8, 5, ORDER_ID, "A1", 75.0, TicketStatus.ISSUED, "Coldplay",
            "Floor", "Acme", "CONCERT", null, "Tel Aviv", "TIX-1", false);
        return new PurchaseRecordDTO(ORDER_ID, MEMBER_ID, null, PAID_AT, 75.0, refundStatus, transactions,
            List.of(ticket), "alice");
    }
}
