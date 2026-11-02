package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_ID;
import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_TOKEN;
import static com.ticketing.system.unit.presentation.ViewHarness.button;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.field;
import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.notifications.application.port.in.INotificationService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.sales.config.ReservationHoldProperties;
import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.shared.dto.InventoryZoneDTO;
import com.ticketing.system.shared.dto.LoginDTO;
import com.ticketing.system.shared.dto.VenueMapDTO;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.presenters.auth.LoginPresenter;
import com.ticketing.system.ui.presenters.catalog.SeatPickerPresenter;
import com.ticketing.system.ui.presenters.notifications.NotificationBellPresenter;
import com.ticketing.system.ui.presenters.order.CartBadgePresenter;
import com.ticketing.system.ui.presenters.order.CartPresenter;
import com.ticketing.system.ui.security.SignOutFlow;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.FreshGuestSession;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.session.UnreadAlertsSendBack;
import com.ticketing.system.ui.views.auth.LoginView;
import com.ticketing.system.ui.views.catalog.SeatPickerView;
import com.ticketing.system.ui.views.order.CartView;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.AfterNavigationEvent;

/**
 * The top bar's cart badge is cached in the session for a few seconds, so a cart change made in this
 * session must clear that cache: the next page shows the new count at once (#187, UI-A12). The cart is
 * changed through the real presenters, so the cache may be cleared in the view or the presenter.
 *
 * <p>Not covered: the checkout, which empties the cart, since its view and presenter need the payment
 * flow. A member's sign-in and a sign-out, which swap the cart, go through {@link LoginView} and
 * {@link FreshGuestSession}.
 */
class CartBadgeFreshnessTest {

    // ---- UI-A12: the cart badge's cache is never invalidated (#187) ----

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-07-15T17:00:00Z"), ZoneOffset.UTC);
    private static final int EVENT_ID = 5;
    private static final int ZONE_ID = 8;
    private static final long HOLD_SECONDS = 300;

    private ViewHarness harness;
    private CartBadgePresenter badge;
    private SessionIdentity identity;
    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
        ViewHarness.signInMember();
        badge = mock(CartBadgePresenter.class);
        identity = mock(SessionIdentity.class);
        when(identity.credential()).thenReturn(MEMBER_TOKEN);
        when(identity.visitor()).thenReturn(new SessionIdentity.Visitor.Member(MEMBER_TOKEN, MEMBER_ID));
        reservationService = mock(ReservationService.class);
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenTheBadgeShowsOneTicket_WhenTheMemberReservesAnotherAndMovesOn_ThenTheBadgeShowsTwoAtOnce() {
        when(badge.loadBadge()).thenReturn(cart(1), cart(2));
        MainLayout layout = mainLayout();
        SeatPickerView seatPicker = standingSeatPicker();

        click(find(seatPicker, LkBtn.class).stream()
            .filter(b -> b.getElement().getTextRecursively().startsWith("Hold"))
            .findFirst()
            .orElseThrow());
        layout.afterNavigation(mock(AfterNavigationEvent.class));

        assertEquals("2", badgeText(layout), "the badge must show the cart as it is after the reservation");
    }

    @Test
    void GivenTheBadgeShowsTwoTickets_WhenTheMemberRemovesOneAndMovesOn_ThenTheBadgeShowsOneAtOnce() {
        when(badge.loadBadge()).thenReturn(cart(2), cart(1));
        when(reservationService.viewMyActiveOrder(MEMBER_TOKEN)).thenReturn(order(2), order(1));
        MainLayout layout = mainLayout();
        CartView cartView = new CartView(new CartPresenter(reservationService, identity));

        click(button(cartView, "Remove"));
        layout.afterNavigation(mock(AfterNavigationEvent.class));

        assertEquals("1", badgeText(layout), "the badge must show the cart as it is after the removal");
    }

    // Kept: without a cart change the badge is read from the cache, not from the server on every page.
    @Test
    void GivenNoCartChange_WhenTheMemberMovesOnWithinTheCacheWindow_ThenTheBadgeIsNotReadAgain() {
        when(badge.loadBadge()).thenReturn(cart(1));
        MainLayout layout = mainLayout();

        layout.afterNavigation(mock(AfterNavigationEvent.class));

        verify(badge, times(1)).loadBadge();
    }

    // A member's sign-in and any sign-out swap the session's cart, so the badge is read again.
    @Test
    void GivenTheBadgeShowsOneTicket_WhenTheMemberSignsOutAndMovesOn_ThenTheBadgeIsReadAgain() {
        when(badge.loadBadge()).thenReturn(cart(1), new CartBadgePresenter.Outcome.Empty());
        MainLayout layout = mainLayout();
        AuthenticationService authenticationService = mock(AuthenticationService.class);
        when(authenticationService.startGuestSession()).thenReturn(new GuestSessionDTO("fresh-sid", Instant.EPOCH));

        new FreshGuestSession(authenticationService, new UnreadAlertsSendBack(mock(INotificationService.class)))
            .restore();
        layout.afterNavigation(mock(AfterNavigationEvent.class));

        verify(badge, times(2)).loadBadge();
    }

    @Test
    void GivenAGuestsBadge_WhenTheVisitorSignsInAsAMemberAndMovesOn_ThenTheBadgeShowsTheirCartAtOnce() {
        AuthSession.signOut();
        when(badge.loadBadge()).thenReturn(new CartBadgePresenter.Outcome.Empty(), cart(2));
        MainLayout layout = mainLayout();
        LoginPresenter login = mock(LoginPresenter.class);
        when(login.attemptLogin(any(), any(), any())).thenReturn(new LoginPresenter.Outcome.Success(
            new LoginDTO(new AuthTokenDTO(MEMBER_TOKEN, 0L, MEMBER_ID, "alice"), null, List.of())));
        LoginView loginView = new LoginView(login, identity);
        field(loginView, TextField.class, "Username").setValue("alice");
        field(loginView, PasswordField.class, "Password").setValue("secret-pw");

        click(button(loginView, "Sign in"));
        layout.afterNavigation(mock(AfterNavigationEvent.class));

        assertEquals("2", badgeText(layout), "the badge must show the signed-in member's cart");
    }

    // ---- arrangement ----

    private MainLayout mainLayout() {
        return new MainLayout(badge, mock(SignOutFlow.class), mock(NotificationBellPresenter.class), CLOCK);
    }

    private SeatPickerView standingSeatPicker() {
        CatalogService catalogService = mock(CatalogService.class);
        InventoryZoneDTO zone = new InventoryZoneDTO(ZONE_ID, "Floor", "STANDING", 100, 50, 0, 50, 10.0, null, null);
        when(catalogService.getEventVenueMap(anyString(), anyInt()))
            .thenReturn(new VenueMapDTO(1, null, 3, 3, List.of(zone)));
        SeatPickerView view = new SeatPickerView(new SeatPickerPresenter(catalogService, reservationService, identity),
            new ReservationHoldProperties(10));
        view.beforeEnter(routeTo("eventId", String.valueOf(EVENT_ID), "zoneId", String.valueOf(ZONE_ID)));
        return view;
    }

    private static CartBadgePresenter.Outcome cart(int count) {
        return new CartBadgePresenter.Outcome.Cart(count, HOLD_SECONDS);
    }

    private static ActiveOrderDTO order(int lines) {
        LocalDateTime added = LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
        List<ActiveOrderDTO.CartLineDTO> cartLines = IntStream.rangeClosed(1, lines)
            .mapToObj(i -> new ActiveOrderDTO.CartLineDTO(EVENT_ID, "Coldplay", ZONE_ID, "A" + i, 10.0, added))
            .toList();
        return new ActiveOrderDTO(MEMBER_ID, null, added, HOLD_SECONDS, 10.0 * lines, cartLines);
    }

    private static String badgeText(MainLayout layout) {
        return find(layout, Span.class).stream()
            .filter(span -> span.hasClassName("lk-bell-badge"))
            .map(Span::getText)
            .findFirst()
            .orElse("no badge");
    }
}
