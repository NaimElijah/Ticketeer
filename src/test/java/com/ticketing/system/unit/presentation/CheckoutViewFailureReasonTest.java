package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_ID;
import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_TOKEN;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.field;
import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.ui.presenters.order.CheckoutPresenter;
import com.ticketing.system.ui.views.order.CheckoutView;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.AfterNavigationEvent;

/**
 * The checkout shows the reason its presenter gives when the cart cannot be read, both when the page
 * loads and when Pay re-reads the cart before charging, instead of a generic "please try again"
 * (#187, UI-A2b).
 */
class CheckoutViewFailureReasonTest {

    // ---- UI-A2b: checkout shows the presenter's failure reason (#187) ----

    private static final String REASON = "reason-under-test";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-07-15T17:00:00Z"), ZoneOffset.UTC);
    private static final long REMAINING_SECONDS = 300;
    private static final long TOTAL_CENTS = 1000;
    private static final String PAY_LABEL_PREFIX = "Pay ";

    private ViewHarness harness;
    private CheckoutPresenter presenter;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
        ViewHarness.signInMember();
        presenter = mock(CheckoutPresenter.class);
        when(presenter.resolveIdentity()).thenReturn(new CheckoutPresenter.Identity(true, MEMBER_TOKEN, null, MEMBER_ID));
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenTheCartCannotBeReadForAReason_WhenTheCheckoutLoads_ThenTheToastShowsTheReason() {
        when(presenter.loadOrder()).thenReturn(new CheckoutPresenter.LoadOutcome.Failure(REASON));
        CheckoutView view = new CheckoutView(presenter, CLOCK);
        harness.attach(view);

        view.beforeEnter(routeTo());
        view.afterNavigation(mock(AfterNavigationEvent.class));

        assertToastShows(REASON);
    }

    @Test
    void GivenTheCartCannotBeReadForAReasonAtPay_WhenTheMemberPays_ThenTheToastShowsTheReasonAndNothingIsCharged() {
        when(presenter.loadOrder()).thenReturn(
            new CheckoutPresenter.LoadOutcome.Loaded(order(), new CheckoutPresenter.Pricing(TOTAL_CENTS, TOTAL_CENTS)),
            new CheckoutPresenter.LoadOutcome.Failure(REASON));
        CheckoutView view = new CheckoutView(presenter, CLOCK);
        harness.attach(view);
        view.beforeEnter(routeTo());
        view.afterNavigation(mock(AfterNavigationEvent.class));
        field(view, TextField.class, "Cardholder name").setValue("Alice Example");
        field(view, TextField.class, "Card number").setValue("4111 1111 1111 1111");
        field(view, TextField.class, "Expiry").setValue("12/30");
        field(view, TextField.class, "CVC").setValue("123");

        click(payButton(view));

        assertToastShows(REASON);
        verify(presenter, never()).payAsMember(any(), any(), any(), any(), any(), any());
    }

    private static NativeButton payButton(CheckoutView view) {
        return find(view, NativeButton.class).stream()
            .filter(b -> b.getElement().getTextRecursively().trim().startsWith(PAY_LABEL_PREFIX))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no Pay button on the checkout"));
    }

    private static ActiveOrderDTO order() {
        LocalDateTime added = LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
        return new ActiveOrderDTO(MEMBER_ID, null, added, REMAINING_SECONDS, 10.0,
            List.of(new ActiveOrderDTO.CartLineDTO(5, "Coldplay", 8, "A1", 10.0, added)));
    }

    private void assertToastShows(String reason) {
        List<String> toasts = harness.toasts();
        assertTrue(toasts.stream().anyMatch(toast -> toast.contains(reason)),
            "expected a toast with \"" + reason + "\", got: " + toasts);
    }
}
