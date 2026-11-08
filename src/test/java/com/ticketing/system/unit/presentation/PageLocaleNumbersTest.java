package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_ID;
import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_TOKEN;
import static com.ticketing.system.unit.presentation.ViewHarness.founderOf;
import static com.ticketing.system.unit.presentation.ViewHarness.gridColumn;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static com.ticketing.system.unit.presentation.ViewHarness.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.reporting.application.service.CompanyAnalyticsService;
import com.ticketing.system.reporting.application.service.MemberAccountService;
import com.ticketing.system.reporting.application.service.SalesSummaryCalculator;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.RefundStatus;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.TicketRecordDTO;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.shared.dto.AdminOverviewDTO;
import com.ticketing.system.shared.dto.CheckoutResultDTO;
import com.ticketing.system.shared.dto.CompanyDashboardDTO;
import com.ticketing.system.shared.dto.EventSummaryDTO;
import com.ticketing.system.shared.dto.MarketStateDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.shared.dto.ShowDateDTO;
import com.ticketing.system.shared.dto.SystemAnalyticsDTO;
import com.ticketing.system.testutil.DefaultLocale;
import com.ticketing.system.ui.components.Money;
import com.ticketing.system.ui.components.buyer.BzRefundDialog;
import com.ticketing.system.ui.components.venue.VkStandingZone;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.presenters.account.MyAccountPresenter;
import com.ticketing.system.ui.presenters.account.ReceiptPresenter;
import com.ticketing.system.ui.presenters.account.RefundPresenter;
import com.ticketing.system.ui.presenters.admin.AdminDashboardPresenter;
import com.ticketing.system.ui.presenters.admin.GlobalHistoryPresenter;
import com.ticketing.system.ui.presenters.admin.SystemAnalyticsPresenter;
import com.ticketing.system.ui.presenters.catalog.BrowseEventsPresenter;
import com.ticketing.system.ui.presenters.company.CompanyEventListPresenter;
import com.ticketing.system.ui.presenters.company.OwnerDashboardPresenter;
import com.ticketing.system.ui.presenters.landing.LandingPresenter;
import com.ticketing.system.ui.presenters.messaging.AdminSendMessagesPresenter;
import com.ticketing.system.ui.presenters.order.CheckoutPresenter;
import com.ticketing.system.ui.session.CurrentCompanies;
import com.ticketing.system.ui.session.OrderSession;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.account.MyAccountView;
import com.ticketing.system.ui.views.account.ReceiptView;
import com.ticketing.system.ui.views.admin.AdminDashboardView;
import com.ticketing.system.ui.views.admin.AdminSendMessagesView;
import com.ticketing.system.ui.views.admin.GlobalHistoryView;
import com.ticketing.system.ui.views.admin.SystemAnalyticsView;
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.ticketing.system.ui.views.company.CompanyEventListView;
import com.ticketing.system.ui.views.company.OwnerDashboardView;
import com.ticketing.system.ui.views.landing.LandingView;
import com.ticketing.system.ui.views.order.CheckoutView;
import com.ticketing.system.ui.views.order.OrderConfirmationView;
import com.vaadin.flow.router.AfterNavigationEvent;

/**
 * The pages are in English ({@code lang="en"}, {@link PageLanguageTest}), so they write money and counts the
 * English way whatever the server JVM's default locale is (#189): "$1,234.50", not "$1.234,50".
 *
 * <p>Most tests build their page under a German default, where a format with no locale groups thousands with
 * "." and marks decimals with ",". A format with neither ("%02d", "%05d") reads the same in German, but not
 * under a locale with digits of its own, so those tests run under Egyptian Arabic, where "%02d" writes "٠٥".
 * These formats run on each call, so a test sees what its page would show on such a server whatever ran first.
 */
class PageLocaleNumbersTest {

    private static final Locale GERMAN_SERVER = Locale.GERMANY;
    private static final Locale ARABIC_DIGITS_SERVER = Locale.forLanguageTag("ar-EG");
    private static final double PRICE = 1234.5;
    private static final String ENGLISH_PRICE = "$1,234.50";
    private static final int COUNT = 1234;
    private static final String ENGLISH_COUNT = "1,234";
    private static final double REVENUE = 12345.0;
    private static final String ENGLISH_REVENUE = "$12,345";
    private static final LocalDateTime ORDERED_AT = LocalDateTime.of(2026, 10, 9, 12, 0);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-07-15T17:00:00Z"), ZoneOffset.UTC);
    private static final int ORDER_ID = 42;
    private static final int COMPANY_ID = 1;

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- money and counts, under a German default ----

    @Test
    void GivenAGermanServerLocale_WhenTheRefundDialogOpens_ThenItWritesTheAmountTheEnglishWay() {
        DefaultLocale.runUnder(GERMAN_SERVER, () -> BzRefundDialog.open("#" + ORDER_ID, PRICE, reason -> { }));

        assertShows(text(harness.dialogs().get(0)), ENGLISH_PRICE);
    }

    @Test
    void GivenAGermanServerLocale_WhenAStandingZoneShowsItsTickets_ThenItWritesTheCountsTheEnglishWay() {
        int capacity = 10_000;

        String shown = underAGermanServer(() -> text(new VkStandingZone("Floor", COUNT, COUNT, capacity, "$10")));

        assertShows(shown, ENGLISH_COUNT + " sold · " + ENGLISH_COUNT + " held · 7,532 left");
    }

    @Test
    void GivenAGermanServerLocale_WhenMyAccountListsAnOrder_ThenItWritesTheTotalTheEnglishWay() {
        ViewHarness.signInMember();
        MyAccountPresenter presenter = mock(MyAccountPresenter.class);
        when(presenter.loadHistory()).thenReturn(new MyAccountPresenter.Outcome.Success(
            new PurchaseHistoryDTO(List.of(order()))));

        List<String> totals = underAGermanServer(() -> gridColumn(new MyAccountView(presenter,
            mock(RefundPresenter.class), memberIdentity(), InboxViewHarness.displayTime()), "Total"));

        assertEquals(List.of(ENGLISH_PRICE), totals);
    }

    @Test
    void GivenAGermanServerLocale_WhenAReceiptOpens_ThenItWritesTheTotalTheEnglishWay() {
        ViewHarness.signInMember();
        MemberAccountService accountService = mock(MemberAccountService.class);
        when(accountService.viewMyReceipt(MEMBER_TOKEN, ORDER_ID)).thenReturn(order());

        String shown = underAGermanServer(() -> {
            ReceiptView view = new ReceiptView(new ReceiptPresenter(accountService), mock(RefundPresenter.class),
                memberIdentity(), InboxViewHarness.displayTime());
            harness.attach(view);
            view.beforeEnter(routeTo("receiptId", String.valueOf(ORDER_ID)));
            return text(view);
        });

        assertShows(shown, ENGLISH_PRICE);
    }

    @Test
    void GivenAGermanServerLocale_WhenTheGlobalHistoryListsAnOrder_ThenItWritesTheMoneyTheEnglishWay() {
        ViewHarness.signInAdmin();
        SystemAdminService admin = mock(SystemAdminService.class);
        when(admin.viewGlobalHistory(any(), any())).thenReturn(List.of(new PurchaseHistoryDTO(List.of(order()))));

        String shown = underAGermanServer(() -> text(new GlobalHistoryView(
            new GlobalHistoryPresenter(admin, new SalesSummaryCalculator()), InboxViewHarness.displayTime())));

        assertShows(shown, ENGLISH_PRICE);
    }

    @Test
    void GivenAGermanServerLocale_WhenTheAdminDashboardShowsItsFigures_ThenItWritesThemTheEnglishWay() {
        AdminDashboardPresenter presenter = mock(AdminDashboardPresenter.class);
        when(presenter.load()).thenReturn(new AdminDashboardPresenter.Outcome.Success(
            new AdminOverviewDTO(COUNT, COUNT, COUNT, REVENUE)));

        String shown = underAGermanServer(() -> text(new AdminDashboardView(presenter)));

        assertShows(shown, ENGLISH_COUNT);
        assertShows(shown, ENGLISH_REVENUE);
    }

    @Test
    void GivenAGermanServerLocale_WhenTheOutreachHistoryListsAMessage_ThenItWritesItsRecipientsTheEnglishWay() {
        ViewHarness.signInAdmin();
        AdminSendMessagesPresenter presenter = mock(AdminSendMessagesPresenter.class);
        when(presenter.load(any())).thenReturn(new AdminSendMessagesPresenter.Outcome.Success(
            List.of(new AdminSendMessagesPresenter.SentOutreach(ORDERED_AT, "Doors open early", COUNT))));

        List<String> recipients = underAGermanServer(() -> gridColumn(
            new AdminSendMessagesView(presenter, InboxViewHarness.displayTime()), "Recipients"));

        assertEquals(List.of(ENGLISH_COUNT), recipients);
    }

    @Test
    void GivenAGermanServerLocale_WhenTheAnalyticsPageShowsARate_ThenItWritesTheDecimalTheEnglishWay() {
        ViewHarness.signInAdmin();
        double rate = 1.5;
        SystemAnalyticsPresenter presenter = mock(SystemAnalyticsPresenter.class);
        when(presenter.load()).thenReturn(new SystemAnalyticsPresenter.Outcome.Success(
            new MarketStateDTO("OPEN", null, null, true, true, null, true),
            new SystemAnalyticsDTO(0L, rate, 0d, 0d, 0d, 0d, 0L, 0L, 0L, 0L, 0L, 5)));

        String shown = underAGermanServer(() -> text(new SystemAnalyticsView(presenter, InboxViewHarness.displayTime())));

        assertShows(shown, "1.50");
    }

    @Test
    void GivenAGermanServerLocale_WhenTheBrowsePageListsAShow_ThenItWritesItsPriceTheEnglishWay() {
        BrowseEventsPresenter presenter = mock(BrowseEventsPresenter.class);
        when(presenter.search(any(), any())).thenReturn(new ListOutcome.Loaded<>(List.of(show())));
        when(presenter.countries()).thenReturn(new ListOutcome.Loaded<>(List.of()));

        List<String> prices = underAGermanServer(() -> gridColumn(new BrowseEventsView(presenter,
            mock(SessionIdentity.class), InboxViewHarness.displayTime()), "From"));

        assertEquals(List.of(ENGLISH_PRICE), prices);
    }

    @Test
    void GivenAGermanServerLocale_WhenTheCompanyEventListShowsAnEvent_ThenItWritesItsPriceTheEnglishWay() {
        ViewHarness.signInMember();
        CompanyEventListPresenter presenter = mock(CompanyEventListPresenter.class);
        when(presenter.load(any(), any(), any())).thenReturn(new CompanyEventListPresenter.Outcome.Success(List.of(
            new EventDetailDTO("9", "Hamlet", 4.5, "Live", EventCategory.THEATER, null, "3", "Acme",
                EventStatus.ON_SALE, List.of(), ZoneOffset.UTC, List.of(), PRICE))));
        when(presenter.countries(any(), any())).thenReturn(new ListOutcome.Loaded<>(List.of()));

        List<String> prices = underAGermanServer(() -> gridColumn(
            new CompanyEventListView(presenter, InboxViewHarness.displayTime()), "From"));

        assertEquals(List.of(ENGLISH_PRICE), prices);
    }

    @Test
    void GivenAGermanServerLocale_WhenTheOwnerDashboardShowsItsFigures_ThenItWritesThemTheEnglishWay() {
        ViewHarness.signInMember();
        CompanyManagementService companies = harness.memberships(founderOf(COMPANY_ID, "Acme"));
        when(companies.findMyCompanies(MEMBER_TOKEN)).thenReturn(
            List.of(new MyCompanyDTO(COMPANY_ID, "Acme", "FOUNDER")));
        CurrentCompanies.setCurrentCompany(COMPANY_ID);
        CompanyAnalyticsService analytics = mock(CompanyAnalyticsService.class);
        when(analytics.dashboard(eq(MEMBER_TOKEN), anyInt()))
            .thenReturn(new CompanyDashboardDTO(0, COUNT, REVENUE, null, 4.5));

        String shown = underAGermanServer(() -> text(
            new OwnerDashboardView(new OwnerDashboardPresenter(companies, analytics))));

        assertShows(shown, ENGLISH_COUNT);
        assertShows(shown, ENGLISH_REVENUE);
    }

    @Test
    void GivenAGermanServerLocale_WhenTheLandingFeaturesAShow_ThenItWritesItsPriceTheEnglishWay() {
        LandingPresenter presenter = mock(LandingPresenter.class);
        when(presenter.load()).thenReturn(new LandingPresenter.Outcome.Success(List.of(show()), List.of()));

        String shown = underAGermanServer(() -> text(new LandingView(presenter)));

        assertShows(shown, "From " + ENGLISH_PRICE);
    }

    // ---- digits, under a default with digits of its own ----

    @Test
    void GivenAnArabicServerLocale_WhenMoneyFormatsCents_ThenItWritesWesternDigits() {
        String shown = DefaultLocale.callUnder(ARABIC_DIGITS_SERVER, () -> Money.format(123_405));

        assertEquals("$1234.05", shown);
    }

    @Test
    void GivenAnArabicServerLocale_WhenTheCheckoutShowsItsTotal_ThenItWritesWesternDigits() {
        long totalCents = 1005;
        CheckoutPresenter presenter = mock(CheckoutPresenter.class);
        when(presenter.resolveIdentity()).thenReturn(new CheckoutPresenter.Identity(false, null, "guest-1", 0));
        LocalDateTime added = LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
        ActiveOrderDTO cart = new ActiveOrderDTO(0, "guest-1", added, 300, 10.05,
            List.of(new ActiveOrderDTO.CartLineDTO(5, "Coldplay", 8, "A1", 10.05, added)));
        when(presenter.loadOrder()).thenReturn(new CheckoutPresenter.LoadOutcome.Loaded(cart,
            new CheckoutPresenter.Pricing(totalCents, totalCents)));

        String shown = DefaultLocale.callUnder(ARABIC_DIGITS_SERVER, () -> {
            CheckoutView view = new CheckoutView(presenter, CLOCK);
            harness.attach(view);
            view.beforeEnter(routeTo());
            view.afterNavigation(mock(AfterNavigationEvent.class));
            return text(view);
        });

        assertShows(shown, "Pay $10.05");
    }

    @Test
    void GivenAnArabicServerLocale_WhenTheOrderConfirmationShows_ThenItWritesTheReceiptNumberInWesternDigits() {
        OrderSession.setResult(new CheckoutResultDTO(PRICE, ORDER_ID, 9001,
            List.of(new CheckoutResultDTO.IssuedTicketDTO(1, "TIX-0001-A1"))), CLOCK.instant());

        String shown = DefaultLocale.callUnder(ARABIC_DIGITS_SERVER, () -> {
            OrderConfirmationView view = new OrderConfirmationView(CLOCK);
            view.beforeEnter(routeTo());
            return text(view);
        });

        assertShows(shown, "Receipt #TKT-00042");
    }

    // ---- arrangement and assertions ----

    private static <T> T underAGermanServer(Supplier<T> call) {
        return DefaultLocale.callUnder(GERMAN_SERVER, call);
    }

    private static void assertShows(String shown, String expected) {
        assertTrue(shown.contains(expected), "expected \"" + expected + "\" in: " + shown);
    }

    private static SessionIdentity memberIdentity() {
        SessionIdentity identity = mock(SessionIdentity.class);
        when(identity.memberToken()).thenReturn(MEMBER_TOKEN);
        when(identity.credential()).thenReturn(MEMBER_TOKEN);
        return identity;
    }

    private static PurchaseRecordDTO order() {
        TicketRecordDTO ticket = new TicketRecordDTO(1, 8, 5, ORDER_ID, "A1", PRICE, TicketStatus.ISSUED, "Hamlet",
            "Stalls", "Acme", EventCategory.THEATER.name(), null, "Tel Aviv", "TIX-1", false);
        return new PurchaseRecordDTO(ORDER_ID, MEMBER_ID, null, ORDERED_AT, PRICE, RefundStatus.NONE, List.of(),
            List.of(ticket), "alice");
    }

    private static EventSummaryDTO show() {
        return new EventSummaryDTO(1, "Hamlet", "ON_SALE", null, "Acme", EventCategory.THEATER.name(), "Tel Aviv",
            List.of(new ShowDateDTO(ORDERED_AT, ORDERED_AT.plusHours(3))), ZoneOffset.UTC, PRICE, PRICE, false,
            List.of());
    }
}
