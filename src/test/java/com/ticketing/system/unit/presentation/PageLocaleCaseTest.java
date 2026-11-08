package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_ID;
import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_TOKEN;
import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static com.ticketing.system.unit.presentation.ViewHarness.gridColumn;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static com.ticketing.system.unit.presentation.ViewHarness.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.reporting.application.service.MemberAccountService;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.RefundStatus;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.TicketRecordDTO;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.testutil.DefaultLocale;
import com.ticketing.system.ui.components.company.EditPermissionsDialog;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.presenters.account.MyAccountPresenter;
import com.ticketing.system.ui.presenters.account.ReceiptPresenter;
import com.ticketing.system.ui.presenters.account.RefundPresenter;
import com.ticketing.system.ui.presenters.notifications.NotificationBellPresenter;
import com.ticketing.system.ui.presenters.order.CartBadgePresenter;
import com.ticketing.system.ui.security.SignOutFlow;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.account.MyAccountView;
import com.ticketing.system.ui.views.account.ReceiptView;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/**
 * The pages are in English ({@code lang="en"}, {@link PageLanguageTest}), so they change the case of their
 * text the English way whatever the server JVM's default locale is (#189). Each test builds its page under a
 * Turkish default, where a case change given no locale turns "i" into "İ" and "I" into "ı": "Manage
 * Inventory" would read "Manage ınventory", and a switch on a lowered category would miss "music". These case
 * changes run on each call, so a test sees what its page would show on such a server whatever ran first.
 */
class PageLocaleCaseTest {

    private static final Locale TURKISH_SERVER = Locale.forLanguageTag("tr-TR");
    private static final LocalDateTime ORDERED_AT = LocalDateTime.of(2026, 10, 9, 12, 0);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-07-15T17:00:00Z"), ZoneOffset.UTC);
    private static final int ORDER_ID = 42;
    private static final double PRICE = 75.0;
    // ReceiptView's swatch for a music ticket, against its default for a category it does not know.
    private static final String MUSIC_GRADIENT = "#8b5cf6, #ec4899";

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
    void GivenATurkishServerLocale_WhenTheBuyerShellShowsAMemberWhoseNameHasAnI_ThenTheInitialsAreEnglishCapitals() {
        AuthSession.storeAuth(new AuthTokenDTO(MEMBER_TOKEN, 0L, MEMBER_ID, "iris"));
        CartBadgePresenter badge = mock(CartBadgePresenter.class);
        when(badge.loadBadge()).thenReturn(new CartBadgePresenter.Outcome.Empty());

        List<String> shown = underATurkishServer(() -> find(new MainLayout(badge, mock(SignOutFlow.class),
            mock(NotificationBellPresenter.class), CLOCK), Span.class).stream().map(Span::getText).toList());

        assertTrue(shown.contains("IR"), "the initials of \"iris\" must read \"IR\": " + shown);
    }

    @Test
    void GivenATurkishServerLocale_WhenThePermissionsDialogOpens_ThenEachPermissionIsNamedTheEnglishWay() {
        DefaultLocale.runUnder(TURKISH_SERVER,
            () -> new EditPermissionsDialog("carol", "Manager", List.of(), names -> { }).open());

        String shown = text(harness.dialogs().get(0));

        assertTrue(shown.contains("Manage inventory"), "expected \"Manage inventory\" in: " + shown);
    }

    @Test
    void GivenATurkishServerLocale_WhenAReceiptShowsAMusicTicket_ThenItsSwatchHasTheMusicColours() {
        ViewHarness.signInMember();
        MemberAccountService accountService = mock(MemberAccountService.class);
        when(accountService.viewMyReceipt(MEMBER_TOKEN, ORDER_ID)).thenReturn(order(TicketStatus.ISSUED));

        List<String> backgrounds = underATurkishServer(() -> {
            ReceiptView view = new ReceiptView(new ReceiptPresenter(accountService), mock(RefundPresenter.class),
                memberIdentity(), InboxViewHarness.displayTime());
            harness.attach(view);
            view.beforeEnter(routeTo("receiptId", String.valueOf(ORDER_ID)));
            return find(view, Div.class).stream().map(div -> div.getStyle().get("background")).toList();
        });

        assertTrue(backgrounds.stream().anyMatch(shown -> shown != null && shown.contains(MUSIC_GRADIENT)),
            "a music ticket's swatch must have the music colours: " + backgrounds);
    }

    @Test
    void GivenATurkishServerLocale_WhenMyAccountListsAPaidTicket_ThenItsStatusReadsPaid() {
        ViewHarness.signInMember();
        MyAccountPresenter presenter = mock(MyAccountPresenter.class);
        when(presenter.loadHistory()).thenReturn(new MyAccountPresenter.Outcome.Success(
            new PurchaseHistoryDTO(List.of(order(TicketStatus.PAID)))));

        List<String> statuses = underATurkishServer(() -> gridColumn(new MyAccountView(presenter,
            mock(RefundPresenter.class), memberIdentity(), InboxViewHarness.displayTime()), "Status"));

        assertEquals(List.of("Paid", "Paid"), statuses, "the order's status, then its ticket's");
    }

    private static <T> T underATurkishServer(Supplier<T> call) {
        return DefaultLocale.callUnder(TURKISH_SERVER, call);
    }

    private static SessionIdentity memberIdentity() {
        SessionIdentity identity = mock(SessionIdentity.class);
        when(identity.memberToken()).thenReturn(MEMBER_TOKEN);
        when(identity.credential()).thenReturn(MEMBER_TOKEN);
        return identity;
    }

    private static PurchaseRecordDTO order(TicketStatus status) {
        TicketRecordDTO ticket = new TicketRecordDTO(1, 8, 5, ORDER_ID, "A1", PRICE, status, "Coldplay",
            "Floor", "Acme", EventCategory.MUSIC.name(), null, "Tel Aviv", "TIX-1", false);
        return new PurchaseRecordDTO(ORDER_ID, MEMBER_ID, null, ORDERED_AT, PRICE, RefundStatus.NONE, List.of(),
            List.of(ticket), "alice");
    }
}
