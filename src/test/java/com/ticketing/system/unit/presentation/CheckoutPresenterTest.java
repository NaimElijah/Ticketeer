package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.shared.exception.ConcurrentReservationException;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.shared.exception.PaymentGatewayException;
import com.ticketing.system.shared.exception.PaymentGatewayUnreachableException;
import com.ticketing.system.shared.exception.TicketIssuanceFailedException;
import com.ticketing.system.ui.presenters.order.CheckoutPresenter;
import com.ticketing.system.ui.session.SessionIdentity;

/**
 * Pins the payment-failure mapping in {@link CheckoutPresenter#payAsMember} (issue #428): a real
 * gateway decline and a gateway-unreachable failure must map to DIFFERENT outcomes, because the
 * View renders a clear "Payment declined" for the former and a generic error for the latter.
 *
 * <p>{@code CheckoutService} rethrows failures wrapped as
 * {@code new RuntimeException("Checkout failed, tickets returned to stock", cause)}; the test mocks
 * that exact wrapper so the presenter's one-level cause-unwrap is exercised faithfully.
 */
class CheckoutPresenterTest {

    private CheckoutService checkoutService;
    private CheckoutPresenter presenter;

    @BeforeEach
    void setUp() {
        ReservationService reservationService = mock(ReservationService.class);
        checkoutService = mock(CheckoutService.class);
        SessionIdentity identity = mock(SessionIdentity.class);
        presenter = new CheckoutPresenter(reservationService, checkoutService, identity);
    }

    /** Drives the member checkout path, which builds the card and calls checkoutService.checkoutMember(...). */
    private CheckoutPresenter.PayOutcome pay() {
        return presenter.payAsMember("member-token", "idem-1",
                "4111111111111111", "986", "12 / 30", "Jane Holder");
    }

    @Test
    void pay_gatewayUnreachable_mapsToGatewayUnavailable() {
        when(checkoutService.checkoutMember(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Checkout failed, tickets returned to stock",
                        new PaymentGatewayUnreachableException("payment gateway unreachable: timeout")));

        assertInstanceOf(CheckoutPresenter.PayOutcome.GatewayUnavailable.class, pay());
    }

    @Test
    void pay_declined_mapsToPaymentDeclined() {
        when(checkoutService.checkoutMember(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Checkout failed, tickets returned to stock",
                        new PaymentGatewayException("payment declined by gateway")));

        // The unreachable arm precedes the PaymentGatewayException arm and unreachable IS-A
        // PaymentGatewayException, so this also pins that a real decline does NOT fall into the
        // GatewayUnavailable arm.
        assertInstanceOf(CheckoutPresenter.PayOutcome.PaymentDeclined.class, pay());
    }

    // Tickets are issued after the charge, so an issuance failure is not "try again in a moment": the
    // order did not complete, the charge is being refunded, and the buyer should check My account
    // before retrying. The outcome carries no reason, so the issuer's error text never reaches the
    // buyer.
    @Test
    void GivenTicketIssuanceFails_WhenPay_ThenOrderNotCompleted() {
        when(checkoutService.checkoutMember(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Checkout failed, tickets returned to stock",
                        new TicketIssuanceFailedException("ticket issuer unreachable: Connection refused")));

        CheckoutPresenter.PayOutcome outcome = pay();

        assertInstanceOf(CheckoutPresenter.PayOutcome.OrderNotCompleted.class, outcome,
                "a ticket-issuance failure after the charge is its own outcome, but was: " + outcome);
    }

    // ---- Defect 2: a cart checkout refuses as a state transition reads as an expired order (#187) ----
    // The presenter's half of CheckoutServiceTest's defect-2 tests: the service must refuse an expired or
    // empty cart with an InvalidStateTransitionException, and this is what the buyer then sees.

    @Test
    void GivenCheckoutRefusesTheCartAsAnInvalidStateTransition_WhenPay_ThenOrderExpired() {
        when(checkoutService.checkoutMember(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Checkout failed, tickets returned to stock",
                        new InvalidStateTransitionException("Cannot checkout because one or more tickets expired")));

        CheckoutPresenter.PayOutcome outcome = pay();

        assertInstanceOf(CheckoutPresenter.PayOutcome.OrderExpired.class, outcome,
                "a cart checkout refuses as a state transition must read as an expired order, but was: " + outcome);
    }

    // ---- G2: a lost race reads as "your cart changed, try again" (#187) ----
    // The presenter's half of CheckoutServiceTest's G2 tests: the service re-types a lost race into a
    // ConcurrentReservationException, whose message the buyer then reads.

    @Test
    void GivenCheckoutLosesAConcurrencyRace_WhenPay_ThenTheBuyerIsToldTheCartChangedAndToTryAgain() {
        String cartChanged = "Your cart changed while you were paying. Please try again.";
        when(checkoutService.checkoutMember(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Checkout failed, nothing was changed",
                        new ConcurrentReservationException(cartChanged, new RuntimeException("version conflict"))));

        CheckoutPresenter.PayOutcome outcome = pay();

        CheckoutPresenter.PayOutcome.Failure failure = assertInstanceOf(CheckoutPresenter.PayOutcome.Failure.class,
                outcome, "a lost race must read as a failure with its own reason, but was: " + outcome);
        assertEquals(cartChanged, failure.reason());
    }
}
