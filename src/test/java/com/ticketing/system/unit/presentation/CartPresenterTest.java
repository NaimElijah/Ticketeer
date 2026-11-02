package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.notifications.application.port.in.INotificationService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.ui.presenters.order.CartPresenter;
import com.ticketing.system.ui.session.FreshGuestSession;
import com.ticketing.system.ui.session.GuestSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.session.UnreadAlertsSendBack;
import com.ticketing.system.ui.support.ServiceErrors;

import ch.qos.logback.classic.Level;
import jakarta.persistence.PersistenceException;

/**
 * {@link CartPresenter} through its public methods, for a guest whose session is live. The visitor is
 * resolved by a real {@link SessionIdentity}; only the project's own services are test doubles.
 */
class CartPresenterTest {

    private static final String GUEST_SESSION_ID = "guest-sid";
    private static final CartPresenter.CartVM.LineVM LINE =
            new CartPresenter.CartVM.LineVM("Coldplay", "A1", 75.0, 3, 9);

    private ReservationService reservationService;
    private CartPresenter presenter;
    private LogCapture serviceErrorsLog;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        GuestSession.setSessionId(GUEST_SESSION_ID);
        AuthenticationService authenticationService = mock(AuthenticationService.class);
        when(authenticationService.isLiveGuestSession(GUEST_SESSION_ID)).thenReturn(true);
        when(authenticationService.startGuestSession()).thenReturn(new GuestSessionDTO("fresh-sid", Instant.EPOCH));
        reservationService = mock(ReservationService.class);
        presenter = new CartPresenter(reservationService, new SessionIdentity(authenticationService,
                new FreshGuestSession(authenticationService, new UnreadAlertsSendBack(mock(INotificationService.class)))));
        serviceErrorsLog = LogCapture.of(ServiceErrors.class);
    }

    @AfterEach
    void tearDown() {
        serviceErrorsLog.close();
        MapBackedVaadinSession.uninstall();
    }

    // ---- UI-B5: the cart's internal failures go through ServiceErrors (#187) ----

    @Test
    void GivenAnInternalFailure_WhenLoadCart_ThenFailureCarriesTheGenericMessageAndServiceErrorsLogsIt() {
        when(reservationService.viewMyActiveOrder(GUEST_SESSION_ID))
                .thenThrow(new PersistenceException("could not prepare statement; table [active_orders]"));

        CartPresenter.LoadOutcome outcome = presenter.loadCart();

        CartPresenter.LoadOutcome.Failure failure = assertInstanceOf(CartPresenter.LoadOutcome.Failure.class, outcome);
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, failure.reason());
        assertEquals(List.of(PersistenceException.class.getName()), serviceErrorsLog.throwableClassNamesAt(Level.ERROR),
                "the internal failure must be logged at ERROR with its exception by ServiceErrors");
    }

    @Test
    void GivenAnInternalFailure_WhenRemoveLine_ThenFailureCarriesTheGenericMessageAndServiceErrorsLogsIt() {
        when(reservationService.removeLine(eq(GUEST_SESSION_ID), anyInt(), anyInt(), any()))
                .thenThrow(new PersistenceException("could not execute statement; constraint [fk_line_order]"));

        CartPresenter.RemoveOutcome outcome = presenter.removeLine(LINE);

        CartPresenter.RemoveOutcome.Failure failure =
                assertInstanceOf(CartPresenter.RemoveOutcome.Failure.class, outcome);
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, failure.reason());
        assertEquals(List.of(PersistenceException.class.getName()), serviceErrorsLog.throwableClassNamesAt(Level.ERROR),
                "the internal failure must be logged at ERROR with its exception by ServiceErrors");
    }

    // ---- UI-C2: a store failure from viewMyActiveOrder reads as the outage (#187) ----

    @Test
    void GivenTheStoreIsDown_WhenLoadCart_ThenFailureCarriesTheOutageMessage() {
        when(reservationService.viewMyActiveOrder(GUEST_SESSION_ID))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        CartPresenter.LoadOutcome outcome = presenter.loadCart();

        CartPresenter.LoadOutcome.Failure failure = assertInstanceOf(CartPresenter.LoadOutcome.Failure.class, outcome,
                "a store outage must read as a failure, but was: " + outcome);
        assertEquals(ServiceErrors.DB_UNAVAILABLE_MESSAGE, failure.reason());
    }

    @Test
    void GivenTheStoreGoesDownAfterTheLineIsRemoved_WhenRemoveLine_ThenFailureCarriesTheOutageMessage() {
        when(reservationService.viewMyActiveOrder(GUEST_SESSION_ID))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        CartPresenter.RemoveOutcome outcome = presenter.removeLine(LINE);

        CartPresenter.RemoveOutcome.Failure failure =
                assertInstanceOf(CartPresenter.RemoveOutcome.Failure.class, outcome);
        assertEquals(ServiceErrors.DB_UNAVAILABLE_MESSAGE, failure.reason());
    }
}
