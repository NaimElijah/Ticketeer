package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_ID;
import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_TOKEN;
import static com.ticketing.system.unit.presentation.ViewHarness.gridColumn;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static com.ticketing.system.unit.presentation.ViewHarness.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.reporting.application.dto.SalesSummaryDTO;
import com.ticketing.system.reporting.application.service.MemberAccountService;
import com.ticketing.system.reporting.application.service.SalesSummaryCalculator;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.RefundStatus;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.TicketRecordDTO;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.shared.dto.EventSummaryDTO;
import com.ticketing.system.shared.dto.ShowDateDTO;
import com.ticketing.system.testutil.DefaultLocale;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.presenters.account.MyAccountPresenter;
import com.ticketing.system.ui.presenters.account.ReceiptPresenter;
import com.ticketing.system.ui.presenters.account.RefundPresenter;
import com.ticketing.system.ui.presenters.admin.GlobalHistoryPresenter;
import com.ticketing.system.ui.presenters.catalog.BrowseEventsPresenter;
import com.ticketing.system.ui.presenters.company.CompanySalesPresenter;
import com.ticketing.system.ui.presenters.messaging.AdminSendMessagesPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.account.MyAccountView;
import com.ticketing.system.ui.views.account.ReceiptView;
import com.ticketing.system.ui.views.admin.AdminSendMessagesView;
import com.ticketing.system.ui.views.admin.GlobalHistoryView;
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.ticketing.system.ui.views.company.CompanySalesView;

/**
 * The pages are in English ({@code lang="en"}, {@link PageLanguageTest}), so their dates name the month in
 * English whatever the server JVM's default locale is (#189).
 *
 * <p>Each test builds its page under a German default, where a formatter with no locale writes "Okt." for
 * October. The views keep their formatters in static fields, which take the default locale when the class is
 * initialised, so each page is first built inside the swap: run alone, a test sees the formatter its view
 * would get on a German server.
 */
class PageLocaleDatesTest {

    private static final Locale GERMAN_SERVER = Locale.GERMANY;
    private static final LocalDateTime IN_OCTOBER = LocalDateTime.of(2026, 10, 9, 12, 0);
    private static final String ENGLISH_DAY_AND_MONTH = "9 Oct";
    private static final String GERMAN_MONTH = "Okt";
    private static final int ORDER_ID = 42;

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenAGermanServerLocale_WhenMyAccountListsAnOctoberOrder_ThenItsDateNamesTheMonthInEnglish() {
        ViewHarness.signInMember();
        MyAccountPresenter presenter = mock(MyAccountPresenter.class);
        when(presenter.loadHistory()).thenReturn(new MyAccountPresenter.Outcome.Success(
            new PurchaseHistoryDTO(List.of(octoberOrder()))));

        List<String> dates = underAGermanServer(() -> gridColumn(new MyAccountView(presenter,
            mock(RefundPresenter.class), memberIdentity(), InboxViewHarness.displayTime()), "Date"));

        assertEquals(List.of("9 Oct 2026"), dates);
    }

    @Test
    void GivenAGermanServerLocale_WhenAnOctoberReceiptOpens_ThenItsDateNamesTheMonthInEnglish() {
        ViewHarness.signInMember();
        MemberAccountService accountService = mock(MemberAccountService.class);
        when(accountService.viewMyReceipt(MEMBER_TOKEN, ORDER_ID)).thenReturn(octoberOrder());

        String shown = underAGermanServer(() -> {
            ReceiptView view = new ReceiptView(new ReceiptPresenter(accountService), mock(RefundPresenter.class),
                memberIdentity(), InboxViewHarness.displayTime());
            harness.attach(view);
            view.beforeEnter(routeTo("receiptId", String.valueOf(ORDER_ID)));
            return text(view);
        });

        assertNamesOctoberInEnglish(shown);
    }

    @Test
    void GivenAGermanServerLocale_WhenTheOutreachHistoryListsAnOctoberMessage_ThenItsDateNamesTheMonthInEnglish() {
        ViewHarness.signInAdmin();
        AdminSendMessagesPresenter presenter = mock(AdminSendMessagesPresenter.class);
        when(presenter.load(any())).thenReturn(new AdminSendMessagesPresenter.Outcome.Success(
            List.of(new AdminSendMessagesPresenter.SentOutreach(IN_OCTOBER, "Doors open early", 3))));

        List<String> dates = underAGermanServer(() -> gridColumn(
            new AdminSendMessagesView(presenter, InboxViewHarness.displayTime()), "Started"));

        assertEquals(List.of("9 Oct 2026 · 12:00"), dates);
    }

    @Test
    void GivenAGermanServerLocale_WhenTheGlobalHistoryListsAnOctoberOrder_ThenItsDateNamesTheMonthInEnglish() {
        ViewHarness.signInAdmin();
        SystemAdminService admin = mock(SystemAdminService.class);
        when(admin.viewGlobalHistory(any(), any())).thenReturn(List.of(new PurchaseHistoryDTO(List.of(octoberOrder()))));

        List<String> dates = underAGermanServer(() -> gridColumn(new GlobalHistoryView(
            new GlobalHistoryPresenter(admin, new SalesSummaryCalculator()), InboxViewHarness.displayTime()), "Date"));

        assertEquals(List.of("9 Oct 2026"), dates);
    }

    @Test
    void GivenAGermanServerLocale_WhenTheBrowsePageListsAnOctoberShow_ThenItsDateNamesTheMonthInEnglish() {
        BrowseEventsPresenter presenter = mock(BrowseEventsPresenter.class);
        when(presenter.search(any(), any())).thenReturn(new ListOutcome.Loaded<>(List.of(new EventSummaryDTO(1,
            "Hamlet", "ON_SALE", null, "Acme", EventCategory.THEATER.name(), "Tel Aviv",
            List.of(new ShowDateDTO(IN_OCTOBER, IN_OCTOBER.plusHours(3))), ZoneOffset.UTC, 50.0, 50.0, false,
            List.of()))));
        when(presenter.countries()).thenReturn(new ListOutcome.Loaded<>(List.of()));

        String shown = underAGermanServer(() -> text(new BrowseEventsView(presenter, mock(SessionIdentity.class),
            InboxViewHarness.displayTime())));

        assertNamesOctoberInEnglish(shown);
    }

    @Test
    void GivenAGermanServerLocale_WhenCompanySalesListsAnOctoberOrder_ThenItsDateNamesTheMonthInEnglish() {
        ViewHarness.signInMember();
        CompanySalesPresenter presenter = mock(CompanySalesPresenter.class);
        when(presenter.load(any(), any())).thenReturn(new CompanySalesPresenter.Outcome.Success("Acme",
            new PurchaseHistoryDTO(List.of(octoberOrder()))));
        when(presenter.summarize(any())).thenReturn(new SalesSummaryDTO(75.0, 1, 75.0, Optional.of("Hamlet"), 0));

        List<String> dates = underAGermanServer(() -> gridColumn(
            new CompanySalesView(presenter, InboxViewHarness.displayTime()), "Date"));

        assertEquals(List.of("9 Oct 2026"), dates);
    }

    private static <T> T underAGermanServer(Supplier<T> call) {
        return DefaultLocale.callUnder(GERMAN_SERVER, call);
    }

    private static void assertNamesOctoberInEnglish(String shown) {
        assertTrue(shown.contains(ENGLISH_DAY_AND_MONTH), "expected \"" + ENGLISH_DAY_AND_MONTH + "\" in: " + shown);
        assertFalse(shown.contains(GERMAN_MONTH), "the month is named in German in: " + shown);
    }

    private static SessionIdentity memberIdentity() {
        SessionIdentity identity = mock(SessionIdentity.class);
        when(identity.memberToken()).thenReturn(MEMBER_TOKEN);
        when(identity.credential()).thenReturn(MEMBER_TOKEN);
        return identity;
    }

    private static PurchaseRecordDTO octoberOrder() {
        TicketRecordDTO ticket = new TicketRecordDTO(1, 8, 5, ORDER_ID, "A1", 75.0, TicketStatus.ISSUED, "Hamlet",
            "Stalls", "Acme", EventCategory.THEATER.name(), null, "Tel Aviv", "TIX-1", false);
        return new PurchaseRecordDTO(ORDER_ID, MEMBER_ID, null, IN_OCTOBER, 75.0, RefundStatus.NONE, List.of(),
            List.of(ticket), "alice");
    }
}
