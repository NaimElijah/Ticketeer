package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.notifications.application.port.in.INotificationService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.shared.dto.InventoryZoneDTO;
import com.ticketing.system.shared.dto.SeatDTO;
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.ui.components.venue.VkSeatedZonePicker;
import com.ticketing.system.ui.presenters.catalog.SeatPickerPresenter;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.FreshGuestSession;
import com.ticketing.system.ui.session.GuestSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.session.UnreadAlertsSendBack;
import com.ticketing.system.ui.support.ServiceErrors;

import ch.qos.logback.classic.Level;
import jakarta.persistence.PersistenceException;

/**
 * {@link SeatPickerPresenter} through its public methods. The visitor is resolved by a real
 * {@link SessionIdentity} over a map-backed Vaadin session, so only the project's own services are
 * test doubles: the catalog, the reservations and the identity service behind {@code SessionIdentity}.
 */
class SeatPickerPresenterTest {

    private static final int EVENT_ID = 3;
    private static final int ZONE_ID = 9;
    private static final String GUEST_SESSION_ID = "guest-sid";
    private static final String MEMBER_TOKEN = "member-jwt";
    private static final int MEMBER_ID = 42;
    private static final int SEAT_STEP_PIXELS = 30;
    // An internal failure whose text must stay in the log: it names a table and a constraint.
    private static final String INTERNAL_DETAIL = "could not execute statement; constraint [uk_ticket_seat]";

    private CatalogService catalogService;
    private ReservationService reservationService;
    private AuthenticationService authenticationService;
    private SeatPickerPresenter presenter;
    private LogCapture serviceErrorsLog;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        GuestSession.setSessionId(GUEST_SESSION_ID);
        catalogService = mock(CatalogService.class);
        reservationService = mock(ReservationService.class);
        authenticationService = mock(AuthenticationService.class);
        when(authenticationService.isLiveGuestSession(GUEST_SESSION_ID)).thenReturn(true);
        when(authenticationService.startGuestSession()).thenReturn(new GuestSessionDTO("fresh-sid", Instant.EPOCH));
        FreshGuestSession freshGuestSession = new FreshGuestSession(authenticationService,
                new UnreadAlertsSendBack(mock(INotificationService.class)));
        presenter = new SeatPickerPresenter(catalogService, reservationService,
                new SessionIdentity(authenticationService, freshGuestSession));
        serviceErrorsLog = LogCapture.of(ServiceErrors.class);
    }

    @AfterEach
    void tearDown() {
        serviceErrorsLog.close();
        MapBackedVaadinSession.uninstall();
    }

    // ---- UI-B1: seat rows after Z are drawn in the wrong place (#187) ----

    // The venue editor allows 99 rows, labelled A..Z then AA, AB, ...; a row is drawn at the height of its
    // place in the zone, so row AA belongs below row Z, not between A and B.
    @Test
    void GivenRowsAToZThenAA_WhenBuildSeatModels_ThenRowsAreDrawnTopToBottomInThatOrder() {
        List<String> rowsInOrder = new ArrayList<>();
        for (char row = 'A'; row <= 'Z'; row++) {
            rowsInOrder.add(String.valueOf(row));
        }
        rowsInOrder.add("AA");
        List<SeatDTO> seats = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < rowsInOrder.size(); rowIndex++) {
            seats.add(new SeatDTO(rowsInOrder.get(rowIndex) + "1", 40, 40 + rowIndex * SEAT_STEP_PIXELS, "AVAILABLE"));
        }
        InventoryZoneDTO zone = new InventoryZoneDTO(ZONE_ID, "Stalls", "SEATED", seats.size(), seats.size(), 0, 0,
                50.0, seats, null);

        List<VkSeatedZonePicker.SeatModel> models = SeatPickerPresenter.buildSeatModels(zone, SEAT_STEP_PIXELS);

        List<String> labelsTopToBottom = models.stream()
                .sorted(Comparator.comparingDouble(VkSeatedZonePicker.SeatModel::y))
                .map(VkSeatedZonePicker.SeatModel::label)
                .toList();
        List<String> expected = rowsInOrder.stream().map(row -> row + "1").toList();
        assertEquals(expected, labelsTopToBottom, "rows must be drawn A..Z then AA, top to bottom");
        assertEquals(expected.size(), models.stream().map(VkSeatedZonePicker.SeatModel::y).distinct().count(),
                "each row must be drawn at its own height");
    }

    // ---- UI-B5: a reservation's internal failure goes through ServiceErrors (#187) ----

    @Test
    void GivenAnInternalFailure_WhenReserveSeated_ThenFailureCarriesTheGenericMessageAndTheErrorIsLogged() {
        doThrow(new PersistenceException(INTERNAL_DETAIL)).when(reservationService)
                .reserveForGuest(any(), anyInt(), anyInt(), any());

        SeatPickerPresenter.ReserveOutcome outcome =
                presenter.reserveSeated(InventorySelectionDTO.seated(List.of("A1")), EVENT_ID, ZONE_ID);

        SeatPickerPresenter.ReserveOutcome.Failure failure =
                assertInstanceOf(SeatPickerPresenter.ReserveOutcome.Failure.class, outcome);
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, failure.message());
        assertEquals(List.of(PersistenceException.class.getName()), serviceErrorsLog.throwableClassNamesAt(Level.ERROR),
                "the internal failure must be logged at ERROR with its exception by ServiceErrors");
    }

    @Test
    void GivenAnInternalFailure_WhenReserveStanding_ThenFailureCarriesTheGenericMessageAndTheErrorIsLogged() {
        doThrow(new PersistenceException(INTERNAL_DETAIL)).when(reservationService)
                .reserveForGuest(any(), anyInt(), anyInt(), any());

        SeatPickerPresenter.ReserveOutcome outcome = presenter.reserveStanding(2, EVENT_ID, ZONE_ID);

        SeatPickerPresenter.ReserveOutcome.Failure failure =
                assertInstanceOf(SeatPickerPresenter.ReserveOutcome.Failure.class, outcome);
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, failure.message());
        assertEquals(List.of(PersistenceException.class.getName()), serviceErrorsLog.throwableClassNamesAt(Level.ERROR),
                "the internal failure must be logged at ERROR with its exception by ServiceErrors");
    }

}
