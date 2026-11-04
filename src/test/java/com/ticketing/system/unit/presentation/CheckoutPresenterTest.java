package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.notifications.application.port.in.INotificationService;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.FreshGuestSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.session.UnreadAlertsSendBack;
import com.ticketing.system.ui.support.ServiceErrors;

import ch.qos.logback.classic.Level;
import jakarta.persistence.PersistenceException;

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
    void GivenAnUnreachableGateway_WhenPay_ThenMapsToGatewayUnavailable() {
        when(checkoutService.checkoutMember(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Checkout failed, tickets returned to stock",
                        new PaymentGatewayUnreachableException("payment gateway unreachable: timeout")));

        assertInstanceOf(CheckoutPresenter.PayOutcome.GatewayUnavailable.class, pay());
    }

    @Test
    void GivenAGatewayDecline_WhenPay_ThenMapsToPaymentDeclined() {
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

    // ---- UI-B5: a checkout's internal failure goes through ServiceErrors (#187) ----

    @Test
    void GivenAnInternalFailure_WhenPay_ThenFailureCarriesTheGenericMessageAndServiceErrorsLogsIt() {
        when(checkoutService.checkoutMember(any(), any(), any(), any()))
                .thenThrow(new PersistenceException("could not execute statement; constraint [uk_ticket_seat]"));

        try (LogCapture serviceErrorsLog = LogCapture.of(ServiceErrors.class)) {
            CheckoutPresenter.PayOutcome outcome = pay();

            CheckoutPresenter.PayOutcome.Failure failure = assertInstanceOf(CheckoutPresenter.PayOutcome.Failure.class,
                    outcome, "an internal failure must read as a failure, but was: " + outcome);
            assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, failure.reason());
            assertEquals(List.of(PersistenceException.class.getName()),
                    serviceErrorsLog.throwableClassNamesAt(Level.ERROR),
                    "the internal failure must be logged at ERROR with its exception by ServiceErrors");
        }
    }

    // ---- UI-C1: an empty cart at Pay reads as empty, not as expired (#187) ----
    // Each refusal is the one the domain itself throws (ActiveOrder.validateCanCheckout), wrapped as
    // CheckoutService wraps a checkout failure.

    private static final LocalDateTime CART_CREATED_AT = LocalDateTime.of(2027, 3, 1, 12, 0);
    private static final int CART_OWNER_ID = 42;

    @Test
    void GivenCheckoutRefusesAnEmptyCart_WhenPay_ThenItDoesNotReadAsAnExpiredOrder() {
        ActiveOrder emptyCart = ActiveOrder.forMember(CART_OWNER_ID, "member-sid", CART_CREATED_AT);
        RuntimeException refusal = assertThrows(RuntimeException.class,
                () -> emptyCart.validateCanCheckout(CART_CREATED_AT), "precondition: the domain refuses an empty cart");
        when(checkoutService.checkoutMember(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Checkout failed, tickets returned to stock", refusal));

        CheckoutPresenter.PayOutcome outcome = pay();

        CheckoutPresenter.PayOutcome.Failure failure = assertInstanceOf(CheckoutPresenter.PayOutcome.Failure.class,
                outcome, "an empty cart must not read as an expired order, but was: " + outcome);
        assertEquals(refusal.getMessage(), failure.reason(), "the buyer must read the refusal's own words");
    }

    @Test
    void GivenCheckoutRefusesACartWhoseHoldRanOut_WhenPay_ThenOrderExpired() {
        ActiveOrder expiredCart = ActiveOrder.forMember(CART_OWNER_ID, "member-sid", CART_CREATED_AT);
        expiredCart.addStandingReservation(1, 1, 1, 50.0, CART_CREATED_AT);
        RuntimeException refusal = assertThrows(RuntimeException.class,
                () -> expiredCart.validateCanCheckout(CART_CREATED_AT.plusDays(7)),
                "precondition: the domain refuses a cart whose hold ran out");
        when(checkoutService.checkoutMember(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Checkout failed, tickets returned to stock", refusal));

        CheckoutPresenter.PayOutcome outcome = pay();

        assertInstanceOf(CheckoutPresenter.PayOutcome.OrderExpired.class, outcome,
                "a cart whose hold ran out must still read as an expired order, but was: " + outcome);
    }

    // ---- UI-B10: resolving the visitor is one action, so the token's liveness is looked up once (#187) ----

    @Test
    void GivenASignedInMember_WhenResolveIdentity_ThenTheTokenIsCheckedForLivenessOnce() {
        AuthenticationService authenticationService = signedInLiveMember();
        CheckoutPresenter withRealIdentity = presenterResolvingTheVisitorThrough(authenticationService,
                mock(ReservationService.class));

        CheckoutPresenter.Identity identity = withRealIdentity.resolveIdentity();

        assertEquals(new CheckoutPresenter.Identity(true, BUYER_TOKEN, null, BUYER_ID), identity);
        verify(authenticationService, times(1)).liveMemberId(BUYER_TOKEN);
    }

    // ---- UI-B10b: CheckoutPresenter.Identity shows no credential (#187) ----
    // A member token and a guest session id are both credentials, so neither may reach a log line or a
    // failure message; whether the visitor is a member, and the member's id, may.

    private static final String GUEST_SESSION_ID = "guest-sid";

    @Test
    void GivenAMemberAndAGuestIdentity_WhenToString_ThenNeitherShowsItsCredential() {
        String member = new CheckoutPresenter.Identity(true, BUYER_TOKEN, null, BUYER_ID).toString();
        String guest = new CheckoutPresenter.Identity(false, null, GUEST_SESSION_ID, SessionIdentity.NO_MEMBER_USER_ID)
                .toString();

        assertFalse(member.contains(BUYER_TOKEN), "the member token must not be shown, but was: " + member);
        assertFalse(guest.contains(GUEST_SESSION_ID), "the guest session id must not be shown, but was: " + guest);
        assertEquals("Identity[member=true, userId=" + BUYER_ID + "]", member);
        assertEquals("Identity[member=false, userId=" + SessionIdentity.NO_MEMBER_USER_ID + "]", guest);
    }

    // ---- UI-C2: a store failure from viewMyActiveOrder reads as the outage, not an expired session (#187) ----

    @Test
    void GivenTheStoreIsDown_WhenLoadOrder_ThenFailureCarriesTheOutageMessage() {
        AuthenticationService authenticationService = signedInLiveMember();
        ReservationService reservations = mock(ReservationService.class);
        when(reservations.viewMyActiveOrder(BUYER_TOKEN))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));
        CheckoutPresenter withRealIdentity = presenterResolvingTheVisitorThrough(authenticationService, reservations);

        CheckoutPresenter.LoadOutcome outcome = withRealIdentity.loadOrder();

        CheckoutPresenter.LoadOutcome.Failure failure =
                assertInstanceOf(CheckoutPresenter.LoadOutcome.Failure.class, outcome);
        assertEquals(ServiceErrors.DB_UNAVAILABLE_MESSAGE, failure.reason());
    }

    // ---- fixture shared by the UI-B10 and UI-C2 blocks: the visitor resolved by a real SessionIdentity ----

    private static final int BUYER_ID = 42;
    private static final String BUYER_TOKEN = "member-jwt";

    @AfterEach
    void uninstallTheVaadinSession() {
        MapBackedVaadinSession.uninstall();
    }

    private static AuthenticationService signedInLiveMember() {
        MapBackedVaadinSession.install();
        AuthSession.storeAuth(new AuthTokenDTO(BUYER_TOKEN, 0L, BUYER_ID, "member"));
        AuthenticationService authenticationService = mock(AuthenticationService.class);
        when(authenticationService.liveMemberId(BUYER_TOKEN)).thenReturn(OptionalInt.of(BUYER_ID));
        return authenticationService;
    }

    private CheckoutPresenter presenterResolvingTheVisitorThrough(AuthenticationService authenticationService,
                                                                 ReservationService reservations) {
        FreshGuestSession freshGuestSession = new FreshGuestSession(authenticationService,
                new UnreadAlertsSendBack(mock(INotificationService.class)));
        return new CheckoutPresenter(reservations, checkoutService,
                new SessionIdentity(authenticationService, freshGuestSession));
    }
}
