package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.shared.exception.PaymentGatewayException;
import com.ticketing.system.shared.exception.PaymentGatewayUnreachableException;
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
}
