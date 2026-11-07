package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_ID;
import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_TOKEN;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.field;
import static com.ticketing.system.unit.presentation.ViewHarness.find;
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
import java.util.Locale;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.domain.ZoneType;
import com.ticketing.system.sales.config.ReservationHoldProperties;
import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.shared.dto.InventoryZoneDTO;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.presenters.catalog.SeatPickerPresenter;
import com.ticketing.system.ui.presenters.order.CartPresenter;
import com.ticketing.system.ui.presenters.order.CheckoutPresenter;
import com.ticketing.system.ui.support.ServiceErrors;
import com.ticketing.system.ui.views.catalog.SeatPickerView;
import com.ticketing.system.ui.views.order.CartView;
import com.ticketing.system.ui.views.order.CheckoutView;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.AfterNavigationEvent;

/**
 * Every presenter failure carries a reason, and for an internal failure that reason is already the
 * generic "please try again" text. A view puts its own context in front of the reason, once
 * ({@code Lk.withReason}), and never adds a "please try again" of its own (#187, PR #250 review).
 */
class InternalFailureToastTest {

    // ---- a failure's toast is the view's context and the reason, once (#187) ----

    private static final String GENERIC = ServiceErrors.GENERIC_FAILURE_MESSAGE;
    private static final Pattern TRY_AGAIN = Pattern.compile("try again");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-07-15T17:00:00Z"), ZoneOffset.UTC);
    private static final long HOLD_SECONDS = 300;
    private static final int EVENT_ID = 5;
    private static final int ZONE_ID = 8;
    private static final long CART_CENTS = 1000;

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
        ViewHarness.signInMember();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenTheCartFailsToLoadInternally_WhenTheCartOpens_ThenTheToastIsItsContextAndTheGenericReasonOnce() {
        CartPresenter presenter = mock(CartPresenter.class);
        when(presenter.loadCart()).thenReturn(new CartPresenter.LoadOutcome.Failure(GENERIC));

        new CartView(presenter);

        assertOneToastWithContextAndTheGenericReasonOnce("Could not load your cart");
    }

    @Test
    void GivenRemovingFailsInternally_WhenTheBuyerRemovesALine_ThenTheToastIsItsContextAndTheGenericReasonOnce() {
        CartPresenter presenter = mock(CartPresenter.class);
        CartPresenter.CartVM cart = new CartPresenter.CartVM(
            List.of(new CartPresenter.CartVM.LineVM("Coldplay", "A1", 10.0, EVENT_ID, ZONE_ID)), HOLD_SECONDS);
        when(presenter.loadCart()).thenReturn(new CartPresenter.LoadOutcome.Shown(cart, CART_CENTS));
        when(presenter.removeLine(any())).thenReturn(new CartPresenter.RemoveOutcome.Failure(GENERIC));
        CartView view = new CartView(presenter);
        harness.attach(view);

        click(button(view, "Remove"));

        assertOneToastWithContextAndTheGenericReasonOnce("Could not remove the item");
    }

    @Test
    void GivenPaymentFailsInternally_WhenTheMemberPays_ThenTheToastIsItsContextAndTheGenericReasonOnce() {
        CheckoutPresenter presenter = mock(CheckoutPresenter.class);
        when(presenter.resolveIdentity()).thenReturn(new CheckoutPresenter.Identity(true, MEMBER_TOKEN, null, MEMBER_ID));
        when(presenter.loadOrder()).thenReturn(new CheckoutPresenter.LoadOutcome.Loaded(order(),
            new CheckoutPresenter.Pricing(CART_CENTS, CART_CENTS)));
        when(presenter.payAsMember(any(), any(), any(), any(), any(), any()))
            .thenReturn(new CheckoutPresenter.PayOutcome.Failure(GENERIC));
        CheckoutView view = new CheckoutView(presenter, CLOCK);
        harness.attach(view);
        view.beforeEnter(routeTo());
        view.afterNavigation(mock(AfterNavigationEvent.class));
        field(view, TextField.class, "Cardholder name").setValue("Alice Example");
        field(view, TextField.class, "Card number").setValue("4111 1111 1111 1111");
        field(view, TextField.class, "Expiry").setValue("12/30");
        field(view, TextField.class, "CVC").setValue("123");

        click(find(view, NativeButton.class).stream()
            .filter(b -> b.getElement().getTextRecursively().trim().startsWith("Pay "))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no Pay button on the checkout")));

        assertOneToastWithContextAndTheGenericReasonOnce("Payment could not be completed");
    }

    @Test
    void GivenReservingFailsInternally_WhenTheBuyerHoldsTickets_ThenTheToastIsItsContextAndTheGenericReasonOnce() {
        SeatPickerPresenter presenter = mock(SeatPickerPresenter.class);
        when(presenter.loadZone(anyInt(), anyInt())).thenReturn(new SeatPickerPresenter.LoadOutcome.Loaded(
            new InventoryZoneDTO(ZONE_ID, "Floor", "STANDING", 100, 50, 0, 50, 10.0, null, null), ZoneType.STANDING));
        when(presenter.reserveStanding(anyInt(), anyInt(), anyInt()))
            .thenReturn(new SeatPickerPresenter.ReserveOutcome.Failure(GENERIC));
        SeatPickerView view = new SeatPickerView(presenter, new ReservationHoldProperties(10));
        harness.attach(view);
        view.beforeEnter(routeTo("eventId", String.valueOf(EVENT_ID), "zoneId", String.valueOf(ZONE_ID)));

        click(find(view, LkBtn.class).stream()
            .filter(b -> b.getElement().getTextRecursively().startsWith("Hold"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no Hold button on the seat picker")));

        assertOneToastWithContextAndTheGenericReasonOnce("Couldn't reserve those tickets");
    }

    private static ActiveOrderDTO order() {
        LocalDateTime added = LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
        return new ActiveOrderDTO(MEMBER_ID, null, added, HOLD_SECONDS, 10.0,
            List.of(new ActiveOrderDTO.CartLineDTO(EVENT_ID, "Coldplay", ZONE_ID, "A1", 10.0, added)));
    }

    private static LkBtn button(CartView view, String label) {
        return find(view, LkBtn.class).stream()
            .filter(b -> b.getElement().getTextRecursively().contains(label))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no " + label + " button"));
    }

    private void assertOneToastWithContextAndTheGenericReasonOnce(String context) {
        List<String> toasts = harness.toasts();
        assertEquals(1, toasts.size(), "expected one toast, got: " + toasts);
        String toast = toasts.get(0);
        assertTrue(toast.startsWith(context), "the toast must open with the view's context \"" + context + "\": " + toast);
        assertEquals(1, toast.split(Pattern.quote(GENERIC), -1).length - 1,
            "the generic reason must show once: " + toast);
        assertEquals(1, TRY_AGAIN.matcher(toast.toLowerCase(Locale.ROOT)).results().count(),
            "\"please try again\" must show once: " + toast);
    }
}
