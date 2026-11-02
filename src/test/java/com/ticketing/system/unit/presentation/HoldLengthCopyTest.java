package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static com.ticketing.system.unit.presentation.ViewHarness.text;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.ZoneType;
import com.ticketing.system.sales.config.ReservationHoldProperties;
import com.ticketing.system.shared.dto.InventoryZoneDTO;
import com.ticketing.system.shared.dto.SeatDTO;
import com.ticketing.system.ui.presenters.catalog.EventDetailsPresenter;
import com.ticketing.system.ui.presenters.catalog.SeatPickerPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.catalog.EventDetailsView;
import com.ticketing.system.ui.views.catalog.SeatPickerView;

/**
 * The pages that tell a buyer how long their seats are held read the configured hold
 * ({@code constants.ticket-reservation-duration}), not a hard-coded ten minutes (#187, UI-A18).
 *
 * <p>Red by compilation: the views take the configured {@link ReservationHoldProperties}, the smallest
 * seam the fix implies, as {@code VenueMapEditorView} takes {@code VenueLimitsProperties}. Kept apart
 * so the other tests compile today.
 */
class HoldLengthCopyTest {

    // ---- UI-A18: the hold length is hard-coded as "10 min" (#187) ----

    private static final ReservationHoldProperties SEVEN_MINUTE_HOLD = new ReservationHoldProperties(7);
    private static final Pattern SEVEN_MINUTES = Pattern.compile("\\b7 min");
    private static final Pattern TEN_MINUTES = Pattern.compile("\\b10 min");

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
    void GivenASevenMinuteHold_WhenTheEventPageShows_ThenItSaysSevenMinutes() {
        EventDetailsPresenter presenter = mock(EventDetailsPresenter.class);
        when(presenter.load(any(), anyInt())).thenReturn(new EventDetailsPresenter.Outcome.Success(
            new EventDetailDTO("5", "Coldplay", null, "Live", EventCategory.CONCERT, null, "3", "Acme",
                EventStatus.ON_SALE, List.of(), ZoneOffset.UTC, List.of(), 10.0),
            List.of(zone("STANDING", List.of())), 3, 3, null));
        EventDetailsView view = new EventDetailsView(presenter, mock(SessionIdentity.class), SEVEN_MINUTE_HOLD);

        view.beforeEnter(routeTo("eventId", "5"));

        assertSaysSevenMinutes(text(view));
    }

    @Test
    void GivenASevenMinuteHold_WhenTheSeatPickerShows_ThenItSaysSevenMinutes() {
        SeatPickerPresenter presenter = mock(SeatPickerPresenter.class);
        when(presenter.loadZone(anyInt(), anyInt())).thenReturn(new SeatPickerPresenter.LoadOutcome.Loaded(
            zone("SEATED", List.of(new SeatDTO("A1", 1, 0, "AVAILABLE"))), ZoneType.SEATED));
        SeatPickerView view = new SeatPickerView(presenter, SEVEN_MINUTE_HOLD);

        view.beforeEnter(routeTo("eventId", "5", "zoneId", "8"));

        assertSaysSevenMinutes(text(view));
    }

    private static InventoryZoneDTO zone(String type, List<SeatDTO> seats) {
        return new InventoryZoneDTO(8, "Floor", type, 100, 50, 0, 50, 10.0, seats, null);
    }

    private static void assertSaysSevenMinutes(String shown) {
        assertTrue(SEVEN_MINUTES.matcher(shown).find(), "expected the 7-minute hold in: " + shown);
        assertFalse(TEN_MINUTES.matcher(shown).find(), "a hard-coded 10-minute hold shows in: " + shown);
    }
}
