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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import java.time.LocalDateTime;
import org.mockito.ArgumentCaptor;
import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.shared.exception.ActiveOrderExpiredException;

/**
 * {@link CartPresenter} through its public methods, for a guest whose session is live. The visitor is
 * resolved by a real {@link SessionIdentity}; only the project's own services are test doubles.
 */
class CartPresenterTest {

    private static final String GUEST_SESSION_ID = "guest-sid";
    private static final CartPresenter.CartVM.LineVM LINE =
            new CartPresenter.CartVM.LineVM("Coldplay", "A1", 75.0, 3, 9);

    private AuthenticationService authenticationService;
    private ReservationService reservationService;
    private CartPresenter presenter;
    private LogCapture serviceErrorsLog;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        GuestSession.setSessionId(GUEST_SESSION_ID);
        authenticationService = mock(AuthenticationService.class);
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

    // ---- what the cart shows, and which line a removal names (#189) ----

    private static final LocalDateTime ADDED_AT = LocalDateTime.of(2027, 7, 15, 17, 0);
    private static final long SECONDS_LEFT = 420;
    private static final ActiveOrderDTO.CartLineDTO SEAT_A1 =
            new ActiveOrderDTO.CartLineDTO(3, "Coldplay", 9, "A1", 75.0, ADDED_AT);
    private static final ActiveOrderDTO.CartLineDTO STANDING =
            new ActiveOrderDTO.CartLineDTO(3, "Coldplay", 10, null, 45.5, ADDED_AT);

    @Test
    void GivenNoActiveOrder_WhenLoadCart_ThenEmpty() {
        when(reservationService.viewMyActiveOrder(GUEST_SESSION_ID)).thenReturn(null);

        assertEquals(new CartPresenter.LoadOutcome.Empty(), presenter.loadCart());
    }

    @Test
    void GivenAnOrderWithNoLines_WhenLoadCart_ThenEmpty() {
        when(reservationService.viewMyActiveOrder(GUEST_SESSION_ID)).thenReturn(order(List.of()));

        assertEquals(new CartPresenter.LoadOutcome.Empty(), presenter.loadCart());
    }

    @Test
    void GivenASeatAndAStandingTicket_WhenLoadCart_ThenBothLinesTheTimeLeftAndTheirSumInCents() {
        when(reservationService.viewMyActiveOrder(GUEST_SESSION_ID)).thenReturn(order(List.of(SEAT_A1, STANDING)));

        assertEquals(new CartPresenter.LoadOutcome.Shown(new CartPresenter.CartVM(List.of(
                new CartPresenter.CartVM.LineVM("Coldplay", "A1", 75.0, 3, 9),
                new CartPresenter.CartVM.LineVM("Coldplay", null, 45.5, 3, 10)), SECONDS_LEFT), 12050),
            presenter.loadCart());
    }

    // A dead guest session whose replacement cannot be started leaves no credential to ask with.
    @Test
    void GivenNoLiveSessionAndNoNewOne_WhenLoadCart_ThenNotAuthenticatedAndTheCartIsNotRead() {
        GuestSession.setSessionId("dead-sid");
        when(authenticationService.startGuestSession()).thenThrow(new IllegalStateException("session store down"));

        assertEquals(new CartPresenter.LoadOutcome.NotAuthenticated(), presenter.loadCart());
        verifyNoInteractions(reservationService);
    }

    @Test
    void GivenTheCartExpired_WhenLoadCart_ThenFailureCarriesTheRule() {
        ActiveOrderExpiredException expired = new ActiveOrderExpiredException();
        when(reservationService.viewMyActiveOrder(GUEST_SESSION_ID)).thenThrow(expired);

        assertEquals(new CartPresenter.LoadOutcome.Failure(expired.getMessage()), presenter.loadCart());
    }

    @Test
    void GivenNoLine_WhenRemoveLine_ThenFailureAndNothingIsRemoved() {
        assertEquals(new CartPresenter.RemoveOutcome.Failure("No line selected"), presenter.removeLine(null));
        verifyNoInteractions(reservationService);
    }

    @Test
    void GivenNoLiveSessionAndNoNewOne_WhenRemoveLine_ThenFailureSaysTheSessionExpiredAndNothingIsRemoved() {
        GuestSession.setSessionId("dead-sid");
        when(authenticationService.startGuestSession()).thenThrow(new IllegalStateException("session store down"));

        assertEquals(new CartPresenter.RemoveOutcome.Failure("Your session has expired"), presenter.removeLine(LINE));
        verifyNoInteractions(reservationService);
    }

    @Test
    void GivenASeatLine_WhenRemoveLine_ThenThatSeatIsReleasedAndTheRestShows() {
        when(reservationService.viewMyActiveOrder(GUEST_SESSION_ID)).thenReturn(order(List.of(STANDING)));

        assertEquals(new CartPresenter.RemoveOutcome.Removed(new CartPresenter.CartVM(List.of(
                new CartPresenter.CartVM.LineVM("Coldplay", null, 45.5, 3, 10)), SECONDS_LEFT), 4550),
            presenter.removeLine(LINE));

        InventorySelectionDTO released = releasedSelection(3, 9);
        assertEquals(List.of("A1"), released.getSeatNumbers());
    }

    @Test
    void GivenAStandingLine_WhenRemoveLine_ThenOneStandingTicketIsReleased() {
        when(reservationService.viewMyActiveOrder(GUEST_SESSION_ID)).thenReturn(order(List.of(SEAT_A1)));

        presenter.removeLine(new CartPresenter.CartVM.LineVM("Coldplay", null, 45.5, 3, 10));

        InventorySelectionDTO released = releasedSelection(3, 10);
        assertEquals(1, released.getQuantity());
        assertEquals(List.of(), released.getSeatNumbers());
    }

    @Test
    void GivenTheLastLine_WhenRemoveLine_ThenAnEmptyCartNotAFailure() {
        when(reservationService.viewMyActiveOrder(GUEST_SESSION_ID)).thenReturn(null);

        assertEquals(new CartPresenter.RemoveOutcome.Removed(new CartPresenter.CartVM(List.of(), 0L), 0L),
            presenter.removeLine(LINE));
    }

    @Test
    void GivenTheRemovalIsRefused_WhenRemoveLine_ThenFailureCarriesTheRule() {
        ActiveOrderExpiredException expired = new ActiveOrderExpiredException();
        when(reservationService.removeLine(eq(GUEST_SESSION_ID), anyInt(), anyInt(), any())).thenThrow(expired);

        assertEquals(new CartPresenter.RemoveOutcome.Failure(expired.getMessage()), presenter.removeLine(LINE));
    }

    private InventorySelectionDTO releasedSelection(int eventId, int zoneId) {
        ArgumentCaptor<InventorySelectionDTO> selection = ArgumentCaptor.forClass(InventorySelectionDTO.class);
        verify(reservationService).removeLine(eq(GUEST_SESSION_ID), eq(eventId), eq(zoneId), selection.capture());
        return selection.getValue();
    }

    private static ActiveOrderDTO order(List<ActiveOrderDTO.CartLineDTO> lines) {
        return new ActiveOrderDTO(null, GUEST_SESSION_ID, ADDED_AT, SECONDS_LEFT, 0, lines);
    }
}
