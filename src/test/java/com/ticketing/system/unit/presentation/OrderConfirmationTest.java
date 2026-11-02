package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.field;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static com.ticketing.system.unit.presentation.ViewHarness.text;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.shared.dto.CheckoutResultDTO;
import com.ticketing.system.ui.presenters.order.CheckoutPresenter;
import com.ticketing.system.ui.session.OrderSession;
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.ticketing.system.ui.views.order.CheckoutView;
import com.ticketing.system.ui.views.order.OrderConfirmationView;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.BeforeEnterEvent;

/**
 * The checkout and its confirmation promise no email, since the platform sends none (#102), and a
 * guest who reloads the confirmation still sees their tickets for the rest of the session (#187, UI-A7).
 */
class OrderConfirmationTest {

    // ---- UI-A7: an email promise the app can't keep, and a guest's barcodes lost on refresh (#187) ----

    private static final String BARCODE = "TIX-0001-A1";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-07-15T17:00:00Z"), ZoneOffset.UTC);
    private static final CheckoutResultDTO RESULT = new CheckoutResultDTO(75.0, 42, 9001,
        List.of(new CheckoutResultDTO.IssuedTicketDTO(1, BARCODE)));

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
    void GivenACompletedCheckout_WhenTheConfirmationShows_ThenItPromisesNoEmail() {
        OrderSession.setResult(RESULT);
        OrderConfirmationView view = new OrderConfirmationView();

        view.beforeEnter(routeTo());

        String shown = text(view);
        assertFalse(shown.toLowerCase(Locale.ROOT).contains("email"), "the confirmation promises an email: " + shown);
    }

    @Test
    void GivenAGuestAtCheckout_WhenTheCheckoutShows_ThenTheEmailFieldPromisesToSendNothing() {
        CheckoutPresenter presenter = mock(CheckoutPresenter.class);
        when(presenter.resolveIdentity()).thenReturn(new CheckoutPresenter.Identity(false, null, "guest-sid", 0));
        CheckoutView view = new CheckoutView(presenter, Clock.fixed(Instant.parse("2027-07-15T17:00:00Z"), ZoneOffset.UTC));

        view.beforeEnter(routeTo());

        String helper = String.valueOf(field(view, EmailField.class, "Your email").getHelperText());
        assertFalse(helper.toLowerCase(Locale.ROOT).contains("send"), "the guest email field promises an email: " + helper);
    }

    @Test
    void GivenAGuestSawTheirConfirmation_WhenTheyReloadIt_ThenTheTicketsStillShow() {
        OrderSession.setResult(RESULT);
        new OrderConfirmationView().beforeEnter(routeTo());

        BeforeEnterEvent reload = routeTo();
        OrderConfirmationView reloaded = new OrderConfirmationView();
        reloaded.beforeEnter(reload);

        verify(reload, never()).rerouteTo(any(Class.class));
        String shown = text(reloaded);
        assertTrue(shown.contains(BARCODE), "a reload in the same session must still show the barcode: " + shown);
    }

    // A kept confirmation belongs to the last order: a new payment attempt drops it first, so one that
    // fails leaves no older order's confirmation to reopen.
    @Test
    void GivenAKeptConfirmation_WhenANewPaymentAttemptFails_ThenTheOldConfirmationIsNoLongerShown() {
        OrderSession.setResult(RESULT);
        ViewHarness.signInMember();
        CheckoutPresenter presenter = mock(CheckoutPresenter.class);
        when(presenter.resolveIdentity()).thenReturn(
            new CheckoutPresenter.Identity(true, ViewHarness.MEMBER_TOKEN, null, ViewHarness.MEMBER_ID));
        LocalDateTime added = LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
        when(presenter.loadOrder()).thenReturn(new CheckoutPresenter.LoadOutcome.Loaded(
            new ActiveOrderDTO(ViewHarness.MEMBER_ID, null, added, 300, 10.0,
                List.of(new ActiveOrderDTO.CartLineDTO(5, "Coldplay", 8, "A1", 10.0, added))),
            new CheckoutPresenter.Pricing(1000, 1000)));
        when(presenter.payAsMember(any(), any(), any(), any(), any(), any()))
            .thenReturn(new CheckoutPresenter.PayOutcome.Failure("declined"));
        CheckoutView checkout = new CheckoutView(presenter, CLOCK);
        harness.attach(checkout);
        checkout.beforeEnter(routeTo());
        checkout.afterNavigation(mock(AfterNavigationEvent.class));
        field(checkout, TextField.class, "Cardholder name").setValue("Alice Example");
        field(checkout, TextField.class, "Card number").setValue("4111 1111 1111 1111");
        field(checkout, TextField.class, "Expiry").setValue("12/30");
        field(checkout, TextField.class, "CVC").setValue("123");

        click(ViewHarness.find(checkout, NativeButton.class).stream()
            .filter(b -> b.getElement().getTextRecursively().trim().startsWith("Pay "))
            .findFirst().orElseThrow());
        BeforeEnterEvent entry = routeTo();
        new OrderConfirmationView().beforeEnter(entry);

        verify(entry).rerouteTo(BrowseEventsView.class);
    }

    // Kept: with no checkout in this session there is nothing to confirm.
    @Test
    void GivenNoCheckoutInThisSession_WhenTheConfirmationIsOpened_ThenTheVisitorIsSentToBrowse() {
        BeforeEnterEvent entry = routeTo();

        new OrderConfirmationView().beforeEnter(entry);

        verify(entry).rerouteTo(BrowseEventsView.class);
    }
}
