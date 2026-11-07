package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_ID;
import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_TOKEN;
import static com.ticketing.system.unit.presentation.ViewHarness.button;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static com.ticketing.system.unit.presentation.ViewHarness.text;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.reporting.application.service.MemberAccountService;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.RefundStatus;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.TicketRecordDTO;
import com.ticketing.system.sales.config.ReservationHoldProperties;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.ui.presenters.account.MyAccountPresenter;
import com.ticketing.system.ui.presenters.account.ReceiptPresenter;
import com.ticketing.system.ui.presenters.account.RefundPresenter;
import com.ticketing.system.ui.presenters.catalog.EventDetailsPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.support.EventCategoryLabels;
import com.ticketing.system.ui.views.account.MyAccountView;
import com.ticketing.system.ui.views.account.ReceiptView;
import com.ticketing.system.ui.views.catalog.EventDetailsView;
import com.vaadin.flow.component.Component;

/**
 * The event page and the ticket stubs name an event's category with its one label from
 * {@link EventCategoryLabels}, as the browse page does, not a prettified or raw enum name (#187).
 */
class CategoryLabelDisplaysTest {

    private static final EventCategory CATEGORY = EventCategory.THEATER;
    private static final String LABEL = EventCategoryLabels.label(CATEGORY);
    private static final int ORDER_ID = 42;

    private ViewHarness harness;
    private SessionIdentity identity;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
        ViewHarness.signInMember();
        identity = mock(SessionIdentity.class);
        when(identity.memberToken()).thenReturn(MEMBER_TOKEN);
        when(identity.credential()).thenReturn(MEMBER_TOKEN);
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenATheatreEvent_WhenItsPageShows_ThenItsCategoryReadsTheLabel() {
        EventDetailsPresenter presenter = mock(EventDetailsPresenter.class);
        when(presenter.load(any(), anyInt())).thenReturn(new EventDetailsPresenter.Outcome.Success(
            new EventDetailDTO("5", "Hamlet", null, "Live", CATEGORY, null, "3", "Acme", EventStatus.ON_SALE,
                List.of(), ZoneOffset.UTC, List.of(), 10.0),
            List.of(), 3, 3, null));
        EventDetailsView view = new EventDetailsView(presenter, identity, new ReservationHoldProperties(10));

        view.beforeEnter(routeTo("eventId", "5"));

        assertShowsTheLabel(text(view));
    }

    @Test
    void GivenATheatreTicket_WhenItsStubOpensFromMyAccount_ThenItsCategoryReadsTheLabel() {
        MyAccountPresenter presenter = mock(MyAccountPresenter.class);
        when(presenter.loadHistory()).thenReturn(new MyAccountPresenter.Outcome.Success(
            new PurchaseHistoryDTO(List.of(order()))));
        MyAccountView view = new MyAccountView(presenter, mock(RefundPresenter.class), identity,
            InboxViewHarness.displayTime());
        harness.attach(view);

        click(button(view, "View ticket + barcode"));

        assertShowsTheLabel(dialogTexts());
    }

    @Test
    void GivenATheatreTicket_WhenItsStubOpensFromItsReceipt_ThenItsCategoryReadsTheLabel() {
        MemberAccountService accountService = mock(MemberAccountService.class);
        when(accountService.viewMyReceipt(MEMBER_TOKEN, ORDER_ID)).thenReturn(order());
        ReceiptView view = new ReceiptView(new ReceiptPresenter(accountService), mock(RefundPresenter.class),
            identity, InboxViewHarness.displayTime());
        harness.attach(view);
        view.beforeEnter(routeTo("receiptId", String.valueOf(ORDER_ID)));

        click(button(view, "View ticket"));

        assertShowsTheLabel(dialogTexts());
    }

    private String dialogTexts() {
        return harness.dialogs().stream().map(ViewHarness::text).collect(Collectors.joining(" "));
    }

    private static PurchaseRecordDTO order() {
        LocalDateTime paidAt = LocalDateTime.of(2027, 7, 1, 12, 0);
        TicketRecordDTO ticket = new TicketRecordDTO(1, 8, 5, ORDER_ID, "A1", 75.0, TicketStatus.ISSUED, "Hamlet",
            "Stalls", "Acme", CATEGORY.name(), null, "Tel Aviv", "TIX-1", false);
        return new PurchaseRecordDTO(ORDER_ID, MEMBER_ID, null, paidAt, 75.0, RefundStatus.NONE, List.of(),
            List.of(ticket), "alice");
    }

    // The ticket stub's chip shows its text in capitals, so case is not compared; the enum's own spelling,
    // "THEATER", raw or prettified, must not show.
    private static void assertShowsTheLabel(String shown) {
        String lower = shown.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains(LABEL.toLowerCase(Locale.ROOT)),
            "expected the category label \"" + LABEL + "\" in: " + shown);
        assertFalse(lower.contains(CATEGORY.name().toLowerCase(Locale.ROOT)), "the enum's name shows in: " + shown);
    }
}
