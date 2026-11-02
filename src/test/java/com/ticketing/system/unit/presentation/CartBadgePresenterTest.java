package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
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
import com.ticketing.system.ui.presenters.order.CartBadgePresenter;
import com.ticketing.system.ui.session.FreshGuestSession;
import com.ticketing.system.ui.session.GuestSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.session.UnreadAlertsSendBack;
import com.ticketing.system.ui.support.ServiceErrors;

import ch.qos.logback.classic.Level;
import jakarta.persistence.PersistenceException;

/**
 * {@link CartBadgePresenter#loadBadge()} for a guest whose session is live, resolved by a real
 * {@link SessionIdentity}. What it logs is read from both its own logger and {@link ServiceErrors}',
 * since a failure must reach the log once, with its exception, whichever of the two logs it.
 */
class CartBadgePresenterTest {

    private static final String GUEST_SESSION_ID = "guest-sid";

    private ReservationService reservationService;
    private CartBadgePresenter presenter;
    private LogCapture presenterLog;
    private LogCapture serviceErrorsLog;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        GuestSession.setSessionId(GUEST_SESSION_ID);
        AuthenticationService authenticationService = mock(AuthenticationService.class);
        when(authenticationService.isLiveGuestSession(GUEST_SESSION_ID)).thenReturn(true);
        when(authenticationService.startGuestSession()).thenReturn(new GuestSessionDTO("fresh-sid", Instant.EPOCH));
        reservationService = mock(ReservationService.class);
        presenter = new CartBadgePresenter(reservationService, new SessionIdentity(authenticationService,
                new FreshGuestSession(authenticationService, new UnreadAlertsSendBack(mock(INotificationService.class)))));
        presenterLog = LogCapture.of(CartBadgePresenter.class);
        serviceErrorsLog = LogCapture.of(ServiceErrors.class);
    }

    @AfterEach
    void tearDown() {
        serviceErrorsLog.close();
        presenterLog.close();
        MapBackedVaadinSession.uninstall();
    }

    // ---- UI-C2: a store failure from viewMyActiveOrder reads as the outage (#187) ----

    @Test
    void GivenTheStoreIsDown_WhenLoadBadge_ThenFailureCarriesTheOutageMessage() {
        when(reservationService.viewMyActiveOrder(GUEST_SESSION_ID))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        CartBadgePresenter.Outcome outcome = presenter.loadBadge();

        CartBadgePresenter.Outcome.Failure failure = assertInstanceOf(CartBadgePresenter.Outcome.Failure.class, outcome);
        assertEquals(ServiceErrors.DB_UNAVAILABLE_MESSAGE, failure.reason());
    }

    // ---- UI-B6: a failure is logged once, with its exception (#187) ----

    @Test
    void GivenAnInternalFailure_WhenLoadBadge_ThenItIsLoggedOnceWithItsException() {
        when(reservationService.viewMyActiveOrder(GUEST_SESSION_ID))
                .thenThrow(new PersistenceException("could not prepare statement; table [active_orders]"));

        presenter.loadBadge();

        assertEquals(1, eventsAtWarnOrError(),
                "the failure must be logged once; logged: " + loggedFailures());
        assertEquals(List.of(PersistenceException.class.getName()), throwablesLoggedAtWarnOrError(),
                "the one log event must carry the exception");
    }

    @Test
    void GivenTheStoreIsDown_WhenLoadBadge_ThenItIsLoggedOnceWithItsException() {
        when(reservationService.viewMyActiveOrder(GUEST_SESSION_ID))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        presenter.loadBadge();

        assertEquals(1, eventsAtWarnOrError(),
                "the failure must be logged once; logged: " + loggedFailures());
        assertEquals(List.of(DataAccessResourceFailureException.class.getName()), throwablesLoggedAtWarnOrError(),
                "the one log event must carry the exception");
    }

    private int eventsAtWarnOrError() {
        int events = 0;
        for (LogCapture log : List.of(presenterLog, serviceErrorsLog)) {
            events += log.messagesAt(Level.WARN).size() + log.messagesAt(Level.ERROR).size();
        }
        return events;
    }

    private List<String> throwablesLoggedAtWarnOrError() {
        List<String> throwables = new ArrayList<>();
        for (LogCapture log : List.of(presenterLog, serviceErrorsLog)) {
            throwables.addAll(log.throwableClassNamesAt(Level.WARN));
            throwables.addAll(log.throwableClassNamesAt(Level.ERROR));
        }
        return throwables;
    }

    private String loggedFailures() {
        return "CartBadgePresenter WARN " + presenterLog.messagesAt(Level.WARN)
                + ", ServiceErrors ERROR " + serviceErrorsLog.messagesAt(Level.ERROR);
    }
}
