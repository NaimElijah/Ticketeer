package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
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

    // ---- UI-B3: a failed zone load reads as the store's failure (#187) ----

    @Test
    void GivenTheStoreIsDown_WhenLoadZone_ThenFailureCarriesTheOutageMessage() {
        when(catalogService.getEventVenueMap(any(), eq(EVENT_ID)))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        SeatPickerPresenter.LoadOutcome outcome = presenter.loadZone(EVENT_ID, ZONE_ID);

        SeatPickerPresenter.LoadOutcome.Failure failure =
                assertInstanceOf(SeatPickerPresenter.LoadOutcome.Failure.class, outcome);
        assertEquals(ServiceErrors.DB_UNAVAILABLE_MESSAGE, failure.message(),
                "a store outage must read as ServiceErrors' outage text");
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

    // ---- UI-B6: a reservation's failure is logged once, with its exception and the ids that locate it (#187) ----

    // A refusal (sold out, not on sale, market closed) is an expected outcome the buyer reads, and
    // LoggingAspect already logs the refused service call at WARN, so the presenter logs it at DEBUG only.
    @Test
    void GivenADomainRefusal_WhenReserveSeated_ThenItIsLoggedAtDebugAndNothingAtWarn() {
        doThrow(new BusinessRuleViolationException("This event is no longer on sale.")).when(reservationService)
                .reserveForGuest(any(), anyInt(), anyInt(), any());

        try (LogCapture presenterLog = LogCapture.of(SeatPickerPresenter.class)) {
            presenter.reserveSeated(InventorySelectionDTO.seated(List.of("A1")), EVENT_ID, ZONE_ID);

            assertEquals(1, presenterLog.messagesAt(Level.DEBUG).stream()
                    .filter(line -> line.contains("eventId=" + EVENT_ID)).count(),
                    "the refusal must be logged once at DEBUG; logged: " + presenterLog.messagesAt(Level.DEBUG));
            for (LogCapture log : List.of(presenterLog, serviceErrorsLog)) {
                assertTrue(log.messagesAt(Level.WARN).isEmpty(), "a refusal must not be logged at WARN here");
                assertTrue(log.messagesAt(Level.ERROR).isEmpty(), "a refusal must not be logged at ERROR here");
            }
        }
    }

    @Test
    void GivenAnInternalFailure_WhenReserveSeated_ThenOneLogLineCarriesTheExceptionAndTheIds() {
        doThrow(new PersistenceException(INTERNAL_DETAIL)).when(reservationService)
                .reserveForGuest(any(), anyInt(), anyInt(), any());

        try (LogCapture presenterLog = LogCapture.of(SeatPickerPresenter.class)) {
            presenter.reserveSeated(InventorySelectionDTO.seated(List.of("A1")), EVENT_ID, ZONE_ID);

            assertTheFailureIsLoggedOnceWithItsIds(presenterLog);
        }
    }

    @Test
    void GivenAnInternalFailure_WhenReserveStanding_ThenOneLogLineCarriesTheExceptionAndTheIds() {
        doThrow(new PersistenceException(INTERNAL_DETAIL)).when(reservationService)
                .reserveForGuest(any(), anyInt(), anyInt(), any());

        try (LogCapture presenterLog = LogCapture.of(SeatPickerPresenter.class)) {
            presenter.reserveStanding(2, EVENT_ID, ZONE_ID);

            assertTheFailureIsLoggedOnceWithItsIds(presenterLog);
        }
    }

    private void assertTheFailureIsLoggedOnceWithItsIds(LogCapture presenterLog) {
        List<String> events = new ArrayList<>();
        for (LogCapture log : List.of(presenterLog, serviceErrorsLog)) {
            events.addAll(log.messagesAt(Level.WARN));
            events.addAll(log.messagesAt(Level.ERROR));
        }
        assertEquals(1, events.size(), "the failure must be logged once; logged: " + events);
        assertTrue(events.get(0).contains("eventId=" + EVENT_ID) && events.get(0).contains("zoneId=" + ZONE_ID),
                "the one log line must name the event and the zone; logged: " + events);
        assertEquals(List.of(PersistenceException.class.getName()), serviceErrorsLog.throwableClassNamesAt(Level.ERROR),
                "the one log line must carry the exception");
    }

    // ---- UI-B10: a seat action resolves the visitor once (#187) ----

    @Test
    void GivenASignedInMember_WhenReserveSeated_ThenTheTokenIsCheckedForLivenessOnce() {
        signInAsLiveMember();

        presenter.reserveSeated(InventorySelectionDTO.seated(List.of("A1")), EVENT_ID, ZONE_ID);

        verify(reservationService).reserveForMember(eq(MEMBER_TOKEN), eq(EVENT_ID), eq(ZONE_ID), any());
        verify(authenticationService, times(1)).liveMemberId(MEMBER_TOKEN);
    }

    @Test
    void GivenASignedInMember_WhenReserveStanding_ThenTheTokenIsCheckedForLivenessOnce() {
        signInAsLiveMember();

        presenter.reserveStanding(2, EVENT_ID, ZONE_ID);

        verify(reservationService).reserveForMember(eq(MEMBER_TOKEN), eq(EVENT_ID), eq(ZONE_ID), any());
        verify(authenticationService, times(1)).liveMemberId(MEMBER_TOKEN);
    }

    private void signInAsLiveMember() {
        AuthSession.storeAuth(new AuthTokenDTO(MEMBER_TOKEN, 0L, MEMBER_ID, "member"));
        when(authenticationService.liveMemberId(MEMBER_TOKEN)).thenReturn(OptionalInt.of(MEMBER_ID));
    }
}
